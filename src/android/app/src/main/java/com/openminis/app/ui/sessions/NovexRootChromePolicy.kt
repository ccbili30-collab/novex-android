package com.openminis.app.ui.sessions

internal enum class NovexRootTopAction {
    SETTINGS,
    UPDATE,
    SEARCH,
    CREATE,
}

internal data class NovexRootChrome(
    val title: String,
    val actions: List<NovexRootTopAction>,
)

internal enum class NovexConversationStart {
    EMPTY,
    WORLD_CONTEXT,
    CREATION_TOOL,
}

internal enum class NovexRootBackAction {
    CLOSE_SEARCH,
    SWITCH_TO_CONVERSATIONS,
    LEAVE_APPLICATION,
}

internal fun novexRootChrome(space: NovexRootSpace): NovexRootChrome = NovexRootChrome(
    title = when (space) {
        NovexRootSpace.CONVERSATIONS -> "Novex"
        NovexRootSpace.WORLDS -> "世界"
        NovexRootSpace.CHARACTERS -> "角色"
    },
    actions = listOf(
        NovexRootTopAction.SETTINGS,
        NovexRootTopAction.UPDATE,
        NovexRootTopAction.SEARCH,
        NovexRootTopAction.CREATE,
    ),
)

internal fun novexConversationCreateMenu(): List<NovexConversationStart> = listOf(
    NovexConversationStart.EMPTY,
    NovexConversationStart.WORLD_CONTEXT,
    NovexConversationStart.CREATION_TOOL,
)

internal fun novexRootBackAction(
    selected: NovexRootSpace,
    searchActive: Boolean,
): NovexRootBackAction = when {
    searchActive -> NovexRootBackAction.CLOSE_SEARCH
    selected != NovexRootSpace.CONVERSATIONS -> NovexRootBackAction.SWITCH_TO_CONVERSATIONS
    else -> NovexRootBackAction.LEAVE_APPLICATION
}
