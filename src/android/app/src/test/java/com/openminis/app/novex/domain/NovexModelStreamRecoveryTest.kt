package com.openminis.app.novex.domain

import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMError
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NovexModelStreamRecoveryTest {
    @Test fun `retry clears partial data and fallback keeps exact endpoint across later turns`() = runBlocking {
        val engine = NovexModelStreamRecovery("entry-a", listOf("entry-b"), FallbackStrategy.always,
            label = { "same-model" }, retryDelaysSeconds = listOf(1), waitSecond = {})
        val calls = mutableListOf<String>()
        val partial = mutableListOf<String>()
        val switches = mutableListOf<Pair<String, String>>()
        var rolledBack = 0
        engine.collect(attempt = { endpoint ->
            calls += endpoint
            partial += endpoint
            if (endpoint == "entry-a") throw LLMError.NetworkError(Exception("offline"))
        }, rollback = { partial.clear(); rolledBack++ }, retrying = { _, _, _ -> }, countdown = {}, settled = {},
            switched = { a, b, _ -> switches += a to b }, unavailable = { emptyList() })
        assertEquals(listOf("entry-a", "entry-a", "entry-b"), calls)
        assertEquals(listOf("entry-b"), partial)
        assertEquals(2, rolledBack)
        assertEquals(listOf("entry-a" to "entry-b"), switches)
        engine.collect({ assertEquals("entry-b", it) }, {}, { _, _, _ -> }, {}, {}, { _, _, _ -> error("unexpected") }, { emptyList() })
    }

    @Test fun `rate limit moves immediately and exhausted fallback is bounded`() = runBlocking {
        val engine = NovexModelStreamRecovery("a", listOf("b"), FallbackStrategy.always, { it }, waitSecond = { error("no retry delay") })
        var calls = 0
        val result = runCatching {
            engine.collect({ calls++; throw LLMError.RateLimited() }, {}, { _, _, _ -> error("no same endpoint retry") }, {}, {},
                { _, _, _ -> }, { listOf("第三个连接不可用") })
        }
        assertEquals(2, calls)
        assertTrue(result.exceptionOrNull() is LLMError.ProviderError)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("第三个连接不可用"))
    }

    @Test fun `cancellation during backoff never switches or makes a new request`() = runBlocking {
        val sleeping = CompletableDeferred<Unit>()
        var calls = 0
        var count = -1
        val engine = NovexModelStreamRecovery("a", listOf("b"), FallbackStrategy.always, { it },
            waitSecond = { sleeping.complete(Unit); awaitCancellation() })
        val caller = launch {
            engine.collect({ calls++; throw LLMError.TransientError("retry") }, {}, { _, _, _ -> }, { count = it }, {},
                { _, _, _ -> error("must not switch") }, { emptyList() })
        }
        sleeping.await()
        caller.cancelAndJoin()
        assertEquals(1, calls)
        assertEquals(0, count)
        assertEquals("a", engine.current)
    }

    @Test fun `wrapped provider error recovers but nontransient decoding failure preserves error`() = runBlocking {
        val engine = NovexModelStreamRecovery("a", emptyList<String>(), FallbackStrategy.default, { it }, retryDelaysSeconds = listOf(1), waitSecond = {})
        var calls = 0
        engine.collect({ if (++calls == 1) throw CancellationException("flow").also { it.initCause(LLMError.TransientError("retry")) } },
            {}, { _, _, _ -> }, {}, {}, { _, _, _ -> }, { emptyList() })
        assertEquals(2, calls)
        val failure = LLMError.DecodingError(Exception("invalid"))
        val result = runCatching { engine.collect({ throw failure }, {}, { _, _, _ -> error("must not retry") }, {}, {},
            { _, _, _ -> error("must not switch") }, { emptyList() }) }
        assertSame(failure, result.exceptionOrNull())
    }
}
