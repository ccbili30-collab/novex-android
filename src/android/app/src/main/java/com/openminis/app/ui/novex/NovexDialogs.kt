package com.openminis.app.ui.novex

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun NovexUnsavedChangesDialog(
    saving: Boolean,
    onSaveAndExit: () -> Unit,
    onDiscard: () -> Unit,
    onContinueEditing: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onContinueEditing,
        shape = RoundedCornerShape(20.dp),
        containerColor = NovexColors.Surface,
        titleContentColor = NovexColors.Text,
        textContentColor = NovexColors.SecondaryText,
        title = { Text("保存更改？", style = NovexType.SectionTitle) },
        text = { Text("你修改的内容尚未保存。", style = NovexType.Body) },
        confirmButton = {
            NovexPrimaryButton(
                label = if (saving) "保存中" else "保存并退出",
                enabled = !saving,
                onClick = onSaveAndExit,
                modifier = Modifier.width(132.dp),
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NovexOutlineButton(
                    label = "不保存",
                    danger = true,
                    enabled = !saving,
                    onClick = onDiscard,
                )
                NovexOutlineButton(
                    label = "继续编辑",
                    enabled = !saving,
                    onClick = onContinueEditing,
                )
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
        shape = RoundedCornerShape(20.dp),
        containerColor = NovexColors.Surface,
        titleContentColor = NovexColors.Text,
        textContentColor = NovexColors.SecondaryText,
        title = { Text(title, style = NovexType.SectionTitle) },
        text = { Text(message, style = NovexType.Body) },
        confirmButton = {
            NovexPrimaryButton(
                label = if (confirming) "删除中" else "删除",
                enabled = !confirming,
                containerColor = NovexColors.Danger,
                onClick = onConfirm,
                modifier = Modifier.width(104.dp),
            )
        },
        dismissButton = {
            NovexOutlineButton(label = "取消", enabled = !confirming, onClick = onDismiss)
        },
    )
}

@Composable
internal fun NovexNoticeDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = NovexColors.Surface,
        titleContentColor = NovexColors.Text,
        textContentColor = NovexColors.SecondaryText,
        title = { Text(title, style = NovexType.SectionTitle) },
        text = { Text(message, style = NovexType.Body) },
        confirmButton = {
            NovexPrimaryButton(
                label = "知道了",
                onClick = onDismiss,
                modifier = Modifier.width(104.dp),
            )
        },
    )
}

/** Shared shell for richer pickers and decisions that need custom body content. */
@Composable
internal fun NovexContentDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = NovexColors.Surface,
        titleContentColor = NovexColors.Text,
        textContentColor = NovexColors.SecondaryText,
        title = { Text(title, style = NovexType.SectionTitle) },
        text = { androidx.compose.foundation.layout.Column(content = content) },
        confirmButton = confirmButton,
        dismissButton = { dismissButton?.invoke() },
    )
}
