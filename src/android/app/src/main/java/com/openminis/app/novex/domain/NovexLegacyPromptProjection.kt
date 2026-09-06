package com.openminis.app.novex.domain

/** Separates legacy generated identity blocks from the conversation's own editable instructions. */
object NovexLegacyPromptProjection {
    fun project(
        prompt: String,
        configuration: NovexConversationConfigurationSnapshot,
        legacyRoleId: String?,
        legacyPlayerId: String?,
        legacyWorldId: String?,
        legacyGeneratedPrompt: String? = null,
    ): String {
        var projected = prompt
        fun remove(tag: String) {
            projected = projected.replace(Regex("<${Regex.escape(tag)}>.*?</${Regex.escape(tag)}>", RegexOption.DOT_MATCHES_ALL), "")
        }
        fun removeUnchangedGeneratedBlock(tag: String) {
            val original = legacyGeneratedPrompt ?: return
            val pattern = Regex("<${Regex.escape(tag)}>.*?</${Regex.escape(tag)}>", RegexOption.DOT_MATCHES_ALL)
            val blocks = pattern.findAll(original).map { it.value }.toSet()
            projected = pattern.replace(projected) { match -> if (match.value in blocks) "" else match.value }
        }
        if (legacyRoleId != null && (configuration.answerIdentity as? AnswerIdentity.CharacterVersion)?.versionId != legacyRoleId) {
            remove("当前角色卡")
        }
        if (legacyPlayerId != null && configuration.playerIdentity?.id != legacyPlayerId) remove("当前玩家身份")
        if (legacyWorldId != null && (configuration.activeInteractiveFiction != null || configuration.backgroundSettings.none {
                it.subject == NovexContentAddress.world(legacyWorldId)
            })) remove("当前世界观")
        val capturedActor = configuration.adoptedContexts.any { it.acting && it.root.id == legacyRoleId && it.isActive(configuration) } ||
            configuration.activeInteractiveFiction?.let { game -> NovexFrozenContextCodec.read(game.contentJson).any {
                it.actorVersionId != null && it.actorVersionId == legacyRoleId &&
                    (configuration.answerIdentity as? AnswerIdentity.CharacterVersion)?.versionId == it.actorVersionId
            } } == true
        if (capturedActor) removeUnchangedGeneratedBlock("当前角色卡")
        if (configuration.adoptedContexts.any { !it.acting && it.root.id == legacyWorldId && it.isActive(configuration) })
            removeUnchangedGeneratedBlock("当前世界观")
        if (configuration.playerIdentity != null) removeUnchangedGeneratedBlock("当前玩家身份")
        return projected.trim()
    }
}
