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
    fun completedReplyMayFreezeIntoMultipleMarkdownRows() {
        val frozen = bodyRows(
            buildFlatChatItems(
                listOf(streamingMessage("第一段。\n\n第二段。\n\n第三段。", streaming = false)),
            ),
        )

        assertTrue("输出结束后允许按段冻结正文", frozen.size > 1)
    }
}
