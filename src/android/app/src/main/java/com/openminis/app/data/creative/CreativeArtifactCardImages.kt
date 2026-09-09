package com.openminis.app.data.creative

import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.ModuleOwnerType
import com.openminis.app.novex.domain.*
import java.io.File
import org.json.JSONObject

/** Bridges old generated-image attachments into the card's single ownership/write path. */
internal class CreativeArtifactCardImages(private val workspace: NovexWorkspace) {
    private fun origin(id: String) = "artifact:$id"
    suspend fun attach(attachment: CreativeArtifactAttachment, record: CreativeArtifactRecord, file: File, onlyMissing: Boolean = false) {
        val moduleId = attachment.moduleId ?: return
        if (record.artifact.kind !in setOf(CreativeArtifactKind.IMAGE, CreativeArtifactKind.MAP)) return
        val detail = requireNotNull(workspace.module(moduleId)) { "图片所属模块不存在" }
        val owner = when (detail.module.ownerType) {
            ModuleOwnerType.WORLD -> NovexContentAddress.world(detail.module.ownerId)
            ModuleOwnerType.CHARACTER_VERSION -> NovexContentAddress.characterVersion(detail.module.ownerId)
            ModuleOwnerType.INTERACTIVE_FICTION -> NovexContentAddress.interactiveFiction(detail.module.ownerId)
            ModuleOwnerType.CONTENT_MODULE -> error("图片缺少所属卡片")
        }
        require(owner == attachment.owner) { "图片模块与所选卡片不一致" }
        if (onlyMissing && (detail.image != null || NovexModuleImageOrigins.ownsMainImage(detail.module.contentJson))) return
        val mime = record.revisions.maxByOrNull { it.number }?.mimeType.orEmpty()
        require(mime.startsWith("image/") && file.isFile && file.length() in 1..32L * 1024 * 1024) { "图片格式或大小不适合存入卡片" }
        workspace.apply(NovexCommand.AttachImage(ModuleOwner.contentModule(moduleId), MediaAssetSlot.MODULE_IMAGE,
            file.readBytes(), mime, source = origin(attachment.artifactId)))
    }
}
