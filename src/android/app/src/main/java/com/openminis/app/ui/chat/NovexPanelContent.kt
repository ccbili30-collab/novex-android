package com.openminis.app.ui.chat

import org.json.JSONArray
import org.json.JSONObject

/** The executor and renderer accept the same visible content, including saved legacy panels. */
internal data class NovexPanelContent(
    val blocks: List<JSONObject>,
    val actions: List<NovexPanelAction>,
    val content: String,
) {
    companion object {
        fun parse(args: JSONObject): NovexPanelContent {
            val blocks = objects(args, "blocks").toMutableList()
            if (blocks.isEmpty()) {
                val images = array(args, "images")
                if (images.length() > 0) blocks += JSONObject().put("type", "gallery").put("images", images)
                val items = array(args, "items")
                if (items.length() > 0) blocks += JSONObject().put("type", "stats").put("items", items)
            }
            blocks.forEachIndexed { index, block ->
                // Normalize accepted encodings before handing the same block to the renderer.
                block.put("type", text(block, "type"))
                val arrayFields = when (text(block, "type")) {
                    "gallery" -> listOf("images")
                    "stats", "timeline" -> listOf("items")
                    "table" -> listOf("columns", "rows")
                    else -> emptyList()
                }
                arrayFields.forEach { block.put(it, array(block, it)) }
                require(validBlock(block)) { "第 ${index + 1} 个内容块格式不完整。正文使用 {\"type\":\"markdown\",\"content\":\"实际正文\"}；状态使用 {\"type\":\"stats\",\"items\":[{\"label\":\"地点\",\"value\":\"邮局\"}]}" }
            }
            val actions = objects(args, "actions").map { action ->
                NovexPanelAction(text(action, "label"), text(action, "prompt")).also {
                    require(it.label.isNotBlank() && it.prompt.isNotBlank()) { "操作需要名称和对应输入内容" }
                }
            }.ifEmpty {
                objects(args, "buttons").map { action ->
                    NovexPanelAction(text(action, "label"), text(action, "value").ifBlank { text(action, "label") }).also {
                        require(it.label.isNotBlank() && it.prompt.isNotBlank()) { "按钮内容为空" }
                    }
                }
            }
            val content = text(args, "content")
            require(content.isNotBlank() || actions.isNotEmpty() || blocks.any { it.optString("type") != "divider" }) {
                "面板只有标题或说明，没有可展示的正文；请在 blocks 中填写实际内容"
            }
            return NovexPanelContent(blocks, actions, content)
        }

        private fun validBlock(block: JSONObject): Boolean = when (text(block, "type")) {
            "markdown", "html", "details" -> text(block, "content").isNotBlank()
            "image" -> text(block, "src").isNotBlank()
            "gallery" -> array(block, "images").let { images -> images.length() > 0 && (0 until images.length()).all {
                val value = images.opt(it)
                (if (value is JSONObject) text(value, "src") else value as? String).orEmpty().isNotBlank()
            } }
            "stats" -> objects(block, "items").let { items -> items.isNotEmpty() && items.all { text(it, "label").isNotBlank() && text(it, "value").isNotBlank() } }
            "timeline" -> objects(block, "items").let { items -> items.isNotEmpty() && items.all { text(it, "title").isNotBlank() } }
            "table" -> array(block, "rows").let { rows -> rows.length() > 0 && (0 until rows.length()).all { index ->
                val row = rows.optJSONArray(index)
                row != null && row.length() > 0 && (0 until row.length()).any { !row.isNull(it) && row.optString(it).isNotBlank() }
            } }
            "divider" -> true
            else -> false
        }
        private fun text(obj: JSONObject, key: String): String = if (obj.isNull(key)) "" else obj.optString(key).trim()
        private fun array(obj: JSONObject, key: String): JSONArray {
            val value = obj.opt(key)
            return when {
                value == null || value == JSONObject.NULL || value == "" -> JSONArray()
                value is JSONArray -> value
                value is String -> JSONArray(value)
                else -> throw IllegalArgumentException("$key 必须为数组")
            }
        }
        private fun objects(obj: JSONObject, key: String): List<JSONObject> = array(obj, key).let { values ->
            (0 until values.length()).map { values.optJSONObject(it) ?: throw IllegalArgumentException("$key 第 ${it + 1} 项必须为对象") }
        }
    }
}

internal data class NovexPanelAction(val label: String, val prompt: String)
