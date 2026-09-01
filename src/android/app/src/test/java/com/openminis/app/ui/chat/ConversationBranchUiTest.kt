package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationBranchUiTest {
    @Test
    fun `user branch switcher appears directly after its bubble`() {
        val message = ChatMessage(
            id = "u2",
            role = "user",
            content = "第二个版本",
            branchIndex = 2,
            branchCount = 2,
        )

        val items = buildFlatChatItems(listOf(message))

        assertTrue(items[0] is FlatChatItem.UserBubble)
        assertEquals(
            FlatChatItem.BranchSwitcher("u2", 2, 2),
            items[1],
        )
    }

    @Test
    fun `assistant with several blocks has one switcher after the complete reply`() {
        val message = ChatMessage(
            id = "a2",
            role = "assistant",
            content = "完成",
            toolBlocks = listOf(
                AssistantBlock(id = "tool", kind = "tool_use", toolName = "file_read"),
                AssistantBlock(id = "text", kind = "text", content = "完成"),
            ),
            branchIndex = 2,
            branchCount = 2,
        )

        val items = buildFlatChatItems(listOf(message))

        assertEquals(1, items.count { it is FlatChatItem.BranchSwitcher })
        assertEquals(
            FlatChatItem.BranchSwitcher("a2", 2, 2),
            items.last(),
        )
    }

    @Test
    fun `single path message has no branch switcher`() {
        val items = buildFlatChatItems(
            listOf(ChatMessage(id = "u1", role = "user", content = "唯一版本")),
        )

        assertTrue(items.none { it is FlatChatItem.BranchSwitcher })
    }
}
