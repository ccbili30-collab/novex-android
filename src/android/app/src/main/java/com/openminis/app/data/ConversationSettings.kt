package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

const val MAX_CONVERSATION_PROMPT_CHARS = 48_000
const val MAX_IMAGE_STYLE_PROMPT_CHARS = 8_000
const val MAX_NOVEX_CONFIGURATION_CHARS = 512_000
const val MAX_PER_TURN_PROMPT_CHARS = 8_000

data class ConversationSettingsSnapshot(
    val conversationPrompt: String,
    val imageStylePrompt: String = "",
    val perTurnPrompt: String = "",
    /** null inherits the source background; empty explicitly hides it. */
    val backgroundPath: String? = null,
    val rolePresentationEnabled: Boolean = false,
    val assistantDisplayName: String = "",
    val assistantAvatarPath: String? = null,
    val playerDisplayName: String = "",
    val playerAvatarPath: String? = null,
    val novexConfigurationJson: String = "",
)

fun normalizeConversationSettings(value: ConversationSettingsSnapshot): ConversationSettingsSnapshot =
    value.copy(
        conversationPrompt = value.conversationPrompt.take(MAX_CONVERSATION_PROMPT_CHARS),
        imageStylePrompt = value.imageStylePrompt.trim().take(MAX_IMAGE_STYLE_PROMPT_CHARS),
        perTurnPrompt = value.perTurnPrompt.trim().take(MAX_PER_TURN_PROMPT_CHARS),
        backgroundPath = value.backgroundPath?.trim(),
        assistantDisplayName = value.assistantDisplayName.trim().take(80),
        assistantAvatarPath = value.assistantAvatarPath?.trim()?.ifBlank { null },
        playerDisplayName = value.playerDisplayName.trim().take(80),
        playerAvatarPath = value.playerAvatarPath?.trim()?.ifBlank { null },
        // Structured snapshots must stay valid JSON. Silently slicing at a character boundary
        // corrupted large games and made the next decode fall back to an empty configuration.
        novexConfigurationJson = value.novexConfigurationJson.trim(),
    )

fun mergeImageStylePrompt(requestPrompt: String, imageStylePrompt: String?): String {
    val request = requestPrompt.trim()
    val style = imageStylePrompt?.trim().orEmpty()
    if (style.isEmpty()) return request
    return buildString {
        append(request)
        append("\n\n<当前对话固定图片风格>\n")
        append(style)
        append("\n</当前对话固定图片风格>")
    }
}

/** Wraps the standing per-turn instruction; null when the feature is off (blank text). */
fun perTurnInjectionContent(perTurnPrompt: String?): String? {
    val text = perTurnPrompt?.trim().orEmpty()
    if (text.isEmpty()) return null
    return "<每轮注入>\n$text\n</每轮注入>"
}

/**
 * Appends the per-turn instruction to the LAST user message of the request copy —
 * post-history placement keeps it close to the newest turn without a separate
 * message (which would break role alternation on strict providers) and without
 * touching the persisted history. Applied once per request; falls back to a new
 * user message when no user turn exists.
 */
fun appendPerTurnInjection(history: List<LLMMessage>, perTurnPrompt: String?): List<LLMMessage> {
    val injection = perTurnInjectionContent(perTurnPrompt) ?: return history
    val index = history.indexOfLast { it.role == LLMMessage.Role.USER }
    if (index < 0) return history + LLMMessage(role = LLMMessage.Role.USER, content = injection)
    val target = history[index]
    val updated = if (target.contentParts.isEmpty()) target.copy(
        content = buildString {
            append(target.content)
            if (target.content.isNotBlank()) append("\n\n")
            append(injection)
        },
    ) else target.copy(contentParts = target.contentParts + AgentContentPart.Text(injection))
    return history.toMutableList().also { it[index] = updated }
}
