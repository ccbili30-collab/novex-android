package com.openminis.app.ui.settings

import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleScope
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.interactivefiction.InteractiveFictionLaunchMode
import com.openminis.app.data.interactivefiction.InteractiveFictionProjectEntity
import com.openminis.app.novex.domain.NovexCommand
import com.openminis.app.novex.domain.NovexImageChange
import com.openminis.app.novex.domain.NovexModuleDraft
import com.openminis.app.ui.novex.ContentModuleDraftList
import com.openminis.app.ui.novex.NovexImageDraft

internal fun nextDefaultInteractiveFictionName(existingNames: Collection<String>): String {
    val names = existingNames.toHashSet()
    if ("新文游" !in names) return "新文游"
    var suffix = 1
    while ("新文游（$suffix）" in names) suffix++
    return "新文游（$suffix）"
}

internal data class InteractiveFictionEditorDraftState(
    val projectId: String?,
    val createdAt: Long,
    val name: String,
    val summary: String,
    val launchMode: InteractiveFictionLaunchMode,
    val playerIdentity: String,
    val contentModules: ContentModuleDraftList,
    val images: NovexImageDraft = NovexImageDraft.empty(),
) {
    val isBlank: Boolean
        get() = name.isBlank()

    val modules: List<NovexModuleDraft>
        get() = contentModules.modules

    val imageChanges: Map<MediaAssetSlot, NovexImageChange>
        get() = images.changes

    fun editModules(
        edit: ContentModuleDraftList.() -> ContentModuleDraftList,
    ): InteractiveFictionEditorDraftState = copy(contentModules = contentModules.edit())

    fun replaceImage(
        slot: MediaAssetSlot,
        bytes: ByteArray,
        mimeType: String,
    ): InteractiveFictionEditorDraftState = copy(images = images.replace(slot, bytes, mimeType))

    fun removeImage(slot: MediaAssetSlot): InteractiveFictionEditorDraftState =
        copy(images = images.remove(slot))

    fun toSaveCommand(now: Long = System.currentTimeMillis()) =
        NovexCommand.SaveInteractiveFictionPage(
            projectId = projectId,
            name = name,
            summary = summary,
            launchMode = launchMode,
            playerIdentity = playerIdentity,
            modules = modules,
            imageChanges = imageChanges.values.filter { it.slot in interactiveFictionImageSlots },
            now = now,
        )

    companion object {
        fun create(
            now: Long = System.currentTimeMillis(),
            name: String = "新文游",
        ) = InteractiveFictionEditorDraftState(
            projectId = null,
            createdAt = now,
            name = name,
            summary = "",
            launchMode = InteractiveFictionLaunchMode.FREE_SANDBOX,
            playerIdentity = "",
            contentModules = ContentModuleDraftList.empty(ContentModuleScope.INTERACTIVE_FICTION),
        )

        fun from(
            project: InteractiveFictionProjectEntity,
            modules: List<ContentModuleEntity>,
        ) = InteractiveFictionEditorDraftState(
            projectId = project.id,
            createdAt = project.createdAt,
            name = project.name,
            summary = project.summary,
            launchMode = project.launchMode,
            playerIdentity = project.playerIdentity,
            contentModules = ContentModuleDraftList.fromSaved(
                ContentModuleScope.INTERACTIVE_FICTION,
                modules,
            ),
        )
    }
}

internal val interactiveFictionImageSlots = setOf(
    MediaAssetSlot.INTERACTIVE_FICTION_COVER,
    MediaAssetSlot.INTERACTIVE_FICTION_BACKGROUND,
)
