package com.openminis.app.novex.domain

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-stage3-save] 存档三元组（总纲 §3.9）：世界快照 + AI 记忆 + 账本状态。
 *
 * 来源：自动档（每次压缩时由调用方保存）+ 手动档（/save 命令，可命名）。
 * 落盘 `<novex>/<session>/saves/<id>.json`。回档 v1（/load）：恢复三元组 +
 * 历史保留 + 系统声明回档点（AI 经状态锚/笔记/声明知晓现局）——硬回档
 * （消息树 fork 弃后续对话）挂账 PR-C。
 */
object NovexSaveStore {

    data class SaveEntry(
        val id: String,
        val name: String,
        val createdAt: Long,
        val anchorMessageId: String?,
        val snapshotJson: String?,
        val memoryJson: String?,
        val ledgerJson: String?,
    )

    fun savesDir(context: Context, sessionId: String): File =
        File(File(File(context.filesDir, "novex"), sessionId), "saves")

    fun save(context: Context, sessionId: String, entry: SaveEntry) {
        val dir = savesDir(context, sessionId)
        dir.mkdirs()
        File(dir, "${entry.id}.json").writeText(encode(entry))
    }

    fun list(context: Context, sessionId: String): List<SaveEntry> =
        savesDir(context, sessionId).listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { file -> runCatching { decode(file.readText()) }.getOrNull() }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    fun load(context: Context, sessionId: String, id: String): SaveEntry? =
        File(savesDir(context, sessionId), "$id.json").takeIf(File::exists)
            ?.let { file -> runCatching { decode(file.readText()) }.getOrNull() }

    fun encode(entry: SaveEntry): String = JSONObject()
        .put("id", entry.id).put("name", entry.name).put("createdAt", entry.createdAt)
        .put("anchorMessageId", entry.anchorMessageId ?: JSONObject.NULL)
        .put("snapshot", entry.snapshotJson ?: JSONObject.NULL)
        .put("memory", entry.memoryJson ?: JSONObject.NULL)
        .put("ledger", entry.ledgerJson ?: JSONObject.NULL)
        .toString()

    fun decode(raw: String): SaveEntry {
        val root = JSONObject(raw)
        fun opt(key: String) = if (root.isNull(key)) null else root.optString(key).takeIf { it.isNotEmpty() }
        return SaveEntry(
            id = root.getString("id"),
            name = root.optString("name"),
            createdAt = root.optLong("createdAt"),
            anchorMessageId = opt("anchorMessageId"),
            snapshotJson = opt("snapshot"),
            memoryJson = opt("memory"),
            ledgerJson = opt("ledger"),
        )
    }

    /** 存档 id：手动档按名去重（同名覆盖），自动档时间戳。 */
    fun manualSaveId(name: String): String = "manual-" + name.trim()
        .replace(Regex("[^0-9A-Za-z\\u4e00-\\u9fff_-]"), "_").take(40).ifEmpty { "unnamed" }

    fun listDescription(saves: List<SaveEntry>): String = buildString {
        if (saves.isEmpty()) {
            appendLine("暂无存档。发送 /save 名称 手动存档；压缩时自动存档。")
            return@buildString
        }
        appendLine("存档列表（/load 序号 回档）：")
        saves.take(20).forEachIndexed { index, save ->
            appendLine("${index + 1}. ${save.name.ifBlank { "（未命名）" }} · ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(save.createdAt))}${if (save.id.startsWith("auto-")) " · 自动" else ""}")
        }
    }
}
