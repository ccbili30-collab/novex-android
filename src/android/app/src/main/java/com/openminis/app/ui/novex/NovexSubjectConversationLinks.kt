package com.openminis.app.ui.novex

import androidx.compose.runtime.*
import com.openminis.app.novex.domain.NovexContentAddress

/** A reverse view of current adopted/managed relations, never a reassignment of conversations. */
@Composable
internal fun NovexSubjectConversationLinks(subject: NovexContentAddress, onOpenSession: (String) -> Unit) {
    val directory = rememberNovexWorkGroups()
    val conversations by directory.conversations.collectAsState(initial = null)
    var selection by remember(subject) { mutableStateOf<String?>(null) }
    val used = conversations.orEmpty().filter { subject in it.used }
    val managed = conversations.orEmpty().filter { subject in it.managed }
    NovexContentSection("关联对话") {
        NovexSummaryRow("使用它的对话", if (conversations == null) "正在读取" else "${used.size} 个 · 身份、背景或活动文游引用",
            onClick = { if (conversations != null) selection = "used" })
        NovexSummaryRow("创作管理它的对话", if (conversations == null) "正在读取" else "${managed.size} 个 · 已挂载或创建此卡，不自动作为背景",
            onClick = { if (conversations != null) selection = "managed" })
    }
    selection?.let { mode ->
        NovexSearchableSelectionSheet(if (mode == "used") "使用它的对话" else "创作管理它的对话",
            (if (mode == "used") used else managed).map { row ->
                NovexSelectionAction(row.title, description = "打开原对话，保留其回答身份与配置") { onOpenSession(row.id) }
            }.ifEmpty { listOf(NovexSelectionAction("尚无此类关联", enabled = false) {}) },
            "搜索对话名称", onDismissRequest = { selection = null })
    }
}
