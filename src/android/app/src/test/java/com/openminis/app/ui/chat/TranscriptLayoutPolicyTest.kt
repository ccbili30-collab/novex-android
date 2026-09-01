package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptLayoutPolicyTest {
    @Test
    fun `streaming transcript uses chronological non-reversed layout`() {
        assertFalse(
            "倒序布局会在最新消息变高时维持底部锚点并把历史内容向上挤",
            CHAT_TRANSCRIPT_REVERSE_LAYOUT,
        )
        assertEquals(
            listOf("oldest", "middle", "newest"),
            transcriptRowsForLayout(listOf("oldest", "middle", "newest")),
        )
    }

    @Test
    fun `distance never grants follow permission and latest means exact list end`() {
        assertTrue(isAtTranscriptLatest(canScrollForward = false))
        assertFalse(isAtTranscriptLatest(canScrollForward = true))
        assertNull(latestTranscriptItemIndex(totalItemsCount = 0))
        assertEquals(0, latestTranscriptItemIndex(totalItemsCount = 1))
        assertEquals(8, latestTranscriptItemIndex(totalItemsCount = 9))
    }

    @Test
    fun `only explicit user navigation may move the viewport`() {
        val allowed = setOf(
            TranscriptViewportMove.SessionOpened,
            TranscriptViewportMove.UserSentMessage,
            TranscriptViewportMove.UserRequestedLatest,
            TranscriptViewportMove.UserRetriedTurn,
        )

        TranscriptViewportMove.entries.forEach { reason ->
            assertEquals(reason.toString(), reason in allowed, allowsTranscriptViewportMove(reason))
        }
    }
}
