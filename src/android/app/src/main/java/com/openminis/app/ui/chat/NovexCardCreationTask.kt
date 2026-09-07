package com.openminis.app.ui.chat

import com.openminis.app.novex.domain.*
import org.json.JSONArray
import org.json.JSONObject

/** Host-derived outcome, never inferred from the assistant saying “done”. */
internal object NovexCardCreationTask {
    const val MARKER = "novex_card_task"
    const val REPAIR = "<system-reminder>用户要求创建应用内卡片，但本次执行还没有卡片写入回执。请继续实际调用卡片工具，不要再给未来计划。原文按章节入卡可用 novex_write_card 的 document_ref 和 source_revision，正文由软件直接复制。不得改写用户要求，不额外创建其他类型，不绕过确认。已有写入先核对，禁止重复创建；无法完成则明确缺口。本轮只进行这一次补救。</system-reminder>"
    data class Outcome(val status: String, val label: String, val cards: List<Pair<String, String>>, val mayRepair: Boolean) {
        fun block(id: String) = AssistantBlock(id, "info", label, toolName = MARKER,
            toolArgs = JSONObject().put("status", status).put("label", label)
                .put("cards", JSONArray(cards.map { JSONObject().put("kind", it.first).put("id", it.second) })).toString())
    }
    fun evaluate(requests: List<String>, blocks: List<AssistantBlock>): Outcome? {
        val latest = requests.lastOrNull() ?: return null
        if (Regex("^(请)?(告诉我|教我|介绍|解释|如何|怎么|怎样)").containsMatchIn(latest.trim())) return null
        val changes = listOf("world" to NovexManagedChange.CreateWorld("", ""),
            "character_version" to NovexManagedChange.CreateCharacter("", "{}"),
            "game" to NovexManagedChange.CreateInteractiveFiction("", "", com.openminis.app.data.interactivefiction.InteractiveFictionLaunchMode.FREE_SANDBOX, ""))
        val expected = changes.filter { it.second.matchesCreationTask(latest, requests.dropLast(1)) }.map { it.first }
        if (expected.isEmpty()) return null
        val calls = blocks.filter { it.kind == "tool_use" }
        val verified = calls.filter { it.toolStatus == ToolBlockStatus.SUCCESS }.flatMap { block ->
            val json = runCatching { JSONObject(block.content) }.getOrNull()
            if (block.toolName != "novex_write_card" || json?.optString("status") != "saved_verified") emptyList()
            else json.optJSONArray("created_cards")?.let { array -> (0 until array.length()).map {
                array.getJSONObject(it).getString("kind") to array.getJSONObject(it).getString("id")
            } }.orEmpty()
        }.distinct()
        val waiting = calls.any { it.toolStatus == ToolBlockStatus.SUCCESS && (
            it.toolName == "present_choices" || runCatching { JSONObject(it.content).optString("status") == "waiting_confirmation" }.getOrDefault(false)) }
        val possiblySaved = calls.any { it.toolStatus == ToolBlockStatus.SUCCESS &&
            (it.toolName == "novex_apply_content_changes" || runCatching { JSONObject(it.content).optBoolean("saved") }.getOrDefault(false)) }
        val request = requests.asReversed().firstOrNull { text -> changes.any { it.second.matchesCreationTask(text, emptyList()) } }.orEmpty()
        // Gameplay/world-building and image requests can say “generate a world/character”.
        // Missing-write repair requires an explicit native-card task; actual writes remain visible.
        val explicitCardTask = request.contains("卡") || Regex("(创建|新建|做成|制作).{0,6}文游").containsMatchIn(request)
        if (!explicitCardTask) return verified.takeIf { it.isNotEmpty() }?.let {
            Outcome("saved_verified", "已实际保存并回读核验 ${it.size} 张卡片", it, false)
        }
        val kindWords = mapOf("world" to "世界", "character_version" to "角色|人物", "game" to "文游|游戏|模拟器")
        fun count(kind: String): Int? {
            if (Regex("(多张|多份|一批|若干|几张)").containsMatchIn(request)) return null
            val token = Regex("([0-9]+|[一二两三四五六七八九十]+)\\s*[张份个]?\\s*(?:(?:一样|相同|不同|独立|全新|额外|空白|新)的?)?\\s*(?:${kindWords.getValue(kind)})").find(request)?.groupValues?.get(1) ?: return 1
            return token.toIntOrNull() ?: mapOf("一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10)[token]
        }
        val allSaved = expected.all { kind -> count(kind)?.let { needed -> verified.count { it.first == kind } >= needed } == true }
        return when {
            waiting -> Outcome("waiting_confirmation", "卡片任务等待你的选择或确认", verified, false)
            allSaved -> Outcome("saved_verified", "已保存并回读核验 ${verified.size} 张卡片 · 内容质量仍需检查", verified, false)
            verified.isNotEmpty() || possiblySaved -> Outcome("saved_needs_review", "卡片已有写入，任务仍需核对数量或内容；请继续核对现有成果", verified, false)
            else -> Outcome("incomplete", "卡片任务尚未完成：没有已保存卡片的回执", emptyList(), true)
        }
    }
}
