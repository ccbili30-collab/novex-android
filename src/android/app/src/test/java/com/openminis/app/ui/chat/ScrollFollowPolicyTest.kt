package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollFollowPolicyTest {
    @Test
    fun fingerDragImmediatelySuspendsStreamingFollow() {
        assertFalse(
            reduceStreamingFollow(
                current = true,
                event = StreamingFollowEvent.UserDragStarted,
            ),
        )
    }

    @Test
    fun followResumesOnlyAfterUserActuallyReturnsToBottom() {
        assertFalse(
            reduceStreamingFollow(
                current = false,
                event = StreamingFollowEvent.UserDragStoppedAway,
            ),
        )
        assertTrue(
            reduceStreamingFollow(
                current = false,
                event = StreamingFollowEvent.UserDragStoppedAtBottom,
            ),
        )
    }

    @Test
    fun explicitSendOrBottomRequestEnablesFollow() {
        assertTrue(
            reduceStreamingFollow(false, StreamingFollowEvent.ExplicitBottomRequested),
        )
    }

    @Test
    fun programmaticScrollCompletionCannotTriggerInteractionSettle() {
        assertFalse(
            shouldSettleAfterInteraction(
                scrollInProgress = false,
                userDragPending = false,
                userScrolledAway = false,
                isNearBottom = true,
                isStreaming = true,
            ),
        )
    }

    @Test
    fun completedFingerDragMaySettleWhileFollowingLiveOutput() {
        assertTrue(
            shouldSettleAfterInteraction(
                scrollInProgress = false,
                userDragPending = true,
                userScrolledAway = false,
                isNearBottom = true,
                isStreaming = true,
            ),
        )
    }

    @Test
    fun activeScrollOrHistoryReadingNeverSettles() {
        assertFalse(
            shouldSettleAfterInteraction(true, true, false, true, true),
        )
        assertFalse(
            shouldSettleAfterInteraction(false, true, true, false, true),
        )
    }

    @Test
    fun smallUpwardPeekLeavesTheStreamingFollowZone() {
        assertFalse(
            isInsideStreamingFollowBottomZone(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = 8,
                pixelsPerDp = 1f,
            ),
        )
    }

    @Test
    fun tinyRoundingOffsetStillCountsAsBottom() {
        assertTrue(
            isInsideStreamingFollowBottomZone(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = 2,
                pixelsPerDp = 1f,
            ),
        )
    }
}
