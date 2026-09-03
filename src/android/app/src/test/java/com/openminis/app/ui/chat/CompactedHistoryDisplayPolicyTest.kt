package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class CompactedHistoryDisplayPolicyTest {
    private fun message(id: String, compacted: Boolean = false) = ChatMessage(
        id = id,
        role = "user",
        content = id,
        isCompactedHistory = compacted,
    )

    @Test
    fun `compacted history is collapsed by default while divider and active messages remain`() {
        val rows = listOf(
            message("old-user", compacted = true),
            ChatMessage(
                id = "compact-divider",
                role = "system",
                content = "",
                toolBlocks = listOf(
                    AssistantBlock(
                        id = "compact-marker",
                        kind = "info",
                        content = "2 messages compacted",
                        toolName = "compact",
                    ),
                ),
            ),
            message("active-user"),
        )

        assertEquals(
            listOf("compact-divider", "active-user"),
            conversationMessagesForDisplay(rows, compactedHistoryExpanded = false).map { it.id },
        )
    }

    @Test
    fun `expanded compacted history restores original chronological rows`() {
        val rows = listOf(message("old", compacted = true), message("new"))

        assertEquals(
            listOf("old", "new"),
            conversationMessagesForDisplay(rows, compactedHistoryExpanded = true).map { it.id },
        )
    }
}
