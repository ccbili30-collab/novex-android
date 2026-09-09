package com.openminis.app.novex.adapter

import com.openminis.app.data.model.AgentContentPart
import org.json.JSONArray
import org.json.JSONObject

/** Normalizes host-produced completion receipts without replaying private arguments or source text.
 * Both native file tools and the older proposal executor remain supported. This is read-only:
 * current permissions govern every address, and the original execution evidence is untouched. */
internal object NovexPublicWriteReceipt {
    private val fileTools = setOf("novex_write_card", "novex_update_card", "novex_write_module",
        "novex_move_module", "novex_link_cards")
    private val configurationTools = mapOf(
        "select_answer_identity" to "选择回答身份",
        "set_player_identity" to "保存玩家身份",
        "start_interactive_fiction" to "启动文游",
    )

    fun project(part: AgentContentPart.ToolResult, scopeKey: String): List<String> {
        configurationTools[part.name]?.let { operation ->
            // A scope change may hide the arguments and private error details,
            // but must not erase whether the host actually performed an action.
            // Never replay the payload (it may contain another role's identity).
            if (part.isError) return listOf("历史操作记录：此前这一次${operation}尝试未完成。它只描述当时的失败，后续重试结果按较晚的记录判断；当前是否已完成，以本轮对话设置为准。")
            val result = runCatching { JSONObject(part.content) }.getOrNull() ?: return emptyList()
            if (result.optBoolean("ok") && result.optString("code") == "conversation.configured") {
                return listOf("软件操作记录：此前已完成一次$operation；当前有效身份与文游状态以本轮对话设置为准。")
            }
            return emptyList()
        }
        if (part.isError || (part.name !in fileTools && part.name != "novex_apply_content_changes")) return emptyList()
        val payload = runCatching { JSONObject(part.content) }.getOrNull() ?: return emptyList()
        val allowed = runCatching { JSONObject(scopeKey).getJSONArray("readableSubjects").let { array ->
            (0 until array.length()).map { array.getString(it) }.toSet()
        } }.getOrDefault(emptySet())
        val legacy = part.name == "novex_apply_content_changes"
        if (legacy) {
            if (payload.optString("proposal_id").isBlank() || payload.optInt("applied_changes", -1) < 0 ||
                payload.optJSONArray("created_subjects") == null) return emptyList()
        } else if (!payload.optBoolean("saved") ||
            payload.optString("status") !in setOf("saved_verified", "saved_needs_review")) return emptyList()

        fun address(id: String, wireKind: String = ""): JSONObject? {
            if (id.isBlank()) return null
            val kind = when (wireKind) {
                "world" -> "WORLD"
                "character_version" -> "CHARACTER_VERSION"
                "game" -> "INTERACTIVE_FICTION"
                "" -> allowed.filter { it.substringAfter(':') == id }.singleOrNull()?.substringBefore(':')
                else -> null
            } ?: return null
            if ("$kind:$id" !in allowed) return null
            return JSONObject().put("kind", kind).put("id", id)
        }
        fun objects(field: String): List<JSONObject> = payload.optJSONArray(field)?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }.orEmpty()
        val facts = buildList {
            (objects(if (legacy) "created_subjects" else "created_cards") + objects("updated_cards")).forEach { card ->
                address(card.optString("id"), card.optString("kind"))?.let { safe ->
                    if (card.has("name")) safe.put("name", card.optString("name"))
                    add(JSONObject().put("card", safe))
                }
            }
            objects("saved_modules").forEach { module ->
                address(module.optString("card_id"), module.optString("card_kind"))?.let { safe ->
                    val id = module.optString("module_id")
                    if (id.isNotBlank()) add(JSONObject().put("card", safe).put("module_id", id)
                        .put("operation", part.name))
                }
            }
            objects("saved_references").forEach { reference ->
                val source = address(reference.optString("source_id"), reference.optString("source_kind"))
                val target = address(reference.optString("target_id"), reference.optString("target_kind"))
                if (source != null && target != null && reference.optString("reference_id").isNotBlank())
                    add(JSONObject().put("source", source).put("target", target)
                        .put("reference_id", reference.getString("reference_id")))
            }
        }
        if (facts.isEmpty()) return emptyList()
        val status = if (!legacy && payload.optString("status") == "saved_verified")
            "此前已保存并核验" else "此前已保存，当前正文需要重新读取核验"
        return listOf("软件保存记录：以下操作$status，对象仍在当前管理范围内：${JSONArray(facts)}。继续使用原编号，不要因身份调整重复创建；需要正文时重新读取。")
    }
}
