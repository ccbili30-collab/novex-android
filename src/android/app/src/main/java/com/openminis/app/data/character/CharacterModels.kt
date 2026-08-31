package com.openminis.app.data.character

import org.json.JSONObject

data class StoryWorld(
    val id: String = "default-world",
    val name: String = "我的世界",
    val description: String = "",
    val backgroundPath: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", "novex-story-world-v1")
        put("id", id)
        put("name", name)
        put("description", description)
        put("backgroundPath", backgroundPath)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): StoryWorld {
            val now = System.currentTimeMillis()
            return StoryWorld(
                id = json.optString("id").ifBlank { "default-world" },
                name = json.optString("name").trim().ifBlank { "我的世界" },
                description = json.optString("description"),
                backgroundPath = json.optNullableString("backgroundPath"),
                createdAt = json.optLong("createdAt", now),
                updatedAt = json.optLong("updatedAt", now),
            )
        }
    }
}

data class CharacterCard(
    val id: String,
    val name: String,
    val worldId: String = "default-world",
    val summary: String = "",
    val personality: String = "",
    val background: String = "",
    val scenario: String = "",
    val greeting: String = "",
    val exampleDialogue: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val creatorNotes: String = "",
    val tags: List<String> = emptyList(),
    val knowledge: String = "",
    /** Empty means strict role chat without structured tools. */
    val allowedTools: List<String> = emptyList(),
    val contentBoundary: String = "",
    val sourceFormat: String? = null,
    val avatarPath: String? = null,
    val coverPath: String? = null,
    val defaultBackgroundPath: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", "novex-character-card-v1")
        put("id", id)
        put("worldId", worldId)
        put("name", name)
        put("summary", summary)
        put("personality", personality)
        put("background", background)
        put("scenario", scenario)
        put("greeting", greeting)
        put("exampleDialogue", exampleDialogue)
        put("systemPrompt", systemPrompt)
        put("postHistoryInstructions", postHistoryInstructions)
        put("alternateGreetings", org.json.JSONArray(alternateGreetings))
        put("creatorNotes", creatorNotes)
        put("tags", org.json.JSONArray(tags))
        put("knowledge", knowledge)
        put("allowedTools", org.json.JSONArray(allowedTools))
        put("contentBoundary", contentBoundary)
        put("sourceFormat", sourceFormat)
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
                worldId = json.optString("worldId").ifBlank { "default-world" },
                name = json.optString("name").trim(),
                summary = json.optString("summary"),
                personality = json.optString("personality"),
                background = json.optString("background"),
                scenario = json.optString("scenario"),
                greeting = json.optString("greeting"),
                exampleDialogue = json.optString("exampleDialogue"),
                systemPrompt = json.optString("systemPrompt"),
                postHistoryInstructions = json.optString("postHistoryInstructions"),
                alternateGreetings = json.optStringList("alternateGreetings"),
                creatorNotes = json.optString("creatorNotes"),
                tags = json.optStringList("tags"),
                knowledge = json.optString("knowledge"),
                allowedTools = json.optStringList("allowedTools")
                    .filter { it in setOf("present_choices", "generate_image") },
                contentBoundary = json.optString("contentBoundary"),
                sourceFormat = json.optNullableString("sourceFormat"),
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
    val worldId: String = "default-world",
    val description: String = "",
    val appearance: String = "",
    val abilities: String = "",
    val personality: String = "",
    val relationship: String = "",
    val preferredAddress: String = "",
    val boundaries: String = "",
    val avatarPath: String? = null,
    val isDefault: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", "novex-player-persona-v1")
        put("id", id)
        put("worldId", worldId)
        put("name", name)
        put("description", description)
        put("appearance", appearance)
        put("abilities", abilities)
        put("personality", personality)
        put("relationship", relationship)
        put("preferredAddress", preferredAddress)
        put("boundaries", boundaries)
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
                worldId = json.optString("worldId").ifBlank { "default-world" },
                name = json.optString("name").trim(),
                description = json.optString("description"),
                appearance = json.optString("appearance"),
                abilities = json.optString("abilities"),
                personality = json.optString("personality"),
                relationship = json.optString("relationship"),
                preferredAddress = json.optString("preferredAddress"),
                boundaries = json.optString("boundaries"),
                avatarPath = json.optNullableString("avatarPath"),
                isDefault = json.optBoolean("isDefault", false),
                createdAt = json.optLong("createdAt", now),
                updatedAt = json.optLong("updatedAt", now),
            )
        }
    }
}

data class ImmersiveChatProfile(
    val world: StoryWorld? = null,
    val character: CharacterCard? = null,
    val persona: PlayerPersona? = null,
    val backgroundPath: String? = null,
)

private fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key)) null else optString(key).trim().ifBlank { null }

private fun JSONObject.optStringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            array.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
        }
    }
}
