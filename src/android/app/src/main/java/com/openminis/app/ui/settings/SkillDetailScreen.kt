package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import novex.android.ui.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.openminis.app.ui.components.DialogTextField
import androidx.compose.material3.Text
import novex.android.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.ui.markdown.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import novex.android.ui.NovexIcons
import novex.android.ui.Scaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 详情屏三条更新动作的瞬时状态（T154）。渲染在按钮组下方的内联状态行，
 * 终态（Done/Failed）约 3 秒后自动清回 Idle。
 */
private sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data class InProgress(val label: String) : UpdateStatus
    data object Done : UpdateStatus
    data class Failed(val reason: String) : UpdateStatus
}

/** 动作行左侧图标配色：蓝=导航/远端动作，绿=本地文件操作（同主设置页）。 */
private val ActionBlue = Color(0xFF007AFF)
private val ActionGreen = Color(0xFF34C759)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillDetailScreen(
    skillId: String,
    skillRepository: SkillRepository,
    onBack: () -> Unit,
    onFileClick: (String, String) -> Unit = { _, _ -> },
) {
    val skills by skillRepository.skills.collectAsState()
    val skill = skills.find { it.id == skillId }
    var deleted by remember { mutableStateOf(false) }

    if (skill == null && !deleted) { onBack(); return }
    if (skill == null) return // 已删除，停止渲染

    var showDeleteDialog by remember { mutableStateOf(false) }
    // T155：名称编辑收敛到模态对话框，避免行内输入框挤动布局。
    var showEditNameDialog by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(skill.name) }

    // T154：三条更新动作共用 updateStatus，就地反馈进行中/成功/失败，
    // 终态 ~3 秒后自清，不留常驻横幅。
    var updateStatus by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Idle) }
    // [T-android-skill-export] 导出压缩包在 IO 线程构建，防抖双击连发。
    var isExporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(updateStatus) {
        if (updateStatus is UpdateStatus.Done || updateStatus is UpdateStatus.Failed) {
            delay(3000)
            updateStatus = UpdateStatus.Idle
        }
    }

    val fileUpdateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        updateStatus = UpdateStatus.InProgress(context.getString(R.string.skill_detail_status_reading))
        try {
            val content = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
            if (content.isNullOrBlank()) {
                updateStatus = UpdateStatus.Failed(context.getString(R.string.skill_detail_error_empty))
            } else {
                // 重新解析传入的 SKILL.md，把 name/description/body 推回既有
                // 记录，不新建条目。
                val parsed = skillRepository.parseSkillMdPublic(content)
                if (parsed == null) {
                    updateStatus = UpdateStatus.Failed(context.getString(R.string.skill_detail_error_invalid))
                } else {
                    val ok = skillRepository.update(
                        id = skill.id,
                        name = parsed.name,
                        description = parsed.description,
                        body = parsed.body,
                    )
                    updateStatus = if (ok) UpdateStatus.Done
                        else UpdateStatus.Failed(context.getString(R.string.skill_detail_error_update_failed))
                }
            }
        } catch (e: Exception) {
            updateStatus = UpdateStatus.Failed("Failed to read file: ${e.message ?: "unknown"}")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(skill.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(NovexIcons.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // [T-android-skill-export] 导出 zip 交系统分享面板——zip
                    // 导入的回程搭档，对齐 iOS 顶栏分享按钮。
                    IconButton(
                        enabled = !isExporting,
                        onClick = {
                            isExporting = true
                            scope.launch {
                                val zip = withContext(Dispatchers.IO) {
                                    skillRepository.exportSkillToZip(skill.id)
                                }
                                isExporting = false
                                if (zip == null) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.skill_export_failed),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    shareSkillZip(context, zip)
                                }
                            }
                        },
                    ) {
                        Icon(
                            NovexIcons.IosShare,
                            contentDescription = stringResource(R.string.skill_export_share),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            DetailSection {
                MetaRow(
                    label = stringResource(R.string.skill_detail_label_name),
                    value = skill.name,
                ) {
                    Icon(
                        NovexIcons.Edit, contentDescription = "Edit name",
                        modifier = Modifier.size(14.dp).clickable {
                            editName = skill.name
                            showEditNameDialog = true
                        },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
                DetailDivider()
                MetaRow(
                    label = stringResource(R.string.skill_detail_label_version),
                    value = skill.version,
                )
                DetailDivider()
                MetaRow(
                    label = stringResource(R.string.skill_detail_label_modified),
                    value = relativeTime(skill.updatedAt),
                )
                DetailDivider()
                MetaRow(
                    label = stringResource(R.string.skill_detail_label_source),
                    value = stringResource(
                        when (skill.importSource) {
                            SkillRepository.ImportSource.URL -> R.string.skill_import_tab_url
                            SkillRepository.ImportSource.FILE -> R.string.skill_detail_source_file
                            SkillRepository.ImportSource.BUNDLED -> R.string.skill_detail_source_bundled
                            SkillRepository.ImportSource.SESSION -> R.string.skill_detail_source_session
                        }
                    ),
                )
            }

            UpdateActionsSection(
                skill = skill,
                updateStatus = updateStatus,
                onStatus = { updateStatus = it },
                onPickFile = { fileUpdateLauncher.launch("*/*") },
                skillRepository = skillRepository,
            )

            // ── 简介 ──
            val previewLines = skill.body.lineSequence().filter { it.isNotBlank() }.take(5).toList()
            val hasMore = skill.body.lines().count { it.isNotBlank() } > 5
            val preview = previewLines.joinToString("\n") + if (hasMore) "\n…" else ""
            if (preview.isNotEmpty()) {
                DetailSection(header = "Description") {
                    Column(modifier = Modifier.padding(14.dp)) {
                        MarkdownText(
                            markdown = preview,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // ── 文件 ──
            // skill 记录一变（rescan/update 推 updatedAt）就重列，新解压的
            // scripts 不离屏即现。
            val skillFiles = remember(skill.id, skill.updatedAt) {
                skillRepository.listSkillFiles(skill.id)
                    .ifEmpty { listOf("SKILL.md") }
            }
            DetailSection(header = "Files") {
                skillFiles.forEachIndexed { index, relativePath ->
                    if (index > 0) DetailDivider()
                    DetailRow(onClick = { onFileClick(skill.id, relativePath) }) {
                        Icon(
                            NovexIcons.Description, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            relativePath,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            NovexIcons.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            MinisButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.skill_detail_delete))
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete ${skill.name}?") },
            text = { Text(stringResource(R.string.skill_delete_confirm_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    deleted = true
                    skillRepository.delete(skill.id)
                    showDeleteDialog = false
                    onBack()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // T155：改名对话框。清空或与原名相同都禁用保存——两者对仓库都是空
    // 操作，去掉入口保持 UX 诚实。
    if (showEditNameDialog) {
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.skill_detail_edit_name_title)) },
            text = {
                DialogTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                val trimmed = editName.trim()
                val canSave = trimmed.isNotEmpty() && trimmed != skill.name
                MinisTextButton(
                    onClick = {
                        if (canSave) skillRepository.update(skill.id, name = trimmed)
                        showEditNameDialog = false
                    },
                    enabled = canSave,
                ) { Text(stringResource(R.string.skill_file_save)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showEditNameDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

// ── 元信息行 ────────────────────────────────────────────────────────────────

@Composable
private fun MetaRow(
    label: String,
    value: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    DetailRow {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            trailing()
        }
    }
}

// ── 更新动作区 ──────────────────────────────────────────────────────────────

@Composable
private fun UpdateActionsSection(
    skill: SkillRepository.Skill,
    updateStatus: UpdateStatus,
    onStatus: (UpdateStatus) -> Unit,
    onPickFile: () -> Unit,
    skillRepository: SkillRepository,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isBusy = updateStatus is UpdateStatus.InProgress

    DetailSection {
        if (skill.importSource == SkillRepository.ImportSource.URL) {
            ActionRow(
                icon = NovexIcons.Refresh,
                iconColor = ActionBlue,
                label = stringResource(R.string.skill_detail_update_url),
                enabled = !isBusy,
                trailing = {
                    Text(
                        relativeTime(skill.updatedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ) {
                onStatus(UpdateStatus.InProgress(context.getString(R.string.skill_detail_status_downloading)))
                scope.launch {
                    onStatus(
                        when (val r = skillRepository.updateFromURL(skill.id)) {
                            is SkillRepository.UpdateResult.Success -> UpdateStatus.Done
                            // T152：SKILL.md 已更新但兄弟文件递归失败（如 GitHub
                            // API 403 限流）。露出真实原因，别只给绿勾。
                            is SkillRepository.UpdateResult.PartialSuccess ->
                                UpdateStatus.Failed("Updated, but: ${r.reason}")
                            is SkillRepository.UpdateResult.Failure -> UpdateStatus.Failed(r.reason)
                        }
                    )
                }
            }
            DetailDivider()
        }

        ActionRow(
            icon = NovexIcons.Description,
            iconColor = ActionBlue,
            label = stringResource(R.string.skill_detail_update_file),
            enabled = !isBusy,
            onClick = onPickFile,
        )
        DetailDivider()

        ActionRow(
            icon = NovexIcons.Refresh,
            iconColor = ActionGreen,
            label = stringResource(R.string.skill_detail_rescan),
            enabled = !isBusy,
        ) {
            onStatus(UpdateStatus.InProgress(context.getString(R.string.skill_detail_status_rescanning)))
            val refreshed = skillRepository.rescanFromDisk(skill.id)
            onStatus(
                if (refreshed != null) UpdateStatus.Done
                else UpdateStatus.Failed(context.getString(R.string.skill_detail_error_missing))
            )
        }

        // 内联状态行——有内容时才渲染；终态由外层 LaunchedEffect 清回 Idle。
        when (val s = updateStatus) {
            is UpdateStatus.InProgress -> {
                DetailDivider()
                DetailRow {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(s.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is UpdateStatus.Done -> {
                DetailDivider()
                DetailRow {
                    Icon(NovexIcons.CheckCircle, contentDescription = null, tint = ActionGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.skill_detail_updated), style = MaterialTheme.typography.bodySmall, color = ActionGreen)
                }
            }
            is UpdateStatus.Failed -> {
                DetailDivider()
                DetailRow {
                    Icon(NovexIcons.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(s.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            UpdateStatus.Idle -> Unit
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    iconColor: Color,
    label: String,
    enabled: Boolean,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    DetailRow(onClick = { if (enabled) onClick() }) {
        SettingsActionIcon(icon, iconColor)
        Spacer(Modifier.width(14.dp))
        Text(label, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

// ── 详情区骨架 ──────────────────────────────────────────────────────────────

/** iOS List 风格的分组卡片区。 */
@Composable
private fun DetailSection(
    header: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        if (header != null) {
            Text(
                text = header.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            content()
        }
    }
}

@Composable
private fun DetailRow(
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun DetailDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

/**
 * [T-android-skill-export] 把导出的 skill zip 交给系统分享面板。文件已在
 * `cacheDir/share/`——`file_provider_paths.xml` 声明过的根；声明外的文件
 * 会让 getUriForFile 直接抛，用户只看到失败。
 *
 * zip 故意不在这里删：接收方（存到文件、Drive、聊天应用）在面板关闭后
 * 异步读 URI，关面板即删会撞车——iOS 文档记过同一个坑。SkillRepository
 * 在每次新导出前清扫 24h 前的旧包。
 */
private fun shareSkillZip(context: android.content.Context, zip: java.io.File) {
    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", zip,
        )
    } catch (t: Throwable) {
        Toast.makeText(context, context.getString(R.string.skill_export_failed), Toast.LENGTH_SHORT).show()
        return
    }
    val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, zip.name)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = android.content.Intent.createChooser(
        sendIntent,
        context.getString(R.string.skill_export_share),
    ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { context.startActivity(chooser) }.onFailure {
        Toast.makeText(context, context.getString(R.string.skill_export_failed), Toast.LENGTH_SHORT).show()
    }
}

/**
 * 动作行图标：30dp 圆底 + 16dp 白色字形 + 14dp 间距——与主设置页行图标
 * 同规格，别让两个面长成两种语言。对齐 iOS SettingsActionIcon。
 */
@Composable
private fun SettingsActionIcon(icon: ImageVector, iconColor: Color) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(color = iconColor, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 毫秒转相对时间（对齐 iOS）。 */
private fun relativeTime(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours hr ago"
        days < 30 -> "$days days ago"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
    }
}
