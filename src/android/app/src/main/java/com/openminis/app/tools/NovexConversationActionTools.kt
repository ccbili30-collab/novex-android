package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

object NovexConversationActionTools {
    const val SELECT_IDENTITY = "select_answer_identity"
    const val START_GAME = "start_interactive_fiction"
    const val SET_PLAYER_IDENTITY = "set_player_identity"
    val names = setOf(SELECT_IDENTITY, START_GAME, SET_PLAYER_IDENTITY)
    fun definitions() = listOf(
        AgentToolDefinition(name = SET_PLAYER_IDENTITY,
            description = "Save the user's own freely described player identity in this conversation, using the same setting as 我的身份. Use when the user explicitly describes who they play, including before starting a game. Preserve the user's established identity. When the user asks you to design or expand that identity, creative additions are allowed; do not silently treat the answering character as the player. This does not change the answering identity, start a game, or edit any shared card. Existing identity changes require explicit user intent; do not ask for a typed confirmation code.",
            parameters = mapOf(
                "description" to AgentToolParam("string", "The identity description requested by the user. Preserve explicitly supplied facts; create a new description only when the user asks for that. Empty only when clear=true."),
                "label" to AgentToolParam("string", "Optional short display name; omission keeps the existing name."),
                "replace_existing" to AgentToolParam("boolean", "True only when the user explicitly requested changing or clearing the existing player identity."),
                "clear" to AgentToolParam("boolean", "True only when the user explicitly requested removing the player identity; use empty description.")),
            required = listOf("description")),
        AgentToolDefinition(name = SELECT_IDENTITY,
            description = "Persist the answering identity of this conversation when the user asks to speak with a character or change who answers. Call this before roleplaying; creating or managing a card alone never selects it. Use the exact character version ID from the saved card receipt or catalog. Keeps existing player identity unless the user explicitly requests replacement. The normal conversation permission gate applies; never ask for a typed confirmation code.",
            parameters = mapOf(
                "kind" to AgentToolParam("string", "nova, character, or custom.", enumValues = listOf("nova", "character", "custom")),
                "version_id" to AgentToolParam("string", "Required for character: exact saved character version ID."),
                "name" to AgentToolParam("string", "Required for custom: short identity name."),
                "instructions" to AgentToolParam("string", "Required for custom: duties and speaking style."),
                "player_identity_id" to AgentToolParam("string", "Optional exact companion identity ID chosen by the user."),
                "replace_player_identity" to AgentToolParam("boolean", "Only true when the user requested replacing their existing player identity.")),
            required = listOf("kind")),
        AgentToolDefinition(name = START_GAME,
            description = "Actually start a saved interactive-fiction card in this conversation before narrating its opening when the user asks to play. Creating, quoting or managing a card does not start it. If needed first create the native card, then call this with its saved ID. When the user describes who they play, pass their established identity in player_description: starting and saving that identity happen together. A separate identity-setting call or extra name/backstory questionnaire is unnecessary. User-requested identity design or expansion is allowed. One active game only; repeated start of the same game preserves its current run and snapshot. Uses the game's answering identity, or the game host by default.",
            parameters = mapOf(
                "project_id" to AgentToolParam("string", "Exact saved game card ID."),
                "player_description" to AgentToolParam("string", "The player identity established by the user, or designed when the user requested identity creation or expansion. Save it and start the game together. Mutually exclusive with player_identity_id and use_current_player_identity. Existing different identity still requires replace_player_identity."),
                "player_identity_id" to AgentToolParam("string", "Optional exact identity choice when the game has multiple player identities."),
                "use_current_player_identity" to AgentToolParam("boolean", "True when the user explicitly chooses their current saved identity instead of a game's companion identity. Use after set_player_identity for a freely described player. Cannot combine with player_identity_id; does not merge identities."),
                "replace_player_identity" to AgentToolParam("boolean", "Only true when the user chose to replace their existing player identity."),
                "replace_active_game" to AgentToolParam("boolean", "Only true when the user explicitly requested switching away from the active game.")),
            required = listOf("project_id")),
    )
}
