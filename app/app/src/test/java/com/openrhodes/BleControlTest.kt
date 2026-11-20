package com.openrhodes

import com.openrhodes.data.*
import com.openrhodes.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BleControlTest {
    private val id = requireNotNull(HardwareId.parse("90:70:69:23:AC:5C"))

    @Test
    fun bluetoothEndpointRoundTripsAndDoesNotMasqueradeAsHttp() {
        val endpoint = Endpoint.bluetooth(id)
        assertTrue(endpoint.isBluetooth)
        assertEquals(id, endpoint.bluetoothIdentity)
        assertEquals(endpoint, Endpoint.parse(endpoint.host))
        assertNull(Endpoint.parse("ble://90706923ac5c/extra"))
        assertNull(Endpoint.parse("ble://ffffffffffff"))
        assertFalse(requireNotNull(Endpoint.parse("192.168.4.1")).isBluetooth)
    }

    @Test
    fun bleAndWifiRecordsMergeByStaMacAndSurviveStorage() {
        val http = requireNotNull(Endpoint.parse("192.168.1.10"))
        val ble = Endpoint.bluetooth(id)
        val old = Lamp("old", "Bedside", id, listOf(http))
        val rows = CatalogRules.enroll(listOf(old), Lamp("new", "RhodesLight", id, listOf(ble)))
        assertEquals(1, rows.size)
        assertEquals(listOf(ble, http), rows.single().endpoints)
        assertEquals(rows, CatalogCodec.decode(CatalogCodec.encode(rows)))
    }

    @Test
    fun controlsHaveExactBoundedWireFrames() {
        val before = LampState(true, 20)
        assertArrayEquals(
            byteArrayOf(0x11, 100),
            BleControlCodec.command(Command.Level(200), before),
        )
        assertArrayEquals(
            byteArrayOf(0x12, 0),
            BleControlCodec.command(Command.Power(false), before),
        )
        assertArrayEquals(
            byteArrayOf(0x11, 5),
            BleControlCodec.command(Command.Power(true), before.copy(brightness = 0)),
        )
        assertArrayEquals(byteArrayOf(0x13, 1), BleControlCodec.command(Command.Mode(true), before))
        assertArrayEquals(
            byteArrayOf(0x14, 0x90.toByte(), 0x1a, 13, 60, 0),
            BleControlCodec.command(Command.Parameters(Breath()), before),
        )
        assertArrayEquals(byteArrayOf(0x11, 50), BleControlCodec.command(Command.Cycle, before))
    }

    @Test
    fun bluetoothReadAndWriteNeverUseHttp() = runBlocking {
        val bleEndpoint = Endpoint.bluetooth(id)
        var bleReads = 0
        var bleWrites = 0
        var httpReads = 0
        val ble =
            object : LampTransport {
                override suspend fun read(endpoint: Endpoint): LampState {
                    bleReads++
                    return LampState(true, 10, hardware = id)
                }

                override suspend fun write(
                    endpoint: Endpoint,
                    command: Command,
                    before: LampState,
                ): LampState {
                    bleWrites++
                    return before.copy(brightness = 20)
                }
            }
        val http =
            object : LampTransport {
                override suspend fun read(endpoint: Endpoint): LampState {
                    httpReads++
                    return LampState(false, 0)
                }

                override suspend fun write(
                    endpoint: Endpoint,
                    command: Command,
                    before: LampState,
                ): LampState = error("HTTP must not receive BLE writes")
            }
        val route = RoutedLampTransport(http, ble)
        val before = route.read(bleEndpoint)
        assertEquals(20, route.write(bleEndpoint, Command.Level(20), before).brightness)
        assertEquals(1, bleReads)
        assertEquals(1, bleWrites)
        assertEquals(0, httpReads)
        route.read(requireNotNull(Endpoint.parse("192.168.1.10")))
        assertEquals(1, httpReads)
    }
}
