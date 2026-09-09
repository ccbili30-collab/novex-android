package com.openminis.app.ui.chat

import org.json.JSONArray
import org.json.JSONObject

/** Summarizes actual write receipts. The host does not guess the user's intent or requested count from words. */
internal object NovexCardCreationTask {
    const val MARKER = "novex_card_task"
    data class Outcome(val status: String, val label: String, val cards: List<Pair<String, String>>,
        val names: Map<Pair<String, String>, String> = emptyMap()) {
        fun block(id: String) = AssistantBlock(id, "info", label, toolName = MARKER,
            toolArgs = JSONObject().put("status", status).put("label", label)
                .put("cards", JSONArray(cards.map { JSONObject().put("kind", it.first).put("id", it.second).put("name", names[it]) })).toString())
    }
    fun evaluate(blocks: List<AssistantBlock>): Outcome? {
        val calls = blocks.filter { it.kind == "tool_use" && it.toolName in setOf("novex_write_card", "novex_update_card") }
            .associateBy { it.id }.values
        if (calls.isEmpty()) return null
        val verified = mutableListOf<Pair<String, String>>()
        val names = mutableMapOf<Pair<String, String>, String>()
        var unfinished = false
        calls.forEach { block ->
            val receipt = runCatching { JSONObject(block.content) }.getOrNull()
            if (block.toolStatus != ToolBlockStatus.SUCCESS || receipt?.optString("status") != "saved_verified") {
                unfinished = true
            } else {
                val cards = receipt.optJSONArray(if (block.toolName == "novex_update_card") "updated_cards" else "created_cards") ?: JSONArray()
                for (index in 0 until cards.length()) {
                    val card = cards.optJSONObject(index) ?: continue
                    val kind = card.optString("kind")
                    val id = card.optString("id")
                    if (kind in setOf("world", "character_version", "game") && id.isNotBlank()) {
                        val key = kind to id
                        verified += key
                        card.optString("name").takeIf(String::isNotBlank)?.let { names[key] = it }
                    }
                }
            }
        }
        val cards = verified.distinct()
        return when {
            cards.isNotEmpty() && !unfinished -> Outcome("saved_verified", "已保存 ${cards.size} 张卡片", cards, names)
            cards.isNotEmpty() -> Outcome("saved_needs_review", "已保存 ${cards.size} 张卡片，另有写入未完成", cards, names)
            else -> Outcome("incomplete", "卡片写入尚未完成，可展开执行过程查看原因", emptyList())
        }
    }
}
