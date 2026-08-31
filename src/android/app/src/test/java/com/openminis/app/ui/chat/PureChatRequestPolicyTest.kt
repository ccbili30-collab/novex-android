package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PureChatRequestPolicyTest {
    @Test
    fun `fallback candidates must keep the same tool mode`() {
        val pureChat = LLMModel("chat", "Chat", "relay", supportsTools = false)
        val historicalDefault = LLMModel("legacy", "Legacy", "relay")
        val toolsEnabled = LLMModel("tools", "Tools", "relay", supportsTools = true)

        assertTrue(hasSameToolMode(pureChat, pureChat.copy(id = "chat-2")))
        assertTrue(hasSameToolMode(historicalDefault, toolsEnabled))
        assertFalse(hasSameToolMode(pureChat, toolsEnabled))
    }

    @Test
    fun `pure chat keeps visible conversation and removes every tool artifact`() {
        val history = listOf(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "建立世界",
                contentParts = listOf(AgentContentPart.Text("建立世界")),
            ),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "世界已经准备好",
                contentParts = listOf(
                    AgentContentPart.Text("世界已经准备好"),
                    AgentContentPart.ToolUse("call-1", "file_write", JSONObject()),
                ),
                reasoningContent = "hidden reasoning",
            ),
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolResult("call-1", "file_write", "saved"),
                ),
            ),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolUse("call-2", "present_choices", JSONObject()),
                ),
            ),
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "继续故事",
            ),
        )

        val clean = pureChatHistory(history)

        assertEquals(3, clean.size)
        assertEquals(listOf("建立世界", "世界已经准备好", "继续故事"), clean.map { it.content })
        assertTrue(clean.flatMap { it.contentParts }.none {
            it is AgentContentPart.ToolUse || it is AgentContentPart.ToolResult
        })
        assertNull(clean[1].reasoningContent)
    }
}
