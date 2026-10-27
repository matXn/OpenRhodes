package com.openrhodes

import com.openrhodes.data.BleProvisionProtocol
import com.openrhodes.domain.HardwareId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BleProvisionProtocolTest {
    @Test
    fun truncatedRepliesFailClosedWithoutLeakingParserDiagnostics() {
        val truncated = """{"rhodes":{"ver":1,"sec_ver":1,"""
        val versionError = runCatching {
            BleProvisionProtocol.sessionProof(truncated)
        }
            .exceptionOrNull()
        assertTrue(versionError is BleProvisionProtocol.IncompleteVersion)
        for (text in listOf("""{"v":1,"secret":"sensitive",""", "[]")) {
            val error = runCatching {
                BleProvisionProtocol.decode(text.toByteArray())
            }
                .exceptionOrNull()
            assertEquals("蓝牙响应不完整，请重新连接灯具", error?.message)
        }
    }

    @Test
    fun oldTransportPartialReadRecoversWithoutChangingSecurityPolicy() = runBlocking {
        val noCode = """{"rhodes":{"ver":1,"sec_ver":1,"pop_required":false}}"""
        var reads = 0
        assertNull(
            BleProvisionProtocol.negotiateVersion({
                reads++
                if (reads == 1) noCode.substring(0, 31) else noCode
            })
        )
        assertEquals(2, reads)

        val legacy = """{"rhodes":{"ver":1,"sec_ver":1}}"""
        reads = 0
        assertEquals(
            "proof",
            BleProvisionProtocol.negotiateVersion(
                {
                    reads++
                    legacy
                },
                "proof",
            ),
        )
        assertEquals(1, reads)
        assertTrue(runCatching { BleProvisionProtocol.negotiateVersion({ legacy }) }.isFailure)

        reads = 0
        val incomplete = runCatching {
            BleProvisionProtocol.negotiateVersion({
                reads++
                noCode.substring(0, 31)
            })
        }
            .exceptionOrNull()
        assertTrue(incomplete is BleProvisionProtocol.IncompleteVersion)
        assertEquals(3, reads)
    }

    @Test
    fun noCodeNegotiationIsExplicitAndLegacyProofIsNotSilentlyDiscarded() {
        val noCode = """{"rhodes":{"ver":1,"sec_ver":1,"pop_required":false}}"""
        val legacy = """{"rhodes":{"ver":1,"sec_ver":1}}"""
        assertNull(BleProvisionProtocol.sessionProof(noCode))
        assertNull(BleProvisionProtocol.sessionProof(noCode, "old-code"))
        assertEquals("old-code", BleProvisionProtocol.sessionProof(legacy, "old-code"))
        for (version in
            listOf(
                legacy,
                """{"rhodes":{"ver":1,"sec_ver":0,"pop_required":false}}""",
                """{"rhodes":{"ver":1,"sec_ver":1,"pop_required":"false"}}""",
            )) {
            assertTrue(runCatching { BleProvisionProtocol.sessionProof(version) }.isFailure)
        }
    }

    @Test
    fun byteLengthsAndExactCredentialsAreRetained() {
        assertArrayEquals(
            byteArrayOf(
                6,
                4,
                'h'.code.toByte(),
                'o'.code.toByte(),
                'm'.code.toByte(),
                'e'.code.toByte(),
            ),
            BleProvisionProtocol.connectSaved("home"),
        )
        val encoded = BleProvisionProtocol.connect("灯 & WiFi", " a+b&1234 ")
        val name = "灯 & WiFi".toByteArray()
        assertEquals(4, encoded[0].toInt())
        assertEquals(name.size, encoded[1].toInt())
        assertEquals(10, encoded[2].toInt())
        assertArrayEquals(name, encoded.copyOfRange(3, 3 + name.size))
        assertEquals(
            " a+b&1234 ",
            encoded.copyOfRange(3 + name.size, encoded.size).toString(Charsets.UTF_8),
        )
        assertEquals(0, BleProvisionProtocol.connect("open", "")[2].toInt())
    }

    @Test
    fun invalidCredentialsAreRejectedBeforeBluetoothWrite() {
        for ((ssid, password) in
            listOf(
                "" to "12345678",
                "灯".repeat(11) to "12345678",
                "wifi" to "short",
                "wifi" to "x".repeat(64),
                "wifi\u0000" to "12345678",
            )) {
            assertTrue(runCatching { BleProvisionProtocol.connect(ssid, password) }.isFailure)
        }
        assertEquals(71, BleProvisionProtocol.connect("wifi", "a".repeat(64)).size)
    }

    @Test
    fun authenticatedIdentityMustMatchSelectedLampAndStateErrorsRemainVisible() {
        val state =
            BleProvisionProtocol.decode(
                """{"v":1,"mac":"90:70:69:22:9c:f4","status":"failed","error":"wrong password"}"""
                    .toByteArray()
            )
        assertEquals(HardwareId.parse("90:70:69:22:9c:f4"), BleProvisionProtocol.identity(state))
        assertTrue(
            runCatching {
                BleProvisionProtocol.identity(state, HardwareId.parse("90:70:69:22:9c:f5"))
            }
                .isFailure
        )
        assertTrue(
            runCatching {
                BleProvisionProtocol.decode("""{"error":"request rejected"}""".toByteArray())
            }
                .isFailure
        )
        assertTrue(
            runCatching { BleProvisionProtocol.decode("""{"v":2}""".toByteArray()) }.isFailure
        )
    }
}
