package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCompactionPolicyTest {
    @Test
    fun `summary transcript keeps complete creative and technical details beyond legacy preview limits`() {
        val lateCreativeDetail = "关键伏笔：伊薇把银钥匙藏在第三座钟楼。"
        val lateToolArgument = "/var/minis/workspace/story/continuity-after-legacy-limit.md"
        val lateToolResult = "最终状态：人物关系从敌对变为暂时结盟。"
        val messages = listOf(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "前情" + "甲".repeat(700) + lateCreativeDetail,
            ),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolUse(
                        id = "call-1",
                        name = "file_write",
                        input = JSONObject().put("content", "乙".repeat(350) + lateToolArgument),
                    ),
                    AgentContentPart.ToolResult(
                        id = "call-1",
                        name = "file_write",
                        content = "丙".repeat(700) + lateToolResult,
                    ),
                ),
            ),
        )

        val transcript = ConversationCompactionPolicy.transcript(messages)

        assertTrue(transcript.contains(lateCreativeDetail))
        assertTrue(transcript.contains(lateToolArgument))
        assertTrue(transcript.contains(lateToolResult))
    }

    @Test
    fun `one universal prompt preserves continuity without reviving obsolete work`() {
        val prompt = ConversationCompactionPolicy.systemPrompt

        assertTrue(prompt.contains("latest user corrections", ignoreCase = true))
        assertTrue(prompt.contains("relationships", ignoreCase = true))
        assertTrue(prompt.contains("time", ignoreCase = true))
        assertTrue(prompt.contains("unresolved", ignoreCase = true))
        assertTrue(prompt.contains("file paths", ignoreCase = true))
        assertTrue(prompt.contains("injected", ignoreCase = true))
        assertFalse(prompt.contains("Do NOT carry forward \"pending\"", ignoreCase = true))
    }

    @Test
    fun `recursive compaction splits between turns without separating tool use and result`() {
        val firstUser = LLMMessage(LLMMessage.Role.USER, "先保存设定")
        val toolUse = LLMMessage(
            LLMMessage.Role.ASSISTANT,
            "",
            contentParts = listOf(
                AgentContentPart.ToolUse("call-1", "file_write", JSONObject().put("path", "/story.md")),
            ),
        )
        val toolResult = LLMMessage(
            LLMMessage.Role.USER,
            "",
            contentParts = listOf(AgentContentPart.ToolResult("call-1", "file_write", "saved")),
        )
        val toolFollowup = LLMMessage(LLMMessage.Role.ASSISTANT, "已经保存")
        val secondUser = LLMMessage(LLMMessage.Role.USER, "下一幕开始")
        val secondAssistant = LLMMessage(LLMMessage.Role.ASSISTANT, "夜幕降临")

        val split = ConversationCompactionPolicy.splitBetweenTurns(
            listOf(firstUser, toolUse, toolResult, toolFollowup, secondUser, secondAssistant),
        )!!

        assertEquals(listOf(firstUser, toolUse, toolResult, toolFollowup), split.first)
        assertEquals(listOf(secondUser, secondAssistant), split.second)
    }
}
