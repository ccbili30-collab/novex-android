package com.openminis.app.ui.creative

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import com.openminis.app.ui.novex.NovexSearchField
import com.openminis.app.ui.novex.NovexTextActionRow
import com.openminis.app.ui.novex.NovexSummaryRow
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import com.openminis.app.novex.domain.NovexContentAddress
import com.openminis.app.novex.domain.NovexDisplayName
import com.openminis.app.novex.domain.NovexLibraryEntry
import com.openminis.app.novex.domain.libraryDirectory
import com.openminis.app.ui.novex.rememberNovexWorkGroups
import com.openminis.app.ui.sessions.NovexWorkGroupControls
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.creative.CreativeArtifactQuery
import com.openminis.app.data.creative.CreativeArtifactRecord
import com.openminis.app.data.creative.CreativeArtifactDeviceDirectory
import com.openminis.app.data.creative.CreativeArtifactDeviceDirectorySettings
import com.openminis.app.data.creative.CreativeArtifactRepository
import com.openminis.app.data.creative.creativeArtifactExportName
import com.openminis.app.novex.domain.CreativeArtifactKind
import com.openminis.app.novex.domain.NovexContentKind
import com.openminis.app.novex.domain.NovexCreativeArtifactOwnerOption
import com.openminis.app.novex.domain.NovexWorkspace
import com.openminis.app.novex.domain.creativeArtifactOwnerOptions
import com.openminis.app.ui.novex.NovexActionMenu
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexDecisionAction
import com.openminis.app.ui.novex.NovexDecisionDialog
import com.openminis.app.ui.novex.NovexDecisionTone
import com.openminis.app.ui.novex.NovexDimensions
import com.openminis.app.ui.novex.NovexFilterTabs
import com.openminis.app.ui.novex.NovexMenuAction
import com.openminis.app.ui.novex.NovexSettingsCustomRow
import com.openminis.app.ui.novex.NovexSettingsScaffold
import com.openminis.app.ui.novex.NovexSelectionAction
import com.openminis.app.ui.novex.NovexSearchableSelectionSheet
import com.openminis.app.ui.novex.NovexTopAction
import com.openminis.app.ui.novex.NovexType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.io.File

private enum class LibraryScope(val label: String) {
    ALL("全部"),
    FAVORITES("收藏"),
    TRASH("回收站"),
}

private data class ArtifactKindFilter(
    val kind: CreativeArtifactKind?,
    val label: String,
)

private enum class ArtifactAssociationFilter(
    val label: String,
    val ownerKind: NovexContentKind? = null,
    val unattachedOnly: Boolean = false,
) {
    ALL("全部归属"),
    WORLDS("世界成果", NovexContentKind.WORLD),
    CHARACTERS("角色成果", NovexContentKind.CHARACTER_VERSION),
    GAMES("文游成果", NovexContentKind.INTERACTIVE_FICTION),
    CONVERSATION_ONLY("仅对话文件", unattachedOnly = true),
}

private val artifactKindFilters = listOf(
    ArtifactKindFilter(null, "全部类型"),
    ArtifactKindFilter(CreativeArtifactKind.DOCUMENT, "文档"),
    ArtifactKindFilter(CreativeArtifactKind.IMAGE, "图片"),
    ArtifactKindFilter(CreativeArtifactKind.MAP, "地图"),
    ArtifactKindFilter(CreativeArtifactKind.CARD_ARCHIVE, "卡片归档"),
    ArtifactKindFilter(CreativeArtifactKind.OTHER, "其他"),
)

@Composable
fun CreativeLibraryScreen(
    repository: CreativeArtifactRepository,
    deviceDirectory: CreativeArtifactDeviceDirectory,
    workspace: NovexWorkspace,
    conversationId: String?,
    onBack: () -> Unit,
    onOpenArtifact: (CreativeArtifactRecord, File) -> Unit,
    onOpenCard: ((NovexContentAddress) -> Unit)? = null,
    onConfigureConversation: (String) -> Unit = {},
) {
    var repositoryFolder by rememberSaveable(conversationId) { mutableStateOf("") }
    var repositoryQuery by rememberSaveable(conversationId) { mutableStateOf("") }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    fun parentFolder() { repositoryFolder = repositoryFolder.substringBeforeLast('/', "") }
    BackHandler(conversationId != null && repositoryFolder.isNotEmpty() && !keyboardVisible) { parentFolder() }
    val libraries = rememberNovexWorkGroups()
    val librarySnapshot by libraries.snapshots.collectAsState(initial = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var libraryScope by remember { mutableStateOf(LibraryScope.ALL) }
    var kindFilter by remember { mutableStateOf(artifactKindFilters.first()) }
    var associationFilter by remember { mutableStateOf(ArtifactAssociationFilter.ALL) }
    var ownerFilter by remember { mutableStateOf<NovexCreativeArtifactOwnerOption?>(null) }
    var ownerOptions by remember { mutableStateOf<List<NovexCreativeArtifactOwnerOption>>(emptyList()) }
    var libraryEntries by remember { mutableStateOf<List<NovexLibraryEntry>>(emptyList()) }
    var records by remember { mutableStateOf<List<CreativeArtifactRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showKindMenu by remember { mutableStateOf(false) }
    var showOwnerSheet by remember { mutableStateOf(false) }
    var showDirectoryMenu by remember { mutableStateOf(false) }
    var directorySettings by remember {
        mutableStateOf<CreativeArtifactDeviceDirectorySettings>(deviceDirectory.settings())
    }
    var pendingDelete by remember { mutableStateOf<CreativeArtifactRecord?>(null) }
    var pendingExport by remember { mutableStateOf<CreativeArtifactRecord?>(null) }

    fun refresh() {
        refreshKey += 1
    }

    fun mutate(block: suspend () -> Unit) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess { refresh() }
                .onFailure { throwable ->
                    Toast.makeText(context, throwable.message ?: "操作失败", Toast.LENGTH_SHORT).show()
                }
        }
    }

    fun copyToCreativeDirectory(record: CreativeArtifactRecord) {
        scope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) { repository.bytes(record.artifact.id) }
                deviceDirectory.export(record, bytes)
            }.onSuccess {
                Toast.makeText(context, "已复制到创作目录", Toast.LENGTH_SHORT).show()
            }.onFailure { throwable ->
                Toast.makeText(context, throwable.message ?: "复制失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val directoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { deviceDirectory.select(uri) }
            .onSuccess {
                directorySettings = deviceDirectory.settings()
                Toast.makeText(context, "创作目录已保存", Toast.LENGTH_SHORT).show()
            }
            .onFailure { throwable ->
                Toast.makeText(context, throwable.message ?: "无法使用该目录", Toast.LENGTH_SHORT).show()
            }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val record = pendingExport
        pendingExport = null
        if (uri == null || record == null) return@rememberLauncherForActivityResult
        scope.launch {
            val success = runCatching {
                val bytes = withContext(Dispatchers.IO) { repository.bytes(record.artifact.id) }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error("无法打开导出位置")
                }
            }.isSuccess
            Toast.makeText(context, if (success) "已导出" else "导出失败", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(repository) {
        val database = (context.applicationContext as com.openminis.app.MinisApp).database
        com.openminis.app.novex.adapter.observeNovexLibraryChanges(database).collect { refresh() }
    }

    LaunchedEffect(workspace) {
        runCatching { withContext(Dispatchers.IO) { workspace.creativeArtifactOwnerOptions() } }
            .onSuccess { ownerOptions = it }
    }

    LaunchedEffect(librarySnapshot, refreshKey) {
        if (conversationId == null) runCatching { workspace.libraryDirectory(repository) }
            .onSuccess { libraryEntries = it }.onFailure { error = "读取创作库失败：${it.message}" }
    }
    fun openLibraryContent(address: NovexContentAddress) {
        if (address.kind == NovexContentKind.CREATIVE_ARTIFACT) scope.launch {
            runCatching {
                val record = repository.list().first { it.artifact.id == address.id }
                val file = withContext(Dispatchers.IO) { repository.file(address.id) }
                onOpenArtifact(record, file)
            }.onFailure { error = it.message ?: "文件未能打开" }
        } else onOpenCard?.invoke(address)
    }

    LaunchedEffect(conversationId, libraryScope, kindFilter, associationFilter, ownerFilter, refreshKey, librarySnapshot) {
        loading = records.isEmpty()
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                repository.list(
                    CreativeArtifactQuery(
                        conversationId = conversationId,
                        owner = ownerFilter?.address,
                        kinds = kindFilter.kind?.let(::setOf).orEmpty(),
                        ownerKinds = if (ownerFilter == null) {
                            associationFilter.ownerKind?.let(::setOf).orEmpty()
                        } else {
                            emptySet()
                        },
                        unattachedOnly = ownerFilter == null && associationFilter.unattachedOnly,
                        favoritesOnly = libraryScope == LibraryScope.FAVORITES,
                        trashOnly = libraryScope == LibraryScope.TRASH,
                    ),
                )
            }
        }.onSuccess { loaded -> records = if (conversationId != null) loaded else loaded.filter {
            librarySnapshot?.includes(NovexContentAddress(NovexContentKind.CREATIVE_ARTIFACT, it.artifact.id)) != false
        } }
            .onFailure { error = it.message ?: "无法读取创作成果" }
        loading = false
    }

    fun relativePath(record: CreativeArtifactRecord): String = runCatching {
        val ref = com.openminis.app.novex.domain.NovexWorkspaceFileRef.parse(record.sourcePath.orEmpty())
        if (ref.relativePath.startsWith("imports/")) ref.relativePath.removePrefix("imports/") else record.artifact.title
    }.getOrDefault(record.artifact.title)
    val folderPrefix = repositoryFolder.takeIf { it.isNotEmpty() }?.let { "$it/" }.orEmpty()
    val inFolder = if (conversationId == null) records else records.filter { relativePath(it).startsWith(folderPrefix) }
    val folderNames = if (conversationId == null || repositoryQuery.isNotBlank()) emptyList() else inFolder.map {
        relativePath(it).removePrefix(folderPrefix)
    }.filter { '/' in it }.map { it.substringBefore('/') }.distinct().sorted()
    val visibleRecords = if (conversationId == null) records else inFolder.filter {
        val path = relativePath(it).removePrefix(folderPrefix)
        if (repositoryQuery.isBlank()) '/' !in path else path.contains(repositoryQuery, ignoreCase = true)
    }
    NovexSettingsScaffold(
        title = if (conversationId == null) "创作库" else "对话仓库",
        onBack = { if (conversationId != null && repositoryFolder.isNotEmpty()) parentFolder() else onBack() },
        scrollable = false,
        actions = {
            Box {
                NovexTopAction(
                    icon = R.drawable.ic_phosphor_download_simple,
                    contentDescription = "创作目录",
                    onClick = { showDirectoryMenu = true },
                )
                NovexActionMenu(
                    expanded = showDirectoryMenu,
                    onDismissRequest = { showDirectoryMenu = false },
                    actions = buildList {
                        add(
                            NovexMenuAction(
                                label = if (directorySettings.configured) {
                                    "更换创作目录 · ${deviceDirectory.displayName().orEmpty()}"
                                } else {
                                    "选择创作目录"
                                },
                                icon = R.drawable.ic_phosphor_download_simple,
                                onClick = {
                                    showDirectoryMenu = false
                                    directoryLauncher.launch(null)
                                },
                            ),
                        )
                        if (directorySettings.configured) {
                            add(
                                NovexMenuAction(
                                    label = if (directorySettings.autoCopyEnabled) {
                                        "自动复制新成果 · 已开启"
                                    } else {
                                        "自动复制新成果 · 已关闭"
                                    },
                                    icon = if (directorySettings.autoCopyEnabled) {
                                        R.drawable.ic_phosphor_check
                                    } else {
                                        R.drawable.ic_phosphor_sliders_horizontal
                                    },
                                    onClick = {
                                        deviceDirectory.setAutoCopyEnabled(!directorySettings.autoCopyEnabled)
                                        directorySettings = deviceDirectory.settings()
                                    },
                                ),
                            )
                            add(
                                NovexMenuAction(
                                    label = "清除创作目录",
                                    icon = R.drawable.ic_phosphor_trash,
                                    destructive = true,
                                    onClick = {
                                        deviceDirectory.clear()
                                        directorySettings = deviceDirectory.settings()
                                    },
                                ),
                            )
                        }
                    },
                )
            }
            Box {
                NovexTopAction(
                    icon = R.drawable.ic_phosphor_sliders_horizontal,
                    contentDescription = "筛选类型",
                    onClick = { showKindMenu = true },
                )
                NovexActionMenu(
                    expanded = showKindMenu,
                    onDismissRequest = { showKindMenu = false },
                    actions = buildList {
                        ArtifactAssociationFilter.entries.forEach { filter ->
                            add(
                                NovexMenuAction(
                                    label = if (ownerFilter == null && filter == associationFilter) {
                                        "${filter.label} · 已选"
                                    } else {
                                        filter.label
                                    },
                                    icon = associationIcon(filter),
                                    onClick = {
                                        ownerFilter = null
                                        associationFilter = filter
                                    },
                                ),
                            )
                        }
                        add(
                            NovexMenuAction(
                                label = ownerFilter?.let { "具体项目：${it.label} · 已选" }
                                    ?: "选择具体项目",
                                icon = ownerFilter?.address?.kind?.let(::ownerKindIcon)
                                    ?: R.drawable.ic_phosphor_search,
                                onClick = { showOwnerSheet = true },
                            ),
                        )
                        artifactKindFilters.forEach { filter ->
                            add(
                                NovexMenuAction(
                                    label = if (filter == kindFilter) {
                                        "类型：${filter.label} · 已选"
                                    } else {
                                        "类型：${filter.label}"
                                    },
                                    icon = kindIcon(filter.kind),
                                    onClick = { kindFilter = filter },
                                ),
                            )
                        }
                    },
                )
            }
        },
    ) {
        if (conversationId == null) NovexWorkGroupControls(librarySnapshot,
            onConfigureConversation = onConfigureConversation,
            onOpenContent = ::openLibraryContent)
        if (conversationId != null) {
            ConversationImportControls(conversationId)
            NovexSearchField(repositoryQuery, { repositoryQuery = it }, "查找文件名称")
        }
        NovexFilterTabs(
            items = LibraryScope.entries,
            selected = libraryScope,
            label = { it.label },
            onSelect = { libraryScope = it },
            modifier = Modifier.padding(top = 5.dp, bottom = 10.dp),
        )
        when {
            conversationId == null && librarySnapshot?.selectedGroup != null && libraryScope == LibraryScope.ALL && kindFilter.kind == null && ownerFilter == null && associationFilter == ArtifactAssociationFilter.ALL ->
                NovexLibraryContents(librarySnapshot!!.selectedGroup!!, libraryEntries, ::openLibraryContent)
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = NovexColors.Primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(26.dp),
                )
            }
            error != null -> CreativeLibraryMessage(
                title = "创作库暂时不可用",
                message = error.orEmpty(),
            )
            records.isEmpty() -> CreativeLibraryMessage(
                title = when (libraryScope) {
                    LibraryScope.ALL -> if (conversationId == null) "还没有创作成果" else "本对话还没有文件"
                    LibraryScope.FAVORITES -> "还没有收藏的成果"
                    LibraryScope.TRASH -> "回收站为空"
                },
                message = if (conversationId == null) {
                    "由工具生成的文档、图片和卡片会自动收录在这里。"
                } else {
                    "可以导入资料，或在对话中创作；保存的文件会出现在这里。"
                },
            )
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = NovexDimensions.PageHorizontal),
                ) {
                    if (conversationId != null && repositoryFolder.isNotEmpty()) item {
                        NovexTextActionRow("返回上一级", R.drawable.ic_phosphor_arrow_left, onClick = ::parentFolder)
                        Text(repositoryFolder, style = NovexType.Metadata, color = NovexColors.SecondaryText)
                    }
                    items(folderNames.size, key = { "folder:" + folderNames[it] }) { index ->
                        val name = folderNames[index]
                        NovexSummaryRow(name, "文件夹", onClick = { repositoryFolder = folderPrefix + name })
                    }
                    if (visibleRecords.isEmpty() && folderNames.isEmpty()) item {
                        Text("没有找到文件", style = NovexType.Body, color = NovexColors.SecondaryText,
                            modifier = Modifier.padding(vertical = 20.dp))
                    }
                    items(visibleRecords.size, key = { visibleRecords[it].artifact.id }) { index ->
                        val record = visibleRecords[index]
                        ArtifactRow(
                            record = record,
                            showDivider = index < visibleRecords.lastIndex,
                            onOpen = {
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) { repository.file(record.artifact.id) }
                                    }.onSuccess { file -> onOpenArtifact(record, file) }
                                        .onFailure { throwable ->
                                            Toast.makeText(
                                                context,
                                                throwable.message ?: "无法打开成果",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                }
                            },
                            onFavorite = {
                                mutate { repository.setFavorite(record.artifact.id, !record.artifact.favorite) }
                            },
                            onExport = {
                                pendingExport = record
                                exportLauncher.launch(creativeArtifactExportName(record))
                            },
                            onExportToDirectory = if (directorySettings.configured) {
                                { copyToCreativeDirectory(record) }
                            } else {
                                null
                            },
                            onTrash = { mutate { repository.moveToTrash(record.artifact.id) } },
                            onRestore = { mutate { repository.restore(record.artifact.id) } },
                            onDelete = { pendingDelete = record },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { record ->
        NovexDecisionDialog(
            title = "永久删除成果？",
            message = "删除后无法恢复；如果仍被世界、角色或文游引用，系统会阻止删除。",
            onDismiss = { pendingDelete = null },
            actions = listOf(
                NovexDecisionAction(
                    label = "永久删除",
                    icon = R.drawable.ic_phosphor_trash,
                    tone = NovexDecisionTone.DESTRUCTIVE,
                    onClick = {
                        pendingDelete = null
                        mutate { repository.permanentlyDelete(record.artifact.id) }
                    },
                ),
                NovexDecisionAction(
                    label = "取消",
                    icon = R.drawable.ic_phosphor_arrow_left,
                    onClick = { pendingDelete = null },
                ),
            ),
        )
    }

    if (showOwnerSheet) {
        NovexSearchableSelectionSheet(
            title = "按具体项目筛选",
            actions = buildList {
                add(
                    NovexSelectionAction(
                        label = if (ownerFilter == null) "不限具体项目 · 已选" else "不限具体项目",
                        icon = R.drawable.ic_phosphor_sliders_horizontal,
                        onClick = { ownerFilter = null },
                    ),
                )
                ownerOptions.forEach { option ->
                    add(
                        NovexSelectionAction(
                            label = if (option == ownerFilter) "${option.label} · 已选" else option.label,
                            icon = ownerKindIcon(option.address.kind),
                            onClick = {
                                associationFilter = ArtifactAssociationFilter.ALL
                                ownerFilter = option
                            },
                        ),
                    )
                }
            },
            searchPlaceholder = "搜索世界、角色版本或文游",
            onDismissRequest = { showOwnerSheet = false },
        )
    }
}

@Composable
private fun ArtifactRow(
    record: CreativeArtifactRecord,
    showDivider: Boolean,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onExport: () -> Unit,
    onExportToDirectory: (() -> Unit)?,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember(record.artifact.id) { mutableStateOf(false) }
    val revision = record.revisions.lastOrNull()
    val metadata = buildList {
        add(kindLabel(record.artifact.kind))
        revision?.let { add(Formatter.formatShortFileSize(LocalContext.current, it.sizeBytes)) }
        add(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(record.artifact.updatedAt)))
    }.joinToString(" · ")
    NovexSettingsCustomRow(
        title = NovexDisplayName.file(record.artifact.title),
        subtitle = metadata,
        showChevron = false,
        showDivider = showDivider,
        onClick = onOpen,
        leading = {
            Icon(
                painter = painterResource(kindIcon(record.artifact.kind)),
                contentDescription = null,
                tint = NovexColors.Primary,
                modifier = Modifier.size(21.dp),
            )
        },
        trailing = {
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_phosphor_more_vertical),
                        contentDescription = "成果操作",
                        tint = NovexColors.SecondaryText,
                        modifier = Modifier.size(20.dp),
                    )
                }
                NovexActionMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    actions = buildList {
                        if (record.artifact.isTrashed) {
                            add(NovexMenuAction("恢复", R.drawable.ic_phosphor_arrow_clockwise, onClick = onRestore))
                            add(
                                NovexMenuAction(
                                    "永久删除",
                                    R.drawable.ic_phosphor_trash,
                                    destructive = true,
                                    onClick = onDelete,
                                ),
                            )
                        } else {
                            add(
                                NovexMenuAction(
                                    if (record.artifact.favorite) "取消收藏" else "收藏",
                                    R.drawable.ic_phosphor_sparkle,
                                    onClick = onFavorite,
                                ),
                            )
                            add(NovexMenuAction("导出副本", R.drawable.ic_phosphor_download_simple, onClick = onExport))
                            onExportToDirectory?.let { export ->
                                add(
                                    NovexMenuAction(
                                        "复制到创作目录",
                                        R.drawable.ic_phosphor_arrow_up,
                                        onClick = export,
                                    ),
                                )
                            }
                            add(
                                NovexMenuAction(
                                    "移到回收站",
                                    R.drawable.ic_phosphor_trash,
                                    destructive = true,
                                    onClick = onTrash,
                                ),
                            )
                        }
                    },
                )
            }
        },
    )
}

@Composable
private fun CreativeLibraryMessage(title: String, message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp).widthIn(max = 300.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_phosphor_note_pencil),
                contentDescription = null,
                tint = NovexColors.Primary,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.padding(top = 6.dp))
            Text(title, color = NovexColors.Text, style = NovexType.SectionTitle, fontWeight = FontWeight.SemiBold)
            Text(
                message,
                color = NovexColors.SecondaryText,
                style = NovexType.Body,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun kindIcon(kind: CreativeArtifactKind?): Int = when (kind) {
    CreativeArtifactKind.IMAGE, CreativeArtifactKind.MAP -> R.drawable.ic_phosphor_image
    CreativeArtifactKind.DOCUMENT -> R.drawable.ic_phosphor_note_pencil
    CreativeArtifactKind.CARD_ARCHIVE -> R.drawable.ic_phosphor_download_simple
    CreativeArtifactKind.OTHER, null -> R.drawable.ic_phosphor_sliders_horizontal
}

private fun associationIcon(filter: ArtifactAssociationFilter): Int = when (filter) {
    ArtifactAssociationFilter.ALL -> R.drawable.ic_phosphor_sliders_horizontal
    ArtifactAssociationFilter.WORLDS -> R.drawable.ic_phosphor_image
    ArtifactAssociationFilter.CHARACTERS -> R.drawable.ic_phosphor_sparkle
    ArtifactAssociationFilter.GAMES -> R.drawable.ic_phosphor_puzzle_piece
    ArtifactAssociationFilter.CONVERSATION_ONLY -> R.drawable.ic_phosphor_chats
}

private fun ownerKindIcon(kind: NovexContentKind): Int = when (kind) {
    NovexContentKind.WORLD -> R.drawable.ic_phosphor_image
    NovexContentKind.CHARACTER_VERSION -> R.drawable.ic_phosphor_sparkle
    NovexContentKind.INTERACTIVE_FICTION -> R.drawable.ic_phosphor_puzzle_piece
    NovexContentKind.CREATIVE_ARTIFACT -> R.drawable.ic_phosphor_note_pencil
}

private fun NovexContentKind.ownerLabel(): String = when (this) {
    NovexContentKind.WORLD -> "世界"
    NovexContentKind.CHARACTER_VERSION -> "角色"
    NovexContentKind.INTERACTIVE_FICTION -> "文游"
    NovexContentKind.CREATIVE_ARTIFACT -> "成果"
}

private fun kindLabel(kind: CreativeArtifactKind): String = when (kind) {
    CreativeArtifactKind.DOCUMENT -> "文档"
    CreativeArtifactKind.IMAGE -> "图片"
    CreativeArtifactKind.MAP -> "地图"
    CreativeArtifactKind.CARD_ARCHIVE -> "卡片归档"
    CreativeArtifactKind.OTHER -> "其他"
}
