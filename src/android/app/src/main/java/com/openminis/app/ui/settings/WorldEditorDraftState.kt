package com.openminis.app.ui.settings

import com.openminis.app.data.character.ContentModuleCatalog
import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleScope
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.WorldEntity
import com.openminis.app.novex.domain.NovexCommand
import com.openminis.app.novex.domain.NovexImageChange
import com.openminis.app.novex.domain.NovexModuleDraft
import java.util.UUID
import org.json.JSONArray

internal data class WorldEditorDraftState(
    val worldId: String?,
    val createdAt: Long,
    val name: String,
    val tagsText: String,
    val overview: String,
    val modules: List<NovexModuleDraft>,
    val expandedModuleIds: Set<String> = emptySet(),
    val imageChanges: Map<MediaAssetSlot, NovexImageChange> = emptyMap(),
) {
    val isBlank: Boolean
        get() = name.isBlank()

    fun toggleModule(moduleId: String): WorldEditorDraftState {
        if (modules.none { it.id == moduleId }) return this
        val expanded = expandedModuleIds.toMutableSet().apply {
            if (!add(moduleId)) remove(moduleId)
        }
        return copy(expandedModuleIds = expanded)
    }

    fun addModule(
        type: ContentModuleType,
        name: String = ContentModuleCatalog.definition(type).displayName,
        moduleId: String = UUID.randomUUID().toString(),
    ): WorldEditorDraftState {
        val definition = ContentModuleCatalog.definition(type)
        require(definition in ContentModuleCatalog.definitions(ContentModuleScope.WORLD)) {
            "世界不支持${definition.displayName}"
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
        return copy(
            modules = modules + draft,
            expandedModuleIds = expandedModuleIds + draft.id,
        )
    }

    fun updateModule(
        moduleId: String,
        name: String,
        document: ContentModuleDocument,
    ): WorldEditorDraftState = copy(
        modules = modules.map { module ->
            if (module.id == moduleId) {
                module.copy(name = name, contentJson = ContentModuleDocumentCodec.encode(document))
            } else {
                module
            }
        },
    )

    fun moveModule(moduleId: String, toIndex: Int): WorldEditorDraftState {
        val mutable = modules.toMutableList()
        val from = mutable.indexOfFirst { it.id == moduleId }
        if (from < 0) return this
        val moved = mutable.removeAt(from)
        mutable.add(toIndex.coerceIn(0, mutable.size), moved)
        return copy(modules = mutable)
    }

    fun removeModule(moduleId: String): WorldEditorDraftState = copy(
        modules = modules.filterNot { it.id == moduleId },
        expandedModuleIds = expandedModuleIds - moduleId,
    )

    fun replaceImage(
        slot: MediaAssetSlot,
        bytes: ByteArray,
        mimeType: String,
    ): WorldEditorDraftState = copy(
        imageChanges = imageChanges + (slot to NovexImageChange.Replace(slot, bytes, mimeType)),
    )

    fun removeImage(slot: MediaAssetSlot): WorldEditorDraftState = copy(
        imageChanges = imageChanges + (slot to NovexImageChange.Remove(slot)),
    )

    fun previewWorld(now: Long = System.currentTimeMillis()): WorldEntity = WorldEntity(
        id = worldId ?: "draft-world",
        name = name,
        overview = overview,
        tagsJson = tagsJson(),
        createdAt = createdAt,
        updatedAt = now,
    )

    fun toSaveCommand(now: Long = System.currentTimeMillis()): NovexCommand.SaveWorldPage =
        NovexCommand.SaveWorldPage(
            worldId = worldId,
            name = name,
            overview = overview,
            tagsJson = tagsJson(),
            modules = modules,
            imageChanges = worldImageSlots().mapNotNull { imageChanges[it.slot] },
            now = now,
        )

    private fun tagsJson(): String = JSONArray(
        tagsText.split(Regex("[、,，\\n]")).map(String::trim).filter(String::isNotEmpty),
    ).toString()

    companion object {
        fun create(now: Long = System.currentTimeMillis()) = WorldEditorDraftState(
            worldId = null,
            createdAt = now,
            name = "我的世界",
            tagsText = "",
            overview = "",
            modules = emptyList(),
        )

        fun from(world: WorldEntity, modules: List<ContentModuleEntity>) = WorldEditorDraftState(
            worldId = world.id,
            createdAt = world.createdAt,
            name = world.name,
            tagsText = world.tagsForDraft().joinToString("、"),
            overview = world.overview,
            modules = modules.sortedWith(
                compareBy<ContentModuleEntity> { it.position }.thenBy { it.createdAt }.thenBy { it.id },
            ).map(NovexModuleDraft::from),
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
            -> ContentModuleDocument.Collection()
            ContentModuleType.CUSTOM -> ContentModuleDocument.Article()
        }
    }
}

private fun WorldEntity.tagsForDraft(): List<String> = runCatching {
    val array = JSONArray(tagsJson)
    buildList {
        repeat(array.length()) { index ->
            array.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
        }
    }
}.getOrDefault(emptyList())
