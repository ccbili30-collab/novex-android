package com.openminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.MemoryGlobalPrefs
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.SectionDesign
import novex.android.ui.AlertDialog
import novex.android.ui.NovexColors
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType

/**
 * 设置里的记忆文件管理：全局默认开关 + GLOBAL.md/日记列表，
 * 点行进全页编辑器。GLOBAL.md 不可删。
 */
@Composable
fun MemoryManagementScreen(
    memoryRepository: MemoryRepository,
    onBack: () -> Unit,
    onFileClick: (fileName: String, isGlobal: Boolean) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<MemoryRepository.MemoryFileInfo>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    // 新会话的默认记忆开关：与会话行上的 memoryEnabled 分库存放，
    // 这里切换不回溯改写已存在的会话。
    var globalEnabled by remember { mutableStateOf(MemoryGlobalPrefs.isGlobalEnabled(context)) }

    LaunchedEffect(Unit) { files = memoryRepository.listAllFiles() }

    SettingsScaffold(title = stringResource(R.string.memory_title), onBack = onBack) {
        SettingsSection(
            header = stringResource(R.string.settings_memory_global_header),
            footer = stringResource(R.string.settings_memory_global_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_memory_global_enabled_title),
                subtitle = stringResource(R.string.settings_memory_global_enabled_subtitle),
                checked = globalEnabled,
                onCheckedChange = {
                    globalEnabled = it
                    MemoryGlobalPrefs.setGlobalEnabled(context, it)
                },
                showDivider = false,
            )
        }
        Spacer(Modifier.height(16.dp))

        if (files.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.memory_empty_title), style = NovexType.PageTitle)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.memory_empty_description),
                    style = NovexType.Body,
                    color = NovexColors.SecondaryText,
                )
            }
        } else {
            SettingsSection(
                header = stringResource(R.string.memory_section_files),
                footer = stringResource(R.string.memory_section_footer),
            ) {
                files.forEachIndexed { i, file ->
                    MemoryFileLine(
                        file = file,
                        onClick = { onFileClick(file.name, file.isGlobal) },
                        onDelete = if (file.isGlobal) null else ({ pendingDelete = file.name }),
                    )
                    if (i < files.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = NovexColors.Divider,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    pendingDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.memory_delete_confirm_title, name)) },
            text = { Text(stringResource(R.string.memory_delete_confirm_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    memoryRepository.deleteFile(name)
                    files = memoryRepository.listAllFiles()
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.common_delete), color = NovexColors.Danger)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun MemoryFileLine(
    file: MemoryRepository.MemoryFileInfo,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(file.name, style = NovexType.ItemTitle)
                    if (file.fileSize.isNotBlank()) {
                        Text(
                            file.fileSize,
                            style = NovexType.Metadata,
                            color = NovexColors.TertiaryText,
                        )
                    }
                }
                Text(
                    file.modifiedDate,
                    style = NovexType.Metadata,
                    color = NovexColors.SecondaryText,
                )
            }
            if (file.preview.isNotBlank()) {
                Text(
                    file.preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = NovexType.Metadata,
                    color = NovexColors.SecondaryText,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // 日记可删（GLOBAL.md 传 null 不渲染垃圾桶）。
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    NovexIcons.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = NovexColors.Danger.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Icon(
            NovexIcons.KeyboardArrowRight,
            contentDescription = null,
            tint = NovexColors.TertiaryText,
            modifier = Modifier.size(20.dp),
        )
    }
}
