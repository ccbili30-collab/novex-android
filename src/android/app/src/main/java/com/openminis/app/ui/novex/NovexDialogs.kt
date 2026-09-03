package com.openminis.app.ui.novex

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
internal fun NovexUnsavedChangesDialog(
    saving: Boolean,
    onSaveAndExit: () -> Unit,
    onDiscard: () -> Unit,
    onContinueEditing: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onContinueEditing,
        containerColor = NovexColors.Surface,
        titleContentColor = NovexColors.Text,
        textContentColor = NovexColors.SecondaryText,
        title = { Text("保存更改？", style = NovexType.SectionTitle) },
        text = { Text("你修改的内容尚未保存。", style = NovexType.Body) },
        confirmButton = {
            TextButton(enabled = !saving, onClick = onSaveAndExit) {
                Text(if (saving) "保存中" else "保存并退出", color = NovexColors.Primary)
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDiscard) {
                Text("不保存", color = NovexColors.Danger)
            }
            TextButton(enabled = !saving, onClick = onContinueEditing) {
                Text("继续编辑", color = NovexColors.Text)
            }
        },
    )
}

@Composable
internal fun NovexDestructiveConfirmationDialog(
    title: String,
    message: String,
    confirming: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!confirming) onDismiss() },
        containerColor = NovexColors.Surface,
        titleContentColor = NovexColors.Text,
        textContentColor = NovexColors.SecondaryText,
        title = { Text(title, style = NovexType.SectionTitle) },
        text = { Text(message, style = NovexType.Body) },
        confirmButton = {
            TextButton(enabled = !confirming, onClick = onConfirm) {
                Text(if (confirming) "删除中" else "删除", color = NovexColors.Danger)
            }
        },
        dismissButton = {
            TextButton(enabled = !confirming, onClick = onDismiss) {
                Text("取消", color = NovexColors.Text)
            }
        },
    )
}
