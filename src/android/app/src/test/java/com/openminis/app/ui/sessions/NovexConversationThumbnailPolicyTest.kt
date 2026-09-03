package com.openminis.app.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexConversationThumbnailPolicyTest {
    @Test
    fun characterAvatarWinsThenWorldImageThenStableTitleInitial() {
        assertEquals(
            NovexConversationThumbnail.Image("/character.png", NovexConversationImageKind.CHARACTER),
            resolveConversationThumbnail(
                conversationId = "chat-a",
                title = "苏晚晴",
                characterAvatarPath = "/character.png",
                worldImagePath = "/world.png",
            ),
        )
        assertEquals(
            NovexConversationThumbnail.Image("/world.png", NovexConversationImageKind.WORLD),
            resolveConversationThumbnail(
                conversationId = "chat-a",
                title = "苏晚晴",
                characterAvatarPath = null,
                worldImagePath = "/world.png",
            ),
        )
        assertEquals(
            NovexConversationThumbnail.Initial("苏", stableConversationColorIndex("chat-a")),
            resolveConversationThumbnail(
                conversationId = "chat-a",
                title = "  苏晚晴",
                characterAvatarPath = "",
                worldImagePath = "",
            ),
        )
    }

    @Test
    fun blankOrPunctuationOnlyTitleUsesConversationFallback() {
        assertEquals(
            NovexConversationThumbnail.Initial("聊", stableConversationColorIndex("chat-b")),
            resolveConversationThumbnail(
                conversationId = "chat-b",
                title = " …… ",
                characterAvatarPath = null,
                worldImagePath = null,
            ),
        )
    }

    @Test
    fun colorSelectionIsStableAndNeverLeavesThePalette() {
        val first = stableConversationColorIndex("same-conversation", paletteSize = 8)
        val second = stableConversationColorIndex("same-conversation", paletteSize = 8)

        assertEquals(first, second)
        assertTrue(first in 0 until 8)
    }
}
