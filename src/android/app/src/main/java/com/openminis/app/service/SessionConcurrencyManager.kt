package com.openminis.app.service

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.LinkedList
import kotlin.coroutines.resume

/**
 * Limits concurrent agent loop sessions to [maxConcurrent].
 * Excess sessions are suspended in a FIFO queue until a slot frees up.
 */
object SessionConcurrencyManager {
    const val MAX_CONCURRENT = 5

    private val _runningSessions = MutableStateFlow<Set<String>>(emptySet())
    val runningSessions: StateFlow<Set<String>> = _runningSessions.asStateFlow()

    private val _suspendedSessions = MutableStateFlow<List<String>>(emptyList())
    val suspendedSessions: StateFlow<List<String>> = _suspendedSessions.asStateFlow()

    private data class Waiter(
        val sessionId: String,
        val continuation: CancellableContinuation<Unit>,
        var granted: Boolean = false,
    )
    private val waitQueue = LinkedList<Waiter>()
    /** Count leases rather than only session ids so overlapping retries of one session are safe. */
    private val activeLeases = mutableMapOf<String, Int>()

    suspend fun acquireSlot(sessionId: String) = suspendCancellableCoroutine<Unit> { cont ->
        var immediateLeaseGranted = false
        val waiter = Waiter(sessionId, cont)
        // Capacity inspection and waiter registration happen in one critical
        // section. A release cannot slip between them and strand this caller.
        synchronized(this@SessionConcurrencyManager) {
            if (activeLeases.values.sum() < MAX_CONCURRENT) {
                if (cont.isActive) {
                    activeLeases[sessionId] = (activeLeases[sessionId] ?: 0) + 1
                    _runningSessions.value = activeLeases.keys.toSet()
                    immediateLeaseGranted = true
                    cont.resume(Unit)
                }
            } else {
                _suspendedSessions.value = _suspendedSessions.value + sessionId
                waitQueue.add(waiter)
            }
        }
        cont.invokeOnCancellation {
            synchronized(this@SessionConcurrencyManager) {
                val removed = waitQueue.removeIf { it === waiter }
                if (removed) {
                    _suspendedSessions.value = _suspendedSessions.value.toMutableList().also { it.remove(sessionId) }
                } else if (waiter.granted || immediateLeaseGranted) {
                    // A cancellation can race with resume(Unit), before the
                    // caller has had a chance to mark its local lease flag.
                    // Reclaim that lease here so a canceled waiter cannot
                    // permanently consume one of the five global slots.
                    releaseSlotLocked(sessionId)
                }
            }
        }
    }

    @Synchronized
    fun releaseSlot(sessionId: String) = releaseSlotLocked(sessionId)

    private fun releaseSlotLocked(sessionId: String) {
        val leases = activeLeases[sessionId] ?: return
        if (leases > 1) {
            activeLeases[sessionId] = leases - 1
            return
        }
        activeLeases.remove(sessionId)
        _runningSessions.value = activeLeases.keys.toSet()

        // Resume next waiter
        while (true) {
            val next = waitQueue.pollFirst() ?: break
            _suspendedSessions.value = _suspendedSessions.value.toMutableList().also { it.remove(next.sessionId) }
            if (!next.continuation.isActive) continue
            next.granted = true
            activeLeases[next.sessionId] = (activeLeases[next.sessionId] ?: 0) + 1
            _runningSessions.value = activeLeases.keys.toSet()
            next.continuation.resume(Unit)
            break
        }
    }

    fun isSuspended(sessionId: String): Boolean = sessionId in _suspendedSessions.value
}
