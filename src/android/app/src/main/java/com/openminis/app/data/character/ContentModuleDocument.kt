package com.openminis.app.data.character

import org.json.JSONArray
import org.json.JSONObject

sealed interface ContentModuleDocument {
    data class Article(val text: String = "") : ContentModuleDocument

    data class SingleImage(val description: String = "") : ContentModuleDocument

    data class Timeline(
        val nodes: List<ContentModuleTimelineNode> = emptyList(),
    ) : ContentModuleDocument

    data class Collection(
        val items: List<ContentModuleCollectionItem> = emptyList(),
    ) : ContentModuleDocument

    /** Raw exchange payload retained when this app version cannot interpret a module. */
    data class Unsupported(
        val originalType: String,
        val presentation: String? = null,
        val contentJson: String,
    ) : ContentModuleDocument
}

data class ContentModuleTimelineNode(
    val time: String = "",
    val title: String = "",
    val description: String = "",
)

data class ContentModuleCollectionItem(
    val id: String = "",
    val name: String = "",
    val summary: String = "",
    val description: String = "",
    /** Opaque stable key resolved by the page's visual adapter. It is never a raw file path. */
    val visualKey: String? = null,
    /** Unknown type-specific fields survive internal edits and native package re-export. */
    val preservedJson: String = "{}",
)

/**
 * Versioned content document seam shared by world and character modules.
 *
 * Room stores the encoded JSON unchanged. Decoding also accepts the previous
 * `{ "text": ... }` payload and malformed legacy plain text, so this schema
 * can ship independently from a database migration.
 */
object ContentModuleDocumentCodec {
    private const val CURRENT_VERSION = 1

    fun encode(document: ContentModuleDocument): String = JSONObject().apply {
        put("version", CURRENT_VERSION)
        when (document) {
            is ContentModuleDocument.Article -> {
                put("kind", "article")
                put("text", document.text)
            }

            is ContentModuleDocument.SingleImage -> {
                put("kind", "single_image")
                put("description", document.description)
            }

            is ContentModuleDocument.Timeline -> {
                put("kind", "timeline")
                put("nodes", JSONArray().apply {
                    document.nodes.forEach { node ->
                        put(JSONObject().apply {
                            put("time", node.time)
                            put("title", node.title)
                            put("description", node.description)
                        })
                    }
                })
            }

            is ContentModuleDocument.Collection -> {
                put("kind", "collection")
                put("items", JSONArray().apply {
                    document.items.forEach { item ->
                        put(runCatching { JSONObject(item.preservedJson) }.getOrDefault(JSONObject()).apply {
                            put("id", item.id)
                            put("name", item.name)
                            put("summary", item.summary)
                            put("description", item.description)
                            item.visualKey?.let { put("visualKey", it) }
                                ?: remove("visualKey")
                        })
                    }
                })
            }

            is ContentModuleDocument.Unsupported -> {
                put("kind", "unsupported")
                put("originalType", document.originalType)
                document.presentation?.let { put("presentation", it) }
                // Keep the opaque payload as text. Parsing and serializing it here would
                // reorder object keys and make a byte-stable re-export impossible.
                put("contentJson", document.contentJson)
            }
        }
    }.toString()

    fun decode(contentJson: String): ContentModuleDocument =
        decodeRich(contentJson) ?: ContentModuleDocument.Article(legacyText(contentJson))

    fun decode(type: ContentModuleType, contentJson: String): ContentModuleDocument =
        decodeRich(contentJson, type) ?: legacyDocument(type, legacyText(contentJson))

    private fun decodeRich(
        contentJson: String,
        fallbackType: ContentModuleType? = null,
    ): ContentModuleDocument? = runCatching {
        val root = JSONObject(contentJson)
        val kind = root.optString("kind")
        when (kind) {
            "article" -> ContentModuleDocument.Article(root.optString("text"))
            "single_image" -> ContentModuleDocument.SingleImage(root.optString("description"))
            "timeline" -> ContentModuleDocument.Timeline(
                root.optJSONArray("nodes").objects().map { node ->
                    ContentModuleTimelineNode(
                        time = node.optString("time"),
                        title = node.optString("title"),
                        description = node.optString("description"),
                    )
                },
            )

            "collection" -> ContentModuleDocument.Collection(
                root.optJSONArray("items").objects().map { item ->
                    val preserved = JSONObject(item.toString()).apply {
                        remove("id")
                        remove("name")
                        remove("summary")
                        remove("description")
                        remove("visualKey")
                    }
                    ContentModuleCollectionItem(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        summary = item.optString("summary"),
                        description = item.optString("description"),
                        visualKey = item.optString("visualKey").takeIf(String::isNotBlank),
                        preservedJson = preserved.toString(),
                    )
                },
            )

            "unsupported" -> ContentModuleDocument.Unsupported(
                originalType = root.optString("originalType"),
                presentation = root.optString("presentation").takeIf(String::isNotBlank),
                contentJson = root.optString("contentJson", "{}"),
            )

            "" -> null
            else -> ContentModuleDocument.Unsupported(
                originalType = fallbackType?.name?.lowercase()
                    ?: root.optString("originalType", "unknown"),
                presentation = kind,
                contentJson = contentJson,
            )
        }
    }.getOrNull()

    private fun legacyDocument(type: ContentModuleType, text: String): ContentModuleDocument = when (type) {
        ContentModuleType.MAP -> ContentModuleDocument.SingleImage(text)
        ContentModuleType.TIMELINE,
        ContentModuleType.ERA_EVENT,
        ContentModuleType.WORLD_EXPERIENCE,
        -> ContentModuleDocument.Timeline(
            text.takeIf(String::isNotBlank)
                ?.let { listOf(ContentModuleTimelineNode(description = it)) }
                .orEmpty(),
        )

        ContentModuleType.REGION,
        ContentModuleType.FACTION,
        ContentModuleType.RACE,
        ContentModuleType.QUOTES,
        ContentModuleType.ATTRIBUTE_PANEL,
        ContentModuleType.EQUIPMENT,
        ContentModuleType.TALENT_SKILL,
        ContentModuleType.APPEARANCE_PERSONALITY,
        ContentModuleType.INTEREST,
        ContentModuleType.GAME_ATTRIBUTES,
        ContentModuleType.GAME_SKILLS,
        ContentModuleType.GAME_EQUIPMENT,
        ContentModuleType.GAME_ITEMS,
        ContentModuleType.GAME_QUESTS,
        ContentModuleType.GAME_CHECKS,
        ContentModuleType.GAME_ENDINGS,
        ContentModuleType.GAME_CHARACTER_STATUS,
        ContentModuleType.GAME_QUICK_ACTIONS,
        -> ContentModuleDocument.Collection(
            text.takeIf(String::isNotBlank)
                ?.let { listOf(ContentModuleCollectionItem(summary = it)) }
                .orEmpty(),
        )

        ContentModuleType.GAME_PLAYER_IDENTITY,
        ContentModuleType.GAME_OPENING,
        ContentModuleType.GAME_NARRATIVE_RULES,
        ContentModuleType.GAME_POWER_SYSTEM,
        ContentModuleType.CUSTOM,
        -> ContentModuleDocument.Article(text)
    }

    private fun legacyText(contentJson: String): String = runCatching {
        JSONObject(contentJson).optString("text")
    }.getOrElse { contentJson }

    private fun JSONArray?.objects(): List<JSONObject> = buildList {
        val array = this@objects ?: return@buildList
        repeat(array.length()) { index ->
            array.optJSONObject(index)?.let(::add)
        }
    }
}

fun ContentModuleDocument.toPlainText(): String = when (this) {
    is ContentModuleDocument.Article -> text
    is ContentModuleDocument.SingleImage -> description
    is ContentModuleDocument.Timeline -> nodes.joinToString("\n") { node ->
        val heading = listOf(node.time, node.title).filter(String::isNotBlank).joinToString(" · ")
        listOf(heading, node.description).filter(String::isNotBlank).joinToString("\n")
    }

    is ContentModuleDocument.Collection -> items.joinToString("\n") { item ->
        when {
            item.name.isNotBlank() && item.summary.isNotBlank() -> "${item.name}：${item.summary}"
            item.name.isNotBlank() -> item.name
            else -> item.summary
        }
    }
    is ContentModuleDocument.Unsupported -> "$originalType 暂不支持，原始内容已保留"
}
