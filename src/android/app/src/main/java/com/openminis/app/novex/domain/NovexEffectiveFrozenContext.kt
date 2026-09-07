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
        fun selected(value: NovexAdoptedContext) = NovexSettingUse.select(configuration, NovexReferenceTarget(value.root),
            value.sources, value.references, value.acting)
        val gameOwned = configuration.activeInteractiveFiction?.let { active ->
            NovexSettingUse.select(configuration, NovexReferenceTarget(NovexContentAddress.interactiveFiction(active.projectId)),
                game.filter { it.actorVersionId == null && it.adoptedByGame }, NovexSettingUse.references(active.contentJson, "backgroundReferences"))
        }.orEmpty()
        val legacyRoots = configuration.backgroundSettings.filter { setting -> adopted.none { !it.acting && it.root == setting.subject } }
            .flatMap { setting -> NovexSettingUse.select(configuration, NovexReferenceTarget(setting.subject),
                game.filter { it.actorVersionId == null && !it.adoptedByGame && setting.subject in it.conversationRoots }, null) }
        return adopted.filter { it.acting && game.none { source -> source.actorVersionId == it.root.id } }.flatMap(::selected) +
            game.filter { it.actorVersionId != null && it.actorVersionId == actor } +
            gameOwned + adopted.filterNot { it.acting }.flatMap(::selected) + legacyRoots
    }
}
