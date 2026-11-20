package com.openrhodes.domain

import com.openrhodes.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class SetupCandidate(
    val key: String,
    val name: String,
    val hardware: HardwareId?,
    val endpoint: Endpoint?,
    val peer: BlePeer?,
)

data class SetupPeer(val peer: BlePeer, val hardware: HardwareId?)

data class SetupNetwork(val ssid: String, val rssi: Int, val secure: Boolean, val saved: Boolean)

enum class SetupPhase {
    DISCOVER,
    NETWORKS,
    PASSWORD,
    JOINING,
    WAITING,
    DONE,
}

enum class SetupControlMethod { BLUETOOTH, WIFI }

data class AddCompletion(val deviceId: String, val firstAdd: Boolean, val snapshot: LampState, val name: String = "Rhodes Light") {
    init { require(snapshot.hardware != null) { "灯具身份尚未验证" } }
}

data class SetupUi(
    val phase: SetupPhase = SetupPhase.DISCOVER,
    val candidates: List<SetupCandidate> = emptyList(),
    val networks: List<SetupNetwork> = emptyList(),
    val network: SetupNetwork? = null,
    val busy: Boolean = false,
    val searching: Boolean = false,
    val connection:ConnectionProgress?=null,
    val status: String? = null,
    val error: String? = null,
    val completedId: String? = null,
    val completion: AddCompletion? = null,
)

interface ProvisioningGateway {
    val nearby: StateFlow<Map<HardwareId, Endpoint>>

    fun name(hardware: HardwareId): String?

    fun rescan()

    suspend fun read(endpoint: Endpoint): LampState

    suspend fun enroll(hardware: HardwareId, endpoint: Endpoint, state: LampState, name: String = "Rhodes Light"): AddCompletion

    suspend fun adoptBluetooth(link: ProvisionLink, peer: BlePeer, hardware: HardwareId, name: String = "Rhodes Light"): AddCompletion =
        error("BLE 暂不可用")


    suspend fun verifyBluetooth(link: ProvisionLink, hardware: HardwareId): LampState {
        val raw = link.request(byteArrayOf(0x10))
        BleProvisionProtocol.identity(raw, hardware)
        return StateCodec.decode(raw.toString(), 0)
    }

    suspend fun legacyProof(endpoint: Endpoint, expected: HardwareId?): String?
}

/** Advertising names and Bluetooth addresses are never sufficient to merge devices. */
fun setupCandidates(
    local: Map<HardwareId, Endpoint>,
    peers: List<SetupPeer>,
    name: (HardwareId) -> String?,
): List<SetupCandidate> {
    val verified = (local.keys + peers.mapNotNull { it.hardware }).distinct()
    return verified.map { hardware ->
        SetupCandidate(
            hardware.value,
            name(hardware) ?: "RhodesLight · ${hardware.value.takeLast(4).uppercase()}",
            hardware,
            local[hardware],
            peers.firstOrNull { it.hardware == hardware }?.peer,
        )
    } +
        peers
            .filter { it.hardware == null }
            .map {
                SetupCandidate("ble:${it.peer.address}", it.peer.name, null, null, it.peer)
            }
}

/** One explicit state per setup screen; all requests belong to this flow's lifetime. */
class ProvisioningFlow(
    private val scope: CoroutineScope,
    private val gateway: ProvisioningGateway,
    private val linkFactory: () -> ProvisionLink,
    private val expected: HardwareId? = null,
    private val reconfigure: Boolean = false,
    private val pollDelay: Long = 700,
    private val discoveryRetryMs:Long=3000,
) : AutoCloseable {
    private val mutable = MutableStateFlow(SetupUi())
    val ui = mutable.asStateFlow()
    private var peers = emptyList<SetupPeer>()
    private var discovery: Job? = null
    private var operation: Job? = null
    private var link: ProvisionLink? = null
    private var identity: HardwareId? = null
    private var connectedState: JsonObject? = null
    private var selected: SetupCandidate? = null
    private var proof: String? = null
    private var bleAllowed = false
    private var discoveryRequested = false
    private var discoveryForeground = true
    private var closed = false
    private var delivered = false
    private var waitingEndpoint: Endpoint? = null
    private var progressAttempt=0
    private fun beginConnection(route:ConnectionRoute,label:String):Int {
        val id=++progressAttempt
        mutable.update {it.copy(connection=ConnectionProgress(id,route,label=label))}
        return id
    }
    private fun reached(id:Int,value:Int,label:String) {
        if(closed)return
        mutable.update {val p=it.connection;if(p?.attempt==id)it.copy(connection=p.reached(value,label)) else it}
    }
    private fun milestone(id:Int,stage:BleConnectionMilestone)=reached(id,stage.percent,stage.nextLabel)
    private suspend fun verifiedConnection(id:Int) {
        currentCoroutineContext().ensureActive()
        if(closed)return
        mutable.update {val p=it.connection;if(p?.attempt==id)it.copy(connection=p.verified()) else it}
        currentCoroutineContext().ensureActive()
    }
    private suspend fun secureLink(progressId: Int? = null): ProvisionLink {
        link?.let { return it }
        val candidate = requireNotNull(selected) { "请先选择灯具" }
        val peer = requireNotNull(candidate.peer) { "请选择 Wi-Fi 连接这盏灯" }
        val active = linkFactory()
        try {
            val raw = active.connect(peer, proof) { stage -> progressId?.let { milestone(it, stage) } }
            val hardware = BleProvisionProtocol.identity(raw, expected ?: candidate.hardware)
            identity = hardware
            connectedState = raw
            peers = peers.map { if (it.peer.address == peer.address) it.copy(hardware = hardware) else it }
            rebuild()
            link = active
            return active
        } catch (failure: Exception) { active.close(); throw failure }
    }

    private fun addName(hardware: HardwareId): String = gateway.name(hardware)
        ?: selected?.name?.trim()?.take(48)?.takeIf { it.isNotEmpty() }
        ?: "Rhodes Light"

    fun takeCompletion(): AddCompletion? {
        val result = mutable.value.completion ?: return null
        if (delivered) return null
        delivered = true
        return result
    }

    private fun complete(result: AddCompletion) {
        mutable.update { it.copy(phase = SetupPhase.DONE, completedId = result.deviceId,
            completion = if (reconfigure) result.copy(firstAdd = false) else result, status = null) }
    }
    private val nearbyJob = scope.launch {
        gateway.nearby.collect { rebuild() }
    }

    private fun rebuild() {
        val rows =
            setupCandidates(gateway.nearby.value, peers, gateway::name).filter {
                expected == null || it.hardware == null || it.hardware == expected
            }
        mutable.update { it.copy(candidates = rows) }
    }

    fun search(allowBluetooth: Boolean, restart:Boolean=false) = startDiscovery(allowBluetooth,restart,true)

    fun pauseDiscovery() {
        discoveryForeground=false
        discovery?.cancel()
        mutable.update {it.copy(searching=false)}
    }

    fun resumeDiscovery(allowBluetooth:Boolean) {
        discoveryForeground=true
        startDiscovery(allowBluetooth,false,false)
    }

    private fun startDiscovery(allowBluetooth:Boolean,restart:Boolean,clearError:Boolean) {
        if (closed || mutable.value.busy || mutable.value.phase != SetupPhase.DISCOVER) return
        val samePermission=bleAllowed==allowBluetooth
        bleAllowed = allowBluetooth
        discoveryRequested=true
        if(!discoveryForeground)return
        if(!restart&&samePermission&&discovery?.isActive==true)return
        val previous = discovery
        previous?.cancel()
        gateway.rescan()
        mutable.update {
            it.copy(
                searching = true,
                connection=if(clearError)null else it.connection,
                error = if(clearError)null else it.error,
                status = if (allowBluetooth) "正在寻找附近的灯具…" else "正在寻找已联网的灯具…",
            )
        }
        val next=scope.launch(start=CoroutineStart.LAZY) {
            val ownJob=currentCoroutineContext()[Job]
            fun eligible()=!closed&&discoveryForeground&&!mutable.value.busy&&mutable.value.phase==SetupPhase.DISCOVER
            try {
                previous?.join()
                if(!eligible())return@launch
                if(!allowBluetooth)awaitCancellation() // 局域网结果继续由 nearby 订阅提供。
                while(isActive&&eligible()) {
                    var failed=false
                    try {
                        // 一轮扫描结束或蓝牙暂未就绪时自动续搜；不探测连接，不清空已发现的行。
                        linkFactory().use { scanner ->
                            scanner.scan { peer ->
                                if(discovery===ownJob&&eligible()) {
                                    val known = peers.firstOrNull { it.peer.address == peer.address }?.hardware
                                    peers = peers.filterNot { it.peer.address == peer.address } + SetupPeer(peer, known)
                                    rebuild()
                                }
                            }
                        }
                        if(eligible())mutable.update {it.copy(status=if(it.candidates.isEmpty())"正在继续寻找附近的灯具…" else "选择要添加的灯具")}
                    } catch(cancel:CancellationException) {throw cancel}
                    catch(_:Exception) {
                        failed=true
                        if(eligible())mutable.update {it.copy(status="蓝牙暂不可用，正在自动重试；已联网的灯具仍会显示。")}
                    }
                    // 给系统扫描器留出间隔，避免频繁重启被限流；连接开始前由 action 取消并等待释放。
                    delay(discoveryRetryMs.coerceAtLeast(1)*(if(failed)3 else 1))
                }
            } finally {
                if(discovery===ownJob)mutable.update {it.copy(searching=false)}
            }
        }
        discovery=next
        next.start()
    }

    private fun action(block: suspend () -> Unit) {
        if (closed || mutable.value.busy || mutable.value.phase == SetupPhase.DONE || mutable.value.phase == SetupPhase.WAITING) return
        mutable.update { it.copy(busy = true, error = null) }
        operation = scope.launch {
            try {
                discovery?.cancelAndJoin()
                block()
            } catch (_: TimeoutCancellationException) {
                disconnect("等待超时，连接结果尚未确认。请重新选择灯具查看状态。")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                disconnect(failure.message ?: "连接中断，请重新选择灯具")
            } finally {
                mutable.update { it.copy(busy = false) }
                if(discoveryRequested&&discoveryForeground&&mutable.value.phase==SetupPhase.DISCOVER)
                    startDiscovery(bleAllowed,false,false)
            }
        }
    }

    private fun disconnect(message: String) {
        link?.close()
        link = null
        mutable.update { it.copy(phase = SetupPhase.DISCOVER, error = message, status = null,connection=it.connection?.stopped()) }
    }

    private suspend fun readLocal(endpoint: Endpoint): LampState? = try {
        gateway.read(endpoint)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }

    private suspend fun addLocal(endpoint: Endpoint, hardware: HardwareId?,connectionId:Int?=null,
        snapshot: LampState? = null): Boolean {
        val id=connectionId?:beginConnection(ConnectionRoute.LOCAL,"正在读取灯具状态")
        val state = snapshot ?: readLocal(endpoint) ?: return false
        reached(id,if(mutable.value.connection?.route==ConnectionRoute.WIFI)ConnectionWeights.WIFI_READ else ConnectionWeights.CONTROL_READ,"正在验证灯具身份")
        val actual = requireNotNull(state.hardware) { "灯具尚未提供完整身份，请升级固件" }
        require(hardware == null || actual == hardware) { "灯具身份已变化，请重新搜索" }
        require(expected == null || expected == actual) { "此地址对应另一盏灯" }
        val result = gateway.enroll(actual, endpoint, state, addName(actual))
        verifiedConnection(id)
        complete(result)
        return true
    }

    fun select(candidate: SetupCandidate, method: SetupControlMethod = SetupControlMethod.BLUETOOTH) = action {
        if (selected?.peer?.address != candidate.peer?.address || selected?.hardware != candidate.hardware) {
            link?.close()
            link = null
            identity = null
            connectedState = null
            waitingEndpoint = null
        }
        selected = candidate
        mutable.update { it.copy(status = "正在连接 ${candidate.name}…") }
        when (method) {
            SetupControlMethod.BLUETOOTH -> connectBluetooth()
            SetupControlMethod.WIFI -> connectWifi()
        }
    }

    private suspend fun connectBluetooth() {
        require(selected?.peer != null) { "这盏灯仅发现局域网地址，请在添加页选择 Wi-Fi" }
        val progressId=beginConnection(ConnectionRoute.BLE,"正在连接灯具")
        val active = secureLink(progressId)
        check(active.supportsLightControl) { "此固件仅支持蓝牙配网，请选择 Wi-Fi" }
        val hardware = requireNotNull(identity)
        val verified = gateway.verifyBluetooth(active, hardware)
        reached(progressId,90,"正在保存灯具")
        require(verified.hardware == hardware)
        val result = gateway.adoptBluetooth(active, requireNotNull(selected?.peer), hardware, addName(hardware))
        // 所有权交给控制会话，关闭添加页面不能再断开这条连接。
        link = null
        verifiedConnection(progressId)
        complete(result)
    }

    private suspend fun connectWifi() {
        val candidate = requireNotNull(selected)
        if (candidate.peer == null) {
            val endpoint = requireNotNull(candidate.endpoint) { "请选择灯具或使用手动地址" }
            check(addLocal(endpoint, candidate.hardware)) { "暂时无法访问灯具，请检查手机与灯具的网络" }
            return
        }
        // 读取网络选项属于前置准备；用户提交网络后才启动连续的连接成功动效。
        mutable.update { it.copy(phase = SetupPhase.NETWORKS, connection = null, status = "正在读取 Wi-Fi 网络…") }
        secureLink()
        val hardware = requireNotNull(identity)
        if (!reconfigure) {
            val endpoints = listOfNotNull(gateway.nearby.value[hardware],
                candidate.endpoint, waitingEndpoint,
                Endpoint.parse(connectedState?.get("ip")?.jsonPrimitive?.contentOrNull.orEmpty())).distinct()
            for (endpoint in endpoints) {
                val state = readLocal(endpoint) ?: continue
                if (addLocal(endpoint, hardware, snapshot = state)) return
            }
        }
        scanNetworksNow()
    }

    fun manual(address: String) = action {
        selected = null
        val endpoint = requireNotNull(Endpoint.parse(address)) { "请输入有效的局域网地址" }
        if (!addLocal(endpoint, expected)) error("无法访问该地址，请检查手机与灯具的网络")
    }

    fun legacy() = action {
        proof = gateway.legacyProof(requireNotNull(Endpoint.parse("192.168.4.1")), expected)
        mutable.update { it.copy(status = "已读取兼容信息，请选择灯具", error = null) }
    }

    suspend fun reopen(endpoint: Endpoint) {
        proof = gateway.legacyProof(endpoint, expected)
    }

    private suspend fun scanNetworksNow() {
        mutable.update {
            it.copy(
                phase = SetupPhase.NETWORKS,
                connection=null,
                network = null,
                status = "正在扫描 Wi-Fi…",
                error = null,
                networks = emptyList(),
            )
        }
        val active = secureLink()
        mutable.update {it.copy(connection=null,status="正在扫描 Wi-Fi…")}
        active.request(byteArrayOf(2))
        val rows = mutableListOf<SetupNetwork>()
        withTimeout(20000) {
            var offset = 0
            var generation: Int? = null
            while (true) {
                val page = active.request(byteArrayOf(3, offset.toByte()))
                if (page["scanning"]?.jsonPrimitive?.booleanOrNull == true) {
                    delay(450)
                    continue
                }
                val current = page["generation"]?.jsonPrimitive?.intOrNull
                check(generation == null || generation == current) { "扫描结果已变化，请重试" }
                generation = current
                rows +=
                    page["networks"]
                        ?.jsonArray
                        .orEmpty()
                        .map { value ->
                            val n = value.jsonObject
                            SetupNetwork(
                                n["ssid"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                n["rssi"]?.jsonPrimitive?.intOrNull ?: -100,
                                n["secure"]?.jsonPrimitive?.booleanOrNull != false,
                                n["saved"]?.jsonPrimitive?.booleanOrNull == true,
                            )
                        }
                        .filter { it.ssid.isNotEmpty() }
                offset = page["next"]?.jsonPrimitive?.intOrNull ?: -1
                if (offset < 0) break
            }
        }
        mutable.update {
            it.copy(
                networks = rows.sortedByDescending { n -> n.rssi }.distinctBy { n -> n.ssid },
                status = if (rows.isEmpty()) "未发现网络，可重新扫描或添加隐藏网络" else null,
            )
        }
    }

    fun scanNetworks() = action { scanNetworksNow() }

    fun network(network: SetupNetwork) {
        if (mutable.value.busy) return
        mutable.update { it.copy(network = network, error = null) }
        if (network.saved || !network.secure) join("", useSaved = network.saved)
        else mutable.update { it.copy(phase = SetupPhase.PASSWORD, status = null) }
    }

    fun hidden() {
        if (!mutable.value.busy)
            mutable.update {
                it.copy(phase = SetupPhase.PASSWORD, network = null, status = null, error = null)
            }
    }

    fun join(password: String, hiddenSsid: String? = null, useSaved: Boolean = false) {
        val network = mutable.value.network
        val ssid = hiddenSsid ?: network?.ssid ?: return
        val payload =
            try {
                if (useSaved) BleProvisionProtocol.connectSaved(ssid)
                else BleProvisionProtocol.connect(ssid, password)
            } catch (failure: IllegalArgumentException) {
                mutable.update { it.copy(error = failure.message) }
                return
            }
        if (closed || mutable.value.busy) {
            payload.fill(0)
            return
        }
        action {
            val progressId=beginConnection(ConnectionRoute.WIFI,"正在提交网络设置")
            mutable.update { it.copy(phase = SetupPhase.JOINING, status = "正在连接 $ssid…") }
            val active = requireNotNull(link)
            val accepted =
                try {
                    active.request(payload)
                } finally {
                    payload.fill(0)
                }
            val attempt = requireNotNull(accepted["attempt_id"]?.jsonPrimitive?.intOrNull)
            check(accepted["accepted"]?.jsonPrimitive?.booleanOrNull == true)
            reached(progressId,ConnectionWeights.WIFI_ACCEPTED,"正在等待灯具接入 Wi-Fi")
            withTimeout(40000) {
                while (true) {
                    delay(pollDelay)
                    val status = active.request(byteArrayOf(1))
                    val hardware = BleProvisionProtocol.identity(status, requireNotNull(identity))
                    check(status["attempt_id"]?.jsonPrimitive?.intOrNull == attempt) {
                        "配网请求已变化，请重新选择灯具"
                    }
                    when (status["status"]?.jsonPrimitive?.contentOrNull) {
                        "failed" -> {
                            mutable.update {
                                it.copy(
                                    phase = SetupPhase.PASSWORD,
                                    connection=it.connection?.stopped(),
                                    network =
                                        (network ?: SetupNetwork(ssid, -100, true, false)).copy(
                                            saved = false
                                        ),
                                    error = "连接失败，请检查密码或选择其他网络",
                                    status = null,
                                )
                            }
                            return@withTimeout
                        }
                        "connected" -> {
                            val endpoint =
                                requireNotNull(
                                    Endpoint.parse(
                                        status["ip"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    )
                                )
                            reached(progressId,ConnectionWeights.WIFI_ONLINE,"正在建立局域网连接")
                            waitingEndpoint = endpoint
                            try {
                                active.request(byteArrayOf(5))
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                /* Wi-Fi success is already verified. */
                            }
                            link?.close()
                            link = null
                            mutable.update {
                                it.copy(
                                    phase = SetupPhase.WAITING,
                                    status = "灯具已连接 $ssid；正在建立局域网连接…",

                                )
                            }
                            gateway.rescan()
                            return@withTimeout
                        }
                    }
                }
            }
            if (mutable.value.phase == SetupPhase.WAITING) {
                mutable.update { it.copy(busy = false) }
                // Keep waiting while the user opens system Wi-Fi settings; never resubmit
                // credentials.
                while (currentCoroutineContext().isActive) {
                    val hardware = requireNotNull(identity)
                    val endpoints = listOfNotNull(gateway.nearby.value[hardware], waitingEndpoint).distinct()
                    var connected = false
                    for (endpoint in endpoints) {
                        if (addLocal(endpoint, hardware,progressId)) { connected = true; break }
                    }
                    if (connected) break
                    mutable.update { it.copy(status = "灯具已联网，请将手机连接到 $ssid。连接恢复后会自动打开。") }
                    delay(2000)
                }
            }
        }
        operation?.invokeOnCompletion { payload.fill(0) }
    }

    fun back(): Boolean {
        if (mutable.value.busy) return false
        when (mutable.value.phase) {
            SetupPhase.PASSWORD ->
                mutable.update { it.copy(phase = SetupPhase.NETWORKS, error = null,connection=null) }
            SetupPhase.NETWORKS -> {
                mutable.update { it.copy(phase = SetupPhase.DISCOVER, error = null,connection=null) }
                search(bleAllowed)
            }
            else -> return false
        }
        return true
    }

    override fun close() {
        closed = true
        discoveryRequested=false
        discoveryForeground=false
        discovery?.cancel()
        operation?.cancel()
        nearbyJob.cancel()
        link?.close()
        link = null
    }
}
