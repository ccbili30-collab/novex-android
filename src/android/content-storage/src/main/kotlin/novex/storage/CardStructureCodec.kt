package novex.storage

import novex.content.*
import org.json.JSONArray
import org.json.JSONObject

/** 共用结构编解码；正文仍由内容引用定位，不嵌入结构。 */
internal object CardStructureCodec {
    fun encode(card: ContentDocument): JSONObject = JSONObject()
        .put("id", card.id).put("kind", card.kind.name).put("name", card.name)
        .put("appearance", JSONObject().put("avatar", card.appearance.avatarResourceId ?: JSONObject.NULL)
            .put("cover", card.appearance.coverResourceId ?: JSONObject.NULL).put("readingLayout",card.appearance.readingLayout.name))
        .put("extensions", JSONObject(card.extensions.mapValues { it.value.value }))
        .put("resources", JSONArray(card.resources.map {
            JSONObject().put("id", it.id).put("content", it.content.value).put("mediaType", it.mediaType)
        }))
        .put("modules", JSONArray(card.modules.map(::encodeModule)))
        .put("characters", JSONArray(card.internalCharacters.map(::encode)))

    private fun encodeModule(module: ContentModule): JSONObject = JSONObject()
        .put("id", module.id).put("name", module.name)
        .put("characters", JSONArray(module.characterIds)).put("layout", module.layout.name).put("children", JSONArray(module.children.map(::encodeModule)))
        .put("blocks", JSONArray(module.blocks.map {
            when (it) {
                is ContentBlock.Text -> JSONObject().put("kind", "text").put("id", it.id).put("content", it.content.value)
                is ContentBlock.Image -> JSONObject().put("kind", "image").put("id", it.id).put("resource", it.resourceId).put("caption", it.caption?.value ?: JSONObject.NULL)
            }
        })).also {
            if (module.tags.isNotEmpty()) it.put("tags", JSONArray(module.tags))
            module.use?.let { rule -> it.put("use", encodeUse(rule)) }
        }

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map(array::getString)
    private fun encodeUse(rule: ModuleUse): JSONObject = when (rule) {
        ModuleUse.Always -> JSONObject().put("kind", "always")
        ModuleUse.Manual -> JSONObject().put("kind", "manual")
        is ModuleUse.Keywords -> JSONObject().put("kind", "keywords").put("words", JSONArray(rule.words))
            .put("caseSensitive", rule.caseSensitive).put("requireAll", rule.requireAll)
    }
    private fun decodeUse(value: JSONObject): ModuleUse = when (value.getString("kind")) {
        "always" -> { fields(value, setOf("kind")); ModuleUse.Always }
        "manual" -> { fields(value, setOf("kind")); ModuleUse.Manual }
        "keywords" -> {
            fields(value, setOf("kind", "words", "caseSensitive", "requireAll"))
            ModuleUse.Keywords(strings(value.getJSONArray("words")), value.getBoolean("caseSensitive"), value.getBoolean("requireAll"))
        }
        else -> error("未支持的携带规则，不能静默丢弃")
    }

    private fun fields(value: JSONObject, expected: Set<String>) {
        require(value.keys().asSequence().toSet() == expected) { "实验结构字段不完整或包含未支持字段，不能静默丢弃" }
    }
    private fun objects(array: JSONArray): List<JSONObject> = (0 until array.length()).map(array::getJSONObject)
    private fun nullable(value: JSONObject, name: String): String? = if (value.isNull(name)) null else value.getString(name)
    fun decode(card: JSONObject): ContentDocument {
        fields(card, setOf("id", "kind", "name", "appearance", "extensions", "resources", "modules", "characters"))
        val appearance = card.getJSONObject("appearance"); fields(appearance, setOf("avatar", "cover")+if(appearance.has("readingLayout"))setOf("readingLayout") else emptySet())
        val extensions = card.getJSONObject("extensions")
        return ContentDocument(
            id = card.getString("id"), kind = CardKind.valueOf(card.getString("kind")), name = card.getString("name"),
            appearance = CardAppearance(nullable(appearance, "avatar"), nullable(appearance, "cover"),if(appearance.has("readingLayout"))ReadingLayout.valueOf(appearance.getString("readingLayout")) else ReadingLayout.CONTINUOUS),
            extensions = extensions.keys().asSequence().toSet().associateWith { ContentRef(extensions.getString(it)) },
            resources = objects(card.getJSONArray("resources")).map {
                fields(it, setOf("id", "content", "mediaType"))
                CardResource(it.getString("id"), ContentRef(it.getString("content")), it.getString("mediaType"))
            },
            modules = objects(card.getJSONArray("modules")).map(::decodeModule),
            internalCharacters = objects(card.getJSONArray("characters")).map(::decode),
        )
    }

    private fun decodeModule(module: JSONObject): ContentModule {
                fields(module, setOf("id", "name", "blocks") + setOf("tags", "use", "children", "layout", "characters").filter { module.has(it) })
                return ContentModule(module.getString("id"), module.getString("name"), objects(module.getJSONArray("blocks")).map {
                    when (it.getString("kind")) {
                        "text" -> { fields(it, setOf("kind", "id", "content")); ContentBlock.Text(it.getString("id"), ContentRef(it.getString("content"))) }
                        "image" -> { fields(it, setOf("kind", "id", "resource", "caption")); ContentBlock.Image(it.getString("id"), it.getString("resource"), nullable(it, "caption")?.let(::ContentRef)) }
                        else -> error("未支持的内容块类型，不能静默丢弃")
                    }
                }, tags = if (module.has("tags")) strings(module.getJSONArray("tags")) else emptyList(), use = if (module.has("use")) decodeUse(module.getJSONObject("use")) else null,
                    children = if (module.has("children")) objects(module.getJSONArray("children")).map(::decodeModule) else emptyList(),
                    layout = if (module.has("layout")) ModuleLayout.valueOf(module.getString("layout")) else ModuleLayout.VERTICAL,
                    characterIds = if(module.has("characters")) strings(module.getJSONArray("characters")) else emptyList())
    }
}
