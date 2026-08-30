package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalUiToolPolicyTest {
    @Test
    fun `successful present choices terminates without entering provider history`() {
        val assistant = listOf(
            AgentContentPart.Text("请选择"),
            AgentContentPart.ToolUse("choice-1", PRESENT_CHOICES_TOOL, JSONObject()),
        )
        val terminalIds = if (isSuccessfulTerminalUiTool(PRESENT_CHOICES_TOOL, success = true)) {
            setOf("choice-1")
        } else {
            emptySet()
        }

        assertEquals(setOf("choice-1"), terminalIds)
        assertEquals(listOf(AgentContentPart.Text("请选择")), withoutTerminalUiToolUses(assistant, terminalIds))
    }

    @Test
    fun `failed present choices remains balanced for model self correction`() {
        assertTrue(!isSuccessfulTerminalUiTool(PRESENT_CHOICES_TOOL, success = false))
    }
}
