package com.openminis.app.novex.domain

import org.json.JSONObject

/** Model-facing directory; full page rendering/export continues to use the unchanged snapshot. */
fun NovexManagementInspection.toModelToolJson(includeAdvanced: Boolean = false): JSONObject = toToolJson().apply {
    if (!includeAdvanced) {
        remove("module_type_catalog")
        remove("version_relation_kinds")
        remove("game_launch_modes")
    }
    if (selectedModule == null) {
        val rows = getJSONArray("modules")
        for (index in 0 until rows.length()) {
            rows.getJSONObject(index).apply {
                val body = remove("content")
                put("content_chars", body?.toString()?.length ?: 0)
            }
        }
        put("reading", "这是卡片与模块目录，不是已读取正文。按 module_id 读取需要的模块；普通创建直接填写模块 name 和 text。专用结构或高级操作按需传 include_advanced=true。")
    }
}
