package com.openminis.app.novex.domain

import com.openminis.app.data.character.MediaAssetSlot
import org.json.JSONObject

data class NovexRetainedMedia(val sourceAssetId: String, val path: String, val mimeType: String, val sha256: String,
    val sourcePath: String = "")

data class NovexSnapshotMedia(
    val asset: NovexRetainedMedia,
    val slot: MediaAssetSlot,
    val moduleId: String? = null,
    val entryId: String? = null,
    val owner: NovexContentAddress? = null,
    val label: String = "",
    val description: String = "",
    val illustrationRule: String? = null,
    val usageConditions: List<String> = emptyList(),
)

object NovexSnapshotMediaCodec {
    fun encode(media: NovexSnapshotMedia) = JSONObject().put("sourceAssetId", media.asset.sourceAssetId)
        .put("path", media.asset.path).put("mimeType", media.asset.mimeType).put("sha256", media.asset.sha256)
        .put("slot", media.slot.name).put("moduleId", media.moduleId).put("entryId", media.entryId)
        .put("sourcePath", media.asset.sourcePath)
        .put("label", media.label).put("description", media.description).put("illustrationRule", media.illustrationRule).put("usageConditions", org.json.JSONArray(media.usageConditions))
        .put("owner", media.owner?.let { JSONObject().put("kind", it.kind.name).put("id", it.id) })

    fun decode(value: JSONObject) = NovexSnapshotMedia(
        NovexRetainedMedia(value.getString("sourceAssetId"), value.getString("path"), value.getString("mimeType"), value.getString("sha256"), value.optString("sourcePath")),
        MediaAssetSlot.valueOf(value.getString("slot")), value.optString("moduleId").takeIf(String::isNotBlank),
        value.optString("entryId").takeIf(String::isNotBlank),
        value.optJSONObject("owner")?.let { NovexContentAddress(NovexContentKind.valueOf(it.getString("kind")), it.getString("id")) },
        value.optString("label"), value.optString("description"), value.optString("illustrationRule").takeIf(String::isNotBlank),
        value.optJSONArray("usageConditions")?.let { rows -> (0 until rows.length()).map { rows.getString(it) } }.orEmpty(),
    )
}
