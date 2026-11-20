package com.openrhodes

import android.app.Application
import android.os.SystemClock
import com.openrhodes.data.*
import com.openrhodes.domain.*
import com.openrhodes.widget.WidgetHost
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update

@OptIn(FlowPreview::class)
class RhodesApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val catalog by lazy { CatalogStore(this) }
    val api = LocalApi(SystemClock::elapsedRealtime)
    val bluetooth by lazy { BleControlTransport(this, SystemClock::elapsedRealtime) }
    private val lampTransport by lazy { RoutedLampTransport(api, bluetooth) }

    suspend fun connectBluetooth(peer: BlePeer, expected: HardwareId? = null, active: ProvisionLink? = null): String =
        completeBluetooth(peer, expected, active).deviceId

    suspend fun completeBluetooth(peer: BlePeer, expected: HardwareId? = null, active: ProvisionLink? = null, name: String = "Rhodes Light"): AddCompletion {
        awaitReady()
        val (endpoint, state) = if (active == null) bluetooth.connectPeer(peer, expected)
            else bluetooth.adoptPeer(active, peer, expected)
        val local = state.deviceIp?.takeIf { it.isNotBlank() }?.let(Endpoint::parse)
        return try {
            completeConnectedAdd(endpoint, state, name, listOfNotNull(local))
        } catch (failure: Exception) {
            bluetooth.disconnect(endpoint)
            throw failure
        }
    }

    internal suspend fun completeConnectedAdd(
        endpoint: Endpoint, state: LampState, name: String, extra: List<Endpoint> = emptyList(),
    ): AddCompletion {
        val hardware = requireNotNull(state.hardware)
        val lit = if (endpoint.isBluetooth || (state.on && state.brightness > 0)) state
            else lampTransport.write(endpoint, Command.Power(true), state)
        check(lit.hardware == hardware && lit.on && lit.brightness > 0) { "尚未确认灯具已点亮，请重试" }
        val result = catalog.completeAdd(endpoint, lit, extra, name)
        val session = session(result.deviceId)
        if (endpoint.isBluetooth) session.networkChanged()
        session.acceptDiscovered(endpoint, lit)
        return result
    }

    suspend fun preferLocal(lamp: Lamp) {
        val current = requireNotNull(catalog.find(lamp.id))
        check(current.endpoints.any { !it.isBluetooth }) { "请先通过蓝牙配网，让灯具接入 Wi-Fi" }
        current.endpoints.filter { it.isBluetooth }.forEach { bluetooth.disconnect(it) }
        catalog.enroll(current.copy(endpoints = current.endpoints.sortedBy { it.isBluetooth }))
        session(lamp.id).networkChanged()
        session(lamp.id).refresh(force = true)
    }

    val startup = MutableStateFlow<String?>("正在读取设备")
    val nearby = MutableStateFlow<Map<HardwareId, Endpoint>>(emptyMap())
    val discoveryStatus = MutableStateFlow("正在搜索局域网灯具…")
    val discoveryRevision = MutableStateFlow(0)
    private var lastLocalNetwork: Set<String>? = null

    fun rescan() {
        discoveryRevision.value += 1
    }

    fun networkChanged() {
        sessions.values.forEach { it.localNetworkChanged() }
    }

    @Synchronized
    fun observeNetwork(snapshot: Set<String>): Boolean {
        val previous = lastLocalNetwork
        lastLocalNetwork = snapshot
        return (previous != null && previous != snapshot).also { changed ->
            if (changed) networkChanged()
        }
    }

    fun foregrounded() {
        catalog.lamps.value.forEach { session(it.id).foregrounded() }
    }

    suspend fun discovered(endpoint: Endpoint, state: LampState) {
        val hardware = state.hardware ?: return
        nearby.update { it + (hardware to endpoint) }
        catalog.discovered(endpoint, state)
        catalog.lamps.value
            .firstOrNull { it.hardware == hardware }
            ?.let {
                if (it.endpoints.firstOrNull()?.isBluetooth != true)
                    session(it.id).acceptDiscovered(endpoint, state)
            }
    }

    private val ready = CompletableDeferred<Unit>()
    private val sessions = ConcurrentHashMap<String, LampSession>()
    private val sessionScopes = ConcurrentHashMap<String, CoroutineScope>()

    suspend fun removeLamp(id: String) {
        val lamp = requireNotNull(catalog.find(id)) { "设备记录不存在" }
        catalog.remove(lamp.id)
        // 保存成功后结束该灯的任务；界面返回或取消不能留下仍可写入的旧会话。
        withContext(NonCancellable) {
            val key = lamp.hardware?.value ?: "unverified:${lamp.id}"
            sessions.remove(key)
            sessionScopes.remove(key)?.cancel()
            lamp.endpoints.filter { it.isBluetooth }.forEach { bluetooth.forget(it) }
            WidgetHost.refreshAll(this@RhodesApp, refresh = false)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.openrhodes.ui.Appearance.update(newConfig)
    }

    override fun onCreate() {
        super.onCreate()
        com.openrhodes.ui.Appearance.update(resources.configuration)
        scope.launch {
            try {
                catalog.load()
                startup.value = null
                ready.complete(Unit)
            } catch (failure: Exception) {
                startup.value = "设备数据读取失败：${failure.message}"
                ready.completeExceptionally(failure)
            }
        }
    }

    suspend fun awaitReady() = ready.await()

    fun session(id: String): LampSession {
        val record = catalog.find(id) ?: error("设备记录不存在")
        val canonical = record.id
        val key = record.hardware?.value ?: "unverified:$canonical"
        return sessions.computeIfAbsent(key) {
            val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
            sessionScopes[key] = sessionScope
            LampSession(
                    sessionScope,
                    { orderForNetwork(this, requireNotNull(catalog.find(canonical))) },
                    lampTransport,
                ) { endpoint, state ->
                    catalog.discovered(endpoint, state)
                }
                .also { session ->
                    sessionScope.launch {
                        session.observation.debounce(100).collect {
                            try {
                                WidgetHost.refreshAll(this@RhodesApp, refresh = false)
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (failure: Exception) {
                                android.util.Log.w("OpenRhodes", "Widget redraw failed", failure)
                            }
                        }
                    }
                }
        }
    }
}
