package com.openminis.app.data.character

import org.json.JSONArray
import org.json.JSONObject

data class CharacterCustomAttribute(
    val name: String,
    val value: String,
)

data class CharacterRelationship(
    val characterName: String,
    val relationship: String,
    val description: String,
)

/** Editable fixed fields for one body/avatar while preserving imported provider-specific data. */
data class CharacterVersionProfile(
    val name: String,
    val tags: List<String> = emptyList(),
    val gender: String = "",
    val age: String = "",
    val race: String = "",
    val occupation: String = "",
    val summary: String = "",
    val customAttributes: List<CharacterCustomAttribute> = emptyList(),
    val relationships: List<CharacterRelationship> = emptyList(),
    private val preservedJson: String = "{}",
) {
    fun toJson(): String = JSONObject(preservedJson).apply {
        put("profileSchema", SCHEMA)
        put("name", name.trim())
        put("tags", JSONArray(tags.map(String::trim).filter(String::isNotEmpty)))
        put("gender", gender.trim())
        put("age", age.trim())
        put("race", race.trim())
        put("occupation", occupation.trim())
        put("summary", summary.trim())
        put("customAttributes", JSONArray().apply {
            customAttributes.forEach { field ->
                put(JSONObject().put("name", field.name.trim()).put("value", field.value.trim()))
            }
        })
        put("relationships", JSONArray().apply {
            relationships.forEach { relation ->
                put(
                    JSONObject()
                        .put("characterName", relation.characterName.trim())
                        .put("relationship", relation.relationship.trim())
                        .put("description", relation.description.trim()),
                )
            }
        })
    }.toString()

    companion object {
        const val SCHEMA = "novex-character-version-profile-v1"

        fun fromJson(raw: String, fallbackName: String = ""): CharacterVersionProfile {
            val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            return CharacterVersionProfile(
                name = json.optString("name").trim().ifBlank { fallbackName },
                tags = json.optJSONArray("tags").stringList(),
                gender = json.optString("gender"),
                age = json.optString("age"),
                race = json.optString("race"),
                occupation = json.optString("occupation"),
                summary = json.optString("summary"),
                customAttributes = json.optJSONArray("customAttributes").objects { item ->
                    CharacterCustomAttribute(item.optString("name"), item.optString("value"))
                },
                relationships = json.optJSONArray("relationships").objects { item ->
                    CharacterRelationship(
                        characterName = item.optString("characterName"),
                        relationship = item.optString("relationship"),
                        description = item.optString("description"),
                    )
                },
                preservedJson = json.toString(),
            )
        }
    }
}

data class CharacterModuleDocument(
    val type: ContentModuleType,
    val name: String,
    val contentJson: String,
    val collapsed: Boolean = true,
)

data class CharacterVersionDocument(
    val kind: CharacterVersionKind,
    val label: String,
    val profileJson: String,
    val modules: List<CharacterModuleDocument> = emptyList(),
)

data class CharacterLibraryDocument(
    val name: String,
    val versions: List<CharacterVersionDocument>,
) {
    init {
        require(name.isNotBlank()) { "角色名称不能为空" }
        require(versions.count { it.kind == CharacterVersionKind.ORIGINAL } == 1) {
            "角色数据必须包含且只能包含一个本体"
        }
    }
}

/** Novex structured transfer format for a root character and all reusable versions. */
object CharacterLibraryDocumentCodec {
    private const val SCHEMA = "novex-character-library-v1"

    fun encode(document: CharacterLibraryDocument): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("name", document.name)
        put("versions", JSONArray().apply {
            document.versions.forEach { version ->
                put(JSONObject().apply {
                    put("kind", version.kind.name)
                    put("label", version.label)
                    put("profile", JSONObject(version.profileJson))
                    put("modules", JSONArray().apply {
                        version.modules.forEach { module ->
                            put(JSONObject().apply {
                                put("type", module.type.name)
                                put("name", module.name)
                                put("content", JSONObject(module.contentJson))
                                put("collapsed", module.collapsed)
                            })
                        }
                    })
                })
            }
        })
    }

    fun decode(raw: String): CharacterLibraryDocument {
        val root = JSONObject(raw)
        require(root.optString("schema") == SCHEMA) { "不是 Novex 角色库结构化数据" }
        val versions = root.optJSONArray("versions")?.objects { item ->
            CharacterVersionDocument(
                kind = CharacterVersionKind.valueOf(item.getString("kind")),
                label = item.optString("label"),
                profileJson = item.optJSONObject("profile")?.toString() ?: "{}",
                modules = item.optJSONArray("modules").objects { module ->
                    CharacterModuleDocument(
                        type = ContentModuleType.valueOf(module.getString("type")),
                        name = module.optString("name"),
                        contentJson = module.optJSONObject("content")?.toString() ?: "{}",
                        collapsed = module.optBoolean("collapsed", true),
                    )
                },
            )
        }.orEmpty()
        return CharacterLibraryDocument(root.optString("name").trim(), versions)
    }

    fun fromTavernCard(card: CharacterCard): CharacterLibraryDocument {
        val profile = CharacterVersionProfile.fromJson(card.toJson().toString(), card.name)
        val modules = buildList {
            val quotes = buildList {
                card.greeting.takeIf(String::isNotBlank)?.let { add("开场白\n$it") }
                if (card.alternateGreetings.isNotEmpty()) {
                    add("备用开场白\n${card.alternateGreetings.joinToString("\n---\n")}")
                }
                card.exampleDialogue.takeIf(String::isNotBlank)?.let { add("示例对话\n$it") }
            }.joinToString("\n\n")
            if (quotes.isNotBlank()) add(textModule(ContentModuleType.QUOTES, "多形态语录", quotes))
            val experience = listOf(card.background, card.scenario, card.knowledge)
                .filter(String::isNotBlank)
                .joinToString("\n\n")
            if (experience.isNotBlank()) {
                add(textModule(ContentModuleType.WORLD_EXPERIENCE, "世界经历", experience))
            }
            if (card.personality.isNotBlank()) {
                add(textModule(ContentModuleType.APPEARANCE_PERSONALITY, "外貌性格", card.personality))
            }
        }
        return CharacterLibraryDocument(
            name = card.name,
            versions = listOf(
                CharacterVersionDocument(
                    kind = CharacterVersionKind.ORIGINAL,
                    label = "本体",
                    profileJson = profile.toJson(),
                    modules = modules,
                ),
            ),
        )
    }

    private fun textModule(type: ContentModuleType, name: String, text: String) =
        CharacterModuleDocument(type, name, JSONObject().put("text", text).toString())
}

private fun JSONArray?.stringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
    }
}

private fun <T> JSONArray?.objects(mapper: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) optJSONObject(index)?.let { add(mapper(it)) }
    }
}
