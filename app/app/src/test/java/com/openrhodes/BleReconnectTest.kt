package com.openrhodes

import android.app.Application
import com.openrhodes.data.*
import com.openrhodes.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BleReconnectTest {
    private val hardware = HardwareId.parse("E8:3D:C1:EB:CE:5C")!!
    private val endpoint = Endpoint.bluetooth(hardware)
    private val peer = BlePeer("E8:3D:C1:EB:CE:5E", "RhodesLight-EBCE5C", -50)
    private val state = Json.parseToJsonElement(
        """{"v":1,"on":true,"brightness":37,"mac":"E8:3D:C1:EB:CE:5C"}"""
    ).jsonObject

    private class Hints : BlePeerHints {
        val values = mutableMapOf<HardwareId, BlePeer>()
        override fun get(hardware: HardwareId) = values[hardware]
        override fun put(hardware: HardwareId, peer: BlePeer) { values[hardware] = peer }
        override fun remove(hardware: HardwareId) { values.remove(hardware) }
    }

    private inner class Radio : ProvisionLink {
        override val supportsLightControl = true
        var connected = true
        override val isConnected get() = connected
        var closed = false
        var scans = 0
        var connects = 0
        val requests = mutableListOf<Int>()
        var onConnect: suspend () -> JsonObject = { state }
        var onRequest: suspend (Int) -> JsonObject = { state }

        override suspend fun scan(found: (BlePeer) -> Unit) {
            scans++
            found(peer)
            awaitCancellation()
        }
        override suspend fun connect(peer: BlePeer, proof: String?): JsonObject =
            error("直控不能进入配网握手")
        override suspend fun connectForControl(peer: BlePeer): JsonObject {
            connects++
            return onConnect()
        }
        override suspend fun request(data: ByteArray): JsonObject {
            val operation = data[0].toInt() and 255
            requests += operation
            return onRequest(operation)
        }
        override fun close() { closed = true; connected = false }
    }

    private fun transport(hints: BlePeerHints, vararg radios: Radio): BleControlTransport {
        val available = ArrayDeque(radios.toList())
        return BleControlTransport(RuntimeEnvironment.getApplication(), { 100L }, {
            check(available.isNotEmpty()) { "意外重复扫描或连接" }
            available.removeFirst()
        }, hints)
    }

    @Test fun firstReadUsesHandshakeStateAndSavesOnlyVerifiedRadioAddress() = runBlocking {
        val hints = Hints()
        val scanner = Radio()
        val radio = Radio()
        val control = transport(hints, scanner, radio)
        assertEquals(37, control.read(endpoint).brightness)
        assertEquals(1, scanner.scans)
        assertTrue(scanner.closed)
        assertEquals(1, radio.connects)
        assertTrue("首次连接不能重复读取状态", radio.requests.isEmpty())
        assertEquals(peer, hints.get(hardware))
        control.read(endpoint)
        assertEquals(listOf(0x10), radio.requests)
    }

    @Test fun coldTransportReusesPersistedAddressWithoutScanning() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val firstHints = StoredBlePeerHints(context)
        val firstRadio = Radio()
        val first = transport(firstHints, Radio(), firstRadio)
        first.read(endpoint)
        first.disconnect(endpoint)
        val restoredHints = StoredBlePeerHints(context)
        assertEquals(peer.address, restoredHints.get(hardware)?.address)
        val nextRadio = Radio()
        assertEquals(37, transport(restoredHints, nextRadio).read(endpoint).brightness)
        assertEquals(0, nextRadio.scans)
        assertEquals(1, nextRadio.connects)
        assertTrue(nextRadio.requests.isEmpty())
    }

    @Test fun staleCachedAddressFallsBackToOneScan() = runBlocking {
        val hints = Hints().apply { put(hardware, peer) }
        val stale = Radio().apply { onConnect = { error("地址已变化") } }
        val scanner = Radio()
        val current = Radio()
        assertEquals(37, transport(hints, stale, scanner, current).read(endpoint).brightness)
        assertTrue(stale.closed)
        assertEquals(1, scanner.scans)
        assertEquals(1, current.connects)
        assertEquals(peer, hints.get(hardware))
    }

    @Test fun disconnectedGattReconnectsBeforeSendingARequest() = runBlocking {
        val hints = Hints().apply { put(hardware, peer) }
        val stale = Radio()
        val replacement = Radio()
        val control = transport(hints, stale, replacement)
        control.read(endpoint)
        stale.connected = false
        assertEquals(37, control.read(endpoint).brightness)
        assertTrue(stale.requests.isEmpty())
        assertTrue(stale.closed)
        assertEquals(1, replacement.connects)
    }

    @Test fun readLostDuringForegroundCheckRecoversInTheSameCall() = runBlocking {
        val hints = Hints().apply { put(hardware, peer) }
        val stale = Radio().apply { onRequest = { error("刚刚断链") } }
        val replacement = Radio()
        val control = transport(hints, stale, replacement)
        control.read(endpoint)
        assertEquals(37, control.read(endpoint).brightness)
        assertEquals(listOf(0x10), stale.requests)
        assertEquals(1, replacement.connects)
        assertTrue(replacement.requests.isEmpty())
    }

    @Test fun writeFailureIsNeverReplayed() = runBlocking {
        val hints = Hints().apply { put(hardware, peer) }
        val radio = Radio().apply { onRequest = { error("指令响应丢失") } }
        val control = transport(hints, radio)
        val before = control.read(endpoint)
        assertTrue(runCatching { control.write(endpoint, Command.Level(50), before) }.isFailure)
        assertEquals(listOf(0x11), radio.requests)
        assertEquals(1, radio.connects)
        assertTrue(radio.closed)
    }

    @Test fun wrongIdentityCannotPopulateTheHintOrReceiveACommand() = runBlocking {
        val hints = Hints()
        val radio = Radio().apply {
            onConnect = { JsonObject(state + ("mac" to JsonPrimitive("02:00:00:00:00:44"))) }
        }
        val control = transport(hints, Radio(), radio)
        assertTrue(runCatching {
            control.write(endpoint, Command.Level(50), LampState(true, 30, hardware = hardware))
        }.isFailure)
        assertNull(hints.get(hardware))
        assertTrue(radio.requests.isEmpty())
        assertTrue(radio.closed)
    }

    @Test fun cancellationClosesTheAttemptWithoutStartingFallbackScan() = runBlocking {
        val hints = Hints().apply { put(hardware, peer) }
        val entered = CompletableDeferred<Unit>()
        val radio = Radio().apply { onConnect = { entered.complete(Unit); awaitCancellation() } }
        val control = transport(hints, radio)
        val attempt = launch { control.read(endpoint) }
        entered.await()
        attempt.cancelAndJoin()
        assertTrue(radio.closed)
        assertEquals(peer, hints.get(hardware))
        assertEquals(0, radio.scans)
    }

    @Test fun unsupportedFirmwareDoesNotTriggerScanRetry() = runBlocking {
        val hints = Hints().apply { put(hardware, peer) }
        val radio = Radio().apply { onConnect = { throw BleControlUnavailable() } }
        val failure = runCatching { transport(hints, radio).read(endpoint) }.exceptionOrNull()
        assertTrue(failure is BleControlUnavailable)
        assertTrue(radio.closed)
        assertEquals(0, radio.scans)
    }

    @Test fun addingAnOffLampLightsItOnTheProvisioningLinkWhileControlIsPaused() = runBlocking {
        for (brightness in listOf(37, 0)) {
            val radio = Radio().apply {
                onRequest = { operation ->
                    if (operation == 0x10) JsonObject(state + mapOf(
                        "on" to JsonPrimitive(false), "brightness" to JsonPrimitive(brightness),
                    )) else state
                }
            }
            val control = transport(Hints())
            control.pauseForProvisioning()
            val (added, lit) = control.adoptPeer(radio, peer, hardware)
            assertEquals(endpoint, added)
            assertTrue(lit.on && lit.brightness > 0)
            assertEquals(listOf(5, 0x10, if (brightness == 0) 0x11 else 0x12), radio.requests)
            assertTrue(runCatching { control.read(endpoint) }.isFailure)
            control.resumeAfterProvisioning()
            assertEquals(hardware, control.read(endpoint).hardware)
            assertEquals(0, radio.connects)
        }
    }

    @Test fun provisioningHandoffReusesTheExistingSessionAndDeletionForgetsItsAddress() = runBlocking {
        val hints = Hints()
        val radio = Radio()
        val control = transport(hints)
        control.adoptPeer(radio, peer, hardware)
        assertEquals(0, radio.connects)
        assertEquals(listOf(5, 0x10), radio.requests)
        control.disconnect(endpoint)
        assertEquals(peer, hints.get(hardware))
        control.forget(endpoint)
        assertNull(hints.get(hardware))
    }
}
