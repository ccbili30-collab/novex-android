package com.openminis.app.novex.domain

/** Shared use filtering and precedence for adopted text and pictures. */
object NovexEffectiveFrozenContext {
    fun gameSources(configuration: NovexConversationConfigurationSnapshot): List<NovexFrozenContext> =
        configuration.activeInteractiveFiction?.let { NovexFrozenContextCodec.read(it.contentJson) }.orEmpty().filter { source ->
            source.actorVersionId != null || source.adoptedByGame || configuration.backgroundSettings.any { it.subject in source.conversationRoots }
        }

    fun sources(configuration: NovexConversationConfigurationSnapshot): List<NovexFrozenContext> {
        val actor = (configuration.answerIdentity as? AnswerIdentity.CharacterVersion)?.versionId
        val game = gameSources(configuration)
        val adopted = configuration.adoptedContexts.filter { it.isActive(configuration) }
        return adopted.filter { it.acting && game.none { source -> source.actorVersionId == it.root.id } }.flatMap { it.sources } +
            game.filter { it.actorVersionId != null && it.actorVersionId == actor } +
            game.filter { it.actorVersionId == null && it.adoptedByGame } +
            adopted.filterNot { it.acting }.flatMap { it.sources } +
            game.filter { it.actorVersionId == null && !it.adoptedByGame }
    }
}
