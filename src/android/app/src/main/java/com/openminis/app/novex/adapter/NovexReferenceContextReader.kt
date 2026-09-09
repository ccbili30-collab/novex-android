package com.openminis.app.novex.adapter

import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.toPlainText
import com.openminis.app.novex.domain.*

/** Reads an exact background scope. A linked game contributes reference material, never another runtime. */
internal class NovexReferenceContextReader(private val workspace: NovexWorkspace) {
    suspend fun read(target: NovexReferenceTarget): List<NovexContextCandidate>? {
        if (workspace.referenceStatus(target) != NovexReferenceTargetStatus.AVAILABLE) return null
        target.moduleId?.let { moduleId ->
            val module = requireNotNull(workspace.module(moduleId)).module
            if (!backgroundModule(module.type)) return emptyList()
            val document = ContentModuleDocumentCodec.decode(module.type, module.contentJson)
            val scoped = target.entryId?.let { entry ->
                val collection = document as? ContentModuleDocument.Collection ?: return null
                collection.copy(items = collection.items.filter { it.id == entry })
            } ?: document
            val candidate = NovexContextCandidate(
                sourceId = moduleId + (target.entryId?.let { ":entry:$it" } ?: ""),
                label = "${module.name}${target.entryId?.let { " · 条目 $it" }.orEmpty()}",
                content = scoped.toPlainText(),
                alwaysInclude = NovexExternalCardImport.isVerbatimModule(module.contentJson),
                aliases = setOf(module.name).filterTo(linkedSetOf(), String::isNotBlank),
                position = module.position,
            )
            return NovexWorldbookConditions.candidates(candidate, module.type, module.contentJson, target.entryId)
        }
        return when (target.subject.kind) {
            NovexContentKind.WORLD, NovexContentKind.CHARACTER_VERSION ->
                WorkspaceNovexContextLoader(workspace, expandReferences = false).load(NovexConversationConfigurationSnapshot(
                    "reference:${target.subject.id}", backgroundSettings = listOf(BackgroundSetting(target.subject))))
                    .filter { it.kind == ContextSourceKind.BACKGROUND_MODULE }
            NovexContentKind.INTERACTIVE_FICTION -> {
                val game = requireNotNull(workspace.interactiveFiction(target.subject.id))
                listOf(NovexContextCandidate("game-reference:${game.project.id}:summary", "文游参考 · ${game.project.name}",
                    game.project.summary, alwaysInclude = true, position = -1)) + game.modules.filter { backgroundModule(it.type) }.map { module ->
                    NovexContextCandidate(module.id, "文游参考 · ${game.project.name} · ${module.name}",
                        ContentModuleDocumentCodec.decode(module.type, module.contentJson).toPlainText(),
                        aliases = setOf(module.name).filterTo(linkedSetOf(), String::isNotBlank), position = module.position)
                }
            }
            NovexContentKind.CREATIVE_ARTIFACT -> emptyList()
        }
    }

    suspend fun references(target: NovexReferenceTarget): List<NovexCardReference> = workspace.referencesFrom(target.subject).filter { reference ->
        (target.moduleId == null || reference.sourceModuleId == target.moduleId) &&
            (reference.sourceModuleId == null || workspace.module(reference.sourceModuleId)?.module?.let { backgroundModule(it.type) } == true)
    }

    private fun backgroundModule(type: ContentModuleType) = NovexModuleVisibility.allowsContext(type, acting = false) &&
        type !in setOf(ContentModuleType.GAME_OPENING, ContentModuleType.GAME_QUICK_ACTIONS)
}
