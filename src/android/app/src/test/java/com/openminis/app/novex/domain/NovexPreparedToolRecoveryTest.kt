package com.openminis.app.novex.domain

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.provider.ToolJsonRepair
import com.openminis.app.tools.NovexCardFileTools
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.buildTurnParts
import com.openminis.app.ui.chat.encodeAssistantTurnParts
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovexPreparedToolRecoveryTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun `typed sort is unchanged and reopened approval executes once`() = verify(
        NovexCardFileTools.MOVE_MODULE, JSONObject().put("module_id", "module-one").put("position", 0),
        repaired = { args, repairs -> assertTrue(repairs.isEmpty()); assertTrue(args.get("position") is Int) })

    @Test fun `repaired field is durable execution metadata while provider input stays original`() = verify(
        NovexCardFileTools.CREATE, JSONObject().put("creation_key", "one").put("kind", "game").put("nane", "雾港"),
        repaired = { args, repairs -> assertEquals(listOf("fuzzy:nane->name"), repairs)
            assertEquals("雾港", args.getString("name")); assertFalse(args.has("nane")) })

    private fun verify(name: String, raw: JSONObject, repaired: (JSONObject, List<String>) -> Unit) = runBlocking<Unit> {
        val original = raw.toString()
        val effective = JSONObject(original)
        val repairs = ToolJsonRepair.repair(name, effective, null, NovexCardFileTools.definitions())
        repaired(effective, repairs)
        assertEquals(original, raw.toString())
        val block = AssistantBlock("call", "tool_use", toolName = name, toolArgs = original,
            executionArgs = effective.toString())
        val parts = buildTurnParts(listOf(block), 0, mapOf(block.id to original))
        val encoded = encodeAssistantTurnParts(parts, mapOf(block.id to block))
        val stored = JSONArray(encoded).getJSONObject(0).getJSONObject("value")
        assertEquals(original, stored.getString("input"))
        assertEquals(effective.toString(), stored.getString("executionInput"))
        val pending = NovexPendingToolTurn.find(listOf(MessageEntity("reply", "chat", "assistant", encoded, 1, sortOrder = -1)))!!
        val operation = NovexToolOperation("chat", "reply", "call", name, effective.toString(), "保存")
        val directory = java.io.File(files.root, "journal")
        NovexOperationJournal(directory).save(NovexOperationRecord(operation, NovexOperationStatus.WAITING))
        val reopened = NovexToolExecution(NovexOperationJournal(directory))
        reopened.restore("chat")
        val recoveredOperation = operation.copy(arguments = pending.calls.single().arguments)
        assertEquals(operation.fingerprint, recoveredOperation.fingerprint)
        assertNull(reopened.recordedResult(recoveredOperation))
        reopened.decide(reopened.pending.value.single(), true)
        var effects = 0
        repeat(2) {
            val result = reopened.execute(recoveredOperation, { NovexExecutionMode.APPROVAL }) {
                effects++; ToolExecutionResult("已保存", true)
            }
            assertTrue(result.success)
        }
        assertEquals(1, effects)
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            reopened.recordedResult(operation.copy(arguments = JSONObject(effective.toString()).put("unexpected", true).toString()))
        } }
    }

    @Test fun `legacy turns without prepared metadata keep original input`() {
        val raw = JSONObject().put("module_id", "module-one").put("position", 0).toString()
        val block = AssistantBlock("call", "tool_use", toolName = NovexCardFileTools.MOVE_MODULE, toolArgs = raw)
        val encoded = encodeAssistantTurnParts(buildTurnParts(listOf(block), 0, mapOf(block.id to raw)), mapOf(block.id to block))
        val pending = NovexPendingToolTurn.find(listOf(MessageEntity("reply", "chat", "assistant", encoded, 1, sortOrder = -1)))!!
        assertEquals(raw, pending.calls.single().arguments)
    }
}
