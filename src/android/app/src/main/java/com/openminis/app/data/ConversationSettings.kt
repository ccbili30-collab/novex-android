package com.openminis.app.data

const val MAX_CONVERSATION_PROMPT_CHARS = 48_000
const val MAX_IMAGE_STYLE_PROMPT_CHARS = 8_000
const val MAX_NOVEX_CONFIGURATION_CHARS = 512_000

data class ConversationSettingsSnapshot(
    val conversationPrompt: String,
    val imageStylePrompt: String = "",
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
        assistantDisplayName = value.assistantDisplayName.trim().take(80),
        assistantAvatarPath = value.assistantAvatarPath?.trim()?.ifBlank { null },
        playerDisplayName = value.playerDisplayName.trim().take(80),
        playerAvatarPath = value.playerAvatarPath?.trim()?.ifBlank { null },
        novexConfigurationJson = value.novexConfigurationJson.trim().take(MAX_NOVEX_CONFIGURATION_CHARS),
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
