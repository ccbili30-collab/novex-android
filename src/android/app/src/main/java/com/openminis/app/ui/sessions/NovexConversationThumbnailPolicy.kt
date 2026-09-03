package com.openminis.app.ui.sessions

internal enum class NovexConversationImageKind {
    CHARACTER,
    WORLD,
}

internal sealed interface NovexConversationThumbnail {
    data class Image(
        val path: String,
        val kind: NovexConversationImageKind,
    ) : NovexConversationThumbnail

    data class Initial(
        val text: String,
        val colorIndex: Int,
    ) : NovexConversationThumbnail
}

internal fun resolveConversationThumbnail(
    conversationId: String,
    title: String?,
    characterAvatarPath: String?,
    worldImagePath: String?,
    paletteSize: Int = 8,
): NovexConversationThumbnail {
    characterAvatarPath?.takeIf(String::isNotBlank)?.let {
        return NovexConversationThumbnail.Image(it, NovexConversationImageKind.CHARACTER)
    }
    worldImagePath?.takeIf(String::isNotBlank)?.let {
        return NovexConversationThumbnail.Image(it, NovexConversationImageKind.WORLD)
    }
    val initial = title.orEmpty().firstOrNull(Char::isLetterOrDigit)?.uppercase() ?: "聊"
    return NovexConversationThumbnail.Initial(
        text = initial,
        colorIndex = stableConversationColorIndex(conversationId, paletteSize),
    )
}

internal fun stableConversationColorIndex(conversationId: String, paletteSize: Int = 8): Int {
    require(paletteSize > 0)
    return (conversationId.hashCode().toLong() and 0x7fff_ffffL).rem(paletteSize).toInt()
}
