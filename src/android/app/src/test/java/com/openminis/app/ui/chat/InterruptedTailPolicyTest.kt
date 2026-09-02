package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptedTailPolicyTest {
    @Test
    fun `unpaired tool tails remain recoverable`() {
        assertTrue(isInterruptedAgentTail("USER", listOf("toolResult")))
        assertTrue(isInterruptedAgentTail("ASSISTANT", listOf("text", "toolUse")))
    }

    @Test
    fun `a consumed continuation reminder cannot resurrect resume after reload`() {
        assertFalse(
            isInterruptedAgentTail(
                role = "USER",
                partTypes = listOf("text"),
                singleText = "The user stopped the previous response but now wants to continue.",
            ),
        )
    }
}
