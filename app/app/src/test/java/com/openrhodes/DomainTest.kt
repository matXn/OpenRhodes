package com.openrhodes

import com.openrhodes.data.CatalogCodec
import com.openrhodes.data.LegacyImport
import com.openrhodes.data.StateCodec
import com.openrhodes.domain.*
import com.openrhodes.ui.slideBrightness
import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    @Test
    fun moduloPhaseReportsDoNotResetGraphToFirstCycle() {
        val first = LampState(true, 30, breathing = true, phase = 0.0, sampledAt = 0)
        val second = first.copy(phase = 0.1, sampledAt = 6100).continuing(first)
        val third = first.copy(phase = 0.2, sampledAt = 12200).continuing(second)
        assertEquals(2 * Math.PI + 0.1, second.phase, 0.001)
        assertEquals(4 * Math.PI + 0.2, third.phase, 0.001)
    }

    private val a = HardwareId.parse("E8:3D:C1:EB:CE:5C")!!
    private val b = HardwareId.parse("00:11:22:33:44:66")!!

    private fun endpoint(ip: String) = Endpoint.parse(ip)!!

    private fun lamp(id: String, mac: HardwareId?, ip: String) =
        Lamp(id, id, mac, listOf(endpoint(ip)))

    @Test
    fun fullMacNormalizesAndRejectsInvalidOrMulticast() {
        assertEquals(a, HardwareId.parse("rhodes-e83dc1ebce5c"))
        assertEquals(a, HardwareId.parse("mac:e83dc1ebce5c"))
        listOf("ebce5c", "RhodesLight", "000000000000", "ffffffffffff", "01:11:22:33:44:55")
            .forEach { assertNull(HardwareId.parse(it)) }
    }

    @Test
    fun addressesAreNotCredentialsOrArbitraryUrls() {
        assertEquals(endpoint("192.168.4.1"), Endpoint.parse("http://192.168.4.1/"))
        listOf(
                "http://user:password@192.168.4.1",
                "https://rhodeslight.local",
                "999.1.2.3",
                "192.168.4.1/setup",
                "example.com",
                "192.168.4.1?foo=1",
            )
            .forEach { assertNull(Endpoint.parse(it)) }
    }

    @Test
    fun sharedApIpDoesNotMergeUnknownWithKnownLamp() {
        val unknown = lamp("legacy-A", null, "192.168.4.1")
        val known = lamp("known-B", b, "192.168.2.11")
        val after =
            CatalogRules.enroll(
                listOf(unknown, known),
                known.copy(endpoints = listOf(endpoint("192.168.4.1"))),
            )
        assertEquals(2, after.size)
        assertEquals(unknown, CatalogRules.resolve(after, "legacy-A"))
    }

    @Test
    fun knownDifferentMacsRemainSeparateAtSameIp() {
        val after =
            CatalogRules.enroll(listOf(lamp("A", a, "192.168.4.1")), lamp("B", b, "192.168.4.1"))
        assertEquals(2, after.size)
    }

    @Test
    fun equalMacMergesAndRetainsAliasesAndAddresses() {
        val old = lamp("original", a, "192.168.2.11").copy(aliases = setOf("old-widget-id"))
        val merged = CatalogRules.enroll(listOf(old), lamp("new-record", a, "192.168.3.20"))
        assertEquals(1, merged.size)
        assertEquals("original", CatalogRules.resolve(merged, "new-record")!!.id)
        assertEquals("original", CatalogRules.resolve(merged, "old-widget-id")!!.id)
        assertEquals(
            listOf(endpoint("192.168.3.20"), endpoint("192.168.2.11")),
            merged.single().endpoints,
        )
    }

    @Test
    fun catalogRoundTripKeepsUnknownIdentityAndAliasIds() {
        val rows =
            listOf(
                lamp("a", a, "192.168.2.11").copy(aliases = setOf("older")),
                lamp("unknown", null, "192.168.4.1"),
            )
        assertEquals(rows, CatalogCodec.decode(CatalogCodec.encode(rows)))
    }

    @Test
    fun catalogRejectsUnsupportedVersionInsteadOfResetting() {
        assertThrows(IllegalArgumentException::class.java) {
            CatalogCodec.decode("""{"version":7,"lamps":[]}""")
        }
    }

    @Test
    fun migrationKeepsHistoricalWidgetIds() {
        val rows =
            listOf(
                LegacyImport.LegacyRow("a", "Bedside", a, listOf(endpoint("192.168.2.11")), null),
                LegacyImport.LegacyRow(
                    "old",
                    "Old name",
                    null,
                    listOf(endpoint("192.168.4.1")),
                    "a",
                ),
                LegacyImport.LegacyRow(
                    "duplicate",
                    "Duplicate",
                    a,
                    listOf(endpoint("192.168.3.11")),
                    null,
                ),
            )
        val merged = LegacyImport.migrate(rows)
        assertEquals(1, merged.size)
        assertEquals("a", CatalogRules.resolve(merged, "old")!!.id)
        assertEquals("a", CatalogRules.resolve(merged, "duplicate")!!.id)
        assertEquals(3, merged.single().endpoints.size)
    }

    @Test
    fun migrationRejectsCyclesAndDifferentIdentities() {
        val first = LegacyImport.LegacyRow("a", "a", a, listOf(endpoint("192.168.4.1")), "b")
        val second = LegacyImport.LegacyRow("b", "b", b, listOf(endpoint("192.168.4.1")), "a")
        assertThrows(IllegalArgumentException::class.java) {
            LegacyImport.migrate(listOf(first, second))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LegacyImport.migrate(listOf(first.copy(parent = null), second))
        }
    }

    @Test
    fun cycleStartsLowAndWrapsAndHandlesIntermediateLevel() {
        fun next(on: Boolean, level: Int) = nextBrightness(LampState(on, level))
        assertEquals(5, next(false, 100))
        assertEquals(20, next(true, 6))
        assertEquals(0, next(true, 100))
        assertEquals(50, next(true, 20))
        assertEquals(100, next(true, 50))
    }

    @Test
    fun sliderReservesLongTravelForDimRange() {
        assertEquals(30, slideBrightness(0, .6f))
        assertEquals(100, slideBrightness(0, 1f))
        assertEquals(0, slideBrightness(100, -1f))
        for (i in 0..100) assertEquals(i, slideBrightness(i, 0f))
    }

    @Test
    fun protocolRequiresLightState() {
        assertThrows(IllegalArgumentException::class.java) {
            StateCodec.decode("""{"ok":true}""", 0)
        }
        val state =
            StateCodec.decode(
                """{"on":true,"brightness":6,"mac":"E8:3D:C1:EB:CE:5C","breath_period":0}""",
                123,
            )
        assertEquals(6, state.brightness)
        assertEquals(a, state.hardware)
        assertEquals(2000, state.breath.periodMs)
    }

    @Test
    fun offCommandHasNoBrightnessAndOnFallbackIsDim() {
        val before = LampState(false, 0)
        assertEquals(mapOf("on" to "0"), StateCodec.form(Command.Power(false), before).second)
        assertEquals("5", StateCodec.form(Command.Power(true), before).second["brightness"])
    }

    @Test
    fun breathIsContinuousAndNeverDropsBelowFloor() {
        val parameters = Breath(6000, 20, 150)
        assertEquals(1f, parameters.wave(0.0), .0001f)
        assertEquals(.2f, parameters.wave(Math.PI), .0001f)
        assertEquals(parameters.wave(.3), parameters.wave(.3 + 2 * Math.PI), .0001f)
    }

    @Test
    fun breathingDefaultsAreUpdatedWithoutReplacingExplicitDeviceParameters() {
        val defaults = Breath(6800, 13, 60)
        assertEquals(defaults, Breath())
        assertEquals(defaults, StateCodec.decode("""{"on":true,"brightness":30}""", 0).breath)
        assertEquals(
            Breath(6000, 0, 100),
            StateCodec.decode(
                    """{"on":true,"brightness":30,"breath_period":6000,"breath_floor":0,"breath_curve":100}""",
                    0,
                )
                .breath,
        )
    }
}
