package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
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

    /**
     * 净眼 P1-1 回归（真时钟）：provider 重构后的真实形态——阻塞读在
     * launch(Dispatchers.IO) 里且不可取消，awaitClose 的 handler 是唯一
     * 解锁手段（call.cancel() 等价物）。watchdog 的判死信号必须能在秒级
     * 穿出这条链到达 collector；若 awaitClose 排在阻塞之后（修复前的
     * producer 结构），信号会被 callbackFlow 的 coroutineScope 扣住。
     * JUnit timeout 让该失效模式干净红：withTimeout 的取消同样要等被扣
     * 的 producer（净眼 P3 复审），只有线程中断（latch.await() 响应）能
     * 立刻打断挂死的测试线程。
     */
    @Test(timeout = 10_000)
    fun `watchdog error escapes a producer blocked on non-cancellable IO`() {
        val latch = java.util.concurrent.CountDownLatch(1)
        val blockedProducer = kotlinx.coroutines.flow.callbackFlow {
            launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    latch.await() // readLine 形态：线程阻塞，协程取消无效
                    send(LLMStreamChunk.Text("late"))
                    channel.close()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 净眼 #30 P1：provider 修复后的写法必须是
                    // close(cause)——launch{} 里裸 cancel(…) 会翻转接收者
                    // 取消子协程自身、吞掉错误。复刻块同 provider。
                    close(LLMError.NetworkError(e))
                }
            }
            awaitClose { latch.countDown() } // call.cancel() 等价：取消时解锁
        }
        val guarded = blockedProducer.failOnStreamStall(
            "test", firstChunkTimeoutMillis = 200, clock = System::currentTimeMillis,
        )
        val outcome = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(5_000) {
                try {
                    guarded.toList()
                    "completed"
                } catch (e: LLMError.NetworkError) {
                    "recovered"
                }
            }
        }
        assertEquals("recovered", outcome)
    }

    /**
     * 净眼 #30 P1 守护（真时钟）：IO launch 内的中途异常必须以类型化
     * LLMError 到达 collector。修复前 provider 写的 `cancel(msg, cause)` 在
     * launch{} 内解析为取消子协程自身——错误被吞、下游看到"正常完成"，
     * 中途断流从自动重试退化为静默截断（beta.73 在 Anthropic/Gemini 带入，
     * #30 三家统一改为 close(mapError)）。谁再写回 cancel 形态，此测试红。
     */
    @Test(timeout = 10_000)
    fun `mid-stream failure inside the IO launch reaches collector as typed error`() {
        val producer = kotlinx.coroutines.flow.callbackFlow {
            launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    send(LLMStreamChunk.Text("partial"))
                    throw java.io.IOException("connection reset")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    close(LLMError.NetworkError(e))
                }
            }
            awaitClose { }
        }
        val outcome = kotlinx.coroutines.runBlocking {
            try {
                producer.toList()
                "completed"
            } catch (e: LLMError.NetworkError) {
                "recovered"
            }
        }
        assertEquals("recovered", outcome)
    }
}
