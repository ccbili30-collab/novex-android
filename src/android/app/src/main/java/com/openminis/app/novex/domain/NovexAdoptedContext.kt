package com.openminis.app.novex.domain

import org.json.JSONArray
import org.json.JSONObject

/** One adopted root and its scoped dependencies. Its use can end without changing shared originals. */
data class NovexAdoptedContext(
    val root: NovexContentAddress,
    val acting: Boolean,
    val sources: List<NovexFrozenContext>,
    /** Null means the legacy snapshot did not retain its reference graph. */
    val references: List<NovexCardReference>? = null,
) {
    init {
        require(!acting || root.kind == NovexContentKind.CHARACTER_VERSION) { "只有具体角色版本可采用为扮演身份" }
    }
    fun isActive(configuration: NovexConversationConfigurationSnapshot): Boolean = if (acting) {
        (configuration.answerIdentity as? AnswerIdentity.CharacterVersion)?.versionId == root.id
    } else configuration.backgroundSettings.any { it.subject == root }
}

object NovexAdoptedContextCodec {
    fun encode(value: NovexAdoptedContext) = JSONObject()
        .put("kind", value.root.kind.name).put("id", value.root.id).put("acting", value.acting)
        .put("linkedContext", JSONArray(value.sources.map(NovexFrozenContextCodec::encode)))
        .put("references", value.references?.let { JSONArray(it.map { reference -> JSONObject(NovexCardReferenceCodec.encode(reference)) }) })

    fun decode(value: JSONObject) = NovexAdoptedContext(
        NovexContentAddress(NovexContentKind.valueOf(value.getString("kind")), value.getString("id")),
        value.getBoolean("acting"), NovexFrozenContextCodec.read(value.toString()),
        value.optJSONArray("references")?.let { array -> (0 until array.length()).map { NovexCardReferenceCodec.decode(array.getJSONObject(it).toString()) } },
    )
}
