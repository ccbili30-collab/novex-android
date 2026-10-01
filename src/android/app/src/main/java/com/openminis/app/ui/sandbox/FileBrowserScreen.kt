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
