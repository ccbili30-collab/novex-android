package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptLayoutPolicyTest {
    @org.junit.Test fun delayedSubmissionCannotOverrideNewerManualReading() {
        org.junit.Assert.assertFalse(SubmittedUserTurn("saved-late", 100).canNavigateAfter(101))
        org.junit.Assert.assertFalse(SubmittedUserTurn("same-tick", 100).canNavigateAfter(100))
        org.junit.Assert.assertTrue(SubmittedUserTurn("next-send", 102).canNavigateAfter(101))
        org.junit.Assert.assertTrue(SubmittedUserTurn("first-send", 0).canNavigateAfter(Long.MIN_VALUE))
    }

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

    @Test
    fun `return to latest follows stream growth until user drags away`() {
        var state = TranscriptFollowState()

        state = state.after(TranscriptFollowEvent.UserRequestedLatest)
        assertTrue(state.shouldMoveFor(TranscriptViewportMove.PassiveStreamGrowth))
        assertTrue(state.shouldMoveFor(TranscriptViewportMove.ImageMeasured))
        assertTrue(state.shouldMoveFor(TranscriptViewportMove.ToolCardMeasured))

        state = state.after(TranscriptFollowEvent.UserDragStarted)
        assertFalse(state.shouldMoveFor(TranscriptViewportMove.PassiveStreamGrowth))
    }

    @Test
    fun `stream completion retains follow through persisted row settlement`() {
        val following = TranscriptFollowState().after(TranscriptFollowEvent.UserRequestedLatest)
        val completed = following.after(TranscriptFollowEvent.StreamCompleted)

        assertTrue(following.shouldMoveFor(TranscriptViewportMove.StreamCompleted))
        assertTrue(completed.shouldMoveFor(TranscriptViewportMove.PassiveStreamGrowth))
        assertTrue(completed.shouldMoveFor(TranscriptViewportMove.ToolCardMeasured))
        val readingHistory = following.after(TranscriptFollowEvent.UserDragStarted)
            .after(TranscriptFollowEvent.StreamCompleted)
        assertFalse(readingHistory.shouldMoveFor(TranscriptViewportMove.PassiveStreamGrowth))
    }

    @Test
    fun `submitted turn waits for its own row and never targets the previous turn`() {
        val awaiting = SubmittedTurnNavigation().awaiting(messageId = "current-user")

        val beforePublish = awaiting.resolve(
            rowKeys = listOf("user:previous-user", "assistant:previous-assistant:text:0"),
        )
        assertNull("旧列表尚未出现本轮消息时不得跳到上一轮", beforePublish.targetIndex)
        assertEquals(awaiting, beforePublish.nextState)

        val afterPublish = beforePublish.nextState.resolve(
            rowKeys = listOf(
                "user:previous-user",
                "assistant:previous-assistant:text:0",
                "user:current-user",
            ),
        )
        assertEquals(2, afterPublish.targetIndex)
        assertNull(afterPublish.nextState.pendingMessageId)

        val afterStreamCompleted = afterPublish.nextState.resolve(
            rowKeys = listOf(
                "user:previous-user",
                "assistant:previous-assistant:text:0",
                "user:current-user",
                "assistant:current-assistant:text:0",
            ),
        )
        assertNull("流开始、增长和结束都不得产生第二次定位", afterStreamCompleted.targetIndex)
    }
}
