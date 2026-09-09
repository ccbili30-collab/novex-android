package com.openminis.app.ui.chat

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.novex.domain.NovexConversationConfigurationSnapshot
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import com.openminis.app.novex.domain.NovexStoryIllustrations
import com.openminis.app.tools.NovexIllustrationTools

/** Stateless presentation coordinator; callers provide the adopted configuration and active branch only. */
internal object NovexStoryImageCoordinator {
    fun tool(configuration: NovexConversationConfigurationSnapshot, visibleMessages: List<String>, name: String, arguments: String): JSONObject {
        val images = NovexStoryIllustrations.available(configuration, visibleMessages)
        val output = if (name == NovexIllustrationTools.INSPECT) {
            val args = JSONObject(arguments.ifBlank { "{}" })
            val offset = args.optInt("offset", 0); val limit = args.optInt("limit", 30)
            require(offset >= 0 && limit in 1..50) { "起始位置须为非负数，每页数量为 1 到 50" }
            JSONObject().put("total", images.size).put("offset", offset)
                .put("next_offset", if (offset.toLong() + limit < images.size) offset + limit else JSONObject.NULL)
                .put("images", JSONArray(images.drop(offset).take(limit).map { image -> JSONObject()
                .put("image_id", NovexStoryIllustrations.id(image))
                .put("name", image.label.ifBlank { "剧情插图" }).put("description", image.description.take(400)) }))
        } else {
            val id = JSONObject(arguments).getString("image_id")
            val image = requireNotNull(images.singleOrNull { NovexStoryIllustrations.id(it) == id }) { "图片不在本局可用范围，请重新查看可用图片" }
            require(File(image.asset.path).isFile) { "采用的图片附件缺失，请恢复图片；本次未选中" }
            JSONObject().put("selected_image_id", id).put("message", "已为本次回答选中图片，正文正常完成后展示。")
        }
        return output
    }
    fun completed(configuration: NovexConversationConfigurationSnapshot, visibleMessages: List<String>, blocks: List<AssistantBlock>,
        start: Int, rows: List<MessageEntity>, messageId: String): AssistantBlock? {
        if (blocks.any { it.toolName == NOVEX_STORY_IMAGE }) return null
        val text = formalAssistantText(blocks.drop(start), "")
        if (text.isBlank()) return null
        val images = NovexStoryIllustrations.available(configuration, visibleMessages)
        val explicit = blocks.lastOrNull { it.toolName == NovexIllustrationTools.SELECT && it.toolStatus == ToolBlockStatus.SUCCESS }
            ?.let { runCatching { JSONObject(it.content).getString("selected_image_id") }.getOrNull() }
        val prior = rows.filter { it.role == "assistant" }.mapNotNull { row ->
            runCatching {
                val values = JSONArray(row.partsJson)
                val entries = (0 until values.length()).map { values.getJSONObject(it) }
                if (entries.none { it.optString("type") == "text" && !it.optBoolean("execution") && it.optString("value").isNotBlank() }) null
                else entries.filter { it.optString("type") == NOVEX_STORY_IMAGE }.mapNotNull { it.optJSONObject("value")?.optString("sha256") }.toSet()
            }.getOrNull()
        }
        val selected = NovexStoryIllustrations.choose(images, text, prior, explicit) ?: return null
        return storyImageBlock(selected, messageId)
    }
}
