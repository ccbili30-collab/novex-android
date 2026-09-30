package com.openminis.app.ui.noven

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.app.R
import novex.android.data.chat.SessionRow
import com.openminis.app.data.repository.ChatRepository
import novex.android.ui.AlertDialog
import novex.android.ui.DropdownMenu
import novex.android.ui.DropdownMenuItem
import novex.android.ui.NovexExportFileName
import novex.android.ui.OutlinedTextField
import novex.android.ui.TextButton
import com.openminis.app.ui.settings.NovexUpdateEntry
import com.openminis.app.ui.settings.NovexUpdateHub
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import novex.android.CardSessionModel
import novex.android.FileTransferModel
import novex.android.LibraryModel
import novex.content.CardKind
import novex.storage.CardSummary

/**
 * 我的 tab（04.png）：整页 staggered grid，顶部 full-span 资料头 +
 * 世界/角色/创作库页签。统计数字全部来自真实数据：会话引用数来自
 * ChatRepository.observeSessions()，没有的口径一律写 0。
 */
@Composable
internal fun NovenMeScreen(
    updateHub: NovexUpdateHub,
    chatRepository: ChatRepository?,
    onOpenCard: (String, String) -> Unit,
    onCreateWorld: () -> Unit,
    onCreateCharacter: () -> Unit,
    onImportCard: (Uri, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCreativeLibrary: () -> Unit,
    libraryReady: Boolean,
) {
    val app = LocalContext.current.applicationContext
    val library: LibraryModel = viewModel(key = "noven-me-library")
    val reader: CardSessionModel = viewModel(key = "noven-me-reader")
    val files: FileTransferModel = viewModel(key = "noven-me-files")
    val profileStore = remember { NovenProfileStore.get(app) }
    val community = remember { NovenCommunity.get(app) }
    val profile = profileStore.profile

    LaunchedEffect(libraryReady) {
        if (libraryReady) library.refresh()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, library) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) library.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 会话引用计数：每张根卡被多少段会话引用（primary/背景/管理任一）。
    val sessions by (chatRepository?.observeSessions()
        ?: kotlinx.coroutines.flow.flowOf<List<SessionRow>>(emptyList()))
        .collectAsState(initial = emptyList())
    val referenceCounts = remember(sessions) { cardSessionReferenceCounts(sessions) }

    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 世界 1 角色 2 创作库
    var editOpen by remember { mutableStateOf(false) }
    var newMenuOpen by remember { mutableStateOf(false) }
    val importPicker = rememberNovenImportPicker { uri, world -> onImportCard(uri, world) }

    var exportId by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val id = exportId
        exportId = null
        if (uri != null && id != null) files.export(id, uri)
    }
    var deleting by remember { mutableStateOf<CardSummary?>(null) }

    val cards = library.state.cards
    val displays by produceState<Map<String, NovenCardDisplay>>(emptyMap(), cards) {
        value = withContext(Dispatchers.IO) {
            val store = com.openminis.app.cards.IntegratedCards(app).store
            cards.associate { it.id to novenCardDisplay(reader, it, store) }
        }
    }
    val shown = cards.filter {
        it.kind == (if (tab == 0) CardKind.WORLD else CardKind.CHARACTER) &&
            community.attribution(it.id) == null // 归属他人的作品不算我的资产
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = NovenDimens.PageHorizontal,
            end = NovenDimens.PageHorizontal,
            top = 8.dp,
            bottom = 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp,
    ) {
        item(span = StaggeredGridItemSpan.FullLine, key = "topbar") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NovexUpdateEntry(updateHub)
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_phosphor_gear),
                        contentDescription = "设置",
                        tint = NovenColors.Text,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        item(span = StaggeredGridItemSpan.FullLine, key = "profile") {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                NovenAvatar(profile.nickname, profile.avatarPath, 72.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(
                        profile.nickname,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = NovenColors.Text,
                    )
                    if (profile.bio.isNotBlank()) {
                        Text(
                            profile.bio,
                            fontSize = 13.sp,
                            color = NovenColors.Secondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Text(
                        "Created with Noven",
                        fontSize = 11.sp,
                        color = NovenColors.Secondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        item(span = StaggeredGridItemSpan.FullLine, key = "stats") {
            Text(
                "0 关注 · 0 粉丝 · 0 被珍藏",
                fontSize = 13.sp,
                color = NovenColors.Secondary,
            )
        }
        item(span = StaggeredGridItemSpan.FullLine, key = "actions") {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NovenPillButton(label = "编辑资料", onClick = { editOpen = true })
                Box {
                    NovenPillButton(label = "+ 新建", mint = true, onClick = { newMenuOpen = true })
                    DropdownMenu(expanded = newMenuOpen, onDismissRequest = { newMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("新建世界") },
                            onClick = { newMenuOpen = false; onCreateWorld() },
                        )
                        DropdownMenuItem(
                            text = { Text("新建角色") },
                            onClick = { newMenuOpen = false; onCreateCharacter() },
                        )
                        DropdownMenuItem(
                            text = { Text("导入世界卡") },
                            onClick = { newMenuOpen = false; importPicker(true) },
                        )
                        DropdownMenuItem(
                            text = { Text("导入角色卡") },
                            onClick = { newMenuOpen = false; importPicker(false) },
                        )
                    }
                }
            }
        }
        item(span = StaggeredGridItemSpan.FullLine, key = "tabs") {
            Row(Modifier.padding(top = 12.dp, bottom = 2.dp)) {
                NovenChannelTabs(
                    labels = listOf("世界", "角色", "创作库"),
                    selected = tab,
                    onSelect = { tab = it },
                )
            }
        }

        // 导出/读取反馈：与 NovexIntegratedLibraryRoot 同一套。
        if (files.state.busy) {
            item(span = StaggeredGridItemSpan.FullLine, key = "export_busy") {
                Text("正在导出", fontSize = 12.sp, color = NovenColors.Secondary)
            }
        }
        files.state.message?.let { message ->
            item(span = StaggeredGridItemSpan.FullLine, key = "export_message") {
                Text(message, fontSize = 12.sp, color = NovenColors.Secondary)
            }
        }
        files.state.error?.let { error ->
            item(span = StaggeredGridItemSpan.FullLine, key = "export_error") {
                Text("导出未完成：$error", fontSize = 12.sp, color = NovenColors.Secondary)
            }
        }
        library.state.error?.let { error ->
            item(span = StaggeredGridItemSpan.FullLine, key = "library_error") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(error, fontSize = 12.sp, color = NovenColors.Secondary)
                    TextButton(onClick = library::refresh) { Text("重新读取") }
                }
            }
        }

        if (tab == 2) {
            item(span = StaggeredGridItemSpan.FullLine, key = "creative_library") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(NovenDimens.CardRadius))
                        .background(NovenColors.Surface)
                        .border(NovenDimens.Hairline, NovenColors.Divider, RoundedCornerShape(NovenDimens.CardRadius))
                        .clickable(onClick = onOpenCreativeLibrary)
                        .padding(14.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "打开创作库",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NovenColors.Text,
                        )
                        Text(
                            "各个会话产出的文档、图片和卡片包",
                            fontSize = 12.sp,
                            color = NovenColors.Secondary,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_phosphor_caret_right),
                        contentDescription = null,
                        tint = NovenColors.Secondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        } else if (shown.isEmpty()) {
            item(span = StaggeredGridItemSpan.FullLine, key = "empty") {
                NovenEmptyState(
                    icon = if (tab == 0) R.drawable.ic_phosphor_globe else R.drawable.ic_phosphor_user,
                    title = if (tab == 0) "还没有世界" else "还没有角色",
                )
            }
        } else {
            items(shown, key = { it.id }) { card ->
                val display = displays[card.id]
                    ?: NovenCardDisplay(card.id, card.name, card.kind, null, "", emptyList(), 0)
                Box {
                    NovenWorkCard(
                        data = display,
                        reader = reader,
                        onClick = { onOpenCard(card.id, card.id) },
                        footer = {
                            val refs = referenceCounts[card.id] ?: 0
                            Text(
                                if (card.kind == CardKind.WORLD) {
                                    "${display.internalCharacterCount} 个角色 · $refs 段会话"
                                } else {
                                    "$refs 段会话"
                                },
                                fontSize = 12.sp,
                                color = NovenColors.Secondary,
                            )
                        },
                    )
                    var more by remember(card.id) { mutableStateOf(false) }
                    Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_phosphor_more_vertical),
                            contentDescription = "更多操作",
                            tint = if (display.imageRef != null) androidx.compose.ui.graphics.Color.White
                            else NovenColors.Secondary,
                            modifier = Modifier
                                .size(28.dp)
                                .clickable(enabled = !library.state.busy && !files.state.busy) { more = true }
                                .padding(4.dp),
                        )
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(
                                text = { Text("导出") },
                                onClick = {
                                    more = false
                                    exportId = card.id
                                    exporter.launch(NovexExportFileName.build(card.name))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除", color = novex.android.ui.NovexColors.Danger) },
                                onClick = { more = false; deleting = card },
                            )
                        }
                    }
                }
            }
        }
    }

    deleting?.let { card ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除「${card.name}」？") },
            text = { Text("从库中移除卡片及其内部角色。对话保留，引用此卡时将提示不可用。历史和原始资源保留。") },
            confirmButton = {
                TextButton(enabled = !library.state.busy, onClick = { deleting = null; library.delete(card) }) {
                    Text("删除")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }

    if (editOpen) {
        NovenProfileEditDialog(
            store = profileStore,
            onDismiss = { editOpen = false },
        )
    }
}

@Composable
private fun NovenProfileEditDialog(store: NovenProfileStore, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var nickname by remember { mutableStateOf(store.profile.nickname) }
    var bio by remember { mutableStateOf(store.profile.bio) }
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val ext = context.contentResolver.getType(uri)
                ?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() }
                ?: "img"
            val stream = runCatching { context.contentResolver.openInputStream(uri) }
                .getOrNull()
            scope.launch { store.saveAvatarFrom(stream, ext) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑资料") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NovenAvatar(store.profile.nickname, store.profile.avatarPath, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { avatarPicker.launch(arrayOf("image/*")) }) {
                        Text("选择头像")
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("昵称") },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it },
                    label = { Text("简介") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { store.update(nickname, bio); onDismiss() }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
