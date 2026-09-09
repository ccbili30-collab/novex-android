package com.openminis.app.novex.adapter

import com.openminis.app.data.character.MediaAssetEntity
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.novex.domain.*

internal class NovexSnapshotMediaCapture(private val workspace: NovexWorkspace, private val store: NovexSnapshotMediaStore?) {
    suspend fun capture(source: NovexFrozenContext): NovexFrozenContext = source.copy(
        media = capture(source.target, source.candidates.mapTo(mutableSetOf()) { it.sourceId }),
        mediaCaptured = true,
    )

    suspend fun capture(target: NovexReferenceTarget, permittedSourceIds: Set<String>): List<NovexSnapshotMedia> {
        var rootMedia = emptyMap<MediaAssetSlot, MediaAssetEntity>()
        var moduleImages = emptyMap<String, MediaAssetEntity>()
        var itemImages = emptyMap<String, Map<String, MediaAssetEntity>>()
        when (target.subject.kind) {
            NovexContentKind.WORLD -> workspace.world(target.subject.id)?.let {
                rootMedia = it.media; moduleImages = it.moduleImages; itemImages = it.moduleItemImages
            }
            NovexContentKind.CHARACTER_VERSION -> workspace.characterForVersion(target.subject.id)?.let {
                rootMedia = it.mediaByVersion[target.subject.id].orEmpty()
                moduleImages = it.moduleImages; itemImages = it.moduleItemImages
            }
            NovexContentKind.INTERACTIVE_FICTION -> workspace.interactiveFiction(target.subject.id)?.let {
                rootMedia = it.media; moduleImages = it.moduleImages; itemImages = it.moduleItemImages
            }
            NovexContentKind.CREATIVE_ARTIFACT -> Unit
        }
        val result = mutableListOf<NovexSnapshotMedia>()
        suspend fun retain(asset: MediaAssetEntity, slot: MediaAssetSlot, moduleId: String? = null, entryId: String? = null) {
            val retained = requireNotNull(store) { "尚未配置采用图片的独立保存目录" }.retain(asset)
            val module = moduleId?.let { workspace.module(it)?.module }
            val item = module?.let { com.openminis.app.data.character.ContentModuleDocumentCodec.decode(it.type, it.contentJson)
                as? com.openminis.app.data.character.ContentModuleDocument.Collection }?.items?.singleOrNull { it.id == entryId }
            result += NovexSnapshotMedia(retained, slot, moduleId, entryId, target.subject,
                label = item?.name ?: module?.name.orEmpty(), description = item?.summary ?: "",
                illustrationRule = module?.let { NovexStoryIllustrations.read(it.contentJson, entryId?.let { id -> "entry:$id" } ?: "main") },
                usageConditions = listOfNotNull(module?.let { NovexWorldbookConditions.read(it.contentJson) }, item?.contextTriggerJson))
        }
        if (target.moduleId == null) rootMedia.forEach { (slot, asset) -> retain(asset, slot) }
        if (target.entryId == null) moduleImages.filterKeys { id ->
            id in permittedSourceIds && (target.moduleId == null || target.moduleId == id)
        }.forEach { (id, asset) -> retain(asset, MediaAssetSlot.MODULE_IMAGE, id) }
        itemImages.forEach { (moduleId, images) ->
            if (target.moduleId == null || target.moduleId == moduleId) images.forEach { (entryId, asset) ->
                val allowed = (target.entryId == null || target.entryId == entryId) &&
                    (moduleId in permittedSourceIds || "$moduleId:entry:$entryId" in permittedSourceIds)
                if (allowed) retain(asset, MediaAssetSlot.MODULE_IMAGE, moduleId, entryId)
            }
        }
        return result
    }
}
