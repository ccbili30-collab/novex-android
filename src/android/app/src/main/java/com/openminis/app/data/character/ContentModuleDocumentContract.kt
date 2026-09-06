package com.openminis.app.data.character

import org.json.JSONArray
import org.json.JSONObject

/** The writable document contract, shared by tool discovery and write validation. */
object ContentModuleDocumentContract {
    fun example(type: ContentModuleType): JSONObject {
        val document = when (ContentModuleDocumentCodec.decode(type, "{}")) {
            is ContentModuleDocument.Article -> ContentModuleDocument.Article("完整正文；可包含多段内容")
            is ContentModuleDocument.SingleImage -> ContentModuleDocument.SingleImage("地图说明；图片通过成果附加工具单独关联")
            is ContentModuleDocument.Timeline -> ContentModuleDocument.Timeline(listOf(
                ContentModuleTimelineNode("纪元一", "事件标题", "事件内容与影响"),
            ))
            is ContentModuleDocument.Collection -> ContentModuleDocument.Collection(listOf(
                ContentModuleCollectionItem("entry-1", "条目名称", "一句摘要", "完整说明；不可只填写摘要"),
            ))
            is ContentModuleDocument.Unsupported -> error("已知模块必须有可编辑的默认文档")
        }
        return JSONObject(ContentModuleDocumentCodec.encode(document))
    }

    /** Validate new tool writes; legacy imports are decoded losslessly by the codec instead. */
    fun validate(raw: String, type: ContentModuleType = ContentModuleType.CUSTOM) {
        val root = runCatching { JSONObject(raw) }.getOrElse {
            throw IllegalArgumentException("content_json 必须是对象；示例：${example(type)}")
        }
        if (root.length() == 0) return // Blank content is an intentional supported state.
        fun fail(reason: String): Nothing = throw IllegalArgumentException(
            "$reason；content_json 示例：${example(type)}。已有内容先检查再修改，不要猜字段。",
        )
        fun strings(value: JSONObject, fields: List<String>, path: String) {
            fields.forEach { field ->
                if (value.has(field) && !value.isNull(field) && value.opt(field) !is String) {
                    fail("$path.$field 必须是文本")
                }
            }
        }
        fun text(field: String) {
            if (!root.has(field)) fail("缺少 $field 字段；留空请显式传空文本")
            strings(root, listOf(field), "content_json")
        }
        fun entries(field: String, textFields: List<String>) {
            val array = root.optJSONArray(field) ?: fail("$field 必须是条目数组；留空请传 []")
            repeat(array.length()) { index ->
                val entry = array.optJSONObject(index) ?: fail("$field[$index] 必须是对象")
                strings(entry, textFields, field + "[$index]")
            }
        }
        when (val kind = root.opt("kind")) {
            "article" -> text("text")
            "single_image" -> text("description")
            "timeline" -> entries("nodes", listOf("time", "title", "description"))
            "collection" -> entries("items", listOf("id", "name", "summary", "description", "visualKey"))
            null -> when {
                root.has("text") -> text("text") // Existing text-only cards remain writable.
                root.has("caption") -> text("caption") // Previous map-tool spelling.
                else -> fail("缺少可渲染的 kind 与对应内容字段")
            }
            else -> fail("不支持内容布局 kind=$kind；可用 article、single_image、timeline、collection")
        }
    }
}
