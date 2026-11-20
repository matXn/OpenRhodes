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
        var existing = false
        var failSave = false
        var adopted: ProvisionLink? = null
        var rescans=0
        override suspend fun adoptBluetooth(link: ProvisionLink, peer: BlePeer, hardware: HardwareId, name: String): AddCompletion {
            check(!failSave) { "save failed" }
            adopted = link
            enrolled++
            return AddCompletion("canonical-lamp", !existing, LampState(false, 56, hardware = hardware),name)
        }

        override fun name(hardware: HardwareId): String? = if (existing) "Lamp" else null

        override fun rescan() {rescans++}

        override suspend fun read(endpoint: Endpoint): LampState {
            reads++
            check(online) { "offline" }
            return LampState(true, 56, hardware = readIdentity)
        }

        override suspend fun enroll(
            hardware: HardwareId,
            endpoint: Endpoint,
            state: LampState,
            name: String,
        ): AddCompletion {
            check(!failSave) { "save failed" }
            assertEquals(this@ProvisioningFlowTest.hardware, hardware)
            enrolled++
            return AddCompletion("canonical-lamp", !existing, state,name)
        }


        override suspend fun legacyProof(endpoint: Endpoint, expected: HardwareId?): String? = null
    }

    private inner class Radio {
        val requests = mutableListOf<Int>()
        val links = mutableListOf<Link>()
        var connects = 0
        var networkSaved = false
        var failJoin = false
        var unknownAttempt = false
        var connected = false
        var blockScan = false
        var identityValue = "02:00:00:00:00:44"
        var scans=0
        var activeScans=0
        var peakScans=0
        var firstScanEmpty=false
        var scanFailures=0
        var failConnect=false

        fun link(): ProvisionLink = Link().also { links += it }

        inner class Link : ProvisionLink {
            var closed = false
            override val supportsLightControl = true

            override suspend fun scan(found: (BlePeer) -> Unit) {
                scans++;activeScans++;peakScans=maxOf(peakScans,activeScans)
                try {
                    if(scanFailures>0){scanFailures--;error("蓝牙刚开启，暂未就绪")}
                    if(!firstScanEmpty||scans>1)found(peer)
                    if (blockScan) awaitCancellation()
                } finally {activeScans--}
            }

            override suspend fun connect(peer: BlePeer, proof: String?): JsonObject {
                connects++
                check(!failConnect){"连接失败"}
                return json("""{"v":1,"mac":"$identityValue","status":"ready","ip":""}""")
            }

            override suspend fun request(data: ByteArray): JsonObject {
                val op = data[0].toInt()
                requests += op
                return when (op) {
                    16 -> json("""{"mac":"$identityValue","on":false,"brightness":56}""")
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

    @Test fun automaticallyFindsLampInLaterScanWithoutManualRefresh() = runBlocking {
        val scope=scope();val gateway=Gateway();val radio=Radio().apply {firstScanEmpty=true}
        val flow=ProvisioningFlow(scope,gateway,radio::link,discoveryRetryMs=10)
        try {
            flow.search(true)
            assertTrue(await(flow){it.candidates.isNotEmpty()}.searching)
            assertTrue(radio.scans>=2)
            assertTrue(radio.links.first().closed)
            assertEquals(0,radio.connects)
            assertEquals("续扫不能反复重启局域网发现",1,gateway.rescans)
        } finally {flow.close();scope.cancel()}
    }

    @Test fun transientScanFailureRetriesAutomatically() = runBlocking {
        val scope=scope();val gateway=Gateway();val radio=Radio().apply {scanFailures=1;blockScan=true}
        val flow=ProvisioningFlow(scope,gateway,radio::link,discoveryRetryMs=10)
        try {
            flow.search(true)
            val state=await(flow){it.candidates.isNotEmpty()}
            assertTrue(state.searching);assertEquals(2,radio.scans)
            assertNull(state.error);assertEquals(0,radio.connects)
            assertTrue(radio.links.first().closed)
        } finally {flow.close();scope.cancel()}
    }

    @Test fun resumeIsIdempotentAndRestartsOnlyAfterBackgroundPause() = runBlocking {
        val scope=scope();val gateway=Gateway();val radio=Radio().apply {blockScan=true}
        val flow=ProvisioningFlow(scope,gateway,radio::link)
        try {
            flow.search(true);await(flow){it.candidates.isNotEmpty()}
            repeat(3){flow.resumeDiscovery(true)}
            assertEquals(1,radio.scans);assertEquals(1,gateway.rescans)
            flow.pauseDiscovery()
            withTimeout(1000){while(radio.activeScans!=0)yield()}
            assertFalse(flow.ui.value.searching)
            flow.resumeDiscovery(true)
            withTimeout(1000){while(radio.scans<2)yield()}
            assertTrue(flow.ui.value.searching);assertEquals(1,radio.peakScans)
            assertEquals(0,radio.connects)
        } finally {flow.close();scope.cancel()}
    }

    @Test fun permissionGrantPromotesLanSearchToBluetoothWithoutDuplicateScans() = runBlocking {
        val scope=scope();val gateway=Gateway();val radio=Radio().apply {blockScan=true}
        val flow=ProvisioningFlow(scope,gateway,radio::link)
        try {
            flow.search(false);assertEquals(0,radio.scans)
            flow.resumeDiscovery(true);await(flow){it.candidates.isNotEmpty()}
            flow.resumeDiscovery(true)
            assertEquals(1,radio.scans);assertEquals(1,radio.peakScans)
        } finally {flow.close();scope.cancel()}
    }

    @Test fun failedConnectionResumesDiscoveryAndKeepsTheFailureMessage() = runBlocking {
        val scope=scope();val gateway=Gateway();val radio=Radio().apply {blockScan=true;failConnect=true}
        val flow=ProvisioningFlow(scope,gateway,radio::link)
        try {
            flow.search(true);val candidate=await(flow){it.candidates.isNotEmpty()}.candidates.single()
            flow.select(candidate)
            val state=await(flow){it.error!=null&&!it.busy&&it.searching}
            assertEquals(SetupPhase.DISCOVER,state.phase)
            assertEquals(1,radio.connects);assertEquals(2,radio.scans)
            assertEquals(1,radio.peakScans)
            assertEquals(0,gateway.enrolled)
        } finally {flow.close();scope.cancel()}
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
            flow.select(flow.ui.value.candidates.single(),SetupControlMethod.WIFI)
            assertEquals("canonical-lamp", await(flow) { it.phase == SetupPhase.DONE }.completedId)
            val completion = requireNotNull(flow.takeCompletion())
            assertTrue(completion.firstAdd)
            assertEquals(56, completion.snapshot.brightness)
            assertNull(flow.takeCompletion())
            flow.manual(endpoint.host)
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
            flow.select(flow.ui.value.candidates.single(),SetupControlMethod.WIFI)
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
                await(flow) { it.candidates.isNotEmpty() }.candidates.single(),SetupControlMethod.WIFI
            )
            val networks = await(flow) { it.networks.isNotEmpty() && !it.busy }
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
            flow.select(SetupCandidate("ble", "Lamp", hardware, null, peer),SetupControlMethod.WIFI)
            flow.network(await(flow) { !it.busy && it.networks.isNotEmpty() }.networks.single())
            await(flow) { it.phase == SetupPhase.WAITING }
            assertEquals(75,flow.ui.value.connection?.percent)
            delay(20);assertEquals(75,flow.ui.value.connection?.percent)
            assertFalse(flow.ui.value.connection!!.complete)
            assertEquals(0, gateway.enrolled)
            assertNull(flow.takeCompletion())
            assertTrue(6 in radio.requests)
            assertFalse(4 in radio.requests)
            assertNull(flow.ui.value.error)
            gateway.online = true
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
            flow.select(SetupCandidate("ble", "Lamp", hardware, null, peer),SetupControlMethod.WIFI)
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
    fun discoveryDoesNotConnectAndBluetoothChoiceTransfersTheSameSession() = runBlocking {
        val scope = scope()
        val gateway = Gateway()
        val radio = Radio().apply { blockScan = true }
        val flow = ProvisioningFlow(scope, gateway, radio::link)
        try {
            flow.search(true)
            val candidate = await(flow) { it.candidates.isNotEmpty() }.candidates.single()
            assertEquals(0, radio.connects)
            assertTrue(radio.requests.isEmpty())
            flow.select(candidate)
            await(flow) { it.phase == SetupPhase.DONE && !it.busy }
            assertEquals(1, radio.connects)
            assertTrue(radio.links.first().closed)
            assertEquals(1,gateway.enrolled)
            assertSame(radio.links.last(), gateway.adopted)
            flow.close()
            assertFalse(radio.links.last().closed)
            assertEquals(1, radio.connects)
        } finally { gateway.adopted?.close(); flow.close(); scope.cancel() }
    }

    @Test
    fun wifiBackToChoiceKeepsConnectionAndDoesNotEnrollEarly() = runBlocking {
        val scope = scope()
        val gateway = Gateway()
        val radio = Radio()
        val flow = ProvisioningFlow(scope, gateway, radio::link)
        try {
            assertEquals(0, gateway.reads)
            assertEquals(0, gateway.enrolled)
            val candidate=SetupCandidate("ble", "Lamp", hardware, endpoint, peer)
            flow.select(candidate,SetupControlMethod.WIFI)
            await(flow) { it.phase == SetupPhase.NETWORKS && !it.busy }
            assertTrue(flow.back())
            assertEquals(SetupPhase.DISCOVER, flow.ui.value.phase)
            assertEquals(0, gateway.enrolled)
            flow.select(candidate,SetupControlMethod.BLUETOOTH)
            await(flow) { it.phase == SetupPhase.DONE }
            assertEquals(1, radio.connects)
        } finally { gateway.adopted?.close(); flow.close(); scope.cancel() }
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
    @Test fun existingAndReconfiguredLampsNeverCelebrate() = runBlocking {
        for (reconfigure in listOf(false, true)) {
            val scope = scope()
            val gateway = Gateway().apply { online = true; existing = !reconfigure }
            val flow = ProvisioningFlow(scope, gateway, Radio()::link, reconfigure = reconfigure)
            try {
                flow.manual(endpoint.host)
                await(flow) { it.phase == SetupPhase.DONE }
                assertFalse(requireNotNull(flow.takeCompletion()).firstAdd)
            } finally { flow.close(); scope.cancel() }
        }
    }

    @Test fun failedSaveNeverEmitsSuccess() = runBlocking {
        val scope = scope()
        val gateway = Gateway().apply { online = true; failSave = true }
        val flow = ProvisioningFlow(scope, gateway, Radio()::link)
        try {
            flow.manual(endpoint.host)
            await(flow) { it.error != null }
            assertNull(flow.takeCompletion())
            assertEquals(0, gateway.enrolled)
            gateway.failSave = false
            flow.manual(endpoint.host)
            await(flow) { it.phase == SetupPhase.DONE }
            assertTrue(requireNotNull(flow.takeCompletion()).firstAdd)
        } finally { flow.close(); scope.cancel() }
    }


    @Test fun wifiPreparationKeepsProgressHiddenAndBackNeverSaves() = runBlocking {
        val scope=scope(); val gateway=Gateway(); val radio=Radio()
        val flow=ProvisioningFlow(scope,gateway,radio::link)
        try {
            flow.select(SetupCandidate("ble","Lamp",hardware,null,peer),SetupControlMethod.WIFI)
            await(flow) { it.phase==SetupPhase.NETWORKS && !it.busy }
            assertNull(flow.ui.value.connection)
            assertEquals(0,gateway.enrolled)
            assertTrue(flow.back()); assertEquals(SetupPhase.DISCOVER,flow.ui.value.phase)
            assertFalse(radio.links.last().closed)
            assertEquals(0,gateway.enrolled); assertNull(flow.takeCompletion())
        } finally { flow.close();scope.cancel() }
    }

    @Test fun bluetoothSaveFailureStaysBelowCompleteAndRetryAutomaticallySaves() = runBlocking {
        val scope=scope();val gateway=Gateway().apply {failSave=true};val radio=Radio()
        val flow=ProvisioningFlow(scope,gateway,radio::link)
        try {
            val candidate=SetupCandidate("ble","Lamp",hardware,null,peer)
            flow.select(candidate)
            await(flow) { it.error!=null && !it.busy }
            assertEquals(90,flow.ui.value.connection?.percent)
            assertFalse(flow.ui.value.connection!!.complete)
            assertTrue(radio.links.last().closed)
            assertEquals(0,gateway.enrolled);assertNull(flow.takeCompletion())
            gateway.failSave=false
            flow.select(candidate)
            await(flow) { it.phase==SetupPhase.DONE }
            assertEquals(1,gateway.enrolled)
            assertTrue(flow.ui.value.connection!!.complete)
            assertEquals("Lamp",flow.takeCompletion()?.name)
            assertEquals(2,radio.connects)
        } finally { gateway.adopted?.close();flow.close();scope.cancel() }
    }
}
