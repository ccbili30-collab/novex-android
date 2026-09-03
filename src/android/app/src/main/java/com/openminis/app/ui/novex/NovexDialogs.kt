package com.openminis.app.ui.novex

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal enum class NovexDecisionTone { PRIMARY, NEUTRAL, DESTRUCTIVE }

internal data class NovexDecisionAction(
    val label: String,
    @DrawableRes val icon: Int,
    val tone: NovexDecisionTone = NovexDecisionTone.NEUTRAL,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** One compact decision surface for save, discard, delete and notice flows. */
@Composable
internal fun NovexDecisionDialog(
    title: String,
    message: String,
    actions: List<NovexDecisionAction>,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val shape = RoundedCornerShape(NovexDimensions.DialogRadius)
        Surface(
            color = NovexColors.Surface,
            shape = shape,
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .widthIn(max = 360.dp)
                .border(NovexDimensions.Hairline, NovexColors.Divider, shape),
        ) {
            Column {
                Text(
                    title,
                    color = NovexColors.Text,
                    style = NovexType.SectionTitle,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
                )
                Text(
                    message,
                    color = NovexColors.SecondaryText,
                    style = NovexType.Body,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 7.dp, bottom = 14.dp),
                )
                actions.forEachIndexed { index, action ->
                    if (index > 0) NovexDivider(Modifier.padding(horizontal = 16.dp))
                    NovexDecisionActionRow(action)
                }
            }
        }
    }
}

@Composable
private fun NovexDecisionActionRow(action: NovexDecisionAction) {
    val tint = when (action.tone) {
        NovexDecisionTone.PRIMARY -> NovexColors.Primary
        NovexDecisionTone.NEUTRAL -> NovexColors.Text
        NovexDecisionTone.DESTRUCTIVE -> NovexColors.Danger
    }
    val tile = when (action.tone) {
        NovexDecisionTone.PRIMARY -> NovexColors.PrimarySoft
        NovexDecisionTone.NEUTRAL -> NovexColors.SurfaceMuted
        NovexDecisionTone.DESTRUCTIVE -> NovexColors.Danger.copy(alpha = 0.12f)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .alpha(if (action.enabled) 1f else 0.42f)
            .clickable(enabled = action.enabled, onClick = action.onClick)
            .padding(horizontal = 16.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(NovexDimensions.ActionIconTile)
                .clip(RoundedCornerShape(NovexDimensions.SmallRadius))
                .background(tile),
        ) {
            Icon(
                painter = painterResource(action.icon),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(action.label, color = tint, style = NovexType.Body)
    }
}

@Composable
internal fun NovexUnsavedChangesDialog(
    saving: Boolean,
    onSaveAndExit: () -> Unit,
    onDiscard: () -> Unit,
    onContinueEditing: () -> Unit,
) {
    NovexDecisionDialog(
        title = "保存更改？",
        message = "你修改的内容尚未保存。",
        onDismiss = { if (!saving) onContinueEditing() },
        actions = listOf(
            NovexDecisionAction(
                label = if (saving) "保存中" else "保存并退出",
                icon = com.openminis.app.R.drawable.ic_phosphor_check,
                tone = NovexDecisionTone.PRIMARY,
                enabled = !saving,
                onClick = onSaveAndExit,
            ),
            NovexDecisionAction(
                label = "不保存",
                icon = com.openminis.app.R.drawable.ic_phosphor_trash,
                tone = NovexDecisionTone.DESTRUCTIVE,
                enabled = !saving,
                onClick = onDiscard,
            ),
            NovexDecisionAction(
                label = "继续编辑",
                icon = com.openminis.app.R.drawable.ic_phosphor_arrow_left,
                enabled = !saving,
                onClick = onContinueEditing,
            ),
        ),
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
    NovexDecisionDialog(
        title = title,
        message = message,
        onDismiss = { if (!confirming) onDismiss() },
        actions = listOf(
            NovexDecisionAction(
                label = if (confirming) "删除中" else "删除",
                icon = com.openminis.app.R.drawable.ic_phosphor_trash,
                tone = NovexDecisionTone.DESTRUCTIVE,
                enabled = !confirming,
                onClick = onConfirm,
            ),
            NovexDecisionAction(
                label = "取消",
                icon = com.openminis.app.R.drawable.ic_phosphor_arrow_left,
                enabled = !confirming,
                onClick = onDismiss,
            ),
        ),
    )
}

@Composable
internal fun NovexNoticeDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    NovexDecisionDialog(
        title = title,
        message = message,
        onDismiss = onDismiss,
        actions = listOf(
            NovexDecisionAction(
                label = "知道了",
                icon = com.openminis.app.R.drawable.ic_phosphor_check,
                tone = NovexDecisionTone.PRIMARY,
                onClick = onDismiss,
            ),
        ),
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
        shape = RoundedCornerShape(NovexDimensions.DialogRadius),
        containerColor = NovexColors.Surface,
        titleContentColor = NovexColors.Text,
        textContentColor = NovexColors.SecondaryText,
        title = { Text(title, style = NovexType.SectionTitle) },
        text = { androidx.compose.foundation.layout.Column(content = content) },
        confirmButton = confirmButton,
        dismissButton = { dismissButton?.invoke() },
    )
}
