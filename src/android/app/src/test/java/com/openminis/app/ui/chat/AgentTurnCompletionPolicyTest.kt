package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentTurnCompletionPolicyTest {
    @Test
    fun `initial stop with no content retries once then fails`() {
        assertEquals(
            AgentTurnCompletionAction.RETRY_EMPTY,
            decideAgentTurnCompletion(false, false, "stop", EmptyResponseContext.INITIAL, false),
        )
        assertEquals(
            AgentTurnCompletionAction.FAIL_EMPTY,
            decideAgentTurnCompletion(false, false, "stop", EmptyResponseContext.INITIAL, true),
        )
    }

    @Test
    fun `empty stop after tool result has its own single retry budget`() {
        assertEquals(
            AgentTurnCompletionAction.RETRY_EMPTY,
            decideAgentTurnCompletion(false, false, "stop", EmptyResponseContext.AFTER_TOOL_RESULT, false),
        )
        assertEquals(
            AgentTurnCompletionAction.FAIL_EMPTY,
            decideAgentTurnCompletion(false, false, "stop", EmptyResponseContext.AFTER_TOOL_RESULT, true),
        )
    }

    @Test
    fun `missing finish reason is interruption even when response is empty`() {
        assertEquals(
            AgentTurnCompletionAction.INTERRUPTED,
            decideAgentTurnCompletion(false, false, null, EmptyResponseContext.INITIAL, false),
        )
    }

    @Test
    fun `finish reason without DONE is still a normal completed response`() {
        assertEquals(
            AgentTurnCompletionAction.COMPLETE,
            decideAgentTurnCompletion(true, false, "stop", EmptyResponseContext.INITIAL, false),
        )
    }

    @Test
    fun `initial and after tool retries have independent one shot budgets`() {
        val state = EmptyResponseRetryState()

        assertEquals(
            AgentTurnCompletionAction.RETRY_EMPTY,
            state.decide(false, false, "stop", EmptyResponseContext.INITIAL),
        )
        assertEquals(
            AgentTurnCompletionAction.FAIL_EMPTY,
            state.decide(false, false, "stop", EmptyResponseContext.INITIAL),
        )
        assertEquals(
            AgentTurnCompletionAction.RETRY_EMPTY,
            state.decide(false, false, "stop", EmptyResponseContext.AFTER_TOOL_RESULT),
        )
        assertEquals(
            AgentTurnCompletionAction.FAIL_EMPTY,
            state.decide(false, false, "stop", EmptyResponseContext.AFTER_TOOL_RESULT),
        )
    }
}
