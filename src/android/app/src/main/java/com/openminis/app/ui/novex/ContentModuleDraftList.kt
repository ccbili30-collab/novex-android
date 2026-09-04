package com.openminis.app.ui.novex

import com.openminis.app.data.character.ContentModuleCatalog
import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleScope
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.novex.domain.NovexModuleDraft
import java.util.UUID

/**
 * The shared in-memory module list used by every Novex content-page editor.
 * Storage is deliberately absent: the parent page owns the single save boundary.
 */
internal class ContentModuleDraftList private constructor(
    val scope: ContentModuleScope,
    val modules: List<NovexModuleDraft>,
    val expandedModuleIds: Set<String>,
) {
    fun toggle(moduleId: String): ContentModuleDraftList {
        if (modules.none { it.id == moduleId }) return this
        val expanded = expandedModuleIds.toMutableSet().apply {
            if (!add(moduleId)) remove(moduleId)
        }
        return next(expandedModuleIds = expanded)
    }

    fun add(
        type: ContentModuleType,
        name: String = ContentModuleCatalog.definition(type).displayName,
        moduleId: String = UUID.randomUUID().toString(),
    ): ContentModuleDraftList {
        val definition = ContentModuleCatalog.definition(type)
        require(definition in ContentModuleCatalog.definitions(scope)) {
            "${scope.displayName()}不支持${definition.displayName}"
        }
        require(definition.repeatable || modules.none { it.type == type }) {
            "${definition.displayName}已经存在"
        }
        val draft = NovexModuleDraft(
            id = moduleId,
            type = type,
            name = name,
            contentJson = ContentModuleDocumentCodec.encode(emptyDocument(type)),
            collapsed = true,
        )
        return next(
            modules = modules + draft,
            expandedModuleIds = expandedModuleIds + draft.id,
        )
    }

    fun update(
        moduleId: String,
        name: String,
        document: ContentModuleDocument,
    ): ContentModuleDraftList = next(
        modules = modules.map { module ->
            if (module.id == moduleId) {
                module.copy(name = name, contentJson = ContentModuleDocumentCodec.encode(document))
            } else {
                module
            }
        },
    )

    fun move(moduleId: String, toIndex: Int): ContentModuleDraftList {
        val mutable = modules.toMutableList()
        val from = mutable.indexOfFirst { it.id == moduleId }
        if (from < 0) return this
        val moved = mutable.removeAt(from)
        mutable.add(toIndex.coerceIn(0, mutable.size), moved)
        return next(modules = mutable)
    }

    fun remove(moduleId: String): ContentModuleDraftList = next(
        modules = modules.filterNot { it.id == moduleId },
        expandedModuleIds = expandedModuleIds - moduleId,
    )

    private fun next(
        modules: List<NovexModuleDraft> = this.modules,
        expandedModuleIds: Set<String> = this.expandedModuleIds,
    ): ContentModuleDraftList = ContentModuleDraftList(scope, modules, expandedModuleIds)

    companion object {
        fun empty(scope: ContentModuleScope): ContentModuleDraftList = ContentModuleDraftList(
            scope = scope,
            modules = emptyList(),
            expandedModuleIds = emptySet(),
        )

        fun fromSaved(
            scope: ContentModuleScope,
            modules: List<ContentModuleEntity>,
            moduleId: (ContentModuleEntity) -> String = ContentModuleEntity::id,
        ): ContentModuleDraftList = ContentModuleDraftList(
            scope = scope,
            modules = modules.sortedWith(
                compareBy<ContentModuleEntity> { it.position }.thenBy { it.createdAt }.thenBy { it.id },
            ).map { saved -> NovexModuleDraft.from(saved).copy(id = moduleId(saved)) },
            expandedModuleIds = emptySet(),
        )

        private fun emptyDocument(type: ContentModuleType): ContentModuleDocument = when (type) {
            ContentModuleType.MAP -> ContentModuleDocument.SingleImage()
            ContentModuleType.TIMELINE,
            ContentModuleType.ERA_EVENT,
            ContentModuleType.WORLD_EXPERIENCE,
            -> ContentModuleDocument.Timeline()
            ContentModuleType.REGION,
            ContentModuleType.FACTION,
            ContentModuleType.RACE,
            ContentModuleType.QUOTES,
            ContentModuleType.ATTRIBUTE_PANEL,
            ContentModuleType.EQUIPMENT,
            ContentModuleType.TALENT_SKILL,
            ContentModuleType.APPEARANCE_PERSONALITY,
            ContentModuleType.INTEREST,
            ContentModuleType.GAME_ATTRIBUTES,
            ContentModuleType.GAME_SKILLS,
            ContentModuleType.GAME_EQUIPMENT,
            ContentModuleType.GAME_ITEMS,
            ContentModuleType.GAME_QUESTS,
            ContentModuleType.GAME_CHECKS,
            ContentModuleType.GAME_ENDINGS,
            ContentModuleType.GAME_CHARACTER_STATUS,
            ContentModuleType.GAME_QUICK_ACTIONS,
            -> ContentModuleDocument.Collection()
            ContentModuleType.GAME_PLAYER_IDENTITY,
            ContentModuleType.GAME_OPENING,
            ContentModuleType.GAME_NARRATIVE_RULES,
            ContentModuleType.GAME_POWER_SYSTEM,
            ContentModuleType.CUSTOM,
            -> ContentModuleDocument.Article()
        }

        private fun ContentModuleScope.displayName(): String = when (this) {
            ContentModuleScope.WORLD -> "世界"
            ContentModuleScope.CHARACTER_VERSION -> "角色"
            ContentModuleScope.INTERACTIVE_FICTION -> "文游"
        }
    }
}
