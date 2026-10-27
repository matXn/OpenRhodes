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
    val startup = MutableStateFlow<String?>("正在读取设备")
    val nearby = MutableStateFlow<Map<HardwareId, Endpoint>>(emptyMap())
    val discoveryStatus = MutableStateFlow("正在搜索局域网灯具…")
    val discoveryRevision = MutableStateFlow(0)
    private var lastLocalNetwork: Set<String>? = null

    fun rescan() {
        discoveryRevision.value += 1
    }

    fun networkChanged() {
        sessions.values.forEach { it.networkChanged() }
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
                session(it.id).acceptDiscovered(endpoint, state)
            }
    }

    private val ready = CompletableDeferred<Unit>()
    private val sessions = ConcurrentHashMap<String, LampSession>()

    override fun onCreate() {
        super.onCreate()
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
            LampSession(
                    scope,
                    { orderForNetwork(this, requireNotNull(catalog.find(canonical))) },
                    api,
                ) { endpoint, state ->
                    catalog.discovered(endpoint, state)
                }
                .also { session ->
                    scope.launch {
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
