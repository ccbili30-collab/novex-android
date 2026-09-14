package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import com.openminis.app.ui.novex.NovexEditorSection
import com.openminis.app.ui.novex.NovexSummaryRow

internal fun conversationSettingsPageTitle(page: String): String = when (page) {
    "answer" -> "回答身份"; "player" -> "我的身份"; "prompt" -> "对话提示词"
    "background" -> "背景资料"; "game" -> "当前文游"; "manage" -> "可管理内容"
    "workspace" -> "对话空间"; "controls" -> "快捷操作"; "display" -> "显示方式"
    "image" -> "图片生成提示词"; "pending" -> "待执行变更"; else -> "对话设置"
}

@Composable
internal fun ConversationSettingsOverview(
    answer: String, player: String, backgroundCount: Int, game: String, managedCount: Int,
    permission: String, promptChanged: Boolean, integrated:Boolean=false, onOpen: (String) -> Unit, onPermission: () -> Unit,
) {
    NovexEditorSection(header = "身份与回答") {
        NovexSummaryRow("回答身份", answer, onClick = { onOpen("answer") })
        NovexSummaryRow("我的身份", player, onClick = { onOpen("player") })
        NovexSummaryRow("对话提示词", if (promptChanged) "已设置" else "默认", onClick = { onOpen("prompt") })
    }
    NovexEditorSection(header = "使用的设定") {
        NovexSummaryRow("背景资料", if (backgroundCount == 0) "未添加" else "$backgroundCount 项", onClick = { onOpen("background") })
        if(!integrated)NovexSummaryRow("当前文游", game, onClick = { onOpen("game") })
    }
    NovexEditorSection(header = "内容与工具") {
        NovexSummaryRow("对话空间", "文件与成果", onClick = { onOpen("workspace") })
        NovexSummaryRow("可管理内容", if (managedCount == 0) "未添加" else "$managedCount 项", onClick = { onOpen("manage") })
        NovexSummaryRow("工具权限", permission, onClick = onPermission)
    }
    NovexEditorSection(header = "其他设置") {
        NovexSummaryRow("显示方式", "头像与气泡", onClick = { onOpen("display") })
        NovexSummaryRow("快捷操作", "查看与行动", onClick = { onOpen("controls") })
        NovexSummaryRow("图片生成提示词", "风格与要求", onClick = { onOpen("image") })
    }
}
