package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovexCardExecutionPresentationTest {
    private fun tool(id: String, name: String = "document_read", status: ToolBlockStatus = ToolBlockStatus.SUCCESS, content: String = "{}") =
        FlatChatItem.AssistantToolUse("reply", AssistantBlock(id, "tool_use", content, status, toolName = name), emptyList())
    private fun text(id: String, value: String, execution: Boolean = false, streaming: Boolean = false) =
        FlatChatItem.AssistantText("reply", AssistantBlock(id, "text", value, executionText = execution), streaming, messageMarkdown = value)

    @Test fun runningFailedAndCancelledOperationsFoldWithoutHidingTheFinalAnswer() {
        val intro = text("intro", "读取并整理资料", execution = true, streaming = true)
        val pending = tool("pending", status = ToolBlockStatus.PENDING)
        val failed = tool("failed", status = ToolBlockStatus.FAILED)
        val cancelled = tool("cancelled", status = ToolBlockStatus.CANCELLED)
        val final = text("final", "一张已保存，另一张未完成。")
        val raw = listOf(intro, pending, failed, cancelled, final)
        val result = foldNovexExecutionProcesses(raw)
        assertEquals(2, result.size)
        val process = result.first() as FlatChatItem.AssistantProcess
        assertEquals(raw.dropLast(1), process.rows)
        assertEquals(intro.key, process.key)
        assertEquals("进行中", process.statusLabel())
        assertSame(final, result.last())
        assertEquals(5, raw.size)
    }
    @Test fun textIsClassifiedByItsChannelInsteadOfPositionOrWording() {
        val formal = text("first", "先给你的完整回答")
        val progress = text("after", "正在保存下一张卡", execution = true)
        val rows = foldNovexExecutionProcesses(listOf(formal, tool("saved"), progress))
        assertSame(formal, rows.first())
        assertTrue((rows.last() as FlatChatItem.AssistantProcess).rows.contains(progress))
        val live = foldNovexExecutionProcesses(listOf(text("live", "开始整理", true, true))).single()
        assertTrue(live is FlatChatItem.AssistantProcess)
        assertEquals("进行中", (live as FlatChatItem.AssistantProcess).statusLabel())
    }
    @Test fun generatedImagesAndInteractiveChoicesRemainUsableAcrossUserBoundaries() {
        val generated = tool("image", "generate_image")
        val picture = text("picture", "![成果图片](novex://images/example)")
        val choice = tool("choice", "present_choices")
        val user = FlatChatItem.UserBubble(ChatMessage("user", "user", "继续"))
        val result = foldNovexExecutionProcesses(listOf(tool("first"), generated, picture, choice, user, tool("second")))
        assertEquals(2, result.filterIsInstance<FlatChatItem.AssistantProcess>().size)
        assertTrue(result.containsAll(listOf(generated, picture, choice, user)))
        val failedChoice = foldNovexExecutionProcesses(listOf(tool("bad", "present_choices", ToolBlockStatus.FAILED))).single()
        assertEquals("有操作未完成", (failedChoice as FlatChatItem.AssistantProcess).statusLabel())
    }
    @Test fun creationReceiptsReportActualSavedCardsWithoutGuessingUserIntent() {
        assertNull(NovexCardCreationTask.evaluate(listOf(AssistantBlock("text", "text", "已经完成"))))
        fun saved(call: String, card: String) = tool(call, "novex_write_card", content = """{"status":"saved_verified","saved":true,"created_cards":[{"kind":"game","id":"$card"}]}""").block
        val one = saved("call-1", "one")
        val retry = saved("call-retry", "one")
        val two = saved("call-2", "two")
        assertEquals(1, NovexCardCreationTask.evaluate(listOf(one, retry))!!.cards.size)
        assertEquals(2, NovexCardCreationTask.evaluate(listOf(one, two))!!.cards.size)
        val partial = NovexCardCreationTask.evaluate(listOf(one, tool("failed", "novex_write_card", ToolBlockStatus.FAILED).block))!!
        assertEquals("saved_needs_review", partial.status)
        assertEquals(listOf("game" to "one"), partial.cards)
    }
    @Test fun turnSerializationPreservesVerbatimTextAndExecutionChannel() {
        val raw = "原话：\"第一章\"\n第二章\\末尾"
        val blocks = listOf(AssistantBlock("text", "text", raw, executionText = true),
            AssistantBlock("call", "tool_use", toolName = "novex_write_card", thoughtSignature = "signature"))
        val parts = buildTurnParts(blocks, 0, mapOf("call" to """{"name":"作品"}"""))
        val stored = JSONArray(encodeAssistantTurnParts(parts, blocks.associateBy { it.id }))
        assertEquals(raw, stored.getJSONObject(0).getString("value"))
        assertTrue(stored.getJSONObject(0).getBoolean("execution"))
        assertEquals("signature", stored.getJSONObject(1).getJSONObject("value").getString("thoughtSignature"))
        assertEquals(raw, (parts.first() as AgentContentPart.Text).text)
        val final = JSONArray(encodeAssistantTurnParts(listOf(AgentContentPart.Text(raw)), emptyMap())).getJSONObject(0)
        assertFalse(final.getBoolean("execution"))
        val interrupted = JSONArray(encodeAssistantTurnParts(listOf(AgentContentPart.Text(raw)), mapOf("text" to blocks.first()))).getJSONObject(0)
        assertTrue(interrupted.getBoolean("execution"))
    }
    @Test fun formalCopyAndSpeechExcludeWorkTextWhileTerminalStoryAndChoicesSurviveRoundtrip() {
        val work = AssistantBlock("work", "text", "读取后台资料", executionText = true)
        val answer = AssistantBlock("answer", "text", "你走进了村庄。", executionText = false)
        val choices = tool("choices", "present_choices").block
        val blocks = listOf(work, answer, choices)
        assertEquals("你走进了村庄。", formalAssistantText(blocks, "全部原文"))
        assertEquals("", formalAssistantText(listOf(work), "不应回退泄漏"))
        assertEquals("旧纯文字", formalAssistantText(emptyList(), "旧纯文字"))
        assertTrue(isCompletedPresentationTurn(listOf(answer, choices)))
        assertFalse(isCompletedPresentationTurn(listOf(work, tool("write", "novex_write_card").block, choices)))
        assertFalse(isCompletedPresentationTurn(listOf(answer, choices.copy(toolStatus = ToolBlockStatus.FAILED))))
        val stored = JSONArray(encodeAssistantTurnParts(buildTurnParts(blocks, 0, emptyMap()), blocks.associateBy { it.id }))
        assertTrue(stored.getJSONObject(0).getBoolean("execution"))
        assertFalse(stored.getJSONObject(1).getBoolean("execution"))
        assertEquals("uiToolUse", stored.getJSONObject(2).getString("type"))
    }

}
