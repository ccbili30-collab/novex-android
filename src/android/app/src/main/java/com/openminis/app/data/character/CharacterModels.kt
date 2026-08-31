package com.openminis.app.data.character

import org.json.JSONObject

data class CharacterCard(
    val id: String,
    val name: String,
    val summary: String = "",
    val personality: String = "",
    val background: String = "",
    val scenario: String = "",
    val greeting: String = "",
    val exampleDialogue: String = "",
    val avatarPath: String? = null,
    val coverPath: String? = null,
    val defaultBackgroundPath: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", "novex-character-card-v1")
        put("id", id)
        put("name", name)
        put("summary", summary)
        put("personality", personality)
        put("background", background)
        put("scenario", scenario)
        put("greeting", greeting)
        put("exampleDialogue", exampleDialogue)
        put("avatarPath", avatarPath)
        put("coverPath", coverPath)
        put("defaultBackgroundPath", defaultBackgroundPath)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): CharacterCard {
            val now = System.currentTimeMillis()
            return CharacterCard(
                id = json.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                name = json.optString("name").trim(),
                summary = json.optString("summary"),
                personality = json.optString("personality"),
                background = json.optString("background"),
                scenario = json.optString("scenario"),
                greeting = json.optString("greeting"),
                exampleDialogue = json.optString("exampleDialogue"),
                avatarPath = json.optNullableString("avatarPath"),
                coverPath = json.optNullableString("coverPath"),
                defaultBackgroundPath = json.optNullableString("defaultBackgroundPath"),
                createdAt = json.optLong("createdAt", now),
                updatedAt = json.optLong("updatedAt", now),
            )
        }
    }
}

data class PlayerPersona(
    val id: String,
    val name: String,
    val description: String = "",
    val relationship: String = "",
    val preferredAddress: String = "",
    val avatarPath: String? = null,
    val isDefault: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", "novex-player-persona-v1")
        put("id", id)
        put("name", name)
        put("description", description)
        put("relationship", relationship)
        put("preferredAddress", preferredAddress)
        put("avatarPath", avatarPath)
        put("isDefault", isDefault)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): PlayerPersona {
            val now = System.currentTimeMillis()
            return PlayerPersona(
                id = json.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                name = json.optString("name").trim(),
                description = json.optString("description"),
                relationship = json.optString("relationship"),
                preferredAddress = json.optString("preferredAddress"),
                avatarPath = json.optNullableString("avatarPath"),
                isDefault = json.optBoolean("isDefault", false),
                createdAt = json.optLong("createdAt", now),
                updatedAt = json.optLong("updatedAt", now),
            )
        }
    }
}

data class ImmersiveChatProfile(
    val character: CharacterCard? = null,
    val persona: PlayerPersona? = null,
    val backgroundPath: String? = null,
)

private fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key)) null else optString(key).trim().ifBlank { null }

