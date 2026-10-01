package com.openminis.app.ui.chat

// [T-android-split-chat] Assistant-message + tool-pill + thinking rendering
// extracted verbatim from ChatScreen.kt: AssistantHeader, AssistantMessageView,
// BoundsTrackedBlock, InlineErrorBanner, ToolStopButton,
// formatToolDetailsForClipboard, ToolCallPill, ThinkingBlock.
// Full import block copied (unused=warnings); externally-called ones internal.

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import novex.android.ui.DropdownMenuItem
import com.openminis.app.ui.components.MinisMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun AssistantMessageActionRow(
    markdown: String,
    showMutations: Boolean,
    onShare: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    val hasMenuEntries = onShare != null || (showMutations && onDelete != null)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 2.dp),
    ) {
        if (markdown.isNotBlank()) {
            AssistantActionIcon(
                icon = novex.android.ui.NovexIcons.ContentCopy,
                contentDescription = "复制全文",
            ) {
                val clipboard = context.getSystemService(
                    android.content.Context.CLIPBOARD_SERVICE,
                ) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("message", markdown))
                android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        // Same T119 gate as the user bubble: retry/delete would mutate an
        // in-flight turn, so they vanish while any stream is running.
        if (showMutations && onRegenerate != null) {
            AssistantActionIcon(
                icon = novex.android.ui.NovexIcons.Refresh,
                contentDescription = "重新生成本轮",
                onClick = onRegenerate,
            )
        }
        if (hasMenuEntries) {
            Box {
                AssistantActionIcon(
                    icon = novex.android.ui.NovexIcons.MoreHoriz,
                    contentDescription = "更多操作",
                ) { showMenu = true }
                MinisMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    if (onShare != null) {
                        DropdownMenuItem(
                            text = { Text("分享到其他文游") },
                            onClick = { showMenu = false; onShare() },
                            leadingIcon = { Icon(novex.android.ui.NovexIcons.Share, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                    if (showMutations && onDelete != null) {
                        DropdownMenuItem(
                            text = { Text("从此处删除") },
                            onClick = { showMenu = false; onDelete() },
                            leadingIcon = { Icon(novex.android.ui.NovexIcons.Delete, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
            }
        }
    }
}

/** Mainstream chat client action-button idiom: 16dp ghost icon, 8dp tap halo. */
@Composable
internal fun AssistantActionIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(8.dp)
            .size(16.dp),
    )
}
