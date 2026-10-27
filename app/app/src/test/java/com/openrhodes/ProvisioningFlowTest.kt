package com.openrhodes

import com.openrhodes.data.*
import com.openrhodes.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProvisioningFlowTest {
    private val hardware = HardwareId.parse("02:00:00:00:00:44")!!
    private val other = HardwareId.parse("02:00:00:00:00:46")!!
    private val endpoint = Endpoint.parse("192.0.2.10")!!
    private val peer = BlePeer("02:00:00:00:00:45", "RhodesLight", -50)

    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    private inner class Gateway : ProvisioningGateway {
        override val nearby = MutableStateFlow<Map<HardwareId, Endpoint>>(emptyMap())
        var online = false
        var readIdentity = hardware
        var reads = 0
        var enrolled = 0
        var reachable = false

        override fun name(hardware: HardwareId): String? = null

        override fun rescan() = Unit

        override suspend fun read(endpoint: Endpoint): LampState {
            reads++
            check(online) { "offline" }
            return LampState(true, 56, hardware = readIdentity)
        }

        override suspend fun enroll(
            hardware: HardwareId,
            endpoint: Endpoint,
            state: LampState?,
        ): String {
            assertEquals(this@ProvisioningFlowTest.hardware, hardware)
            enrolled++
            return "canonical-lamp"
        }

        override suspend fun reachable(id: String) = reachable

        override suspend fun legacyProof(endpoint: Endpoint, expected: HardwareId?): String? = null
    }

    private inner class Radio {
        val requests = mutableListOf<Int>()
        val links = mutableListOf<Link>()
        var networkSaved = false
        var failJoin = false
        var unknownAttempt = false
        var connected = false
        var blockScan = false
        var identityValue = "02:00:00:00:00:44"

        fun link(): ProvisionLink = Link().also { links += it }

        inner class Link : ProvisionLink {
            var closed = false

            override suspend fun scan(found: (BlePeer) -> Unit) {
                found(peer)
                if (blockScan) awaitCancellation()
            }

            override suspend fun connect(peer: BlePeer, proof: String?) =
                json("""{"v":1,"mac":"$identityValue","status":"ready","ip":""}""")

            override suspend fun request(data: ByteArray): JsonObject {
                val op = data[0].toInt()
                requests += op
                return when (op) {
                    2 -> json("""{"v":1}""")
                    3 ->
                        json(
                            """{"v":1,"generation":1,"next":-1,"networks":[{"ssid":"Home","secure":true,"rssi":-50,"saved":$networkSaved}]}"""
                        )
                    4,
                    6 -> {
                        connected = true
                        json("""{"v":1,"accepted":true,"attempt_id":7}""")
                    }
                    1 ->
                        json(
                            """{"v":1,"mac":"$identityValue","attempt_id":${if (unknownAttempt) 8 else 7},"status":"${if (failJoin) "failed" else "connected"}","ip":"192.0.2.10","ssid":"Home"}"""
                        )
                    else -> json("""{"v":1}""")
                }
            }

            override fun close() {
                closed = true
            }
        }
    }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private suspend fun await(flow: ProvisioningFlow, predicate: (SetupUi) -> Boolean) =
        withTimeout(5000) { flow.ui.first(predicate) }

    @Test
    fun mergesOnlyVerifiedFullIdentityAndPrefersLocal() {
        val rows =
            setupCandidates(
                mapOf(hardware to endpoint),
                listOf(SetupPeer(peer, hardware), SetupPeer(peer.copy(address = "other"), null)),
            ) {
                null
            }
        assertEquals(2, rows.size)
        assertEquals(endpoint, rows[0].endpoint)
        assertEquals(peer, rows[0].peer)
        assertNull(rows[1].hardware)
        assertEquals(
            2,
            setupCandidates(mapOf(hardware to endpoint), listOf(SetupPeer(peer, other))) { null }
                .size,
        )
    }

    @Test
    fun localLampAddsInOneActionWithoutBluetooth() = runBlocking {
        val scope = scope()
        val gateway =
            Gateway().apply {
                online = true
                nearby.value = mapOf(hardware to endpoint)
            }
        val radio = Radio()
        val flow = ProvisioningFlow(scope, gateway, radio::link)
        try {
            flow.search(false)
            flow.select(flow.ui.value.candidates.single())
            assertEquals("canonical-lamp", await(flow) { it.phase == SetupPhase.DONE }.completedId)
            assertEquals(1, gateway.reads)
            assertEquals(1, gateway.enrolled)
            assertTrue(radio.links.isEmpty())
        } finally {
            flow.close()
            scope.cancel()
        }
    }

    @Test
    fun staleAddressWithDifferentIdentityIsNeverAdded() = runBlocking {
        val scope = scope()
        val gateway =
            Gateway().apply {
                online = true
                readIdentity = other
                nearby.value = mapOf(hardware to endpoint)
            }
        val radio = Radio()
        val flow = ProvisioningFlow(scope, gateway, radio::link)
        try {
            flow.select(flow.ui.value.candidates.single())
            await(flow) { it.error != null }
            assertEquals(0, gateway.enrolled)
        } finally {
            flow.close()
            scope.cancel()
        }
    }

    @Test
    fun bluetoothSelectionAutomaticallyScansAndPasswordFailureStaysEditable() = runBlocking {
        val scope = scope()
        val gateway = Gateway()
        val radio = Radio().apply { failJoin = true }
        val flow = ProvisioningFlow(scope, gateway, radio::link, pollDelay = 1)
        try {
            flow.search(true)
            flow.select(
                await(flow) { it.candidates.any { c -> c.hardware != null } }.candidates.single()
            )
            val networks = await(flow) { it.networks.isNotEmpty() }
            assertEquals(listOf(2, 3), radio.requests)
            flow.network(networks.networks.single())
            assertEquals(SetupPhase.PASSWORD, flow.ui.value.phase)
            flow.join("wrong-password")
            val failed = await(flow) { it.phase == SetupPhase.PASSWORD && it.error != null }
            assertEquals("Home", failed.network?.ssid)
            assertEquals(0, gateway.enrolled)
            assertTrue(flow.back())
            assertEquals(SetupPhase.NETWORKS, flow.ui.value.phase)
        } finally {
            flow.close()
            scope.cancel()
        }
    }

    @Test
    fun savedNetworkConnectsWithoutPasswordAndWaitsForLocalAccess() = runBlocking {
        val scope = scope()
        val gateway = Gateway()
        val radio = Radio().apply { networkSaved = true }
        val flow = ProvisioningFlow(scope, gateway, radio::link, pollDelay = 1)
        try {
            flow.select(SetupCandidate("ble", "Lamp", hardware, null, peer))
            flow.network(await(flow) { !it.busy && it.networks.isNotEmpty() }.networks.single())
            await(flow) { it.phase == SetupPhase.WAITING }
            assertEquals(1, gateway.enrolled)
            assertTrue(6 in radio.requests)
            assertFalse(4 in radio.requests)
            assertNull(flow.ui.value.error)
            gateway.reachable = true
            await(flow) { it.phase == SetupPhase.DONE }
            assertEquals(1, radio.requests.count { it == 6 })
        } finally {
            flow.close()
            scope.cancel()
        }
    }

    @Test
    fun mismatchedAttemptCannotCompleteOrResend() = runBlocking {
        val scope = scope()
        val gateway = Gateway()
        val radio = Radio().apply { unknownAttempt = true }
        val flow = ProvisioningFlow(scope, gateway, radio::link, pollDelay = 1)
        try {
            flow.select(SetupCandidate("ble", "Lamp", hardware, null, peer))
            flow.network(await(flow) { !it.busy && it.networks.isNotEmpty() }.networks.single())
            flow.join("test-password")
            await(flow) { it.error != null }
            assertEquals(0, gateway.enrolled)
            assertEquals(1, radio.requests.count { it == 4 })
        } finally {
            flow.close()
            scope.cancel()
        }
    }

    @Test
    fun closingCancelsScanAndClosesAllLinks() = runBlocking {
        val scope = scope()
        val gateway = Gateway()
        val radio = Radio().apply { blockScan = true }
        val flow = ProvisioningFlow(scope, gateway, radio::link)
        try {
            flow.search(true)
            await(flow) { it.candidates.isNotEmpty() }
            flow.close()
            withTimeout(1000) { while (radio.links.any { !it.closed }) yield() }
            assertEquals(0, gateway.enrolled)
        } finally {
            flow.close()
            scope.cancel()
        }
    }
}
