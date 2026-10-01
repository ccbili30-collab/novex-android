package com.openminis.app.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import novex.android.ui.NovexIcons

// 工具块的展示换算：强调色 / 图标 / 中文动作名 / 时长与时间戳格式化。
// 工具名是 wire 协议（冻结），查表按「类别 → 成员」分组维护。

private object ToolLooks {
    val TERMINAL = ToolLook(Color(0xFF34C759), NovexIcons.Terminal)
    val READ = ToolLook(Color(0xFF32ADE6), NovexIcons.Description)
    val WRITE = ToolLook(Color(0xFF007AFF), NovexIcons.NoteAdd)
    val EDIT = ToolLook(Color(0xFFFF9500), NovexIcons.EditNote)
    val COMPUTE = ToolLook(Color(0xFFAF52DE), NovexIcons.Build)
    val WEB = ToolLook(Color(0xFF007AFF), NovexIcons.Language)
    val IMAGE = ToolLook(Color(0xFFAF52DE), NovexIcons.Image)
    val MEMORY = ToolLook(Color(0xFFFF2D55), NovexIcons.Psychology)
    val SEARCH = ToolLook(Color(0xFF32ADE6), NovexIcons.Search)
    val GENERIC = ToolLook(Color(0xFF8E8E93), NovexIcons.Build)
}

private class ToolLook(val accent: Color, val icon: ImageVector)

private val toolLookByName = buildMap {
    fun group(look: ToolLook, vararg names: String) = names.forEach { put(it, look) }
    group(ToolLooks.TERMINAL, "shell_execute")
    group(ToolLooks.READ,
        "file_read", "document_inspect", "document_read",
        "workspace_inspect", "workspace_search", "workspace_read")
    group(ToolLooks.WRITE, "file_write", "workspace_write")
    group(ToolLooks.EDIT, "file_edit", "workspace_edit")
    group(ToolLooks.COMPUTE, "workspace_compute")
    group(ToolLooks.WEB, "browser_use")
    group(ToolLooks.IMAGE, "read_image")
    group(ToolLooks.MEMORY,
        "memory_write", "memory_get",
        "novex_inspect_memory", "novex_propose_memory_changes", "novex_apply_memory_changes")
    group(ToolLooks.SEARCH, "web_search")
}

internal fun toolAccentColor(toolName: String): Color =
    (toolLookByName[toolName] ?: ToolLooks.GENERIC).accent

internal fun toolIconFor(toolName: String): ImageVector =
    (toolLookByName[toolName] ?: ToolLooks.GENERIC).icon

/** 「正在做什么」里的动作名。 */
private val toolActionByName = mapOf(
    "shell_execute" to "后台处理",
    "file_read" to "读取资料",
    "document_inspect" to "检查文档",
    "document_read" to "读取文档",
    "file_write" to "保存资料",
    "file_edit" to "更新资料",
    "browser_use" to "联网检索",
    "web_search" to "联网检索",
    "read_image" to "查看图片",
    "memory_write" to "读取记忆",
    "memory_get" to "读取记忆",
    "present_choices" to "提供选项",
    "render_panel" to "显示资料面板",
    "panel" to "显示资料面板",
    "present_system_panel" to "显示资料面板",
    "save_checkpoint" to "保存进度",
    "register_controls" to "更新快捷操作",
    "update_playthrough_state" to "更新本局状态",
    "novex_inspect_content" to "查看挂载内容",
    "novex_propose_content_changes" to "提出内容变更",
    "novex_apply_content_changes" to "执行内容变更",
    "novex_inspect_memory" to "查看长期记忆",
    "novex_propose_memory_changes" to "提出记忆变更",
    "novex_apply_memory_changes" to "执行记忆变更",
    "workspace_inspect" to "检查工作区",
    "workspace_read" to "读取工作区",
    "workspace_search" to "查找仓库资料",
    "workspace_write" to "写入工作区",
    "workspace_edit" to "编辑工作区",
    "workspace_compute" to "处理工作区",
)

internal fun toolDisplayName(toolName: String): String =
    toolActionByName[toolName] ?: "处理内容"

/** 详情面板底栏的完整标题。 */
internal fun toolTitleLabel(toolName: String): String = "Novex 正在${toolDisplayName(toolName)}"

// ── 时长与时间戳 ────────────────────────────────────────────────────────────

internal fun formatStepTimestamp(epochMs: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(epochMs))

/**
 * 步骤耗时短标签（详情顶栏用）：
 *   <60s → "3s"；<1h → "2m30s"（整分省秒）；≥1h → "1h12m"（整时省分）。
 * 仍在跑时尾缀 "…"；非正数按 0 计。
 */
internal fun formatStepDuration(seconds: Long, stillRunning: Boolean): String {
    val total = seconds.coerceAtLeast(0)
    val label = buildString {
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        when {
            h > 0 -> {
                append(h).append('h')
                if (m > 0) append(m).append('m')
            }
            m > 0 -> {
                append(m).append('m')
                if (s > 0) append(s).append('s')
            }
            else -> append(s).append('s')
        }
    }
    return if (stillRunning) "$label…" else label
}

/** 毫秒耗时格式化：<1s 一位小数，<60s 整数秒，≥60s "Xm Ys"。 */
internal fun formatToolDuration(ms: Long): String {
    val totalSeconds = ms / 1000.0
    return if (totalSeconds < 1) {
        "%.1fs".format(totalSeconds)
    } else if (totalSeconds < 60) {
        "%.0fs".format(totalSeconds)
    } else {
        "%dm %ds".format((totalSeconds / 60).toInt(), (totalSeconds % 60).toInt())
    }
}
