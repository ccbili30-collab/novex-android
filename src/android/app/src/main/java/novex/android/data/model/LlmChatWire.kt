package novex.android.data.model

import kotlinx.serialization.Serializable

/*
 * Wire shapes for plain (non-agent) chat: the request message, the complete
 * response, generated media payloads, and the token accounting block. These
 * mirror the iOS LLMTypes.swift shapes so request/response semantics stay
 * comparable across the two clients.
 */

data class LLMMessage(
    val role: Role,
    val content: String,
    val imageParts: List<ImagePart> = emptyList(),
    // Audio kept as the caller's base64: payloads can be huge and the only
    // consumers (audio-input request blocks) want base64 anyway.
    val audioParts: List<AudioPart> = emptyList(),
    val contentParts: List<AgentContentPart> = emptyList(),
    // Row id of the backing message, when one exists. Compact logic anchors
    // marker boundaries on this instead of positional indexes, which shift
    // across reloads; null for synthesized turns like context summaries.
    val dbMessageId: String? = null,
    // Interleaved-reasoning echo (DeepSeek-style reasoning_content) that
    // must ride along on later turns once thinking is on.
    val reasoningContent: String? = null,
    // Verbatim public text for host-created messages that have no row.
    // Never filled from tool output, reasoning, or a fallback body.
    val publicHistoryText: String? = null,
) {
    enum class Role(val value: String) {
        USER("user"),
        ASSISTANT("assistant"),
    }

    data class ImagePart(
        val data: ByteArray,
        val mimeType: String,
        // Linux-side path the bytes were offloaded to, when they were; lets
        // budgeting swap the pixels for a re-fetchable text placeholder.
        val linuxPath: String? = null,
        // Text substituted when the target model has no native vision: it
        // points at the image path and directs the model to a vision tool.
        val noVisionPlaceholder: String? = null,
    )

    data class AudioPart(
        val format: String,
        val base64Data: String,
    )
}

data class LLMResponse(
    val text: String,
    val stopReason: String?,
    val usage: LLMUsage?,
    val mediaAttachments: List<LLMMediaAttachment> = emptyList(),
)

data class LLMMediaAttachment(
    val type: MediaType,
    val mimeType: String,
    val data: ByteArray,
) {
    enum class MediaType(val value: String) {
        IMAGE("image"),
        AUDIO("audio"),
        VIDEO("video"),
    }
}

@Serializable
data class LLMUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheCreationInputTokens: Int? = null,
    val cacheReadInputTokens: Int? = null,
    // Total input tokens the API billed for this call — the context-window
    // occupancy signal dynamic max-tokens math relies on.
    val latestContextTokens: Int = 0,
)
