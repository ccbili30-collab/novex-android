package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollFollowPolicyTest {
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
}
