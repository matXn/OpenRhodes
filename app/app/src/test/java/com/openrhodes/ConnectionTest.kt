package com.openrhodes

import com.openrhodes.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class ConnectionTest {
    private val hardware = HardwareId.parse("E8:3D:C1:EB:CE:5C")!!
    private val endpoint = Endpoint.parse("192.168.2.11")!!
    private val lamp = Lamp("lamp", "Bedside", hardware, listOf(endpoint))

    private open inner class Fake : LampTransport {
        var state = LampState(true, 50, hardware = hardware)
        val writes = mutableListOf<Command>()

        override suspend fun read(endpoint: Endpoint) = state

        override suspend fun write(
            endpoint: Endpoint,
            command: Command,
            before: LampState,
        ): LampState {
            writes += command
            state =
                when (command) {
                    is Command.Level ->
                        state.copy(on = command.percent > 0, brightness = command.percent)
                    Command.Cycle ->
                        nextBrightness(state).let { state.copy(on = it > 0, brightness = it) }
                    else -> state
                }
            return state
        }
    }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun initialProbeAndNetworkChangeStayCheckingUntilAResponseArrives() = runBlocking {
        val scope = scope()
        try {
            var gate = CompletableDeferred<Unit>()
            val fake =
                object : Fake() {
                    override suspend fun read(endpoint: Endpoint): LampState {
                        gate.await()
                        return state
                    }
                }
            val session = LampSession(scope, { lamp }, fake)
            assertTrue(session.observation.value.connecting)
            assertNull(session.observation.value.error)
            val first = launch { session.refresh() }
            yield()
            assertTrue(session.observation.value.connecting)
            gate.complete(Unit)
            first.join()
            assertFalse(session.observation.value.connecting)
            val previous = session.observation.value.state
            session.foregrounded()
            assertEquals(previous, session.observation.value.state)
            assertFalse(session.observation.value.connecting)
            session.networkChanged()
            assertTrue(session.observation.value.connecting)
            assertNull(session.observation.value.error)
            assertEquals(previous, session.observation.value.state)
            gate = CompletableDeferred()
            val recheck = launch { session.refresh() }
            yield()
            assertTrue(session.observation.value.connecting)
            gate.complete(Unit)
            recheck.join()
            assertFalse(session.observation.value.connecting)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun foregroundRechecksImmediatelyAndOnlyActualFailureShowsOffline() = runBlocking {
        val scope = scope()
        try {
            var broken = true
            var reads = 0
            val fake =
                object : Fake() {
                    override suspend fun read(endpoint: Endpoint): LampState {
                        reads++
                        if (broken) throw java.io.IOException("offline")
                        return state
                    }
                }
            val session = LampSession(scope, { lamp }, fake, clock = { 100L })
            session.refresh()
            assertNotNull(session.observation.value.error)
            assertFalse(session.observation.value.connecting)
            session.refresh()
            assertEquals(1, reads)
            session.foregrounded()
            assertNull(session.observation.value.error)
            assertTrue(session.observation.value.connecting)
            broken = false
            session.refresh()
            assertEquals(2, reads)
            assertFalse(session.observation.value.connecting)
            assertNull(session.observation.value.error)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun oldNetworkFailureCannotFlashOfflineAndRechecksTheNewLinkImmediately() = runBlocking {
        val scope = scope()
        try {
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var reads = 0
            val fake =
                object : Fake() {
                    override suspend fun read(endpoint: Endpoint): LampState {
                        if (++reads == 1) {
                            entered.complete(Unit)
                            gate.await()
                            throw java.io.IOException("old network")
                        }
                        return state
                    }
                }
            val session = LampSession(scope, { lamp }, fake)
            val observations = mutableListOf<Observation>()
            scope.launch { session.observation.collect { observations += it } }
            val probe = launch { session.refresh() }
            entered.await()
            session.networkChanged()
            gate.complete(Unit)
            probe.join()
            assertEquals(2, reads)
            assertTrue(observations.all { it.error == null })
            assertEquals(fake.state, session.observation.value.state)
            assertFalse(session.observation.value.connecting)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun sliderReusesRecentResponseButCycleReadsPhysicalState() = runBlocking {
        val scope = scope()
        try {
            var now = 100L
            var reads = 0
            val fake =
                object : Fake() {
                    override suspend fun read(endpoint: Endpoint): LampState {
                        reads++
                        return state
                    }
                }
            val session = LampSession(scope, { lamp }, fake, clock = { now })
            session.execute(Command.Level(40))
            session.execute(Command.Level(42))
            assertEquals(1, reads)
            fake.state = fake.state.copy(brightness = 5)
            assertEquals(20, session.execute(Command.Cycle).brightness)
            assertEquals(2, reads)
            now += 2100
            session.execute(Command.Level(30))
            assertEquals(3, reads)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun discoveryPromotesNewIpWithoutWaitingForOldAddressTimeout() = runBlocking {
        val scope = scope()
        try {
            val newIp = Endpoint.parse("192.168.2.77")!!
            val reads = mutableListOf<Endpoint>()
            val writes = mutableListOf<Endpoint>()
            val fake =
                object : Fake() {
                    override suspend fun read(endpoint: Endpoint): LampState {
                        reads += endpoint
                        return state
                    }

                    override suspend fun write(
                        endpoint: Endpoint,
                        command: Command,
                        before: LampState,
                    ): LampState {
                        writes += endpoint
                        return super.write(endpoint, command, before)
                    }
                }
            val session = LampSession(scope, { lamp }, fake)
            session.acceptDiscovered(newIp, fake.state)
            session.execute(Command.Level(30))
            assertTrue(reads.isEmpty())
            assertEquals(listOf(newIp), writes)
            assertEquals(newIp, session.observation.value.endpoint)
            assertEquals(listOf(endpoint), lamp.endpoints)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedPollsBackOffButManualRetryAndNetworkChangeDoNotWait() = runBlocking {
        val scope = scope()
        try {
            var now = 100L
            var reads = 0
            val fake =
                object : Fake() {
                    override suspend fun read(endpoint: Endpoint): LampState {
                        reads++
                        throw java.io.IOException("offline")
                    }
                }
            val session = LampSession(scope, { lamp }, fake, clock = { now })
            session.refresh()
            session.refresh()
            assertEquals(1, reads)
            session.refresh(force = true)
            assertEquals(2, reads)
            session.networkChanged()
            session.refresh()
            assertEquals(3, reads)
            now += 6000
            session.refresh()
            assertEquals(4, reads)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedGestureDiscardsOldCommandsWithoutReplayingAfterRecovery() = runBlocking {
        val scope = scope()
        try {
            val gate = CompletableDeferred<Unit>()
            var attempts = 0
            val fake =
                object : Fake() {
                    override suspend fun write(
                        endpoint: Endpoint,
                        command: Command,
                        before: LampState,
                    ): LampState {
                        attempts++
                        if (attempts == 1) {
                            gate.await()
                            throw java.io.IOException("response lost")
                        }
                        return super.write(endpoint, command, before)
                    }
                }
            val session = LampSession(scope, { lamp }, fake)
            val first = async { runCatching { session.execute(Command.Level(10)) } }
            yield()
            session.submit(Command.Level(20))
            val queued = async { runCatching { session.execute(Command.Cycle) } }
            yield()
            gate.complete(Unit)
            assertTrue(first.await().isFailure)
            assertTrue(queued.await().isFailure)
            assertTrue(session.observation.value.uncertainWrite)
            session.refresh(force = true)
            assertFalse(session.observation.value.uncertainWrite)
            assertEquals(1, attempts)
            session.execute(Command.Level(30))
            assertEquals(listOf(Command.Level(30)), fake.writes)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun lateOldProbeFailureCannotEraseSuccessfulDiscovery() = runBlocking {
        val scope = scope()
        try {
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val fake =
                object : Fake() {
                    override suspend fun read(endpoint: Endpoint): LampState {
                        entered.complete(Unit)
                        gate.await()
                        throw java.io.IOException("stale IP")
                    }
                }
            val session = LampSession(scope, { lamp }, fake)
            val probe = launch { session.refresh() }
            entered.await()
            val newIp = Endpoint.parse("192.168.2.88")!!
            session.acceptDiscovered(newIp, fake.state)
            gate.complete(Unit)
            probe.join()
            assertNull(session.observation.value.error)
            assertEquals(newIp, session.observation.value.endpoint)
        } finally {
            scope.cancel()
        }
    }
}
