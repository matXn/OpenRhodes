package com.openrhodes.data

import android.content.Context
import com.openrhodes.domain.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** HTTP 与 BLE 共用原有会话队列，写入失败不自动重放。 */
class RoutedLampTransport(private val http: LampTransport, private val ble: LampTransport) :
    LampTransport {
    override suspend fun read(endpoint: Endpoint) =
        if (endpoint.isBluetooth) ble.read(endpoint) else http.read(endpoint)

    override suspend fun write(endpoint: Endpoint, command: Command, before: LampState) =
        if (endpoint.isBluetooth) ble.write(endpoint, command, before)
        else http.write(endpoint, command, before)
}

object BleControlCodec {
    fun command(command: Command, before: LampState): ByteArray =
        when (command) {
            is Command.Level -> byteArrayOf(0x11, command.percent.coerceIn(0, 100).toByte())
            is Command.Power ->
                if (command.on && before.brightness <= 0) byteArrayOf(0x11, 5)
                else byteArrayOf(0x12, if (command.on) 1 else 0)
            is Command.Mode -> byteArrayOf(0x13, if (command.breathing) 1 else 0)
            is Command.Parameters ->
                command.breath.normalized().let {
                    byteArrayOf(
                        0x14,
                        it.periodMs.toByte(),
                        (it.periodMs ushr 8).toByte(),
                        it.floor.toByte(),
                        it.curve.toByte(),
                        (it.curve ushr 8).toByte(),
                    )
                }
            Command.Cycle -> byteArrayOf(0x11, nextBrightness(before).toByte())
        }
}

class BleControlTransport(
    private val context: Context,
    private val clock: () -> Long,
    private val linkFactory: () -> ProvisionLink = {
        BleProvisionClient(context.applicationContext)
    },
    private val peerHints: BlePeerHints = StoredBlePeerHints(context),
) : LampTransport {
    private class Entry {
        val lock = Mutex()
        var link: ProvisionLink? = null
        var hint: BlePeer? = null
    }

    private val entries = ConcurrentHashMap<Endpoint, Entry>()
    @Volatile private var provisioning = false

    private data class Connection(val link: ProvisionLink, val initial: LampState? = null)

    suspend fun pauseForProvisioning() {
        provisioning = true
        entries.values.forEach { entry ->
            entry.lock.withLock {
                entry.link?.close()
                entry.link = null
            }
        }
    }

    fun resumeAfterProvisioning() {
        provisioning = false
    }

    suspend fun disconnect(endpoint: Endpoint) {
        entries[endpoint]?.let { entry ->
            entry.lock.withLock {
                entry.link?.close()
                entry.link = null
            }
        }
    }

    suspend fun forget(endpoint: Endpoint) {
        entries[endpoint]?.let { entry ->
            entry.lock.withLock {
                entry.link?.close()
                entry.link = null
                entry.hint = null
                endpoint.bluetoothIdentity?.let(peerHints::remove)
            }
        } ?: endpoint.bluetoothIdentity?.let(peerHints::remove)
    }

    private fun decode(
        raw: kotlinx.serialization.json.JsonObject,
        expected: HardwareId? = null,
    ): LampState {
        if (!raw.containsKey("on") || !raw.containsKey("brightness")) throw BleControlUnavailable()
        BleProvisionProtocol.identity(raw, expected)
        return StateCodec.decode(raw.toString(), clock())
    }

    /** 手动选择只用作地址线索；完整 STA MAC 必须由加密响应确认。 */
    suspend fun connectPeer(
        peer: BlePeer,
        expected: HardwareId? = null,
    ): Pair<Endpoint, LampState> {
        val link = linkFactory()
        try {
            val state = decode(link.connectForControl(peer), expected)
            return finishAdd(link, peer, state)
        } catch (failure: Throwable) {
            link.close()
            throw failure
        }
    }

    /** 接管添加流程已完成的安全会话，不重新连接、不重做握手。 */
    suspend fun adoptPeer(link: ProvisionLink, peer: BlePeer, expected: HardwareId? = null): Pair<Endpoint, LampState> {
        if (!link.supportsLightControl) throw BleControlUnavailable()
        link.request(byteArrayOf(5))
        val state = decode(link.request(byteArrayOf(0x10)), expected)
        return finishAdd(link, peer, state)
    }

    private suspend fun finishAdd(link: ProvisionLink, peer: BlePeer, state: LampState): Pair<Endpoint, LampState> {
        val hardware = requireNotNull(state.hardware)
        val endpoint = Endpoint.bluetooth(hardware)
        val entry = entries.computeIfAbsent(endpoint) { Entry() }
        return entry.lock.withLock {
            // 添加流程仍持有这条安全会话，普通控制应继续暂停直到添加结束。
            val lit = if (state.on && state.brightness > 0) state else {
                val powerOn = BleControlCodec.command(Command.Power(true), state)
                decode(link.request(powerOn), hardware)
            }
            check(lit.on && lit.brightness > 0) { "尚未确认灯具已点亮，请重试" }
            remember(entry, link, peer, lit)
            endpoint to lit
        }
    }

    private fun remember(entry: Entry, link: ProvisionLink, peer: BlePeer, state: LampState) {
        peerHints.put(requireNotNull(state.hardware), peer)
        entry.link?.takeIf { it !== link }?.close()
        entry.link = link
        entry.hint = peer
    }

    private suspend fun locate(expected: HardwareId): BlePeer = coroutineScope {
        val scanner = linkFactory()
        val found = CompletableDeferred<BlePeer>()
        val suffix = expected.value.removePrefix("mac:").takeLast(6)
        val job = launch {
            try {
                scanner.scan { if (it.name.endsWith(suffix, true)) found.complete(it) }
            } finally {
                if (!found.isCompleted)
                    found.completeExceptionally(IllegalStateException("未找到灯具，请开启蓝牙并靠近灯具"))
            }
        }
        try {
            withTimeout(10000) { found.await() }
        } finally {
            job.cancelAndJoin()
            scanner.close()
        }
    }

    private suspend fun openPeer(entry: Entry, peer: BlePeer, expected: HardwareId): Connection {
        val link = linkFactory()
        try {
            val state = decode(link.connectForControl(peer), expected)
            currentCoroutineContext().ensureActive()
            remember(entry, link, peer, state)
            return Connection(link, state)
        } catch (failure: Throwable) {
            link.close()
            throw failure
        }
    }

    private suspend fun link(entry: Entry, endpoint: Endpoint): Connection {
        entry.link?.let {
            if (it.isConnected) return Connection(it)
            it.close()
            entry.link = null
        }
        val expected = requireNotNull(endpoint.bluetoothIdentity)
        val hint = entry.hint ?: peerHints.get(expected)
        if (hint != null) {
            try {
                // 已验证的地址先直连；地址变化或灯具复位时仍可重新发现。
                withTimeoutOrNull(5000) { openPeer(entry, hint, expected) }?.let { return it }
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (failure is CancellationException || failure is SecurityException || failure is BleControlUnavailable)
                    throw failure
            }
            entry.link?.close()
            entry.link = null
            entry.hint = null
            peerHints.remove(expected)
        }
        return openPeer(entry, locate(expected), expected)
    }

    private suspend fun exchange(endpoint: Endpoint, bytes: ByteArray): LampState =
        withContext(Dispatchers.IO) {
            require(endpoint.isBluetooth)
            check(!provisioning) { "正在蓝牙配网，控制暂时暂停" }
            val entry = entries.computeIfAbsent(endpoint) { Entry() }
            entry.lock.withLock {
                check(!provisioning) { "正在选择连接方式，控制暂时暂停" }
                try {
                    val connection = link(entry, endpoint)
                    val reading = bytes.contentEquals(byteArrayOf(0x10))
                    // 首次握手已经带回并核验状态，不再多走一次 GATT 写读。
                    if (reading && connection.initial != null) return@withLock connection.initial
                    try {
                        decode(connection.link.request(bytes), endpoint.bluetoothIdentity)
                    } catch (failure: Exception) {
                        currentCoroutineContext().ensureActive()
                        if (!reading || failure is CancellationException || failure is SecurityException)
                            throw failure
                        // 后台断链刚发生时回调可能尚未到达。只读查询立即恢复一次，
                        // 不等待会话下一轮退避；写入始终只发一次，避免重复调光。
                        connection.link.close()
                        entry.link = null
                        check(!provisioning) { "正在蓝牙配网，控制暂时暂停" }
                        requireNotNull(link(entry, endpoint).initial)
                    }
                } catch (failure: Throwable) {
                    entry.link?.close()
                    entry.link = null
                    if (failure is TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        throw IllegalStateException("蓝牙响应超时，请靠近灯具后重试", failure)
                    }
                    throw failure
                }
            }
        }

    override suspend fun read(endpoint: Endpoint) = exchange(endpoint, byteArrayOf(0x10))

    override suspend fun write(endpoint: Endpoint, command: Command, before: LampState) =
        exchange(endpoint, BleControlCodec.command(command, before))
}
