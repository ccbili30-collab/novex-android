package com.openminis.app.novex.domain

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Scoped source text adopted by a playthrough; never falls back to a mutable original. */
data class NovexFrozenContext(
    val target: NovexReferenceTarget,
    val candidates: List<NovexContextCandidate>,
    val actorVersionId: String? = null,
)

object NovexFrozenContextCodec {
    fun encode(value: NovexFrozenContext): JSONObject = JSONObject().apply {
        put("kind", value.target.subject.kind.name)
        put("id", value.target.subject.id)
        put("moduleId", value.target.moduleId)
        put("entryId", value.target.entryId)
        put("actorVersionId", value.actorVersionId)
        val sources = JSONArray().apply { value.candidates.forEach { candidate -> put(JSONObject().apply {
            put("sourceId", candidate.sourceId); put("label", candidate.label); put("content", candidate.content)
            put("kind", candidate.kind.name); put("aliases", JSONArray(candidate.aliases.toList()))
            put("relatedSourceIds", JSONArray(candidate.relatedSourceIds.toList()))
            put("alwaysInclude", candidate.alwaysInclude); put("position", candidate.position)
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
                        value.optBoolean("alwaysInclude"), value.optInt("position"))
                },
                actorVersionId = row.optionalText("actorVersionId"),
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
