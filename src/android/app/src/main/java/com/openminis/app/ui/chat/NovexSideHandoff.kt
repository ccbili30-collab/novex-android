package com.openminis.app.ui.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 侧边对话回传（2026-09-14 决策 3/5/16）：侧边页的符号入口触发，侧边模型先产出
 * 增量交接简报，简报以带标记的用户消息并入主线历史。水位线持久化在侧边会话上，
 * 每次回传只总结水位线之后的新内容；主对话侧只认"回传后新产生的助手回复"，
 * 生成失败绝不把旧回复当简报（交接守卫，见 ChatScreen 的 handoff 状态机）。
 */
internal object NovexSideHandoff {
    const val PREFS = "novex_side_conversations"

    /** 回传后新简报写入主线时，主对话页需要重载一次才能看到（导航返回时消费）。 */
    fun markParentDirty(context: Context, parentId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("dirty-parent", parentId).apply()
    }

    fun consumeParentDirty(context: Context, sessionId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("dirty-parent", null) != sessionId) return false
        prefs.edit().remove("dirty-parent").apply()
        return true
    }

    fun readWatermark(context: Context, sideId: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("brief:$sideId", null)

    fun writeWatermark(context: Context, sideId: String, brief: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("brief:$sideId", brief).apply()
    }

    /** 简报作为带标记的用户消息进入主线（决策 4）：一行折叠标记，正文可展开。 */
    fun parts(brief: String): String = JSONArray()
        .put(JSONObject().put("type", "text").put("value", "〔侧边结论 · 已并入〕\n$brief"))
        .toString()

    fun instruction(previous: String?): String =
        (previous?.takeIf { it.isNotBlank() }?.let { "上一次已交接的内容：\n$it\n\n" } ?: "") +
            "请把本次讨论中上次交接之后的新结论，压缩成一份交接简报：只列确定的事实、设定变更和接下来要做的事，" +
            "不要复述剧情，不要空话，500 字以内。状态相关的变化要写明字段与数值（如 hp 83/100），主线会据此自行落账。直接输出简报正文。"
}
