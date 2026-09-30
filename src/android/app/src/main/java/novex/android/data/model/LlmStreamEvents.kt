package novex.android.data.model

import org.json.JSONObject

/*
 * Streaming events for plain chat: one variant per observable increment
 * (text, thinking, reasoning echo, tool assembly, usage, media) plus the
 * lifecycle boundaries. Providers translate their native deltas into these
 * so the UI and the persistence layer see one shape.
 */

sealed class LLMStreamChunk {
    data object Started : LLMStreamChunk()

    data class Text(val text: String) : LLMStreamChunk()

    data class Usage(val usage: LLMUsage) : LLMStreamChunk()

    data class Finished(val stopReason: String?) : LLMStreamChunk()

    /** Live reasoning/thinking delta. */
    data class ThinkingDelta(val text: String) : LLMStreamChunk()

    /**
     * Fully accumulated opaque reasoning (the reasoning_content blob) — not
     * a delta, but the complete text replayed on subsequent turns.
     */
    data class ReasoningContent(val content: String) : LLMStreamChunk()

    data class ToolUseStart(val id: String, val name: String) : LLMStreamChunk()

    data class ToolInputDelta(val id: String, val accumulated: String) : LLMStreamChunk()

    data class ToolCallComplete(
        val id: String,
        val name: String,
        val args: JSONObject,
        // Gemini 3.x thought signature that must be replayed on the next
        // turn's matching function call or the request is rejected.
        val thoughtSignature: String? = null,
    ) : LLMStreamChunk()

    /** A generated media payload, usually arriving once near stream end. */
    data class MediaAttachment(val attachment: LLMMediaAttachment) : LLMStreamChunk()
}
