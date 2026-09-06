package com.openminis.app.novex.domain

import org.json.JSONObject

/** One identity representation shared by configuration and historical context records. */
internal object NovexAnswerIdentityCodec {
    fun encode(identity: AnswerIdentity): JSONObject = when (identity) {
        AnswerIdentity.Nova -> JSONObject().put("kind", "nova")
        is AnswerIdentity.CharacterVersion -> JSONObject()
            .put("kind", "characterVersion")
            .put("versionId", identity.versionId)
        is AnswerIdentity.PersonaPreset -> JSONObject()
            .put("kind", "personaPreset")
            .put("presetId", identity.presetId)
            .put("label", identity.label)
            .put("instructions", identity.instructions)
    }

    fun decode(value: JSONObject?): AnswerIdentity = when (value?.optString("kind")) {
        "characterVersion" -> AnswerIdentity.CharacterVersion(value.getString("versionId"))
        "personaPreset" -> AnswerIdentity.PersonaPreset(
            presetId = value.getString("presetId"),
            label = value.getString("label"),
            instructions = value.optString("instructions"),
        )
        else -> AnswerIdentity.Nova
    }
}
