package com.openrhodes.domain

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

interface LampTransport {
    suspend fun read(endpoint: Endpoint): LampState

    suspend fun write(endpoint: Endpoint, command: Command, before: LampState): LampState
}

data class Observation(
    val state: LampState? = null,
    val endpoint: Endpoint? = null,
    val error: String? = null,
    val connecting: Boolean = false,
    val confirmedAt: Long? = null,
    val uncertainWrite: Boolean = false,
)

/** One session per canonical lamp, shared by screens, polling and widget actions. */
class LampSession(
    private val scope: CoroutineScope,
    private val lamp: () -> Lamp,
    private val transport: LampTransport,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val learned: suspend (Endpoint, LampState) -> Unit = { _, _ -> },
) {
    private data class Pending(val command: Command, val result: CompletableDeferred<LampState>?)

    private val queue = ArrayDeque<Pending>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val io = Mutex()

    private data class Verified(val endpoint: Endpoint, val state: LampState, val at: Long)

    private val verified = AtomicReference<Verified?>(null)
    private val networkRevision = AtomicLong()
    @Volatile private var failedPolls = 0
    @Volatile private var nextPollAt = 0L
    private val verifiedIdentity = lamp().hardware
    private val _observation = MutableStateFlow(Observation(connecting = true))
    val observation = _observation.asStateFlow()

    init {
        val worker = scope.launch {
            for (ignored in wake) {
                while (true) {
                    val next = synchronized(queue) { queue.pollFirst() } ?: break
                    if (next.result?.isCancelled == true) continue
                    io.lock()
                    var writeAttempted = false
                    try {
                        val after =
                            withTimeoutOrNull(8000) {
                                val (endpoint, before) =
                                    reach(reuse = next.command !is Command.Cycle)
                                require(
                                    verifiedIdentity != null && lamp().hardware == verifiedIdentity
                                ) {
                                    "请在设备信息中确认这条旧记录的灯具身份"
                                }
                                if (next.result?.isCancelled == true)
                                    return@withTimeoutOrNull before
                                writeAttempted = true
                                val response = transport.write(endpoint, next.command, before)
                                checkIdentity(response)
                                learned(endpoint, response)
                                confirm(endpoint, response)
                                response
                            } ?: error("灯具响应超时，请刷新确认实际状态")
                        next.result?.complete(after)
                    } catch (cancel: CancellationException) {
                        next.result?.cancel(cancel)
                        throw cancel
                    } catch (failure: Exception) {
                        fail(failure, writeAttempted)
                        // A disconnected swipe must not replay its remaining commands on
                        // reconnection.
                        val abandoned =
                            synchronized(queue) {
                                queue.toList().also { queue.clear() }
                            }
                        abandoned.forEach { it.result?.completeExceptionally(failure) }
                        next.result?.completeExceptionally(failure)
                    } finally {
                        io.unlock()
                    }
                }
            }
        }
        worker.invokeOnCompletion { reason ->
            synchronized(queue) {
                while (queue.isNotEmpty()) queue
                    .pollFirst()
                    ?.result
                    ?.completeExceptionally(reason ?: CancellationException("Lamp session closed"))
            }
        }
    }

    fun submit(command: Command) {
        enqueue(command, null)
    }

    suspend fun execute(command: Command): LampState {
        val result = CompletableDeferred<LampState>()
        enqueue(command, result)
        try {
            return result.await()
        } catch (cancel: CancellationException) {
            result.cancel(cancel)
            throw cancel
        }
    }

    private fun enqueue(command: Command, result: CompletableDeferred<LampState>?) {
        synchronized(queue) {
            if (command is Command.Power || command is Command.Mode || command is Command.Cycle) {
                queue.removeIf { it.command is Command.Level && it.result == null }
            }
            val last = queue.peekLast()
            if (
                result == null &&
                    last?.result == null &&
                    ((command is Command.Level && last?.command is Command.Level) ||
                        (command is Command.Parameters && last?.command is Command.Parameters))
            )
                queue.pollLast()
            queue.addLast(Pending(command, result))
        }
        wake.trySend(Unit)
    }

    /** Polls never wait behind a long request or overwrite an in-flight command. */
    suspend fun refresh(force: Boolean = false) {
        var immediate = force
        while (currentCoroutineContext().isActive) {
            if (!immediate && clock() < nextPollAt) return
            if (!io.tryLock()) return
            val revision = networkRevision.get()
            val probeHint = verified.get()
            try {
                val (endpoint, state) = reach()
                if (revision == networkRevision.get()) {
                    learned(endpoint, state)
                    if (revision == networkRevision.get()) confirm(endpoint, state)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (
                    revision == networkRevision.get() &&
                        verified.get()?.let { it !== probeHint } != true
                ) {
                    fail(failure, false)
                    failedPolls = (failedPolls + 1).coerceAtMost(3)
                    nextPollAt = clock() + (2500L shl failedPolls)
                }
            } finally {
                io.unlock()
            }
            if (revision == networkRevision.get()) return
            // A probe started on the old network cannot mark the new network offline.
            immediate = true
        }
    }

    /** HTTP-verified discovery can replace a stale DHCP address immediately. */
    fun acceptDiscovered(endpoint: Endpoint, state: LampState) {
        checkIdentity(state)
        confirm(endpoint, state)
    }

    fun networkChanged() {
        networkRevision.incrementAndGet()
        verified.set(null)
        nextPollAt = 0
        failedPolls = 0
        _observation.update {
            it.copy(error = if (it.uncertainWrite) it.error else null, connecting = true)
        }
    }

    /** Recheck immediately on return; retain a good last observation during that check. */
    fun foregrounded() {
        nextPollAt = 0
        failedPolls = 0
        _observation.update {
            if (it.state == null || it.error != null) {
                it.copy(error = if (it.uncertainWrite) it.error else null, connecting = true)
            } else it
        }
    }

    private fun confirm(endpoint: Endpoint, state: LampState) {
        val now = clock()
        verified.set(Verified(endpoint, state, now))
        failedPolls = 0
        nextPollAt = 0
        _observation.update { old ->
            Observation(state.continuing(old.state), endpoint, confirmedAt = now)
        }
    }

    private fun fail(failure: Exception, uncertain: Boolean) {
        verified.set(null)
        val message =
            when {
                uncertain -> "指令未确认，灯具可能已执行；请刷新状态"
                failure is IdentityMismatch -> "此地址对应另一盏灯，已停止控制；请更新地址"
                failure is IllegalArgumentException && lamp().hardware == null ->
                    "旧记录的灯具身份尚未确认，请打开设备信息确认"
                else -> "无法连接灯具：地址可能已变化，或手机与灯具不在同一局域网"
            }
        _observation.update {
            it.copy(error = message, connecting = false, uncertainWrite = uncertain)
        }
    }

    private class IdentityMismatch : IllegalArgumentException("此地址对应另一台灯具")

    private fun checkIdentity(state: LampState) {
        val expected = lamp().hardware
        if (expected != null && expected != state.hardware) throw IdentityMismatch()
    }

    private suspend fun reach(reuse: Boolean = false): Pair<Endpoint, LampState> {
        verified
            .get()
            ?.takeIf { reuse && clock() - it.at in 0..2000 }
            ?.let {
                return it.endpoint to it.state
            }
        _observation.update {
            it.copy(connecting = it.connecting || it.state == null || it.error != null)
        }
        var last: Exception? = null
        // Total retry time is bounded, independent of address-history length.
        return withTimeoutOrNull(6500) {
            // Discovery and the last successful response outrank historical address order.
            val preferred = verified.get()?.endpoint
            val candidates = (listOfNotNull(preferred) + lamp().endpoints).distinct()
            for (endpoint in candidates) {
                try {
                    val state = withTimeoutOrNull(2200) { transport.read(endpoint) } ?: continue
                    checkIdentity(state)
                    return@withTimeoutOrNull endpoint to state
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    last = failure
                }
            }
            throw last ?: IllegalStateException("灯具暂时无法连接")
        } ?: throw IllegalStateException("灯具连接超时")
    }
}
