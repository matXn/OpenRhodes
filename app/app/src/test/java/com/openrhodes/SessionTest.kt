package com.openrhodes

import com.openrhodes.domain.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SessionTest {
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
                    is Command.Power -> state.copy(on = command.on)
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
    fun offSupersedesPendingLevelAndCannotBeReenabledByIt() = runBlocking {
        val scope = scope()
        try {
            val gate = CompletableDeferred<Unit>()
            val fake =
                object : Fake() {
                    override suspend fun write(
                        endpoint: Endpoint,
                        command: Command,
                        before: LampState,
                    ): LampState {
                        if (command == Command.Level(52)) gate.await()
                        return super.write(endpoint, command, before)
                    }
                }
            val session = LampSession(scope, { lamp }, fake)
            session.submit(Command.Level(52))
            session.submit(Command.Level(54))
            session.submit(Command.Power(false))
            gate.complete(Unit)
            withTimeout(2000) { while (fake.writes.size < 2) yield() }
            assertEquals(listOf(Command.Level(52), Command.Power(false)), fake.writes)
            assertFalse(fake.state.on)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun repeatedCycleCommandsReadLatestAcknowledgedState() = runBlocking {
        val scope = scope()
        try {
            val fake = Fake().apply { state = LampState(false, 100, hardware = hardware) }
            val session = LampSession(scope, { lamp }, fake)
            val results = coroutineScope {
                (1..5).map { async { session.execute(Command.Cycle).brightness } }.awaitAll()
            }
            assertEquals(listOf(5, 20, 50, 100, 0), results)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun concurrentRefreshCallsNeverOverlap() = runBlocking {
        val scope = scope()
        try {
            val gate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val reads = AtomicInteger()
            val fake =
                object : Fake() {
                    override suspend fun read(endpoint: Endpoint): LampState {
                        reads.incrementAndGet()
                        entered.complete(Unit)
                        gate.await()
                        return state
                    }
                }
            val session = LampSession(scope, { lamp }, fake)
            val first = launch { session.refresh() }
            entered.await()
            coroutineScope { repeat(10) { launch { session.refresh() } } }
            assertEquals(1, reads.get())
            gate.complete(Unit)
            first.join()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun identityMismatchPreventsMutation() = runBlocking {
        val scope = scope()
        try {
            val fake =
                Fake().apply {
                    state = state.copy(hardware = HardwareId.parse("00:11:22:33:44:66"))
                }
            val session = LampSession(scope, { lamp }, fake)
            val failure = runCatching { session.execute(Command.Power(false)) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(fake.writes.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun unknownLegacyRecordNeedsExplicitIdentityConfirmation() = runBlocking {
        val scope = scope()
        try {
            val fake = Fake()
            val session = LampSession(scope, { lamp.copy(hardware = null) }, fake)
            assertTrue(runCatching { session.execute(Command.Cycle) }.isFailure)
            assertTrue(fake.writes.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun ambiguousWriteFailureDoesNotRetryOnAnotherAddress() = runBlocking {
        val scope = scope()
        try {
            val count = AtomicInteger()
            val fake =
                object : Fake() {
                    override suspend fun write(
                        endpoint: Endpoint,
                        command: Command,
                        before: LampState,
                    ): LampState {
                        count.incrementAndGet()
                        throw java.io.IOException("Response lost")
                    }
                }
            val session =
                LampSession(
                    scope,
                    { lamp.copy(endpoints = listOf(endpoint, Endpoint.parse("192.168.4.1")!!)) },
                    fake,
                )
            assertTrue(runCatching { session.execute(Command.Cycle) }.isFailure)
            assertEquals(1, count.get())
        } finally {
            scope.cancel()
        }
    }
}
