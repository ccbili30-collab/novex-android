package com.openminis.app.novex.domain

import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Owns retry budgets and endpoint progression for one agent run; never executes tools. */
class NovexModelStreamRecovery<C>(
    initial: C,
    fallbacks: List<C>,
    private val strategy: FallbackStrategy,
    private val label: (C) -> String,
    private val retryDelaysSeconds: List<Int> = listOf(1, 2, 4),
    private val waitSecond: suspend () -> Unit = { delay(1000) },
) {
    var current: C = initial
        private set
    private val remaining = fallbacks.toMutableList()
    private val reasons = mutableListOf<String>()

    suspend fun collect(
        attempt: suspend (C) -> Unit,
        rollback: suspend () -> Unit,
        retrying: suspend (Throwable, Int, Int) -> Unit,
        countdown: suspend (Int) -> Unit,
        settled: suspend () -> Unit,
        switched: suspend (C, C, List<String>) -> Unit,
        unavailable: () -> List<String>,
    ) {
        var retries = 0
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val failure = try { attempt(current); return }
                catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (error is CancellationException && error.cause == null) throw error
                    unwrap(error)
                }
                val rateLimited = failure is LLMError.RateLimited
                val serverError = failure is LLMError.ProviderError && failure.detail.contains(Regex("[5][0-9]{2}"))
                val transient = failure is LLMError.NetworkError || failure is LLMError.TransientError || serverError
                if (transient && retries < retryDelaysSeconds.size) {
                    val seconds = retryDelaysSeconds[retries++]
                    retrying(failure, retries, retryDelaysSeconds.size)
                    try { for (left in seconds downTo 1) { countdown(left); waitSecond() } }
                    finally { countdown(0) }
                    settled()
                    rollback()
                    continue
                }
                settled()
                val mayFallback = rateLimited || serverError || strategy == FallbackStrategy.always
                val next = if (mayFallback) remaining.removeFirstOrNull() else null
                if (next == null) {
                    val trail = if (mayFallback) reasons + unavailable() else emptyList()
                    if (trail.isNotEmpty()) throw LLMError.ProviderError(trail.joinToString("\n") + "\n" + (failure.message ?: failure.toString()))
                    throw failure
                }
                val previous = current
                val reason = if (rateLimited) "请求受限" else failure.message ?: "模型请求失败"
                reasons += "${label(previous)}：$reason"
                // All failed-attempt state is discarded before switching, including partial calls/signatures.
                rollback()
                current = next
                switched(previous, next, reasons.toList())
            }
        } finally { countdown(0); settled() }
    }

    private fun unwrap(error: Throwable): Throwable {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = error
        while (cause != null && seen.add(cause)) {
            if (cause is LLMError) return cause
            cause = cause.cause
        }
        return error
    }
}
