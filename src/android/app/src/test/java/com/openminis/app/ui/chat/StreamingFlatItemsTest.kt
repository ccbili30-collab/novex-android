package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingFlatItemsTest {
    private fun streamingMessage(content: String, streaming: Boolean = true) = ChatMessage(
        id = "assistant-1",
        role = "assistant",
        content = content,
        isStreaming = streaming,
        toolBlocks = listOf(
            AssistantBlock(
                id = "text-1",
                kind = "text",
                content = content,
            ),
        ),
    )

    private fun bodyRows(items: List<FlatChatItem>): List<FlatChatItem> = items.filter {
        it is FlatChatItem.AssistantText ||
            it is FlatChatItem.AssistantMarkdownBlock ||
            it is FlatChatItem.AssistantLegacyContent
    }

    @Test
    fun streamingParagraphGrowthKeepsOneStableLiveBodyRow() {
        val threeParagraphs = buildFlatChatItems(
            listOf(streamingMessage("第一段。\n\n第二段。\n\n第三段。")),
        )
        val fourParagraphs = buildFlatChatItems(
            listOf(streamingMessage("第一段。\n\n第二段。\n\n第三段。\n\n第四段。")),
        )

        val before = bodyRows(threeParagraphs)
        val after = bodyRows(fourParagraphs)

        assertEquals("流式回复必须始终只有一个实时正文行", 1, before.size)
        assertEquals("新增段落不能插入新的懒加载列表行", 1, after.size)
        assertEquals("流式正文增长时列表行键必须保持稳定", before.single().key, after.single().key)
    }

    @Test
    fun latestReplyKeepsTheSameBodyRowWhenStreamingEnds() {
        val live = bodyRows(
            buildFlatChatItems(
                listOf(streamingMessage("第一段。\n\n第二段。\n\n第三段。", streaming = true)),
            ),
        )
        val frozen = bodyRows(
            buildFlatChatItems(
                listOf(streamingMessage("第一段。\n\n第二段。\n\n第三段。", streaming = false)),
            ),
        )

        assertEquals("流式结束不能把当前正文替换成一批新列表行", 1, frozen.size)
        assertEquals("流式与最终排版必须保留同一个列表行锚点", live.single().key, frozen.single().key)
    }

    @Test
    fun firstReplyContentKeepsTheWaitingRowAnchor() {
        val waiting = buildFlatChatItems(
            listOf(
                ChatMessage(
                    id = "assistant-1",
                    role = "assistant",
                    content = "",
                    isStreaming = true,
                    isAwaitingModelResponse = true,
                ),
            ),
        )
        val firstText = buildFlatChatItems(
            listOf(streamingMessage("第一段。")),
        )

        assertEquals(
            "首个流式内容到达时必须继承等待行的锚点，不能回退到旧消息",
            waiting.single().key,
            firstText.first().key,
        )
    }

    @Test
    fun firstThinkingContentKeepsTheWaitingRowAnchor() {
        val waiting = buildFlatChatItems(
            listOf(
                ChatMessage(
                    id = "assistant-1",
                    role = "assistant",
                    content = "",
                    isStreaming = true,
                    isAwaitingModelResponse = true,
                    thinkingLevel = com.openminis.app.data.model.ThinkingLevel.LOW,
                ),
            ),
        )
        val firstThinking = buildFlatChatItems(
            listOf(
                ChatMessage(
                    id = "assistant-1",
                    role = "assistant",
                    content = "",
                    isStreaming = true,
                    thinkingLevel = com.openminis.app.data.model.ThinkingLevel.LOW,
                    toolBlocks = listOf(
                        AssistantBlock(
                            id = "thinking-1",
                            kind = "thinking",
                            content = "分析中",
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            "首个思考内容到达时必须继承等待行的锚点，不能回退到旧消息",
            waiting.single().key,
            firstThinking.first().key,
        )
    }

    @Test
    fun olderReplyStillFreezesIntoVirtualizedMarkdownRows() {
        val rows = bodyRows(
            buildFlatChatItems(
                listOf(
                    streamingMessage("第一段。\n\n第二段。\n\n第三段。", streaming = false),
                    ChatMessage(id = "user-2", role = "user", content = "继续"),
                ),
            ),
        )

        assertTrue("离开活动尾部后仍应拆分长正文，避免冷历史成为超高单行", rows.size > 1)
    }

    @Test
    fun toolStatusAndGeneratedArtifactGrowthKeepTheSameRowKey() {
        fun rows(status: ToolBlockStatus) = buildFlatChatItems(
            listOf(
                ChatMessage(
                    id = "assistant-tool",
                    role = "assistant",
                    content = "",
                    isStreaming = status != ToolBlockStatus.SUCCESS,
                    toolBlocks = listOf(
                        AssistantBlock(
                            id = "image-tool",
                            kind = "tool_use",
                            toolName = "generate_image",
                            toolStatus = status,
                            imageFilePath = if (status == ToolBlockStatus.SUCCESS) "/tmp/result.png" else null,
                        ),
                    ),
                ),
            ),
        ).filterIsInstance<FlatChatItem.AssistantToolUse>()

        assertEquals(
            "工具卡和图片完成重测不能更换列表锚点",
            rows(ToolBlockStatus.RUNNING).single().key,
            rows(ToolBlockStatus.SUCCESS).single().key,
        )
    }

    @Test
    fun characterConversationShowsOneHeaderPerAssistantRun() {
        val messages = listOf(
            ChatMessage(id = "user-1", role = "user", content = "开始"),
            streamingMessage("第一条", streaming = false).copy(id = "assistant-1"),
            streamingMessage("第二条", streaming = false).copy(id = "assistant-2"),
            ChatMessage(id = "user-2", role = "user", content = "打断"),
            streamingMessage("第三条", streaming = false).copy(id = "assistant-3"),
        )

        val headers = buildFlatChatItems(
            messages = messages,
            showAssistantIdentity = true,
        ).filterIsInstance<FlatChatItem.AssistantHeader>()

        assertEquals(
            "连续角色回复共用一个头像，玩家消息后才显示下一组头像",
            listOf("assistant-1", "assistant-3"),
            headers.map { it.messageId },
        )
    }

    @Test
    fun novaConversationDoesNotReceiveCharacterHeader() {
        val items = buildFlatChatItems(
            messages = listOf(streamingMessage("通用回复", streaming = false)),
            showAssistantIdentity = false,
        )

        assertTrue(items.none { it is FlatChatItem.AssistantHeader })
    }
}
