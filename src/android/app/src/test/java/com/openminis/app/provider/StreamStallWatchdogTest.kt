package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-stream-stall-watchdog] 流挂死看门狗（conversation-f899bf05：中转站回
 * 200 后 SSE 零字节，黑洞 3069 秒才被 readTimeout 兜住）。守护：
 * 首块超时、chunk 间空闲超时、任意 chunk 类型（含 thinking）计入活动、
 * 正常流不误杀、抛 NetworkError 以进既有 auto-retry 链、取消无残留。
 *
 * clock 与虚拟时间联动（testScheduler.currentTime），watchdog 的 1s tick
 * 在 runTest 里由 advanceTimeBy 步进。
 */
class StreamStallWatchdogTest {

    private fun chunkFlow(vararg chunks: LLMStreamChunk) = flow {
        chunks.forEach { emit(it) }
    }

    /** 黑洞形态：上游挂起、一个块都不发（对应 readLine 永久阻塞）。 */
    private fun silentFlow() = flow<LLMStreamChunk> { kotlinx.coroutines.awaitCancellation() }

    @Test
    fun `first chunk timeout fires as retryable NetworkError`() = runTest {
        val guarded = silentFlow()
            .failOnStreamStall("test", firstChunkTimeoutMillis = 10_000, clock = { currentTime })
        try {
            guarded.toList()
            fail("expected NetworkError")
        } catch (e: LLMError.NetworkError) {
            assertTrue(e.message!!, e.message!!.contains("first chunk"))
        }
    }

    @Test
    fun `idle timeout after first chunk reports stream data phase`() = runTest {
        // 先发一块再永久静默——流中途死掉的形态。
        val guarded = flow {
            emit(LLMStreamChunk.Started)
            kotlinx.coroutines.awaitCancellation()
        }.failOnStreamStall("test", firstChunkTimeoutMillis = 10_000, idleTimeoutMillis = 10_000, clock = { currentTime })
        try {
            guarded.toList()
            fail("expected NetworkError")
        } catch (e: LLMError.NetworkError) {
            assertTrue(e.message!!, e.message!!.contains("stream data"))
        }
    }

    @Test
    fun `steady chunks never trip the watchdog`() = runTest {
        val chunks = (1..20).map { LLMStreamChunk.Text("chunk $it") }
        val guarded = chunkFlow(*chunks.toTypedArray())
            .failOnStreamStall("test", idleTimeoutMillis = 5_000, clock = { currentTime })
        assertEquals(chunks, guarded.toList())
    }

    @Test
    fun `thinking delta counts as activity`() = runTest {
        // T171 实测：reasoning 阶段持续产出 thinking 事件但正文不动——
        // 只要事件在流，计时必须重置（5 分钟默认值 > 3:10 静默的余量语义
        // 由默认参数承担，这里验证"事件=活动"本身）。
        val guarded = flow {
            emit(LLMStreamChunk.Started)
            repeat(5) { emit(LLMStreamChunk.ThinkingDelta("推理…")) }
            emit(LLMStreamChunk.Text("正文"))
        }.failOnStreamStall("test", idleTimeoutMillis = 5_000, clock = { currentTime })
        assertEquals(7, guarded.toList().size)
    }

    @Test
    fun `cancelling collector stops the watchdog without error`() = runTest {
        val guarded = silentFlow()
            .failOnStreamStall("test", firstChunkTimeoutMillis = 10_000, clock = { currentTime })
        val job = async { guarded.take(1).toList() }
        advanceTimeBy(1_000)
        job.cancel()
        job.join()
        // 走到这里未抛 NetworkError 即通过：取消路径静默、看门狗随 scope 结束。
    }
}
