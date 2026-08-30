package com.openminis.app.ui.chat

internal enum class EmptyResponseContext { INITIAL, AFTER_TOOL_RESULT }

internal enum class AgentTurnCompletionAction {
    COMPLETE,
    EXECUTE_TOOLS,
    INTERRUPTED,
    RETRY_EMPTY,
    FAIL_EMPTY,
}

/** Completion policy shared by the real agent loop and its regression tests. */
internal fun decideAgentTurnCompletion(
    hasVisibleContent: Boolean,
    hasToolCalls: Boolean,
    finishReason: String?,
    emptyContext: EmptyResponseContext,
    retryAlreadyUsed: Boolean,
): AgentTurnCompletionAction {
    if (finishReason == null) return AgentTurnCompletionAction.INTERRUPTED
    if (hasToolCalls) return AgentTurnCompletionAction.EXECUTE_TOOLS
    if (hasVisibleContent) return AgentTurnCompletionAction.COMPLETE
    val retryableEmptyStop = finishReason == "stop" || finishReason == "end_turn"
    if (retryableEmptyStop && !retryAlreadyUsed) {
        return AgentTurnCompletionAction.RETRY_EMPTY
    }
    return AgentTurnCompletionAction.FAIL_EMPTY
}

/** Maintains independent one-shot retry budgets for the two empty contexts. */
internal class EmptyResponseRetryState {
    private var initialRetryUsed = false
    private var afterToolRetryUsed = false

    fun decide(
        hasVisibleContent: Boolean,
        hasToolCalls: Boolean,
        finishReason: String?,
        context: EmptyResponseContext,
    ): AgentTurnCompletionAction {
        val retryUsed = when (context) {
            EmptyResponseContext.INITIAL -> initialRetryUsed
            EmptyResponseContext.AFTER_TOOL_RESULT -> afterToolRetryUsed
        }
        val action = decideAgentTurnCompletion(
            hasVisibleContent = hasVisibleContent,
            hasToolCalls = hasToolCalls,
            finishReason = finishReason,
            emptyContext = context,
            retryAlreadyUsed = retryUsed,
        )
        if (action == AgentTurnCompletionAction.RETRY_EMPTY) {
            when (context) {
                EmptyResponseContext.INITIAL -> initialRetryUsed = true
                EmptyResponseContext.AFTER_TOOL_RESULT -> afterToolRetryUsed = true
            }
        }
        return action
    }
}
