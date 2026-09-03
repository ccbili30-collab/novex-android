package com.openminis.app.ui.settings

import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleScope
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.WorldEntity
import com.openminis.app.novex.domain.NovexCommand
import com.openminis.app.novex.domain.NovexImageChange
import com.openminis.app.novex.domain.NovexModuleDraft
import com.openminis.app.ui.novex.ContentModuleDraftList
import com.openminis.app.ui.novex.NovexImageDraft
import org.json.JSONArray

internal data class WorldEditorDraftState(
    val worldId: String?,
    val createdAt: Long,
    val name: String,
    val tagsText: String,
    val overview: String,
    val contentModules: ContentModuleDraftList,
    val images: NovexImageDraft = NovexImageDraft.empty(),
) {
    val isBlank: Boolean
        get() = name.isBlank()

    val modules: List<NovexModuleDraft>
        get() = contentModules.modules

    val imageChanges: Map<MediaAssetSlot, NovexImageChange>
        get() = images.changes

    fun editModules(edit: ContentModuleDraftList.() -> ContentModuleDraftList): WorldEditorDraftState =
        copy(contentModules = contentModules.edit())

    fun replaceImage(
        slot: MediaAssetSlot,
        bytes: ByteArray,
        mimeType: String,
    ): WorldEditorDraftState = copy(images = images.replace(slot, bytes, mimeType))

    fun removeImage(slot: MediaAssetSlot): WorldEditorDraftState = copy(images = images.remove(slot))

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
            contentModules = ContentModuleDraftList.empty(ContentModuleScope.WORLD),
        )

        fun from(world: WorldEntity, modules: List<ContentModuleEntity>) = WorldEditorDraftState(
            worldId = world.id,
            createdAt = world.createdAt,
            name = world.name,
            tagsText = world.tagsForDraft().joinToString("、"),
            overview = world.overview,
            contentModules = ContentModuleDraftList.fromSaved(ContentModuleScope.WORLD, modules),
        )
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
