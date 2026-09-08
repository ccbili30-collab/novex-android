package com.openminis.app.novex.domain

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.tools.ToolExecutionResult
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovexPendingToolTurnTest {
    @get:Rule val files = TemporaryFolder()
    private fun row(id: String, role: String, vararg parts: JSONObject) =
        MessageEntity(id, "chat", role, JSONArray(parts.toList()).toString(), 1, sortOrder = -1)
    private fun text(value: String) = JSONObject().put("type", "text").put("value", value)
    private fun call(id: String) = JSONObject().put("type", "toolUse").put("value", JSONObject()
        .put("toolUseId", id).put("name", "novex_write_card").put("input", "{\"name\":\"作品\"}"))
    private fun result(id: String) = JSONObject().put("type", "toolResult").put("value", JSONObject()
        .put("toolUseId", id).put("name", "novex_write_card").put("output", "已保存").put("success", true))

    @Test fun `recovery keeps exact reply request and pending call identities and never crosses a later user turn`() {
        val rows = listOf(row("request", "user", text("把它放进去")), row("reply", "assistant", call("one"), call("two")),
            row("receipt", "user", result("one")))
        val pending = NovexPendingToolTurn.find(rows)!!
        assertEquals("request", pending.requestId)
        assertEquals("reply", pending.replyId)
        assertEquals(listOf("two"), pending.calls.map { it.id })
        assertEquals("作品", JSONObject(pending.calls.single().arguments).getString("name"))
        assertNull(NovexPendingToolTurn.find(rows + row("next", "user", text("改做别的"))))
        assertNull(NovexPendingToolTurn.find(rows + row("done", "assistant", text("完成"))))
        assertNull(NovexPendingToolTurn.find(rows + row("receipt2", "user", result("two"))))
    }

    @Test fun `reopened approved calls use the real gate and replay effects after a receipt persistence interruption`() = runBlocking {
        val pending = NovexPendingToolTurn.find(listOf(row("request", "user", text("保存")),
            row("reply", "assistant", call("one"))))!!
        fun journal() = NovexOperationJournal(File(files.root, "operations"))
        val call = pending.calls.single()
        val operation = NovexToolOperation("chat", pending.replyId, call.id, call.name, call.arguments, "写卡")
        journal().save(NovexOperationRecord(operation, NovexOperationStatus.APPROVED))
        var effects = 0
        suspend fun invoke(call: NovexPendingToolCall): ToolExecutionResult =
            NovexToolExecution(journal()).execute(operation, { NovexExecutionMode.APPROVAL }) {
                effects++; ToolExecutionResult("已经保存真实内容", true)
            }
        assertThrows(IllegalStateException::class.java) { runBlocking {
            pending.recover(::invoke, persist = { _, _ -> error("模拟回执落入消息前中断") })
        } }
        assertEquals(1, effects)
        val persisted = mutableListOf<String>()
        assertFalse(pending.recover(::invoke, persist = { _, result -> persisted += result.output }))
        assertEquals(1, effects)
        assertEquals(listOf("已经保存真实内容"), persisted)
    }

    @Test fun `reopened waiting approval completes through the same gate and decline never runs the effect`() = runBlocking {
        val pending = NovexPendingToolTurn.find(listOf(row("request", "user", text("保存")), row("reply", "assistant", call("one"))))!!
        val journal = NovexOperationJournal(File(files.root, "operations"))
        val call = pending.calls.single()
        val operation = NovexToolOperation("chat", pending.replyId, call.id, call.name, call.arguments, "写卡")
        journal.save(NovexOperationRecord(operation, NovexOperationStatus.WAITING))
        val reopened = NovexToolExecution(journal)
        reopened.restore("chat")
        reopened.decide(reopened.pending.value.single(), false)
        var effects = 0
        var failure: ToolExecutionResult? = null
        pending.recover(invoke = {
            reopened.execute(operation, { NovexExecutionMode.APPROVAL }) { effects++; ToolExecutionResult("错误", true) }
        }, persist = { _, result -> failure = result })
        assertEquals(0, effects)
        assertFalse(failure!!.success)
    }
}
