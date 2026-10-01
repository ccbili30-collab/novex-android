package com.openminis.app.ui.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.ui.components.MinisTextButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import novex.android.ui.NovexColors
import novex.android.ui.NovexDimensions
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType

/**
 * 会话记忆面板：列表态 + 三种详情态（自动注入文件 / memory_write 条目 /
 * memory_get 条目），详情态标题栏左槽换成返回箭头，右侧关闭始终关整个面板。
 * 编辑缓冲放在面板层，顶栏的保存按钮直接读它。
 */
@Composable
fun SessionMemorySheet(
    memoryRepository: MemoryRepository,
    toolRecords: List<MemoryToolRecord>,
    onDismiss: () -> Unit,
    onRevokeRecord: (MemoryToolRecord) -> MemoryRepository.EntryMutationResult,
    onSaveRecord: (MemoryToolRecord, String) -> MemoryRepository.EntryMutationResult,
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf<MemorySheetMode>(MemorySheetMode.List) }
    val autoItems = remember(memoryRepository) { autoInjectedItems(context, memoryRepository) }

    var isEditing by remember(mode) { mutableStateOf(false) }
    var editBuffer by remember(mode) { mutableStateOf("") }
    var savedToast by remember { mutableStateOf(false) }
    var revoking by remember { mutableStateOf(false) }
    var mutationResult by remember { mutableStateOf<MemoryRepository.EntryMutationResult?>(null) }

    LaunchedEffect(savedToast) {
        if (savedToast) {
            delay(1500)
            savedToast = false
        }
    }
    // 进入编辑态时把当前详情内容灌进缓冲。
    LaunchedEffect(mode, isEditing) {
        if (isEditing) {
            editBuffer = when (val m = mode) {
                is MemorySheetMode.AutoFile -> m.content
                is MemorySheetMode.Write -> m.record.writtenContent.orEmpty()
                else -> ""
            }
        }
    }

    val title = when (val m = mode) {
        MemorySheetMode.List -> stringResource(R.string.session_memory_title)
        is MemorySheetMode.AutoFile -> m.name
        is MemorySheetMode.Write -> m.record.title
        is MemorySheetMode.Get -> m.record.title
    }

    StandardChatSheet(
        title = title,
        onDismiss = onDismiss,
        leadingAction = if (mode != MemorySheetMode.List) {
            {
                IconButton(onClick = {
                    if (isEditing) isEditing = false else mode = MemorySheetMode.List
                }) {
                    Icon(NovexIcons.ArrowBack, stringResource(R.string.memory_action_back))
                }
            }
        } else null,
    ) {
        when (val m = mode) {
            MemorySheetMode.List -> MemoryListBody(
                autoItems = autoItems,
                records = toolRecords,
                onOpenAuto = { mode = MemorySheetMode.AutoFile(it.fileName, it.content, editable = true) },
                onOpenRecord = { rec ->
                    mode = if (rec.isWrite) MemorySheetMode.Write(rec) else MemorySheetMode.Get(rec)
                },
            )

            is MemorySheetMode.AutoFile -> Column(Modifier.fillMaxSize()) {
                MemoryDetailActions(
                    showEdit = m.editable && !isEditing,
                    showSave = m.editable && isEditing,
                    showRevoke = false,
                    onEdit = { isEditing = true; editBuffer = m.content },
                    onSave = {
                        runCatching { memoryRepository.saveFile(m.name, editBuffer) }
                            .onSuccess {
                                // saveFile 绕过 SoulStore.save()，SOUL.md 存后手动刷缓存
                                // 让聊天气泡上的名字保持同步。
                                if (m.name == "SOUL.md") {
                                    com.openminis.app.agent.SoulStore.refreshCache(context)
                                }
                                mode = MemorySheetMode.AutoFile(m.name, editBuffer, m.editable)
                                isEditing = false
                                savedToast = true
                            }
                    },
                    onRevoke = {},
                )
                MemoryFileViewerBody(
                    initialContent = m.content,
                    isEditing = isEditing,
                    editedContent = editBuffer,
                    onEditedContentChange = { editBuffer = it },
                    showSavedToast = savedToast,
                )
            }

            is MemorySheetMode.Write -> Column(Modifier.fillMaxSize()) {
                val mutable = m.record.writtenContent != null
                MemoryDetailActions(
                    showEdit = mutable && !isEditing,
                    showSave = mutable && isEditing,
                    showRevoke = mutable && !isEditing,
                    onEdit = { isEditing = true; editBuffer = m.record.writtenContent.orEmpty() },
                    onSave = {
                        when (val result = onSaveRecord(m.record, editBuffer)) {
                            is MemoryRepository.EntryMutationResult.Success -> {
                                // 就地更新展示的 record，后续撤销针对的是新内容。
                                mode = MemorySheetMode.Write(m.record.copy(writtenContent = editBuffer))
                                isEditing = false
                                savedToast = true
                            }
                            else -> mutationResult = result
                        }
                    },
                    onRevoke = { revoking = true },
                )
                MemoryWriteDetailBody(
                    record = m.record,
                    isEditing = isEditing,
                    editedContent = editBuffer,
                    onEditedContentChange = { editBuffer = it },
                    showSavedToast = savedToast,
                )
            }

            is MemorySheetMode.Get -> MemoryGetDetailBody(record = m.record)
        }
    }

    (mode as? MemorySheetMode.Write)?.takeIf { revoking }?.let { writeMode ->
        RevokeConfirmDialog(
            onConfirm = {
                revoking = false
                val result = onRevokeRecord(writeMode.record)
                mutationResult = result
                // 成功后 ViewModel 会把该条目移出 toolRecords；退回列表看新态。
                if (result is MemoryRepository.EntryMutationResult.Success) {
                    mode = MemorySheetMode.List
                }
            },
            onDismiss = { revoking = false },
        )
    }

    mutationResult?.let {
        MutationResultDialog(result = it, onDismiss = { mutationResult = null })
    }
}

// ── 详情态右上动作条 ────────────────────────────────────────────────────────

@Composable
private fun MemoryDetailActions(
    showEdit: Boolean,
    showSave: Boolean,
    showRevoke: Boolean,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onRevoke: () -> Unit,
) {
    if (!showEdit && !showSave && !showRevoke) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        if (showEdit) {
            IconButton(onClick = onEdit) {
                Icon(NovexIcons.Edit, stringResource(R.string.memory_action_edit))
            }
        }
        if (showSave) {
            MinisTextButton(onClick = onSave) { Text(stringResource(R.string.memory_action_save)) }
        }
        if (showRevoke) {
            IconButton(onClick = onRevoke) {
                Icon(NovexIcons.Undo, stringResource(R.string.memory_action_revoke), tint = NovexColors.Danger)
            }
        }
    }
}

// ── 列表态 ──────────────────────────────────────────────────────────────────

@Composable
private fun MemoryListBody(
    autoItems: List<AutoItem>,
    records: List<MemoryToolRecord>,
    onOpenAuto: (AutoItem) -> Unit,
    onOpenRecord: (MemoryToolRecord) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            MemorySection(
                label = stringResource(R.string.memory_section_auto_injected),
                footnote = stringResource(R.string.memory_section_auto_injected_footer),
            ) {
                autoItems.forEachIndexed { i, item ->
                    MemoryListRow(
                        title = item.name,
                        subtitle = item.detail,
                        showDivider = i < autoItems.lastIndex,
                        onClick = { onOpenAuto(item) },
                    )
                }
            }
        }
        if (records.isNotEmpty()) {
            item {
                MemorySection(
                    label = stringResource(R.string.memory_section_tool_activity),
                    footnote = stringResource(R.string.memory_section_tool_activity_footer),
                ) {
                    records.forEachIndexed { i, record ->
                        MemoryRecordRow(
                            record = record,
                            showDivider = i < records.lastIndex,
                            onClick = { onOpenRecord(record) },
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun MemorySection(
    label: String,
    footnote: String,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            label.uppercase(),
            style = NovexType.Metadata,
            color = NovexColors.TertiaryText,
            modifier = Modifier.padding(horizontal = NovexDimensions.PageHorizontal, vertical = 4.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = NovexDimensions.PageHorizontal)
                .clip(RoundedCornerShape(NovexDimensions.SectionRadius))
                .background(NovexColors.Surface),
        ) {
            content()
        }
        Text(
            footnote,
            style = NovexType.Metadata,
            color = NovexColors.TertiaryText,
            modifier = Modifier.padding(horizontal = NovexDimensions.PageHorizontal, vertical = 6.dp),
        )
    }
}

@Composable
private fun MemoryListRow(
    title: String,
    subtitle: String,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    Column(Modifier.clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                title,
                style = NovexType.ItemTitle,
                color = NovexColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = NovexType.Metadata,
                    color = NovexColors.SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showDivider) MemoryRowDivider()
    }
}

/** 工具日志行：标题 + 操作类型胶囊 + 等宽预览。 */
@Composable
private fun MemoryRecordRow(
    record: MemoryToolRecord,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    Column(Modifier.clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                record.title,
                style = NovexType.ItemTitle,
                color = NovexColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (record.isWrite) "memory_write" else "memory_get",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = NovexColors.Primary,
                modifier = Modifier
                    .background(NovexColors.PrimarySoft, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (record.preview.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    record.preview,
                    style = NovexType.Metadata.copy(fontFamily = FontFamily.Monospace),
                    color = NovexColors.SecondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showDivider) MemoryRowDivider()
    }
}

@Composable
private fun MemoryRowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(NovexDimensions.Hairline)
            .background(NovexColors.Divider),
    )
}

// ── 数据 ────────────────────────────────────────────────────────────────────

/** 本会话里一条 memory 工具调用记录（ChatViewModel 组装）。 */
data class MemoryToolRecord(
    val title: String,
    val isWrite: Boolean,
    val preview: String,
    val output: String,
    val writtenContent: String? = null,
    val keywords: String? = null,
)

/** 面板内导航态；详情态的返回箭头靠它弹回列表而不卸载面板。 */
private sealed class MemorySheetMode {
    data object List : MemorySheetMode()
    data class AutoFile(val name: String, val content: String, val editable: Boolean) : MemorySheetMode()
    data class Write(val record: MemoryToolRecord) : MemorySheetMode()
    data class Get(val record: MemoryToolRecord) : MemorySheetMode()
}

internal data class AutoItem(
    val name: String,
    val detail: String,
    /** 磁盘文件名（GLOBAL.md / 2026-04-26.md），点进去按它加载全文。 */
    val fileName: String,
    /** 打开面板时快照的全文，点开即看不等 IO。 */
    val content: String,
)

private fun autoInjectedItems(context: Context, repo: MemoryRepository): List<AutoItem> {
    fun fileItem(name: String, fileName: String, content: String): AutoItem {
        val lines = content.lines().size
        return AutoItem(
            name = name,
            detail = when {
                content.isBlank() -> context.getString(R.string.memory_file_empty)
                lines > 200 -> "${minOf(lines, 200)}/$lines lines injected"
                else -> "$lines lines (full)"
            },
            fileName = fileName,
            content = content,
        )
    }

    val items = mutableListOf<AutoItem>()
    // SOUL.md：身份/人格文件，随系统提示注入，与 GLOBAL.md 一样可见可编。
    items += fileItem("SOUL.md", "SOUL.md", repo.readFile("SOUL.md"))
    items += fileItem("GLOBAL.md", "GLOBAL.md", repo.loadGlobalMd())

    val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val today = dateFmt.format(Date())
    val yesterday = dateFmt.format(Date(System.currentTimeMillis() - 86400_000L))
    for (dateStr in listOf(today, yesterday)) {
        val fileName = "$dateStr.md"
        val content = repo.readFile(fileName)
        if (content.isBlank()) continue
        val label = context.getString(
            if (dateStr == today) R.string.time_today else R.string.time_yesterday,
        )
        items += fileItem("$label — $fileName", fileName, content)
    }
    return items
}
