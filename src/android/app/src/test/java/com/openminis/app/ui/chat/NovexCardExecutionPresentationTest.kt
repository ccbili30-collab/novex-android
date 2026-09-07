package com.openminis.app.ui.chat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovexCardExecutionPresentationTest {
    private fun tool(id: String, name: String = "document_read", status: ToolBlockStatus = ToolBlockStatus.SUCCESS, content: String = "{}") =
        FlatChatItem.AssistantToolUse("reply", AssistantBlock(id, "tool_use", content, status, toolName = name), emptyList())
    private fun text(id: String, value: String) = FlatChatItem.AssistantText("reply", AssistantBlock(id, "text", value), false, messageMarkdown = value)
    @Test fun processFoldsProgressAndSuccessfulToolsButKeepsFinalAnswerFailureAndApproval() {
        val intro = text("intro", "读取并整理资料")
        val read = tool("read")
        val failed = tool("failed", status = ToolBlockStatus.FAILED)
        val approval = tool("approval", "novex_write_card", content = """{"status":"waiting_confirmation"}""")
        val final = text("final", "等待你的确认")
        val raw = listOf(intro, read, failed, approval, final)
        val result = foldNovexExecutionProcesses(raw)
        assertEquals(4, result.size)
        val process = result.first() as FlatChatItem.AssistantProcess
        assertEquals(listOf(intro, read), process.rows)
        assertEquals(intro.key, process.key)
        assertEquals(listOf(failed, approval, final), result.drop(1))
        assertEquals(5, raw.size)
    }
    @Test fun generatedImagesRemainOnTheMainReadingSurface() {
        val generated = tool("image", "generate_image")
        val picture = text("picture", "![成果图片](novex://images/example)")
        val result = foldNovexExecutionProcesses(listOf(tool("read"), generated, picture, tool("save", "save_checkpoint")))
        assertTrue(result.contains(generated))
        assertTrue(result.contains(picture))
        assertEquals(2, result.filterIsInstance<FlatChatItem.AssistantProcess>().single().tools.size)
    }
    @Test fun userBoundaryAndInteractiveChoicesNeverDisappear() {
        val choice = tool("choice", "present_choices")
        val user = FlatChatItem.UserBubble(ChatMessage("user", "user", "继续"))
        val result = foldNovexExecutionProcesses(listOf(tool("first"), choice, user, tool("second"), text("done", "正文")))
        assertEquals(2, result.filterIsInstance<FlatChatItem.AssistantProcess>().size)
        assertTrue(result.contains(choice)); assertTrue(result.contains(user))
    }
    @Test fun creationCompletionRequiresSavedReadbackAndRequestedNumber() {
        val request = listOf("创建两张文游卡")
        val planOnly = NovexCardCreationTask.evaluate(request, listOf(AssistantBlock("text", "text", "已经完成")))!!
        assertEquals("incomplete", planOnly.status); assertTrue(planOnly.mayRepair)
        fun saved(id: String) = tool(id, "novex_write_card", content = """{"status":"saved_verified","saved":true,"created_cards":[{"kind":"game","id":"$id"}]}""").block
        assertEquals("saved_needs_review", NovexCardCreationTask.evaluate(request, listOf(saved("one")))!!.status)
        assertEquals("saved_needs_review", NovexCardCreationTask.evaluate(listOf("创建两张一样的文游卡"), listOf(saved("one")))!!.status)
        assertEquals("saved_verified", NovexCardCreationTask.evaluate(request, listOf(saved("one"), saved("two")))!!.status)
        assertEquals("saved_needs_review", NovexCardCreationTask.evaluate(request, listOf(saved("one"), saved("one")))!!.status)
        assertNull(NovexCardCreationTask.evaluate(listOf("请告诉我怎么创建文游卡？"), emptyList()))
        assertNull(NovexCardCreationTask.evaluate(listOf("不要创建文游，只讨论格式"), emptyList()))
        assertNull(NovexCardCreationTask.evaluate(listOf("创建文游", "取消", "继续"), emptyList()))
    }
}
