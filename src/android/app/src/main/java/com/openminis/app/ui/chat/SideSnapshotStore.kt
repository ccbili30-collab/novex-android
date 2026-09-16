package com.openminis.app.ui.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-side-snapshot] 侧边分裂点快照（2026-09-16 用户批δ，决策 1/2/3 已确认）：
 * 「隐藏历史记录」只是界面不显示——模型上下文要带主线历史，但**分裂即定格**：
 * 建侧边那一刻记下主线活跃路径的消息 ID 清单，之后两边各走各的；主线切分支、
 * 改活跃路径都影响不到快照（按 ID 只读拉取，认 ID 不认现状）。
 *
 * 存储用 SharedPreferences（清单是 UUID 列表，几十 KB 级）；上限 400 条，
 * 更早的主线交给两边各自的压缩机制。
 */
object SideSnapshotStore {
    private const val PREFS = "novex_side_snapshots"
    private const val CAP = 400

    data class Snapshot(val parentSessionId: String, val messageIds: List<String>)

    fun write(context: Context, sideId: String, parentSessionId: String, messageIds: List<String>) {
        val ids = messageIds.takeLast(CAP)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(sideId, JSONObject()
                .put("parent", parentSessionId)
                .put("ids", JSONArray(ids))
                .toString())
            .apply()
    }

    fun read(context: Context, sideId: String): Snapshot? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(sideId, null)
            ?: return null
        val value = JSONObject(raw)
        val ids = value.getJSONArray("ids").let { a -> (0 until a.length()).map { a.getString(it) } }
        Snapshot(value.getString("parent"), ids)
    }.getOrNull()

    fun clear(context: Context, sideId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(sideId).apply()
    }
}
