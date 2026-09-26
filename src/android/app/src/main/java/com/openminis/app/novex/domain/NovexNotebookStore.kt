package com.openminis.app.novex.domain

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-stage2-memory] AI 随身笔记本——会话级记忆存储（总纲 §3.7）。
 *
 * 落盘 `<workspace>/novex/<session>/memory.json`：独立于消息树，压缩永不
 * 触碰（阶段 3 存档三元组的记忆分量复用同文件）。跨压缩/重启天然存活、
 * 无 Room 迁移。损坏文件视为空重置（静默——记忆是潜在收益，不值得为它
 * 打断游玩）。
 */
class NovexNotebookStore(private val file: File) {

    data class MemoryEntry(
        val id: String,
        val kind: String,
        val text: String,
        val createdAt: Long,
        val updatedAt: Long,
    )

    data class SessionMemory(
        val entries: List<MemoryEntry> = emptyList(),
        /** 刻度高水位（MemoryWindowBudget.crossedMemoryTick 的已触发记录）。 */
        val highWaterTickPercent: Int = 0,
        val updatedAt: Long = 0L,
    )

    fun load(): SessionMemory = runCatching { decode(file.readText()) }.getOrDefault(SessionMemory())

    fun save(memory: SessionMemory) {
        file.parentFile?.mkdirs()
        file.writeText(encode(memory))
    }

    /** 常驻注入块（v1 折衷：条目全量；阶段 4 换目录指针+按需检索）。 */
    fun injectionBlock(memory: SessionMemory): String? {
        if (memory.entries.isEmpty()) return null
        val lines = memory.entries.joinToString("\n") { "- [${it.kind}] ${it.text}" }
        return "<AI 随身笔记>\n$lines\n</AI 随身笔记>"
    }

    companion object {
        /** 软上限：超限由整理调用指示合并最旧（不硬截断——静默丢记忆违反立身承诺）。 */
        const val SOFT_MAX_ENTRIES = 60
        const val SOFT_MAX_CHARS = 8_000

        fun forSession(context: Context, sessionId: String): NovexNotebookStore =
            NovexNotebookStore(File(File(File(context.filesDir, "novex"), sessionId), "memory.json"))

        fun decode(raw: String): SessionMemory {
            val root = JSONObject(raw)
            val entries = root.optJSONArray("entries") ?: JSONArray()
            return SessionMemory(
                entries = (0 until entries.length()).mapNotNull { i ->
                    val e = entries.optJSONObject(i) ?: return@mapNotNull null
                    MemoryEntry(
                        id = e.optString("id"),
                        kind = e.optString("kind", "fact"),
                        text = e.optString("text"),
                        createdAt = e.optLong("createdAt"),
                        updatedAt = e.optLong("updatedAt"),
                    ).takeIf { it.id.isNotBlank() && it.text.isNotBlank() }
                },
                highWaterTickPercent = root.optInt("highWaterTickPercent", 0),
                updatedAt = root.optLong("updatedAt"),
            )
        }

        fun encode(memory: SessionMemory): String = JSONObject()
            .put("entries", JSONArray(memory.entries.map {
                JSONObject().put("id", it.id).put("kind", it.kind).put("text", it.text)
                    .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt)
            }))
            .put("highWaterTickPercent", memory.highWaterTickPercent)
            .put("updatedAt", memory.updatedAt)
            .toString()

        /** 整理 prompt（后台子任务用；输出=完整更新后条目集 JSON）。 */
        fun consolidationPrompt(memory: SessionMemory, recentTurns: String, cardName: String?): String = buildString {
            appendLine("你是记忆整理器。整理下述对话笔记本：保留仍相关的内容，合并重复，淘汰过时；新增值得长期记住的事实/事件/人物/线索。")
            if (cardName != null) appendLine("本局：$cardName")
            appendLine("软上限 ${SOFT_MAX_ENTRIES} 条/${SOFT_MAX_CHARS} 字——超限时把最旧的条目合并成一条摘要（kind=archive），不要静默丢弃整条信息。")
            appendLine("只输出 JSON 对象：{\"entries\":[{\"id\":\"m1\",\"kind\":\"fact|event|person|thread|archive\",\"text\":\"...\"}]}，无其他文字。")
            appendLine("当前笔记本：")
            appendLine(if (memory.entries.isEmpty()) "（空）" else JSONObject().put("entries", JSONArray(memory.entries.map {
                JSONObject().put("id", it.id).put("kind", it.kind).put("text", it.text)
            })).toString())
            appendLine("自上次整理以来的对话增量：")
            appendLine(recentTurns.ifBlank { "（无）" })
        }

        /** 解析整理输出：非法/超上限结构静默返回 null（调用方跳过本档）。 */
        fun parseConsolidation(raw: String, now: Long): SessionMemory? = runCatching {
            val root = JSONObject(raw.trim())
            val entries = root.getJSONArray("entries")
            val parsed = (0 until entries.length()).mapNotNull { i ->
                val e = entries.optJSONObject(i) ?: return@mapNotNull null
                MemoryEntry(
                    id = e.optString("id").ifBlank { "m${i + 1}" },
                    kind = e.optString("kind", "fact"),
                    text = e.optString("text").trim(),
                    createdAt = e.optLong("createdAt", now),
                    updatedAt = now,
                ).takeIf { it.text.isNotBlank() }
            }.distinctBy { it.id }
            SessionMemory(entries = parsed, updatedAt = now)
        }.getOrNull()
    }
}
