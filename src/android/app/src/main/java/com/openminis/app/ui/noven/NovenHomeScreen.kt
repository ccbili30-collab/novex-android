package com.openminis.app.ui.noven

import android.net.Uri
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
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.openminis.app.cards.IntegratedCardStart
import com.openminis.app.cards.IntegratedCards
import com.openminis.app.ui.novex.AlertDialog
import com.openminis.app.ui.novex.DropdownMenu
import com.openminis.app.ui.novex.DropdownMenuItem
import com.openminis.app.ui.novex.TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import novex.android.CardSessionModel
import novex.android.LibraryModel
import novex.android.introductionModule
import novex.android.moduleExcerptText
import novex.content.CardKind
import novex.content.ContentBlock
import novex.content.flattenModules
import novex.storage.CardStore
import novex.storage.CardSummary

/**
 * 首页（01.png）：发现频道 + 本地卡片瀑布流。数据来自 LibraryModel 的正式卡
 * 列表；卡名搜索；「只看收藏」过滤接 NovenFavoritesStore。世界卡上的
 * 进入/创作动作走 IntegratedCardStart（与卡片库同一条 CardBinding 链路）。
 */
@Composable
internal fun NovenHomeScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenCard: (String, String) -> Unit,
    onChat: (String) -> Unit,
    onCreateWorld: () -> Unit,
    onImportCard: (Uri, Boolean) -> Unit,
    libraryReady: Boolean,
    migrationError: String?,
) {
    val app = LocalContext.current.applicationContext as com.openminis.app.MinisApp
    val library: LibraryModel = viewModel(key = "noven-home-library")
    val reader: CardSessionModel = viewModel(key = "noven-home-reader")
    val profileStore = remember { NovenProfileStore.get(app) }
    val favoritesStore = remember { NovenFavoritesStore.get(app) }
    val community = remember { NovenCommunity.get(app) }
    val profile = profileStore.profile
    val scope = rememberCoroutineScope()

    // 旧卡迁移在根页面执行一次；ready 后各 tab 各自 refresh（另有
    // ON_RESUME 刷新与 NovexIntegratedLibraryRoot 一致）。
    LaunchedEffect(libraryReady) {
        if (libraryReady) library.refresh()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, library) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                library.refresh()
                community.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var channel by rememberSaveable { mutableIntStateOf(0) }
    var kindFilter by rememberSaveable { mutableIntStateOf(0) } // 0 全部 1 角色 2 世界
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var filterMenuOpen by remember { mutableStateOf(false) }
    var importChoiceOpen by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val importPicker = rememberNovenImportPicker { uri, world -> onImportCard(uri, world) }

    val cards = library.state.cards
    val displays by produceState<Map<String, NovenCardDisplay>>(emptyMap(), cards, libraryReady) {
        if (!libraryReady) return@produceState
        value = withContext(Dispatchers.IO) {
            // 一份 CardStore 复用整轮，不再每张卡 new 一个 IntegratedCards。
            val store = IntegratedCards(app).store
            cards.associate { it.id to novenCardDisplay(reader, it, store) }
        }
    }

    fun openCardChat(cardId: String, manage: Boolean) {
        scope.launch {
            try {
                onChat(IntegratedCardStart.start(app, cardId, cardId, manage))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                actionError = failure.message ?: "对话未创建"
            }
        }
    }

    val shown = cards
        .asSequence()
        .filter { kindFilter == 0 || (kindFilter == 1) == (it.kind == CardKind.CHARACTER) }
        .filter { !favoritesOnly || favoritesStore.isFavorite(it.id) }
        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        // 「关注」只显示归属表里标记为已关注作者的作品；本地阶段由种子归属表驱动。
        .filter { channel == 0 || community.attribution(it.id)?.followed == true }
        .toList()

    Column(Modifier.fillMaxSize()) {
        // 顶栏：诺文 / Noven + 搜索框 + 过滤菜单
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NovenDimens.PageHorizontal)
                .padding(top = 8.dp, bottom = 6.dp),
        ) {
            Text(
                "诺文",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = NovenColors.Text,
            )
            Text(
                "Noven",
                fontSize = 13.sp,
                color = NovenColors.Secondary,
                modifier = Modifier.padding(start = 6.dp, top = 2.dp),
            )
            Spacer(Modifier.width(12.dp))
            NovenSearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "搜索角色、世界",
                modifier = Modifier.weight(1f),
                trailing = {
                    Box {
                        Icon(
                            painter = painterResource(R.drawable.ic_phosphor_sliders_horizontal),
                            contentDescription = "筛选",
                            tint = NovenColors.Secondary,
                            modifier = Modifier
                                .size(28.dp)
                                .clickable { filterMenuOpen = true }
                                .padding(5.dp),
                        )
                        DropdownMenu(
                            expanded = filterMenuOpen,
                            onDismissRequest = { filterMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("全部") },
                                onClick = { filterMenuOpen = false; favoritesOnly = false },
                            )
                            DropdownMenuItem(
                                text = { Text("只看收藏") },
                                onClick = { filterMenuOpen = false; favoritesOnly = true },
                            )
                        }
                    }
                },
            )
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = NovenDimens.PageHorizontal)) {
            NovenChannelTabs(
                labels = listOf("发现", "关注"),
                selected = channel,
                onSelect = { channel = it },
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NovenFilterChip("全部", kindFilter == 0) { kindFilter = 0 }
                NovenFilterChip("角色", kindFilter == 1) { kindFilter = 1 }
                NovenFilterChip("世界", kindFilter == 2) { kindFilter = 2 }
            }
        }
        migrationError?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = NovenColors.Secondary,
                modifier = Modifier.padding(horizontal = NovenDimens.PageHorizontal, vertical = 4.dp),
            )
        }
        library.state.error?.let { error ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = NovenDimens.PageHorizontal, vertical = 4.dp),
            ) {
                Text(error, fontSize = 12.sp, color = NovenColors.Secondary)
                TextButton(onClick = library::refresh) { Text("重新读取") }
            }
        }

        when {
            channel == 1 && shown.isEmpty() -> {
                NovenEmptyState(
                    icon = R.drawable.ic_phosphor_user,
                    title = "还没有关注",
                    subtitle = "关注创作者后，他们的更新会出现在这里",
                    modifier = Modifier.fillMaxSize(),
                )
            }
            !libraryReady -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("载入中", fontSize = 13.sp, color = NovenColors.Secondary)
                }
            }
            shown.isEmpty() -> {
                NovenEmptyState(
                    icon = R.drawable.ic_phosphor_globe,
                    title = if (query.isNotBlank() || favoritesOnly) "没有匹配的作品" else "还没有作品",
                    modifier = Modifier.fillMaxSize(),
                    actions = {
                        NovenPillButton(label = "新建世界", onClick = onCreateWorld)
                        Spacer(Modifier.width(8.dp))
                        NovenPillButton(label = "导入卡片", onClick = { importChoiceOpen = true })
                    },
                )
            }
            else -> {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = NovenDimens.PageHorizontal,
                        end = NovenDimens.PageHorizontal,
                        top = 10.dp,
                        bottom = 24.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalItemSpacing = 10.dp,
                ) {
                    items(shown, key = { it.id }) { card ->
                        val display = displays[card.id]
                            ?: NovenCardDisplay(card.id, card.name, card.kind, null, "", emptyList(), 0)
                        NovenWorkCard(
                            data = display,
                            reader = reader,
                            onClick = { onOpenCard(card.id, card.id) },
                            onEnterWorld = { openCardChat(card.id, manage = false) },
                            onCreateWith = { openCardChat(card.id, manage = true) },
                            footer = {
                                val attribution = community.attribution(card.id)
                                NovenAuthorRow(
                                    name = attribution?.author ?: profile.nickname,
                                    avatarPath = attribution?.avatarPath ?: profile.avatarPath,
                                    favorited = favoritesStore.isFavorite(card.id),
                                    onFavorite = { favoritesStore.toggle(card.id) },
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    if (importChoiceOpen) {
        AlertDialog(
            onDismissRequest = { importChoiceOpen = false },
            title = { Text("导入卡片") },
            confirmButton = {
                TextButton(onClick = { importChoiceOpen = false; importPicker(true) }) {
                    Text("导入世界卡")
                }
            },
            dismissButton = {
                TextButton(onClick = { importChoiceOpen = false; importPicker(false) }) {
                    Text("导入角色卡")
                }
            },
        )
    }
    actionError?.let {
        AlertDialog(
            onDismissRequest = { actionError = null },
            title = { Text("操作未完成") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { actionError = null }) { Text("知道了") } },
        )
    }
}

@Composable
internal fun NovenPillButton(label: String, onClick: () -> Unit, mint: Boolean = false) {
    Box(
        Modifier
            .height(34.dp)
            .background(
                if (mint) NovenColors.Mint else NovenColors.Surface,
                RoundedCornerShape(17.dp),
            )
            .border(
                NovenDimens.Hairline,
                if (mint) NovenColors.Mint else NovenColors.Divider,
                RoundedCornerShape(17.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (mint) NovenColors.OnMint else NovenColors.Text,
        )
    }
}

/** 打开卡片内容，读取首页卡片需要的展示数据（图、简介摘要、模块 tags）。 */
internal suspend fun novenCardDisplay(
    reader: CardSessionModel,
    summary: CardSummary,
    store: CardStore,
): NovenCardDisplay {
    val doc = runCatching { store.open(summary.id)?.content }.getOrNull()
        ?: return NovenCardDisplay(summary.id, summary.name, summary.kind, null, "", emptyList(), 0)
    val imageId = doc.appearance.coverResourceId ?: doc.appearance.avatarResourceId
    val imageRef = doc.resources.firstOrNull { it.id == imageId }?.content
    val excerptRef = doc.introductionModule()
        ?.blocks?.filterIsInstance<ContentBlock.Text>()?.firstOrNull()?.content
    val excerpt = if (excerptRef == null) "" else runCatching {
        moduleExcerptText(reader.textPage(excerptRef, 0, 600).text).take(160)
    }.getOrDefault("")
    val tags = doc.modules.flattenModules().flatMap { it.tags }.distinct().take(2)
    return NovenCardDisplay(
        id = summary.id,
        name = doc.name.ifBlank { summary.name },
        kind = summary.kind,
        imageRef = imageRef,
        excerpt = excerpt,
        tags = tags,
        internalCharacterCount = doc.internalCharacters.size,
    )
}
