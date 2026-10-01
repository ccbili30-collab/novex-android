package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.ui.components.MinisTextButton
import novex.android.ui.AlertDialog
import novex.android.ui.NovexColors
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType

/**
 * 会话记忆面板的详情主体（无状态；编辑缓冲和保存按钮都由父级
 * SessionMemorySheet 持有）。查看态等宽可选文本；编辑态 BasicTextField；
 * 保存后底部弹一个"已保存"胶囊。
 */

private val MonoBody @Composable get() = NovexType.Metadata.copy(
    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 18.sp,
)

/** 查看态：整屏可滚动的等宽文本。 */
@Composable
private fun MemoryTextPane(text: String, modifier: Modifier = Modifier) {
    SelectionContainer(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text.ifEmpty { "(empty)" }, style = MonoBody, color = NovexColors.Text)
    }
}

/** 编辑态：等宽 BasicTextField，每次击键回写给父级缓冲。 */
@Composable
private fun MemoryEditPane(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        textStyle = MonoBody.copy(color = NovexColors.Text),
        cursorBrush = SolidColor(NovexColors.Primary),
    )
}

@Composable
private fun SavedToast(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(bottom = 12.dp)
            .background(NovexColors.Text, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            stringResource(R.string.memory_save_toast),
            style = NovexType.Metadata.copy(fontWeight = FontWeight.Medium),
            color = NovexColors.Background,
        )
    }
}

@Composable
private fun DetailFrame(showSavedToast: Boolean, body: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        body()
        if (showSavedToast) SavedToast(Modifier.align(Alignment.BottomCenter))
    }
}

/** GLOBAL.md / 日记类记忆文件：只读或整块编辑。 */
@Composable
fun MemoryFileViewerBody(
    initialContent: String,
    isEditing: Boolean,
    editedContent: String,
    onEditedContentChange: (String) -> Unit,
    showSavedToast: Boolean,
) {
    DetailFrame(showSavedToast) {
        if (isEditing) MemoryEditPane(editedContent, onEditedContentChange)
        else MemoryTextPane(initialContent)
    }
}

/** memory_write 日志条目：写入内容 + 工具回执两段；编辑态只编辑写入内容。 */
@Composable
fun MemoryWriteDetailBody(
    record: MemoryToolRecord,
    isEditing: Boolean,
    editedContent: String,
    onEditedContentChange: (String) -> Unit,
    showSavedToast: Boolean,
) {
    DetailFrame(showSavedToast) {
        if (isEditing) {
            MemoryEditPane(editedContent, onEditedContentChange)
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                record.writtenContent?.takeIf { it.isNotEmpty() }?.let { written ->
                    MemorySectionLabel(stringResource(R.string.memory_section_written_content))
                    SelectionContainer {
                        Text(written, style = MonoBody, color = NovexColors.Text)
                    }
                }
                MemorySectionLabel(stringResource(R.string.memory_section_tool_result))
                SelectionContainer {
                    Text(record.output, style = MonoBody, color = NovexColors.SecondaryText)
                }
            }
        }
    }
}

/** memory_get 日志条目：关键词行 + 结果文本。 */
@Composable
fun MemoryGetDetailBody(record: MemoryToolRecord) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        record.keywords?.takeIf { it.isNotEmpty() }?.let { keywords ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    NovexIcons.Search,
                    contentDescription = null,
                    tint = NovexColors.SecondaryText,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    keywords,
                    style = NovexType.ItemTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = NovexColors.Text,
                )
            }
        }
        SelectionContainer {
            Text(record.output, style = MonoBody, color = NovexColors.Text)
        }
    }
}

@Composable
private fun MemorySectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = NovexType.Metadata,
        color = NovexColors.SecondaryText,
        letterSpacing = 0.5.sp,
    )
}

/** 撤销一条 memory_write 的确认框。 */
@Composable
fun RevokeConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_revoke_dialog_title)) },
        text = { Text(stringResource(R.string.memory_revoke_dialog_message)) },
        confirmButton = {
            MinisTextButton(onClick = onConfirm) {
                Text(stringResource(R.string.memory_action_revoke), color = NovexColors.Danger)
            }
        },
        dismissButton = {
            MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** 撤销结果反馈（成功 / 找不到 / IO 错误）。 */
@Composable
fun MutationResultDialog(
    result: MemoryRepository.EntryMutationResult,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val msg = when (result) {
        is MemoryRepository.EntryMutationResult.Success ->
            ctx.getString(R.string.memory_revoke_result_removed, result.dateStr)
        is MemoryRepository.EntryMutationResult.NotFound ->
            ctx.getString(R.string.memory_revoke_result_not_found)
        is MemoryRepository.EntryMutationResult.IOError ->
            ctx.getString(R.string.memory_revoke_result_io_error, result.message)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(msg) },
        confirmButton = { MinisTextButton(onClick = onDismiss) { Text("OK") } },
    )
}
