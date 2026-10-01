package com.openminis.app.ui.sandbox

import com.openminis.app.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalContext
import novex.android.ui.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import novex.android.ui.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import novex.android.ui.Scaffold
import androidx.compose.material3.Text
import novex.android.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.components.MinisTextButton
import novex.android.ui.NovexIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    viewModel: FileBrowserViewModel,
    onBack: () -> Unit,
    onPreviewFile: (FileItem) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var deleteTarget by remember { mutableStateOf<FileItem?>(null) }

    // 进入子目录后，顶栏返回键和系统返回手势都先弹一级目录。
    // ViewModel.goBack() 在已经回到入口目录时返回 false（T145），此时
    // 直接退出本屏而不是继续往 rootfs 根爬，对齐 iOS FileBrowserView。
    // canGoBack 预检让回到入口后返回键变成纯粹的「关闭」。
    val handleBack = {
        if (!state.canGoBack || !viewModel.goBack()) onBack()
    }
    BackHandler(enabled = true, onBack = handleBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.filebrowser_title)) },
                navigationIcon = {
                    IconButton(onClick = handleBack) {
                        Icon(NovexIcons.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // T-hidden-files a3e7f1d0: 尾栏收成单个 ⋯ 菜单。
                    SortAndDisplayMenu(
                        state = state,
                        onSelectKey = { viewModel.setSort(key = it) },
                        onToggleDirection = { viewModel.setSort(ascending = !state.sortAscending) },
                        onToggleFoldersFirst = { viewModel.setSort(foldersFirst = !state.foldersFirst) },
                        onToggleShowHidden = { viewModel.setShowHidden(!state.showHidden) },
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PathBreadcrumb(
                components = state.pathComponents,
                onNavigate = viewModel::navigateToPathComponent,
            )
            HorizontalDivider()

            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.isEmpty -> EmptyFolderPlaceholder()
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(state.items, key = { it.file.absolutePath }) { item ->
                        FileRow(
                            item = item,
                            currentLinuxPath = state.currentLinuxPath,
                            onClick = {
                                if (item.isDirectory) viewModel.navigateTo(item) else onPreviewFile(item)
                            },
                            onDelete = { deleteTarget = item },
                        )
                        HorizontalDivider(Modifier.padding(start = 56.dp))
                    }
                }
            }
        }
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.filebrowser_delete_title, item.name)) },
            text = { Text(stringResource(R.string.filebrowser_delete_message)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    viewModel.deleteItem(item)
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    state.errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text(stringResource(R.string.filebrowser_error_title)) },
            text = { Text(msg) },
            confirmButton = {
                MinisTextButton(onClick = { viewModel.dismissError() }) {
                    Text("OK")  // OK is locale-neutral
                }
            },
        )
    }
}

@Composable
private fun EmptyFolderPlaceholder() {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                NovexIcons.Folder,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.filebrowser_empty_folder),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun PathBreadcrumb(
    components: List<String>,
    onNavigate: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        components.forEachIndexed { index, component ->
            Text(
                text = component,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onNavigate(index) },
            )
            if (index < components.lastIndex) {
                Icon(
                    NovexIcons.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: FileItem,
    currentLinuxPath: String?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember(item.file.absolutePath) { mutableStateOf(false) }

    // [T-android-file-context-copy-abs-path] 该文件的 Linux 绝对路径 =
    // 当前目录 linux 路径 + 文件名；非 bind-mount 浏览器回退宿主绝对路径。
    val copyablePath = currentLinuxPath?.let { "${it.trimEnd('/')}/${item.name}" }
        ?: item.file.absolutePath

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = fileKindIcon(item),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (item.isDirectory)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (item.isSymlink) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "link",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                // 副行对齐 iOS「size · mtime」：目录不显示大小，stat 为 0
                // 不显示时间。
                val mtime = item.formattedDate
                val parts = buildList {
                    if (!item.isDirectory) add(item.formattedSize)
                    if (mtime.isNotEmpty()) add(mtime)
                }
                if (parts.isNotEmpty()) {
                    Text(
                        text = parts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (item.isDirectory) {
                Icon(
                    NovexIcons.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        NovexIcons.Delete,
                        contentDescription = stringResource(R.string.delete),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val context = LocalContext.current
        com.openminis.app.ui.components.MinisMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.filebrowser_copy_abs_path)) },
                leadingIcon = { Icon(NovexIcons.ContentCopy, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("path", copyablePath))
                    // Android 13+ 自带剪贴板确认条；Toast 兼容旧版本并给明确反馈。
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.filebrowser_copy_abs_path_toast),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }
    }
}

@Composable
private fun SortAndDisplayMenu(
    state: FileBrowserUiState,
    onSelectKey: (FileSortKey) -> Unit,
    onToggleDirection: () -> Unit,
    onToggleFoldersFirst: () -> Unit,
    onToggleShowHidden: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(NovexIcons.MoreVert, contentDescription = stringResource(R.string.filebrowser_more_action))
        }
        com.openminis.app.ui.components.MinisMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            // 排序键在最上——最高频操作离拇指最近。
            for (key in FileSortKey.entries) {
                CheckableMenuEntry(
                    label = stringResource(sortKeyLabel(key)),
                    checked = key == state.sortKey,
                ) {
                    onSelectKey(key)
                    expanded = false
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(stringResource(
                        if (state.sortAscending) R.string.filebrowser_sort_ascending
                        else R.string.filebrowser_sort_descending
                    ))
                },
                leadingIcon = {
                    Icon(
                        if (state.sortAscending) NovexIcons.ArrowUpward else NovexIcons.ArrowDownward,
                        contentDescription = null,
                    )
                },
                onClick = {
                    onToggleDirection()
                    expanded = false
                },
            )
            CheckableMenuEntry(
                label = stringResource(R.string.filebrowser_sort_folders_first),
                checked = state.foldersFirst,
            ) {
                onToggleFoldersFirst()
                expanded = false
            }
            DropdownMenuItem(
                text = {
                    Text(stringResource(
                        if (state.showHidden) R.string.filebrowser_hide_hidden
                        else R.string.filebrowser_show_hidden
                    ))
                },
                leadingIcon = {
                    Icon(
                        if (state.showHidden) NovexIcons.VisibilityOff else NovexIcons.Visibility,
                        contentDescription = null,
                    )
                },
                onClick = {
                    onToggleShowHidden()
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun CheckableMenuEntry(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            if (checked) {
                Icon(NovexIcons.Check, contentDescription = null)
            } else {
                Spacer(Modifier.size(24.dp))
            }
        },
        onClick = onClick,
    )
}

private fun sortKeyLabel(key: FileSortKey): Int = when (key) {
    FileSortKey.NAME -> R.string.filebrowser_sort_name
    FileSortKey.MODIFIED -> R.string.filebrowser_sort_modified
    FileSortKey.SIZE -> R.string.filebrowser_sort_size
    FileSortKey.KIND -> R.string.filebrowser_sort_kind
}

private fun fileKindIcon(item: FileItem): ImageVector {
    if (item.isDirectory) return NovexIcons.Folder
    return when (item.iconRes) {
        "text" -> NovexIcons.Description
        "terminal" -> NovexIcons.Terminal
        "code" -> NovexIcons.Code
        "image" -> NovexIcons.Image
        "audio" -> NovexIcons.AudioFile
        "video" -> NovexIcons.VideoFile
        "archive" -> NovexIcons.Archive
        "pdf" -> NovexIcons.PictureAsPdf
        "database" -> NovexIcons.Storage
        else -> NovexIcons.InsertDriveFile
    }
}
