package com.openminis.app.ui.navigation

/** Context that must survive when a conversation creates another draft. */
internal data class ChatDraftContext(
    val worldId: String? = null,
    val characterId: String? = null,
    val personaId: String? = null,
)

internal fun buildChatDraftId(
    draftId: String,
    context: ChatDraftContext = ChatDraftContext(),
): String = buildString {
    append("__new__").append(draftId)
    when {
        !context.characterId.isNullOrBlank() -> append("__char__").append(context.characterId)
        !context.worldId.isNullOrBlank() -> append("__world__").append(context.worldId)
    }
    context.personaId?.takeIf(String::isNotBlank)?.let {
        append("__persona__").append(it)
    }
}
