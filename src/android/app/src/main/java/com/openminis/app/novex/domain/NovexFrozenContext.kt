package com.openminis.app.novex.domain

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Scoped source text adopted by a playthrough; never falls back to a mutable original. */
data class NovexFrozenContext(
    val target: NovexReferenceTarget,
    val candidates: List<NovexContextCandidate>,
    val actorVersionId: String? = null,
    val adoptedByGame: Boolean = true,
    val conversationRoots: Set<NovexContentAddress> = emptySet(),
    val media: List<NovexSnapshotMedia> = emptyList(),
    val mediaCaptured: Boolean = false,
    val tavernWorldbookJson: String? = null,
)

object NovexFrozenContextCodec {
    fun encode(value: NovexFrozenContext): JSONObject = JSONObject().apply {
        put("kind", value.target.subject.kind.name)
        put("id", value.target.subject.id)
        put("moduleId", value.target.moduleId)
        put("entryId", value.target.entryId)
        put("actorVersionId", value.actorVersionId)
        put("adoptedByGame", value.adoptedByGame)
        put("conversationRoots", JSONArray(value.conversationRoots.map { JSONObject().put("kind", it.kind.name).put("id", it.id) }))
        put("media", JSONArray(value.media.map(NovexSnapshotMediaCodec::encode)))
        put("mediaCaptured", value.mediaCaptured)
        put("tavernWorldbook", value.tavernWorldbookJson)
        val sources = JSONArray().apply { value.candidates.forEach { candidate -> put(JSONObject().apply {
            put("sourceId", candidate.sourceId); put("label", candidate.label); put("content", candidate.content)
            put("kind", candidate.kind.name); put("aliases", JSONArray(candidate.aliases.toList()))
            put("relatedSourceIds", JSONArray(candidate.relatedSourceIds.toList()))
            put("alwaysInclude", candidate.alwaysInclude); put("position", candidate.position)
            put("worldbookConditions", JSONArray(candidate.worldbookConditions))
        }) } }
        put("sources", sources)
        put("revision", digest(sources.toString()))
    }

    fun read(raw: String): List<NovexFrozenContext> {
        val array = JSONObject(raw).optJSONArray("linkedContext") ?: return emptyList()
        return (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            val sources = row.getJSONArray("sources")
            NovexFrozenContext(
                target = NovexReferenceTarget(NovexContentAddress(NovexContentKind.valueOf(row.getString("kind")), row.getString("id")),
                    row.optionalText("moduleId"), row.optionalText("entryId")),
                candidates = (0 until sources.length()).map { sourceIndex ->
                    val value = sources.getJSONObject(sourceIndex)
                    NovexContextCandidate(value.getString("sourceId"), value.getString("label"), value.getString("content"),
                        ContextSourceKind.valueOf(value.getString("kind")), value.strings("aliases"), value.strings("relatedSourceIds"),
                        value.optBoolean("alwaysInclude"), value.optInt("position"),
                        value.optJSONArray("worldbookConditions")?.let { conditions -> (0 until conditions.length()).map { conditions.getString(it) } }.orEmpty())
                },
                actorVersionId = row.optionalText("actorVersionId"),
                adoptedByGame = row.optBoolean("adoptedByGame", true),
                conversationRoots = row.optJSONArray("conversationRoots")?.let { roots ->
                    (0 until roots.length()).mapTo(linkedSetOf()) { index -> roots.getJSONObject(index).let {
                        NovexContentAddress(NovexContentKind.valueOf(it.getString("kind")), it.getString("id"))
                    } }
                }.orEmpty(),
                media = row.optJSONArray("media")?.let { images ->
                    (0 until images.length()).map { NovexSnapshotMediaCodec.decode(images.getJSONObject(it)) }
                }.orEmpty(),
                mediaCaptured = row.optBoolean("mediaCaptured", false),
                tavernWorldbookJson = row.optionalText("tavernWorldbook"),
            )
        }
    }

    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun JSONObject.optionalText(key: String) = optString(key).takeIf(String::isNotBlank)
    private fun JSONObject.strings(key: String): Set<String> = optJSONArray(key)?.let { array ->
        (0 until array.length()).mapTo(linkedSetOf()) { array.getString(it) }
    }.orEmpty()
}
