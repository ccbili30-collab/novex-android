package com.openminis.app.novex.domain

import com.openminis.app.tools.ToolExecutionResult
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovexToolExecutionTest {
    @get:Rule val files = TemporaryFolder()
    private fun operation(chat: String = "chat", args: String = """{"name":"生生"}""") =
        NovexToolOperation(chat, "reply", "call", "novex_write_card", args, "保存文游卡")
    private fun engine() = NovexToolExecution(NovexOperationJournal(File(files.root, "operations")))

    @Test fun `unavailable recovered call retires its popup but never erases a completed receipt`() = runBlocking {
        val journal = NovexOperationJournal(File(files.root, "operations"))
        val waiting = operation()
        journal.save(NovexOperationRecord(waiting, NovexOperationStatus.WAITING))
        val runtime = engine()
        runtime.restore("chat")
        assertEquals(1, runtime.pending.value.size)
        val refused = runtime.retireUnexecutable(waiting, "当前对话已切换为只读")
        assertFalse(refused.success)
        assertTrue(runtime.pending.value.isEmpty())
        assertEquals(NovexOperationStatus.FAILED, journal.read(waiting.id)?.status)
        assertFalse(runtime.execute(waiting, { NovexExecutionMode.FREE }) { error("失效操作不得执行") }.success)
        val completed = operation("completed")
        val saved = ToolExecutionResult("卡片此前确已保存", true)
        journal.save(NovexOperationRecord(completed, NovexOperationStatus.SUCCEEDED, saved))
        assertEquals(saved, runtime.recordedResult(completed))
        assertEquals(saved, runtime.retireUnexecutable(completed, "工具现已不可用"))
        assertEquals(NovexOperationStatus.SUCCEEDED, journal.read(completed.id)?.status)
    }

    @Test fun `readonly executes nothing while free executes once and replays persisted result`() = runBlocking {
        var calls = 0
        val first = engine()
        val op = operation()
        val effect: suspend () -> ToolExecutionResult = {
            assertTrue(NovexActiveToolAuthorization.allows("chat"))
            assertFalse(NovexActiveToolAuthorization.allows("other"))
            calls++; ToolExecutionResult("已保存", true)
        }
        assertFalse(first.execute(op, { NovexExecutionMode.READ_ONLY }, effect).success)
        assertEquals(0, calls)
        assertTrue(first.execute(op, { NovexExecutionMode.FREE }, effect).success)
        assertTrue(engine().execute(op, { NovexExecutionMode.FREE }, effect).success)
        assertEquals(1, calls)
        assertFalse(NovexActiveToolAuthorization.allows("chat"))
    }

    @Test fun `approval belongs to exact operation and another conversation remains waiting`() = runBlocking {
        val runtime = engine()
        var calls = 0
        val a = operation("a")
        val b = operation("b")
        withTimeout(5000) {
            val one = async { runtime.execute(a, { NovexExecutionMode.APPROVAL }) { calls++; ToolExecutionResult("a", true) } }
            val two = async { runtime.execute(b, { NovexExecutionMode.APPROVAL }) { calls++; ToolExecutionResult("b", true) } }
            runtime.pending.first { it.size == 2 }
            assertEquals(0, calls)
            runtime.decide(a, true)
            assertTrue(one.await().success)
            runtime.decide(a, true) // Duplicate UI callback cannot execute again or raise an error.
            assertEquals(listOf(b), runtime.pending.value)
            assertEquals(1, calls)
            runtime.decide(b, false)
            assertFalse(two.await().success)
            assertEquals(1, calls)
        }
    }

    @Test fun `pending approval survives restart and changing parameters cannot reuse it`() = runBlocking {
        val op = operation()
        val journal = NovexOperationJournal(File(files.root, "operations"))
        journal.save(NovexOperationRecord(op, NovexOperationStatus.WAITING))
        val runtime = engine()
        runtime.restore("chat")
        assertEquals(listOf(op), runtime.pending.value)
        runtime.decide(op, true)
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            runtime.execute(operation(args = """{"name":"另一张"}"""), { NovexExecutionMode.APPROVAL }) { error("must not execute") }
        } }
        assertTrue(runtime.execute(op, { NovexExecutionMode.APPROVAL }) { ToolExecutionResult("saved", true) }.success)
    }

    @Test fun `restore marks abandoned execution interrupted and export keeps exact conversation records`() = runBlocking {
        val journal = NovexOperationJournal(File(files.root, "operations"))
        val op = operation(args = """{"name":"原文\\n第二行"}""")
        journal.save(NovexOperationRecord(op, NovexOperationStatus.RUNNING))
        journal.save(NovexOperationRecord(operation("another"), NovexOperationStatus.WAITING))
        val runtime = engine()
        runtime.restore("chat")
        assertEquals(NovexOperationStatus.INTERRUPTED, journal.read(op.id)!!.status)
        assertTrue(runtime.pending.value.isEmpty())
        val exported = journal.exportRecords("chat")
        assertEquals(setOf(op.id), exported.keys)
        assertEquals(op.arguments, org.json.JSONObject(exported.getValue(op.id)).getString("arguments"))
        assertEquals("INTERRUPTED", org.json.JSONObject(exported.getValue(op.id)).getString("status"))
    }

    @Test fun `switching to readonly while waiting blocks the approved call`() = runBlocking {
        var mode = NovexExecutionMode.APPROVAL
        val runtime = engine()
        val op = operation()
        withTimeout(5000) {
            val pending = async { runtime.execute(op, { mode }) { error("must not execute") } }
            runtime.pending.first { it.isNotEmpty() }
            mode = NovexExecutionMode.READ_ONLY
            runtime.decide(op, true)
            assertFalse(pending.await().success)
        }
    }

    @Test fun `concurrent executors share the operation claim and do not duplicate effects`() = runBlocking {
        val op = operation()
        val first = engine()
        val second = engine()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        withTimeout(5000) {
            val pending = async { first.execute(op, { NovexExecutionMode.FREE }) {
                entered.complete(Unit); finish.await(); ToolExecutionResult("saved", true)
            } }
            entered.await()
            assertFalse(second.execute(op, { NovexExecutionMode.FREE }) { error("duplicate effect") }.success)
            finish.complete(Unit)
            assertTrue(pending.await().success)
            assertTrue(second.execute(op, { NovexExecutionMode.FREE }) { error("duplicate effect") }.success)
        }
    }

    @Test fun `cancelling a running effect leaves a durable interruption`() = runBlocking {
        val op = operation()
        val runtime = engine()
        val entered = CompletableDeferred<Unit>()
        withTimeout(5000) {
            val task = launch { runtime.execute(op, { NovexExecutionMode.FREE }) {
                entered.complete(Unit); awaitCancellation()
            } }
            entered.await()
            task.cancelAndJoin()
            assertFalse(engine().execute(op, { NovexExecutionMode.FREE }) { error("duplicate effect") }.success)
        }
    }

    @Test fun `interrupted effect is not automatically repeated`() = runBlocking {
        val op = operation()
        val runtime = engine()
        assertThrows(IllegalStateException::class.java) { runBlocking {
            runtime.execute(op, { NovexExecutionMode.FREE }) { error("lost result") }
        } }
        assertFalse(engine().execute(op, { NovexExecutionMode.FREE }) { error("must not repeat") }.success)
    }
}
