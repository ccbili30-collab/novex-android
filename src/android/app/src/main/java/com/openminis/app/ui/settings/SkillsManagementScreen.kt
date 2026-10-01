package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import novex.android.ui.AlertDialog
import novex.android.ui.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import novex.android.ui.ModalBottomSheet
import com.openminis.app.ui.components.DialogTextField
import novex.android.ui.NovexCheckToggle
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.repository.SkillRepository
import kotlinx.coroutines.launch
import novex.android.ui.NovexIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsManagementScreen(
    skillRepository: SkillRepository,
    onBack: () -> Unit,
    onSkillClick: (String) -> Unit = {},
) {
    val skills by skillRepository.skills.collectAsState()
    var showImportSheet by remember { mutableStateOf(false) }
    var deleteSkillId by remember { mutableStateOf<String?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current

    // 排序偏好走 SharedPreferences（同文件浏览器），默认按名称升序。
    // 对齐 iOS skillsList.sortKey/sortAscending。
    val sortPrefs = remember {
        context.getSharedPreferences("skills_prefs", android.content.Context.MODE_PRIVATE)
    }
    var sortByModified by remember { mutableStateOf(sortPrefs.getBoolean("sort_by_modified", false)) }
    var sortAscending by remember { mutableStateOf(sortPrefs.getBoolean("sort_ascending", true)) }

    val visibleSkills = remember(skills, searchQuery, sortByModified, sortAscending) {
        val q = searchQuery.trim().lowercase()
        val matched = if (q.isEmpty()) skills else skills.filter {
            it.name.lowercase().contains(q) || it.description.lowercase().contains(q)
        }
        val sorted = if (sortByModified) matched.sortedBy { it.updatedAt }
            else matched.sortedBy { it.name.lowercase() }
        if (sortAscending) sorted else sorted.reversed()
    }

    // T-skillscan: 进屏即重扫——会话里 agent 用 git clone 装到 skillsDir
    // 的技能绕过了 file_write 钩子，开屏时就要捞到，不用等进程重启。
    LaunchedEffect(Unit) {
        skillRepository.reloadFromDisk()
    }

    SettingsScaffold(
        title = stringResource(R.string.skill_title),
        onBack = onBack,
        actions = {
            SkillSortMenu(
                sortByModified = sortByModified,
                sortAscending = sortAscending,
                onSortByModified = {
                    sortByModified = it
                    sortPrefs.edit().putBoolean("sort_by_modified", it).apply()
                },
                onToggleDirection = {
                    sortAscending = !sortAscending
                    sortPrefs.edit().putBoolean("sort_ascending", sortAscending).apply()
                },
            )
            IconButton(onClick = { showAddMenu = true }) {
                Icon(NovexIcons.Add, contentDescription = stringResource(R.string.skill_add))
            }
        },
    ) {
        if (skills.isNotEmpty()) {
            DialogTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = stringResource(R.string.skills_search_placeholder),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp),
            )
        }

        SettingsSection(
            header = stringResource(R.string.skill_section_installed),
            footer = stringResource(R.string.skill_section_footer),
        ) {
            when {
                skills.isEmpty() -> SkillsEmptyState()
                visibleSkills.isEmpty() -> Text(
                    text = stringResource(R.string.skills_search_no_match, searchQuery),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                )
                else -> visibleSkills.forEachIndexed { index, skill ->
                    SkillRow(
                        skill = skill,
                        showDivider = index < visibleSkills.size - 1,
                        onClick = { onSkillClick(skill.id) },
                        onToggle = { skillRepository.setEnabled(skill.id, it) },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showAddMenu) {
        ModalBottomSheet(onDismissRequest = { showAddMenu = false }) {
            Column(Modifier.padding(bottom = 32.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAddMenu = false
                            showImportSheet = true
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(NovexIcons.Description, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(
                        stringResource(R.string.skill_import_modal_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }

    if (showImportSheet) {
        SkillImportSheet(
            skillRepository = skillRepository,
            onDismiss = { showImportSheet = false },
        )
    }

    deleteSkillId?.let { id ->
        val skill = skills.find { it.id == id }
        AlertDialog(
            onDismissRequest = { deleteSkillId = null },
            title = { Text("Delete ${skill?.name ?: "skill"}?") },
            text = { Text(stringResource(R.string.skill_delete_confirm_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    skillRepository.delete(id)
                    deleteSkillId = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                MinisTextButton(onClick = { deleteSkillId = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

// ── 排序菜单 ────────────────────────────────────────────────────────────────

@Composable
private fun SkillSortMenu(
    sortByModified: Boolean,
    sortAscending: Boolean,
    onSortByModified: (Boolean) -> Unit,
    onToggleDirection: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                NovexIcons.Sort,
                contentDescription = stringResource(R.string.filebrowser_sort_by),
            )
        }
        com.openminis.app.ui.components.MinisMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SortKeyEntry(
                label = stringResource(R.string.filebrowser_sort_name),
                checked = !sortByModified,
            ) {
                onSortByModified(false)
                expanded = false
            }
            SortKeyEntry(
                label = stringResource(R.string.filebrowser_sort_modified),
                checked = sortByModified,
            ) {
                onSortByModified(true)
                expanded = false
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(stringResource(
                        if (sortAscending) R.string.filebrowser_sort_ascending
                        else R.string.filebrowser_sort_descending
                    ))
                },
                leadingIcon = {
                    Icon(
                        if (sortAscending) NovexIcons.ArrowUpward else NovexIcons.ArrowDownward,
                        contentDescription = null,
                    )
                },
                onClick = {
                    onToggleDirection()
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun SortKeyEntry(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            if (checked) Icon(NovexIcons.Check, contentDescription = null)
            else Spacer(Modifier.size(24.dp))
        },
        onClick = onClick,
    )
}

// ── 空态与行 ────────────────────────────────────────────────────────────────

@Composable
private fun SkillsEmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            NovexIcons.Description,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        )
        Text(
            stringResource(R.string.skill_empty_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.skill_empty_action),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SkillRow(
    skill: SkillRepository.Skill,
    showDivider: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val (badgeIcon, badgeColor) = sourceIconAndColor(skill.importSource)
    SettingsRow(
        title = skill.name,
        subtitle = skill.description.takeIf { it.isNotEmpty() }?.let { stripMarkdown(it) },
        showChevron = true,
        showDivider = showDivider,
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    badgeIcon,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                NovexCheckToggle(checked = skill.isEnabled, onCheckedChange = onToggle)
            }
        },
    )
}

/**
 * 把 Markdown 语法剥成单行纯文本，供副标题等紧凑面预览。处理围栏代码、
 * 行内码、链接、标题、引用、粗斜体，剩余空白压成单空格；去头尾、截 120 字。
 */
private fun stripMarkdown(text: String): String = text
    .replace(Regex("```[\\s\\S]*?```"), "")
    .replace(Regex("`([^`]+)`"), "$1")
    .replace(Regex("!?\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
    .replace(Regex("^#+ ", RegexOption.MULTILINE), "")
    .replace(Regex("^> ", RegexOption.MULTILINE), "")
    .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
    .replace(Regex("__([^_]+)__"), "$1")
    .replace(Regex("\\*([^*]+)\\*"), "$1")
    .replace(Regex("_([^_]+)_"), "$1")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(120)

// ─── 导入 Sheet（URL / 粘贴 / 文件 三模式）─────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillImportSheet(
    skillRepository: SkillRepository,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var urlText by remember { mutableStateOf("") }
    var pasteContent by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // [T-android-skill-import-zip] 文件选择器：读裸字节按魔数分叉。
    // 旧实现把 InputStream 直接 bufferedReader().readText()，.zip/.skill
    // 二进制被当 UTF-8 读成乱码，报 "Invalid SKILL.md content"。技能包
    // （scripts/references/assets）是 iOS 侧的常态，这里对齐
    // SkillsManagementView.documentPicker + SkillStore.importFromArchive。
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                errorText = "Failed to read file"
                return@rememberLauncherForActivityResult
            }
            if (looksLikeZip(bytes)) {
                val result = skillRepository.importFromArchive(
                    java.io.ByteArrayInputStream(bytes),
                )
                if (result != null) onDismiss()
                else errorText = "Invalid skill archive — no SKILL.md found at the root or one directory deep"
            } else {
                val result = skillRepository.importFromContent(
                    String(bytes, Charsets.UTF_8), SkillRepository.ImportSource.FILE)
                if (result != null) onDismiss()
                else errorText = "Invalid SKILL.md content"
            }
        } catch (e: Exception) {
            errorText = "Failed to read file: ${e.message}"
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.skill_import_modal_title),
                style = MaterialTheme.typography.titleMedium,
            )

            TabRow(selectedTabIndex = selectedTab) {
                ImportTab.entries.forEachIndexed { idx, tab ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx; errorText = null },
                    ) {
                        Text(
                            stringResource(
                                when (tab) {
                                    ImportTab.URL -> R.string.skill_import_tab_url
                                    ImportTab.PASTE -> R.string.skill_import_tab_paste
                                    ImportTab.FILE -> R.string.skill_import_tab_file
                                }
                            ),
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            Text(
                stringResource(
                    when (ImportTab.entries[selectedTab]) {
                        ImportTab.URL -> R.string.skill_import_url_instruction
                        ImportTab.PASTE -> R.string.skill_import_paste_instruction
                        ImportTab.FILE -> R.string.skill_import_file_instruction
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (ImportTab.entries[selectedTab]) {
                ImportTab.URL -> {
                    Spacer(Modifier.height(6.dp))
                    DialogTextField(
                        value = urlText,
                        onValueChange = { urlText = it; errorText = null },
                        placeholder = stringResource(R.string.skill_import_url_placeholder),
                        singleLine = true,
                        isError = errorText != null,
                    )
                }
                ImportTab.PASTE -> {
                    Spacer(Modifier.height(6.dp))
                    DialogTextField(
                        value = pasteContent,
                        onValueChange = { pasteContent = it; errorText = null },
                        modifier = Modifier.height(200.dp),
                        singleLine = false,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        isError = errorText != null,
                    )
                }
                ImportTab.FILE -> {
                    MinisTextButton(
                        onClick = { fileLauncher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.skill_import_file_button)) }
                }
            }

            errorText?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (ImportTab.entries[selectedTab] != ImportTab.FILE) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MinisTextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    MinisTextButton(
                        onClick = {
                            when (ImportTab.entries[selectedTab]) {
                                ImportTab.URL -> {
                                    if (urlText.isBlank()) {
                                        errorText = context.getString(R.string.skill_import_error_no_url)
                                        return@MinisTextButton
                                    }
                                    isLoading = true
                                    scope.launch {
                                        try {
                                            // importFromGitHub 抓 SKILL.md 并经
                                            // GitHub Contents API 递归兄弟文件
                                            // （scripts/references 才会落盘）。
                                            val result = skillRepository.importFromGitHub(urlText.trim())
                                            if (result != null) onDismiss()
                                            else errorText = context.getString(R.string.skill_import_error_invalid)
                                        } catch (e: Exception) {
                                            errorText = "Error: ${e.message}"
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                }
                                ImportTab.PASTE -> {
                                    val result = skillRepository.importFromContent(pasteContent)
                                    if (result != null) onDismiss()
                                    else errorText = context.getString(R.string.skill_import_error_format)
                                }
                                ImportTab.FILE -> Unit
                            }
                        },
                        enabled = when (ImportTab.entries[selectedTab]) {
                            ImportTab.URL -> urlText.isNotBlank() && !isLoading
                            ImportTab.PASTE -> pasteContent.isNotBlank()
                            ImportTab.FILE -> false
                        },
                    ) {
                        Text(
                            if (isLoading) stringResource(R.string.skill_import_in_progress)
                            else stringResource(R.string.skill_import_submit)
                        )
                    }
                }
            }
        }
    }
}

private enum class ImportTab { URL, PASTE, FILE }

/** PK\x03\x04 标准头 / PK\x05\x06 空档 EOCD / PK\x07\x08 分卷——三种都收，
 *  非主流 zip 工具的分卷导出不会掉进文本路径。URI 可能丢扩展名（部分
 *  DocumentsProvider 会剥），魔数嗅探比看后缀可靠。 */
private fun looksLikeZip(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
        bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
        (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())
