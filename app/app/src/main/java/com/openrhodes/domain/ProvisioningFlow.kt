package com.openrhodes.domain

import com.openrhodes.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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

data class SetupUi(
    val phase: SetupPhase = SetupPhase.DISCOVER,
    val candidates: List<SetupCandidate> = emptyList(),
    val networks: List<SetupNetwork> = emptyList(),
    val network: SetupNetwork? = null,
    val busy: Boolean = false,
    val searching: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val completedId: String? = null,
)

interface ProvisioningGateway {
    val nearby: StateFlow<Map<HardwareId, Endpoint>>

    fun name(hardware: HardwareId): String?

    fun rescan()

    suspend fun read(endpoint: Endpoint): LampState

    suspend fun enroll(hardware: HardwareId, endpoint: Endpoint, state: LampState? = null): String

    suspend fun reachable(id: String): Boolean

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
) : AutoCloseable {
    private val mutable = MutableStateFlow(SetupUi())
    val ui = mutable.asStateFlow()
    private var peers = emptyList<SetupPeer>()
    private var discovery: Job? = null
    private var operation: Job? = null
    private var link: ProvisionLink? = null
    private var identity: HardwareId? = null
    private var selected: SetupCandidate? = null
    private var proof: String? = null
    private var bleAllowed = false
    private var closed = false
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

    fun search(allowBluetooth: Boolean) {
        if (closed || mutable.value.busy || mutable.value.phase != SetupPhase.DISCOVER) return
        bleAllowed = allowBluetooth
        val previous = discovery
        previous?.cancel()
        gateway.rescan()
        mutable.update {
            it.copy(
                searching = allowBluetooth,
                error = null,
                status = if (allowBluetooth) "正在寻找附近的灯具…" else "正在寻找已联网的灯具…",
            )
        }
        if (!allowBluetooth) return
        discovery = scope.launch {
            try {
                previous?.join()
                mutable.update { it.copy(searching = true) }
                coroutineScope {
                    val queue = Channel<BlePeer>(Channel.UNLIMITED)
                    val seen = mutableSetOf<String>()
                    launch {
                        linkFactory().use { scanner ->
                            try {
                                scanner.scan { peer ->
                                    if (seen.add(peer.address)) {
                                        peers =
                                            peers.filterNot { it.peer.address == peer.address } +
                                                SetupPeer(peer, null)
                                        rebuild()
                                        queue.trySend(peer)
                                    }
                                }
                            } finally {
                                queue.close()
                            }
                        }
                    }
                    for (peer in queue) {
                        // A short encrypted read verifies station MAC; it never changes Wi-Fi.
                        val hardware =
                            try {
                                withTimeoutOrNull(4500) {
                                    linkFactory().use { probe ->
                                        BleProvisionProtocol.identity(probe.connect(peer, proof))
                                    }
                                }
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                null
                            } // Legacy firmware remains selectable.
                        peers = peers.map {
                            if (it.peer.address == peer.address) it.copy(hardware = hardware)
                            else it
                        }
                        rebuild()
                    }
                }
                mutable.update {
                    it.copy(
                        status = if (it.candidates.isEmpty()) "暂未找到灯具，请确认灯具已上电。" else "选择要添加的灯具"
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutable.update { it.copy(status = "蓝牙暂不可用，仍在寻找已联网的灯具。可在“没有找到灯具？”中重试。") }
            } finally {
                mutable.update { it.copy(searching = false) }
            }
        }
    }

    private fun action(block: suspend () -> Unit) {
        if (closed || mutable.value.busy) return
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
            }
        }
    }

    private fun disconnect(message: String) {
        link?.close()
        link = null
        mutable.update { it.copy(phase = SetupPhase.DISCOVER, error = message, status = null) }
    }

    private suspend fun addLocal(endpoint: Endpoint, hardware: HardwareId?): Boolean {
        val state =
            try {
                gateway.read(endpoint)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                return false
            }
        val actual = requireNotNull(state.hardware) { "灯具尚未提供完整身份，请升级固件" }
        require(hardware == null || actual == hardware) { "灯具身份已变化，请重新搜索" }
        require(expected == null || expected == actual) { "此地址对应另一盏灯" }
        val id = gateway.enroll(actual, endpoint, state)
        mutable.update { it.copy(phase = SetupPhase.DONE, completedId = id, status = null) }
        return true
    }

    fun select(candidate: SetupCandidate) = action {
        selected = candidate
        mutable.update { it.copy(status = "正在连接 ${candidate.name}…") }
        if (
            !reconfigure &&
                candidate.endpoint != null &&
                addLocal(candidate.endpoint, candidate.hardware)
        )
            return@action
        val peer = candidate.peer ?: error("暂时无法连接这盏灯，请重新搜索或使用蓝牙配网")
        link?.close()
        val active = linkFactory().also { link = it }
        val state = active.connect(peer, proof)
        val hardware = BleProvisionProtocol.identity(state, expected ?: candidate.hardware)
        identity = hardware
        if (!reconfigure) {
            val endpoints =
                listOfNotNull(
                        gateway.nearby.value[hardware],
                        Endpoint.parse(state["ip"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                    )
                    .distinct()
            for (endpoint in endpoints) if (addLocal(endpoint, hardware)) return@action
        }
        scanNetworksNow()
    }

    fun manual(address: String) = action {
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
                network = null,
                status = "正在扫描 Wi-Fi…",
                error = null,
                networks = emptyList(),
            )
        }
        val active = requireNotNull(link)
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
                            val id = gateway.enroll(hardware, endpoint)
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
                                    completedId = id,
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
                    val reachable =
                        try {
                            gateway.reachable(requireNotNull(mutable.value.completedId))
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            false
                        }
                    if (reachable) {
                        mutable.update { it.copy(phase = SetupPhase.DONE, status = null) }
                        break
                    }
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
                mutable.update { it.copy(phase = SetupPhase.NETWORKS, error = null) }
            SetupPhase.NETWORKS -> {
                link?.close()
                link = null
                mutable.update { it.copy(phase = SetupPhase.DISCOVER, error = null) }
                search(bleAllowed)
            }
            else -> return false
        }
        return true
    }

    override fun close() {
        closed = true
        discovery?.cancel()
        operation?.cancel()
        nearbyJob.cancel()
        link?.close()
        link = null
    }
}
