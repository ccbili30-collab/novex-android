package com.openminis.app.novex.domain

/** Separates legacy generated identity blocks from the conversation's own editable instructions. */
object NovexLegacyPromptProjection {
    fun project(
        prompt: String,
        configuration: NovexConversationConfigurationSnapshot,
        legacyRoleId: String?,
        legacyPlayerId: String?,
        legacyWorldId: String?,
    ): String {
        var projected = prompt
        fun remove(tag: String) {
            projected = projected.replace(Regex("<${Regex.escape(tag)}>.*?</${Regex.escape(tag)}>", RegexOption.DOT_MATCHES_ALL), "")
        }
        if (legacyRoleId != null && (configuration.answerIdentity as? AnswerIdentity.CharacterVersion)?.versionId != legacyRoleId) {
            remove("当前角色卡")
        }
        if (legacyPlayerId != null && configuration.playerIdentity?.id != legacyPlayerId) remove("当前玩家身份")
        if (legacyWorldId != null && (configuration.activeInteractiveFiction != null || configuration.backgroundSettings.none {
                it.subject == NovexContentAddress.world(legacyWorldId)
            })) remove("当前世界观")
        return projected.trim()
    }
}
