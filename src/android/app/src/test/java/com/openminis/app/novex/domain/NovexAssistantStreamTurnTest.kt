package com.openminis.app.novex.domain

import com.openminis.app.data.model.LLMStreamChunk as Chunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.ui.chat.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovexAssistantStreamTurnTest {
    private fun turn(clock: () -> Long = { 10_000 }) = AssistantStreamTurn(0, { it }, clock)

    @Test fun availableToolsDoNotHideOrdinaryTextBeforeAnyActualCall() = runBlocking {
        val state = turn()
        val visible = mutableListOf<AssistantStreamTurn.Snapshot>()
        state.accept(Chunk.Text("雨停了，邮局窗前亮起一盏灯。"), true) { visible += it }
        state.finish { visible += it }
        assertFalse(state.snapshot().blocks.single().executionText)
        assertTrue(visible.any { snapshot -> snapshot.blocks.any { it.isText && !it.executionText } })
        state.accept(Chunk.ToolUseStart("save", "save_checkpoint"), true) { visible += it }
        assertTrue(state.snapshot().blocks.filter { it.isText }.all { !it.executionText })
        state.reset()
        state.accept(Chunk.Text("雨后的街道安静下来。"), true) {}
        assertFalse(state.snapshot().blocks.single().executionText)
    }

    @Test fun unexpectedReadonlyToolRetainsItsTextWithoutInferringAHiddenChannel() = runBlocking {
        val state = turn()
        val rendered = mutableListOf<AssistantStreamTurn.Snapshot>()
        state.accept(Chunk.Text("正在保存"), true) { rendered += it }
        assertFalse(state.snapshot().blocks.single().executionText)
        state.accept(Chunk.ToolUseStart("call", "novex_write_card"), true) { rendered += it }
        state.accept(Chunk.Text("这张卡。"), true) { rendered += it }
        state.accept(Chunk.ToolCallComplete("call", "novex_write_card", JSONObject()), true) { rendered += it }
        state.finish { rendered += it }
        val final = state.snapshot()
        assertEquals("正在保存这张卡。", final.text)
        assertEquals(1, final.blocks.count { it.isText })
        assertFalse(final.blocks.first().executionText)
        assertEquals("正在保存这张卡。", final.blocks.first().content)
        // Earlier emitted immutable snapshots cannot mutate underneath the renderer.
        assertEquals("正在保存", rendered.first().blocks.single().content)
        assertFalse(rendered.first().blocks.single().executionText)
    }

    @Test fun orderedBoundariesFlushUnpublishedTailAndDoNotMergeAcrossProviderResponses() = runBlocking {
        val state = turn()
        val rendered = mutableListOf<AssistantStreamTurn.Snapshot>()
        for (chunk in listOf(Chunk.Text("第一"), Chunk.Text("段末尾"), Chunk.ToolUseStart("a", "read"),
            Chunk.Text("第二段"), Chunk.ThinkingDelta("核对"), Chunk.Text("第三段"))) {
            state.accept(chunk, false) { rendered += it }
        }
        state.finish { rendered += it }
        assertEquals(listOf("第一段末尾", "第二段", "第三段"), state.snapshot().blocks.filter { it.isText }.map { it.content })
        val beforeTool = rendered.indexOfFirst { it.blocks.any { b -> b.kind == "tool_use" } }
        assertTrue(beforeTool > 0)
        assertEquals("第一段末尾", rendered[beforeTool - 1].blocks.last().content)
        val next = turn()
        next.accept(Chunk.Text("最终回答"), true) {}
        assertEquals("最终回答", next.snapshot().text)
        assertEquals("第一段末尾第二段第三段", state.snapshot().text)
    }

    @Test fun rollbackClearsCompletedCallsPartialInputReasoningUsageAndDedupeTogether() = runBlocking {
        val state = turn()
        val args = JSONObject().put("name", "原卡")
        for (chunk in listOf(Chunk.Text("失败的片段"), Chunk.ThinkingDelta("思考"), Chunk.ReasoningContent("opaque"),
            Chunk.ToolUseStart("a", "write"), Chunk.ToolInputDelta("a", args.toString()),
            Chunk.ToolCallComplete("a", "write", args, "signature"), Chunk.Usage(LLMUsage(inputTokens = 3, outputTokens = 2)),
            Chunk.Finished("tool_calls"))) state.accept(chunk, true) {}
        val failed = state.reset()
        assertEquals("失败的片段", failed.text)
        assertTrue(state.toolCalls.isEmpty()); assertTrue(state.toolSignatures.isEmpty())
        assertNull(state.reasoningContent); assertNull(state.usage); assertNull(state.finishReason)
        assertNull(state.inputTail("a")); assertTrue(state.snapshot().blocks.isEmpty())
        state.accept(Chunk.ToolUseStart("a", "write"), true) {}
        state.accept(Chunk.ToolCallComplete("a", "write", args), true) {}
        assertEquals("a", state.toolCalls.single().first)
        assertEquals("a", state.snapshot().blocks.single().id)
        state.accept(Chunk.ReasoningContent(""), true) {}
        assertEquals("", state.reasoningContent)
    }

    @Test fun duplicateToolIdsKeepInputsAndSignaturesAlignedAndInputHistoryIsBounded() = runBlocking {
        val state = turn()
        repeat(2) { index ->
            state.accept(Chunk.ToolUseStart("call", "write"), true) {}
            repeat(14) { state.accept(Chunk.ToolInputDelta("call", "$index:$it"), true) {} }
        }
        repeat(2) { state.accept(Chunk.ToolCallComplete("call", "write", JSONObject().put("n", it), "sig$it"), true) {} }
        assertEquals(listOf("call", "call-2"), state.toolCalls.map { it.first })
        assertEquals(mapOf("call" to "sig0", "call-2" to "sig1"), state.toolSignatures)
        assertEquals((4..13).map { "0:$it" }, state.takeInputHistory("call"))
        assertEquals((4..13).map { "1:$it" }, state.takeInputHistory("call-2"))
        assertTrue(state.takeInputHistory("call").isEmpty())
    }

    @Test fun contentAfterToolStartAndStartlessCompletionBothProduceCompleteBlocks() = runBlocking {
        val state = turn()
        state.accept(Chunk.ToolUseStart("a", "read"), true) {}
        state.accept(Chunk.Text("说明"), true) {}
        state.accept(Chunk.ToolCallComplete("a", "read", JSONObject()), true) {}
        assertTrue(state.snapshot().blocks.first().isText)
        assertFalse(state.snapshot().blocks.first().executionText)
        val startless = turn()
        startless.accept(Chunk.Text("准备"), false) {}
        startless.accept(Chunk.ToolCallComplete("x", "read", JSONObject()), false) {}
        assertEquals(listOf("text", "tool_use"), startless.snapshot().blocks.map { it.kind })
        assertFalse(startless.snapshot().blocks.first().executionText)
        assertEquals(ToolBlockStatus.PENDING, startless.snapshot().blocks.last().toolStatus)
    }
}
