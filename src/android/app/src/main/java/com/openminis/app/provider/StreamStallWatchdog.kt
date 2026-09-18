package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * [T-stream-stall-watchdog] Stream-stall watchdog for SSE LLM streams
 * (2026-09-18, conversation-f899bf05): a relay can return HTTP 200 and then
 * never emit a single SSE byte. OkHttp's readTimeout is the only guard and it
 * resets on ANY socket activity, so a half-dead relay connection hung the chat
 * for 51 minutes before "Network error: timeout" fired and the (healthy)
 * auto-retry chain recovered it in 183s.
 *
 * This operator bounds BOTH silent phases from the coroutine side, provider-
 * agnostic (mounted once in [LLMProvider.streamMessage], covering every
 * provider):
 *  - first-chunk: response accepted but no stream chunk of any kind within
 *    [firstChunkTimeoutMillis];
 *  - idle: two consecutive chunks further apart than [idleTimeoutMillis].
 *
 * Any chunk type counts as activity — reasoning/thinking deltas keep arriving
 * during long model thinking, resetting the idle timer. The 300s defaults sit
 * ABOVE T171's measured legit silent gap (GPT-5.x Codex reasoning sat silent
 * 2:50–3:10 with no keep-alive bytes; OpenAIProvider.kt readTimeout notes) and
 * far BELOW the 3069s black hole that triggered this fix.
 *
 * Fires as [LLMError.NetworkError] so `isRetryable` is true and the existing
 * NovexModelStreamRecovery transient chain (retry ×3 + endpoint fallback)
 * picks it up unchanged. Cancellation propagates: cancelling the collector
 * cancels the watchdog job and the upstream flow (providers cancel the OkHttp
 * call in awaitClose).
 */
fun Flow<LLMStreamChunk>.failOnStreamStall(
    providerName: String,
    firstChunkTimeoutMillis: Long = 300_000,
    idleTimeoutMillis: Long = 300_000,
    clock: () -> Long = System::currentTimeMillis,
): Flow<LLMStreamChunk> = flow {
    coroutineScope {
        val lastActivityAt = AtomicLong(clock())
        val sawFirstChunk = java.util.concurrent.atomic.AtomicBoolean(false)

        val watchdog = launch {
            while (isActive) {
                delay(STALL_WATCHDOG_TICK_MILLIS)
                val now = clock()
                val first = !sawFirstChunk.get()
                val budget = if (first) firstChunkTimeoutMillis else idleTimeoutMillis
                val idleFor = now - lastActivityAt.get()
                if (idleFor >= budget) {
                    val phase = if (first) "first chunk" else "stream data"
                    throw LLMError.NetworkError(
                        SocketTimeoutException(
                            "stream watchdog: no $phase from $providerName for ${idleFor / 1000}s (budget ${budget / 1000}s); connection presumed dead",
                        ),
                    )
                }
            }
        }
        try {
            collect { chunk ->
                lastActivityAt.set(clock())
                sawFirstChunk.set(true)
                emit(chunk)
            }
        } finally {
            watchdog.cancel()
        }
    }
}

private const val STALL_WATCHDOG_TICK_MILLIS = 1_000L
