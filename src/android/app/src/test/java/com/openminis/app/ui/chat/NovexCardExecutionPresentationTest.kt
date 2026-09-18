package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovexCardExecutionPresentationTest {
    private fun tool(
        id: String,
        name: String = "document_read",
        status: ToolBlockStatus = ToolBlockStatus.SUCCESS,
        content: String = "{}",
        messageIsStreaming: Boolean = false,
    ) = FlatChatItem.AssistantToolUse(
        "reply", AssistantBlock(id, "tool_use", content, status, toolName = name), emptyList(),
        messageIsStreaming = messageIsStreaming,
    )
    private fun text(id: String, value: String, execution: Boolean = false, streaming: Boolean = false) =
        FlatChatItem.AssistantText("reply", AssistantBlock(id, "text", value, executionText = execution), streaming, messageMarkdown = value)

    // [T-live-tool-tail]（2026-09-17 用户批：参照 dsh/codex）行为变更：
    // **活流中**（messageIsStreaming 且 STREAMING/PENDING/RUNNING）的工具行
    // 不再折叠——留在主文流配滚动尾巴；旧断言"pending 也折进工作行"随之
    // 改写。恢复路径产出的历史飞行块（无活流）仍照折。
    @Test fun inFlightToolsStayLiveWhileSettledOnesFold() {
        val intro = text("intro", "读取并整理资料", execution = true, streaming = true)
        val streaming = tool("streaming", status = ToolBlockStatus.STREAMING, messageIsStreaming = true)
        val pending = tool("pending", status = ToolBlockStatus.PENDING, messageIsStreaming = true)
        val running = tool("running", status = ToolBlockStatus.RUNNING, messageIsStreaming = true)
        val failed = tool("failed", status = ToolBlockStatus.FAILED)
        val cancelled = tool("cancelled", status = ToolBlockStatus.CANCELLED)
        val final = text("final", "一张已保存，另一张未完成。")
        val result = foldNovexExecutionProcesses(listOf(intro, streaming, pending, running, failed, cancelled, final))
        // 三个飞行中的工具原样留在主文流（各配 ToolCallPill 尾巴），叙述保留，
        // 其余（过程文字/失败/取消）折成唯一工作行钉在回合末尾。
        assertTrue(result.contains(streaming))
        assertTrue(result.contains(pending))
        assertTrue(result.contains(running))
        assertTrue(result.contains(final))
        val process = result.last() as FlatChatItem.AssistantProcess
        assertEquals(listOf<FlatChatItem>(intro, failed, cancelled), process.rows)
        assertEquals(intro.key, process.key)
        // 主文流仍有飞行工具 → 工作行标"进行中"（净眼 P2：不再退化为查看记录）。
        assertEquals("进行中", process.statusLabel())
        assertEquals(5, result.size)
    }

    @Test fun restoredHistoricalInFlightBlocksStillFold() {
        // 净眼 P1：journal 重放产出的历史 PENDING/RUNNING 块不是本回合活流
        // （messageIsStreaming=false）——照常折叠，不永久挂 shimmer/尾巴。
        val historicalPending = tool("restored-pending", status = ToolBlockStatus.PENDING)
        val historicalRunning = tool("restored-running", status = ToolBlockStatus.RUNNING)
        val result = foldNovexExecutionProcesses(listOf(historicalPending, historicalRunning))
        val process = result.single() as FlatChatItem.AssistantProcess
        assertTrue(process.rows.containsAll(listOf<FlatChatItem>(historicalPending, historicalRunning)))
        assertEquals("进行中", process.statusLabel())
    }

    @Test fun settledToolStillFolds() {
        val done = tool("done", status = ToolBlockStatus.SUCCESS)
        val result = foldNovexExecutionProcesses(listOf(done))
        assertTrue(result.single() is FlatChatItem.AssistantProcess)
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
        assertTrue(failedChoice is FlatChatItem.AssistantToolUse)
    }
    // [T-thinking-live]（2026-09-15 用户决策：思考先实时显示，输出完再收档）
    @Test fun streamingTrailingThinkingStaysLiveWhileCompletedThinkingFolds() {
        fun thinking(id: String, content: String, last: Boolean, streaming: Boolean, trailing: Boolean) =
            FlatChatItem.AssistantThinking(
                messageId = "reply",
                block = AssistantBlock(id, "thinking", content),
                isLast = last,
                messageIsStreaming = streaming,
                isLastBlockOverall = trailing,
            )
        val done = thinking("t1", "先想了一步", last = false, streaming = true, trailing = false)
        val call = tool("call-1")
        val live = thinking("t2", "正在想……", last = true, streaming = true, trailing = true)
        val rows = foldNovexExecutionProcesses(listOf(done, call, live))
        // 已完成的思考与工具收进工作记录；进行中的思考留在直播区实时展开。
        val process = rows.filterIsInstance<FlatChatItem.AssistantProcess>().single()
        assertTrue(process.rows.contains(done))
        assertTrue(process.rows.contains(call))
        // [T-turn-single-card] 直播思考是唯一保留行；工作行钉在回合末尾。
        assertSame(live, rows.first())
        assertSame(process, rows.last())
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
