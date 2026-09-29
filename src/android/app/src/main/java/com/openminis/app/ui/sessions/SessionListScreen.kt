package com.openminis.app.ui.sessions

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import android.content.Intent
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.openminis.app.ui.novex.DropdownMenu
import com.openminis.app.ui.novex.DropdownMenuItem
import com.openminis.app.ui.noven.NovenSessionRow
import com.openminis.app.ui.noven.categoryStyle
import com.openminis.app.ui.noven.relativeDate
import com.openminis.app.ui.noven.novenSessionGroupRowShape
import androidx.compose.material3.Surface
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import com.openminis.app.ui.novex.ModalBottomSheet
import com.openminis.app.ui.novex.OutlinedButton
import com.openminis.app.ui.novex.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.openminis.app.ui.novex.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.character.WorldEntity
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.hasUsableNovexModel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.novex.rememberNovexWorkspace
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.openminis.app.ui.components.MinisTextButton

// FAB color — use shared theme values
// 16-category styles, relativeDate, the session row and its badge/palette
// helpers moved to ui/noven/NovenSessionRow.kt — shared with the launcher
// home surface (installNovexHomeSurface 也 host 本屏).

// Date period for section grouping (matching iOS)
private enum class DatePeriod {
    PINNED,
    TODAY,
    EARLIER,
}

/** Map a session to the preview home's intentionally compact Today/Earlier split. */
private fun datePeriod(timestamp: Long): DatePeriod = when (sessionHomeRecency(timestamp)) {
    SessionHomeRecency.TODAY -> DatePeriod.TODAY
    SessionHomeRecency.EARLIER -> DatePeriod.EARLIER
}

/**
 * [T-android-session-grouping] One rendered group block: a user-created group
 * plus the sessions filed into it.
 *
 * Holds session IDS, not session objects — the list differ re-evaluates this on
 * every emission, so the value must stay cheap to compare. (iOS learned the
 * same lesson as `SidebarGroup`; a `List<ChatSessionEntity>` here deep-compares
 * long message strings on every tick.)
 *
 * `ids` is EMPTY while collapsed, but [totalCount] keeps the real number so the
 * card can still say "5 chats".
 */
data class FolderGroupBlock(
    val folder: FolderEntity,
    val ids: List<String>,
    val totalCount: Int,
    val isCollapsed: Boolean,
    val latestUpdatedAt: Long,
    /** Newest member's title — iOS folderSectionHeader's "N chats · title" summary line. */
    val summaryTitle: String? = null,
    /** Newest member's category — tints the composed folder icon like iOS FolderComposedIcon. */
    val firstCategory: String? = null,
)

/**
 * [T-android-session-grouping] Partition sessions into group blocks + the
 * ungrouped remainder.
 *
 * Ordering rules, ported from iOS `computeGroupedSessionIDs`:
 *  - Input arrives `updated_at DESC`, so first-encounter order over the filed
 *    sessions IS the groups' activity order — no separate sort needed.
 *  - Pinned groups float above unpinned as a STABLE PARTITION, not a re-sort,
 *    so activity order survives inside each half.
 *  - **Group membership outranks pin for PLACEMENT**: a pinned session that is
 *    also filed renders inside its group, not in the Pinned bucket. Otherwise
 *    filing a pinned session looks like a no-op — the write lands but the row
 *    never moves. The pin itself is untouched: pinned members sort first inside
 *    the group and keep their pin glyph.
 *  - A `folder_id` pointing at a group we don't have renders as UNGROUPED
 *    rather than vanishing. There is no FK, so this is a normal state.
 *  - Empty groups still render — a group that disappears when its last session
 *    moves out reads as data loss.
 */
private fun partitionByFolder(
    sessions: List<ChatSessionEntity>,
    folders: List<FolderEntity>,
    collapsedIds: Set<String>,
): Pair<List<FolderGroupBlock>, List<ChatSessionEntity>> {
    if (folders.isEmpty()) return emptyList<FolderGroupBlock>() to sessions

    val byId = folders.associateBy { it.id }
    val members = LinkedHashMap<String, MutableList<ChatSessionEntity>>()
    val ungrouped = mutableListOf<ChatSessionEntity>()

    for (s in sessions) {
        val fid = s.folderId
        // Presence check against the loaded map — never a DB constraint.
        if (fid != null && byId.containsKey(fid)) {
            members.getOrPut(fid) { mutableListOf() }.add(s)
        } else {
            ungrouped.add(s)
        }
    }

    // First-encounter order = activity order. Groups with no members are
    // appended afterwards so they still render.
    val ordered = members.keys.toMutableList()
    for (f in folders) if (f.id !in members) ordered.add(f.id)

    val blocks = ordered.mapNotNull { fid ->
        val folder = byId[fid] ?: return@mapNotNull null
        val m = members[fid].orEmpty()
        val collapsed = fid in collapsedIds
        // Pinned members first, stable partition — the pin is a display
        // affordance inside the group, not a reason to leave it.
        val displayOrdered = m.filter { it.pinnedAt != null } + m.filter { it.pinnedAt == null }
        FolderGroupBlock(
            folder = folder,
            ids = if (collapsed) emptyList() else displayOrdered.map { it.id },
            totalCount = m.size,
            isCollapsed = collapsed,
            // Recency order (not display order) — this means "newest activity".
            latestUpdatedAt = m.firstOrNull()?.updatedAt ?: folder.updatedAt,
            summaryTitle = m.firstOrNull()?.title,
            firstCategory = m.firstOrNull()?.category,
        )
    }

    val pinnedFirst = blocks.filter { it.folder.isPinned } + blocks.filter { !it.folder.isPinned }
    return pinnedFirst to ungrouped
}

private fun groupSessionsByDate(sessions: List<ChatSessionEntity>): List<Pair<DatePeriod, List<ChatSessionEntity>>> {
    val pinned = sessions.filter { it.pinnedAt != null }.sortedByDescending { it.pinnedAt }
    val unpinned = sessions.filter { it.pinnedAt == null }
    val grouped = unpinned.groupBy { datePeriod(it.updatedAt) }
    val result = mutableListOf<Pair<DatePeriod, List<ChatSessionEntity>>>()
    if (pinned.isNotEmpty()) {
        result.add(DatePeriod.PINNED to pinned)
    }
    for (period in DatePeriod.entries) {
        if (period == DatePeriod.PINNED) continue
        grouped[period]?.let { result.add(period to it) }
    }
    return result
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionListScreen(
    chatRepository: ChatRepository,
    /**
     * Nullable: the lightweight launcher surface (NovexLaunchActivity →
     * installNovexHomeSurface) hosts this screen BEFORE
     * `initializeRuntimeSubsystems()` runs, so `MinisApp.providerRepository`
     * is still unassigned there. On that path every provider-dependent
     * affordance is hidden rather than stubbed: the onboarding landing,
     * 「添加模型」/model-group hints, AI title regeneration and AI group
     * suggestion. Everything backed by Room + the card library (folders,
     * multi-select, pin, move, search + snippets, delete, export, card
     * lookup) works unchanged.
     */
    providerRepository: ProviderRepository?,
    onSessionClick: (String) -> Unit,
    onNewChat: (String) -> Unit,
    onAddProviderClick: () -> Unit = {},
    onSelectModelsClick: () -> Unit = {},
    onTerminalClick: () -> Unit = {},
    onRootfsClick: () -> Unit = {},
    // [T-android-scheduled-tasks-design] Entry to the scheduled-tasks list.
    onScheduledTasksClick: () -> Unit = {},
    onRootNavigationVisibilityChange: (Boolean) -> Unit = {},
    /** Fires once when the first Room session emission lands. The launcher
     *  surface uses it for the `home_content_ready` startup metric. */
    onContentLoaded: () -> Unit = {},
) {
    val context = LocalContext.current
    // T46: hoist VM ownership to the NavBackStackEntry's ViewModelStore so
    // [searchQuery] / [isSearchActive] survive navigation. The previous
    // `remember {}` scoping tied the VM to the composable's lifetime — pushing
    // to chat detail destroyed it, then pop-back rebuilt a fresh VM with
    // empty search state. Mirrors iOS where ContentView's `@State searchText`
    // survives a NavigationLink push because the parent view never unmounts.
    val viewModel: SessionListViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = SessionListViewModel.factory(chatRepository, providerRepository, context),
    )
    val sessions by viewModel.displayedSessions.collectAsState()
    val isInitialLoadComplete by viewModel.isInitialLoadComplete.collectAsState()
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    // Only the debounced query that produced the visible result set reaches
    // this large composition scope. Raw keystrokes are collected inside the
    // small search controls below, preventing a full page recomposition per
    // character.
    val searchQuery by viewModel.appliedSearchQuery.collectAsState()
    val searchSnippets by viewModel.searchSnippets.collectAsState()
    val isSelecting by viewModel.isSelecting.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val regeneratingIds by viewModel.regeneratingIds.collectAsState()
    // 轻量启动面 providerRepository == null：空配置 + 未加载态。onboarding
    // 引导由下面的 providerRepository == null 分支接管（显示普通空态），
    // 这里两个 flow 只是让 hasProviders/hasGroups 保持 false。
    val providerConfig by remember(providerRepository) {
        providerRepository?.config ?: MutableStateFlow(ProviderConfig())
    }.collectAsState()
    val hasProviders = providerConfig.instances.any { it.isEnabled }
    val hasGroups = providerConfig.hasUsableNovexModel()
    // [T-android-startup-config-stall] Provider config now loads off-thread, so
    // for a brief startup window `providerConfig` is the empty placeholder.
    // Gate the onboarding/list render on this too (alongside the sessions
    // initial-load flag) so an existing user with providers but zero sessions
    // doesn't flash the "add a provider" onboarding before the real config emits.
    // repo 为 null 时恒为 false —— 真实分支判断见 listEmpty/providerRepository==null。
    val configLoaded by remember(providerRepository) {
        providerRepository?.configLoaded ?: MutableStateFlow(false)
    }.collectAsState()
    // provider 运行时可用：null → 隐藏 AI 标题重生成 / AI 分组建议 / onboarding。
    val providerRuntimeAvailable = providerRepository != null
    val scope = rememberCoroutineScope()
    val novex = rememberNovexWorkspace()
    var worlds by remember { mutableStateOf<List<WorldEntity>>(emptyList()) }
    var worldsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        worlds = novex.worlds().map { it.world }
        worldsLoaded = true
    }
    // home-v2：底栏常驻，仅多选时隐藏。
    LaunchedEffect(isSelecting) {
        onRootNavigationVisibilityChange(!isSelecting)
    }
    // 首个 Room 会话快照到达即触发（轻量启动面用它上报
    // home_content_ready，等价于已删除的 NovexConversationRoot 的
    // onContentLoaded → availability.contentReady 时机）。
    LaunchedEffect(isInitialLoadComplete) {
        if (isInitialLoadComplete) onContentLoaded()
    }

    // home-v2 会话行卡片标签 + primary 缩略图：IO 线程一次性构建，库变化时
    // 随 sessions 刷新重建。
    val cardReader: novex.android.CardSessionModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "noven-session-cards",
    )
    // 依赖键只放真正影响卡片面信息的字段 —— 流式刷 lastMessage 时不再
    // 整份重开卡片文档。
    val cardFaceKey = sessions.map {
        listOf(it.id, it.novexConfigurationJson, it.worldId, it.characterId,
            it.characterVersionId, it.personaId)
    }
    val cardFaces by androidx.compose.runtime.produceState<Map<String, com.openminis.app.ui.noven.SessionCardFace>>(
        initialValue = emptyMap(),
        cardFaceKey,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.openminis.app.ui.noven.sessionCardFaces(
                    sessions,
                    com.openminis.app.cards.IntegratedCards(context.applicationContext).store,
                )
            }.getOrDefault(emptyMap())
        }
    }

    BackHandler(enabled = novexHomeBackAction(searchActive = isSearchActive) == NovexHomeBackAction.CLOSE_SEARCH) {
        viewModel.searchQuery.value = ""
        viewModel.isSearchActive.value = false
    }
    // 多选时返回键先退出多选（clearSelection 同时复位 isSelecting →
    // 上面的 LaunchedEffect 会把底栏重新打开）。没有这一层时返回被根页面
    // 的 GoHome 抢走，底栏停在隐藏态没有恢复路径。
    BackHandler(enabled = isSelecting) { viewModel.clearSelection() }

    // [T-android-search-focus-sticky] When the user opens search but types
    // nothing (or only whitespace) and then navigates into a chat, the
    // VM-backed search state survives the navigation, so on return the search
    // bar is still open and focused — the user has to manually tap the X to
    // close it. Collapse search BEFORE navigating when the query is blank;
    // keep it (query + results) when there's a real query so returning lands
    // back on the same search. Collapsing flips isSearchActive false, which
    // removes the search TextField from composition and releases its focus.
    fun exitSearchIfQueryBlank() {
        if (viewModel.isSearchActive.value && viewModel.searchQuery.value.isBlank()) {
            viewModel.searchQuery.value = ""
            viewModel.isSearchActive.value = false
        }
    }
    // Wrapped navigation callbacks: run the search-collapse check first, then
    // navigate. Used everywhere a session tap / new-chat creation navigates.
    // [T-session-paused-badge-active-false-positive] Do NOT clear the PAUSED
    // badge on open: merely opening an interrupted session does not resolve it.
    // The badge is now driven by ChatViewModel's canResume flow — it clears only
    // when the interruption is actually resolved (Resume tapped / new message
    // sent / loop completed), and the ChatViewModel re-asserts it on load if the
    // session is still interrupted. Clearing here just caused a flicker.
    val onSessionClickGuarded: (String) -> Unit = { id ->
        exitSearchIfQueryBlank()
        onSessionClick(id)
    }
    val onNewChatGuarded: (String) -> Unit = { id -> exitSearchIfQueryBlank(); onNewChat(id) }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteTargetId by remember { mutableStateOf<String?>(null) }
    // [T-android-session-grouping] Group management dialogs.
    var folderToRename by remember { mutableStateOf<FolderEntity?>(null) }
    var folderToDissolve by remember { mutableStateOf<FolderEntity?>(null) }
    // iOS "Delete Group & N Sessions" — pair carries the member count so the
    // confirmation can restate the consequence.
    var folderToDelete by remember { mutableStateOf<Pair<FolderEntity, Int>?>(null) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var editSession by remember { mutableStateOf<ChatSessionEntity?>(null) }

    // [T-android-session-grouping] Groups are pulled out FIRST; only the
    // leftovers go through date bucketing. Assembly order below is
    // Pinned → group block → date buckets, matching iOS.
    val folders by viewModel.folders.collectAsState()
    val collapsedFolderIds by viewModel.collapsedFolderIds.collectAsState()
    val folderMemberCounts by viewModel.folderMemberCounts.collectAsState()
    val groupPickerRequest by viewModel.groupPickerRequest.collectAsState()
    // While searching, group cards are suppressed: padding a result set with
    // every non-matching group is noise, not structure.
    val showFolderBlock = !isSearchActive || searchQuery.isBlank()
    val folderPartition = remember(sessions, folders, collapsedFolderIds, showFolderBlock) {
        if (showFolderBlock) partitionByFolder(sessions, folders, collapsedFolderIds)
        else emptyList<FolderGroupBlock>() to sessions
    }
    val folderBlocks = folderPartition.first
    val groupedSessions = remember(folderPartition) { groupSessionsByDate(folderPartition.second) }

    // [T-android-folder-accordion-anchor] Assembly order (Pinned → groups →
    // date buckets) hoisted OUT of the LazyColumn body so the scroll-anchor
    // effect and the mini-bar can reconstruct each folder header's flat item
    // index. MUST stay in lockstep with the item builder below — same data,
    // same order, one item per header/row.
    val pinnedFirst = groupedSessions.firstOrNull()?.first == DatePeriod.PINNED
    val leadingDateGroups = if (pinnedFirst) groupedSessions.take(1) else emptyList()
    val trailingDateGroups = if (pinnedFirst) groupedSessions.drop(1) else groupedSessions
    val folderHeaderIndices = remember(leadingDateGroups, folderBlocks) {
        buildMap {
            var idx = 0
            // 每组日期区块 = 组标题 1 项 + 每行 1 个 lazy item（行不再整组打包）。
            leadingDateGroups.forEach { (_, rows) -> idx += 1 + rows.size }
            if (folderBlocks.isNotEmpty()) {
                idx += 1 // the "分组" section header item
                folderBlocks.forEach { b ->
                    put(b.folder.id, idx)
                    idx += 1 + b.ids.size
                }
            }
        }
    }

    // [T-android-newchat-list-autoscroll] Hoisted scroll state so the VM's
    // new-session signal can drive it. The list is ORDER BY updated_at DESC,
    // so a freshly-used session lands at index 0 (top of the TODAY bucket).
    // But the LazyColumn retains its scroll offset across navigation (open
    // chat → back), so if the user had scrolled down, the new session sits
    // above the viewport and they have to scroll up to find it (Jackson 41429).
    //
    // The new-session DETECTION lives in the VM (retained across navigation)
    // because the list composable is disposed during the chat-detail push — a
    // composable-scoped tracker would reset its baseline on pop-back and miss
    // the new session that appeared while we were in the chat. Here we just
    // collect the one-shot event and scroll, skipping it during search (which
    // reorders the list) and selection mode (so it can't fight #765 multi-select).
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        viewModel.newTopSessionEvent.collect {
            if (!viewModel.isSearchActive.value && !viewModel.isSelecting.value) {
                listState.animateScrollToItem(0)
            }
        }
    }

    // [T-android-folder-accordion-anchor] Port of iOS toggleFolderCollapsed's
    // scroll correction. Opening folder B closes folder A (accordion); when A
    // sat ABOVE B with a long member list, A's rows vanish and B slides up —
    // often clean off the top edge, leaving the user staring at the wrong part
    // of the list. MINIMAL correction, not "scroll to top": after the
    // structural change lands (two frames — same reason iOS defers a runloop
    // turn: measuring now would read pre-collapse geometry), scroll B's header
    // back only if it actually LEFT the viewport. Expand-only — collapsing is
    // self-anchoring (the tapped header stays under the finger).
    var pendingExpandFolderId by remember { mutableStateOf<String?>(null) }
    val densityForAnchor = LocalDensity.current
    LaunchedEffect(pendingExpandFolderId) {
        val fid = pendingExpandFolderId ?: return@LaunchedEffect
        withFrameNanos {}
        withFrameNanos {}
        val key = "folder_$fid"
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.key == key }
        // 8dp slack so a header sitting exactly on the boundary isn't judged
        // off-screen by a sub-pixel rounding difference.
        val slack = with(densityForAnchor) { 8.dp.toPx() }.toInt()
        val offscreen = item == null ||
            item.offset < layout.viewportStartOffset - slack ||
            item.offset + item.size > layout.viewportEndOffset + slack
        if (offscreen) {
            folderHeaderIndices[fid]?.let { listState.animateScrollToItem(it) }
        }
        pendingExpandFolderId = null
    }

    // [T-android-folder-minibar] Port of iOS folderMiniBar: when an EXPANDED
    // group's header scrolls off the TOP, float a capsule with the group's
    // icon + name (tap → jump back to the header) and a circled chevron (tap
    // → collapse). Derived straight from LazyListState — no visibility probes
    // needed: header offscreen-above ⇔ the first visible item's index is past
    // the header's reconstructed index. Suppressed in select mode (iOS guard).
    val miniBarBlock by remember(folderBlocks, folderHeaderIndices, isSelecting) {
        derivedStateOf {
            if (isSelecting) return@derivedStateOf null
            val block = folderBlocks.firstOrNull { !it.isCollapsed && it.ids.isNotEmpty() }
                ?: return@derivedStateOf null
            val headerIdx = folderHeaderIndices[block.folder.id] ?: return@derivedStateOf null
            val infos = listState.layoutInfo.visibleItemsInfo
            when {
                infos.isEmpty() -> null
                infos.any { it.key == "folder_${block.folder.id}" } -> null
                infos.first().index > headerIdx -> block
                else -> null
            }
        }
    }

    // [T-android-scheduled-tasks-full] 注：定时任务角标（scheduledTaskCount）
    // 在 home-v2 顶栏改版后已无渲染点，相关 collect 已移除；入口回调
    // onScheduledTasksClick 保留在签名里供后续菜单项使用。

    Scaffold(
        containerColor = com.openminis.app.ui.noven.NovenColors.Canvas,
        topBar = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 6.dp, bottom = 4.dp)
                    .height(44.dp),
            ) {
                if (isSelecting) {
                    MinisTextButton(onClick = { viewModel.clearSelection() }) {
                        Text(stringResource(R.string.cancel))
                    }
                    Text(
                        if (selectedIds.isEmpty())
                            stringResource(R.string.sessionlist_select_title)
                        else
                            stringResource(R.string.sessionlist_n_selected, selectedIds.size),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    MinisTextButton(onClick = { viewModel.selectAll() }) {
                        Text(
                            stringResource(
                                if (selectedIds.size == sessions.size) R.string.sessionlist_deselect_all
                                else R.string.sessionlist_select_all
                            )
                        )
                    }
                } else {
                    Text(
                        "会话",
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp,
                        color = com.openminis.app.ui.noven.NovenColors.Text,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        if (isSearchActive) {
                            viewModel.searchQuery.value = ""
                            viewModel.isSearchActive.value = false
                        } else {
                            viewModel.isSearchActive.value = true
                        }
                    }) {
                        Icon(
                            painterResource(R.drawable.ic_phosphor_search),
                            contentDescription = stringResource(R.string.sessionlist_search_action),
                            tint = com.openminis.app.ui.noven.NovenColors.Text,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    // 薄荷绿胶囊：+ 新对话
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .height(32.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(com.openminis.app.ui.noven.NovenColors.Mint)
                            .clickable {
                                scope.launch {
                                    viewModel.createNewSession()?.let(onNewChatGuarded)
                                }
                            }
                            .padding(horizontal = 10.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_phosphor_plus),
                            contentDescription = null,
                            tint = com.openminis.app.ui.noven.NovenColors.OnMint,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            "新对话",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = com.openminis.app.ui.noven.NovenColors.OnMint,
                        )
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                painterResource(R.drawable.ic_phosphor_more_vertical),
                                contentDescription = stringResource(R.string.novex_create_menu),
                                tint = com.openminis.app.ui.noven.NovenColors.Text,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        MinisMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                            offset = DpOffset(0.dp, 0.dp),
                        ) {
                            if (sessions.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sessionlist_select_action)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.isSelecting.value = true
                                    },
                                    leadingIcon = {
                                        Icon(com.openminis.app.ui.novex.NovexIcons.ChecklistRtl, contentDescription = null)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        // No default FAB — 新对话入口在顶栏薄荷绿胶囊。
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Main content — render an empty frame until the first DB
            // emission lands. Otherwise `sessions.isEmpty()` reads true for
            // the brief window before Room delivers real data and the
            // onboarding flashes on top of existing user history. Mirrors
            // iOS `didInitialLoad` on ContentView. The transition is usually
            // sub-200ms, so no spinner.
            if (isInitialLoadComplete && worldsLoaded) Column(modifier = Modifier.fillMaxSize()) {
                if (isSearchActive) {
                    SessionInlineSearchField(
                        valueFlow = viewModel.searchQuery,
                        searchingFlow = viewModel.isSearching,
                        onValueChange = { viewModel.searchQuery.value = it },
                        onDismiss = {
                            viewModel.searchQuery.value = ""
                            viewModel.isSearchActive.value = false
                        },
                    )
                }
                // 「按卡片查找对话」从被删的 NovexConversationRoot 搬入统一
                // 列表：只依赖 Room + 卡片库（novexWorkGroups / novexWorkspace
                // 均为 minimum 子系统），轻量启动面同样可用。多选时隐藏。
                if (!isSelecting) {
                    com.openminis.app.ui.novex.NovexConversationCardLookup(onSessionClickGuarded)
                }
                // 轻量启动面读不到 provider 配置：worlds 不再参与空态判定 —
                // 有卡无会话同样落到普通空态，而不是一张空白列表。
                val listEmpty = sessions.isEmpty() &&
                    (!providerRuntimeAvailable || worlds.isEmpty())
                if (listEmpty) {
                    if (isSearchActive && searchQuery.isNotBlank()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.search_no_results, searchQuery),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (!providerRuntimeAvailable) {
                        // 轻量启动面：provider 运行时未初始化，「添加服务商 /
                        // 选模型」引导不可用 —— 按用户拍板显示普通空态（等价
                        // 于被删的 NovexConversationRoot 的空列表），新建对话
                        // 走 openLegacy → ensureRuntime 再拉起运行时。
                        SessionListEmptyState(
                            onNewChat = {
                                scope.launch {
                                    viewModel.createNewSession()?.let(onNewChatGuarded)
                                }
                            },
                        )
                    } else if (configLoaded) {
                        // Show the 3-step onboarding whenever there are no sessions —
                        // Step 3 (Start a Conversation) is the call-to-action after the
                        // user finishes Steps 1 and 2, so we must keep the landing
                        // visible even when hasProviders && hasGroups. Mirrors iOS
                        // ContentView.emptyState.
                        // [T-android-startup-config-stall] Gated on configLoaded so
                        // hasProviders/hasGroups reflect the real persisted config —
                        // otherwise a returning user with providers but no sessions
                        // would briefly see the "add a provider" step before the
                        // async config load emits. The list branch (sessions present)
                        // is intentionally NOT gated, so users with history still see
                        // it immediately without waiting on the config decode.
                        OnboardingLanding(
                            hasProviders = hasProviders,
                            hasGroups = hasGroups,
                            onAddProvider = onAddProviderClick,
                            onSelectModels = onSelectModelsClick,
                            onStartConversation = {
                                scope.launch {
                                    val sessionId = viewModel.createNewSession()
                                    if (sessionId != null) onNewChatGuarded(sessionId)
                                }
                            },
                        )
                    }
                } else {
                    LazyColumn(
                        // [T-android-newchat-list-autoscroll] Hoisted state so
                        // the new-session autoscroll effect above can drive it.
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // Leave space for bottom FAB row
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
                    ) {
                        // T25: search-active path used to flatten the list and skip
                        // section headers entirely. Now reuses the same grouped
                        // rendering — `displayedSessions` is already filtered by
                        // the VM when active && query.isNotBlank, so the same
                        // groupSessionsByDate(sessions) computation produces
                        // header buckets over the filtered set.
                        // [T-android-session-grouping] Assembly: Pinned →
                        // groups → date buckets — pinnedFirst / leading /
                        // trailing are hoisted above the LazyColumn so the
                        // accordion anchor + mini-bar share them; keep the
                        // builder and folderHeaderIndices in lockstep.

                        // [T-android-session-grouping] Membership is "points at a
                        // group that exists", matching partitionByFolder. Hoisted
                        // out of the row so it is not rebuilt per item.
                        val existingFolderIds = folders.mapTo(HashSet()) { it.id }

                        @Composable
                        fun sessionRowContent(session: ChatSessionEntity) {
                            val activeQuery =
                                if (isSearchActive && searchQuery.isNotBlank()) searchQuery else ""
                            SessionItemContent(
                                session = session,
                                isSelecting = isSelecting,
                                selectedIds = selectedIds,
                                onSessionClick = onSessionClickGuarded,
                                onToggleSelect = { viewModel.toggleSelect(it) },
                                onEnterSelect = { viewModel.enterSelection(it) },
                                onPinToggle = { viewModel.togglePin(it) },
                                onEditRequest = { editSession = it },
                                onExportRequest = { s, fmt ->
                                    exportSession(context, s, chatRepository, scope, fmt)
                                },
                                onRegenerateTitle = { viewModel.regenerateTitle(it) },
                                canRegenerateTitle = providerRuntimeAvailable,
                                onDuplicate = { viewModel.duplicateSession(it) },
                                onDeleteRequest = { id ->
                                    deleteTargetId = id
                                    showDeleteDialog = true
                                },
                                onMoveToGroup = { viewModel.requestGroupPicker(it) },
                                isFiled = session.folderId != null &&
                                    session.folderId in existingFolderIds,
                                isRegenerating = session.id in regeneratingIds,
                                searchQuery = activeQuery,
                                searchSnippet = searchSnippets[session.id],
                                // Transparent so the enclosing group/folder
                                // container's surface shows through.
                                rowBackground = Color.Transparent,
                                cardFace = cardFaces[session.id],
                                cardReader = cardReader,
                            )
                        }

                        // [T-android-folder-card-ios-parity] Folder members
                        // render as MIDDLE/BOTTOM segments of the group's
                        // welded container; per-item animateItem gives the
                        // accordion its motion.
                        fun androidx.compose.foundation.lazy.LazyListScope.renderFolderRows(
                            rows: List<ChatSessionEntity>,
                        ) {
                            items(rows, key = { it.id }) { session ->
                                val isLast = session.id == rows.last().id
                                Box(
                                    modifier = Modifier
                                        .animateItem(
                                            fadeInSpec = tween(250),
                                            fadeOutSpec = tween(250),
                                            placementSpec = tween(250),
                                        )
                                        .padding(
                                            start = 6.dp, end = 6.dp,
                                            bottom = if (isLast) 4.dp else 0.dp,
                                        )
                                        .folderSurface(
                                            segment = if (isLast) FolderSegment.BOTTOM
                                            else FolderSegment.MIDDLE,
                                            fill = folderFillColor(),
                                            edge = folderEdgeColor(),
                                        )
                                        .clip(
                                            if (isLast) {
                                                RoundedCornerShape(
                                                    bottomStart = 16.dp, bottomEnd = 16.dp,
                                                )
                                            } else {
                                                RoundedCornerShape(0.dp)
                                            },
                                        ),
                                ) {
                                    sessionRowContent(session)
                                }
                            }
                        }

                        // home-v2：分组容器外观由每行自己的 Surface 背景加
                        // 位置相关圆角拼出（novenSessionGroupRowShape），每行
                        // 仍是独立 lazy item；行间 0.5dp 分隔线从 72dp 开始。
                        fun androidx.compose.foundation.lazy.LazyListScope.renderSessionGroup(
                            key: String,
                            rows: List<ChatSessionEntity>,
                        ) {
                            itemsIndexed(
                                rows,
                                key = { _, session -> "group_${key}_${session.id}" },
                            ) { index, session ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp)
                                        .animateItem(placementSpec = tween(250)),
                                ) {
                                    Box(
                                        Modifier
                                            .clip(novenSessionGroupRowShape(index, rows.size))
                                            .background(com.openminis.app.ui.noven.NovenColors.Surface),
                                    ) {
                                        sessionRowContent(session)
                                    }
                                    if (index < rows.lastIndex) {
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(start = 72.dp)
                                                .height(0.5.dp)
                                                .background(com.openminis.app.ui.noven.NovenColors.Divider),
                                        )
                                    }
                                }
                            }
                        }

                        leadingDateGroups.forEach { (period, periodSessions) ->
                            item(key = "header_${period.name}") {
                                SectionHeader(title = stringResource(R.string.sessionlist_section_pinned))
                            }
                            renderSessionGroup("pinned", periodSessions)
                        }

                        if (folderBlocks.isNotEmpty()) {
                            item(key = "header_groups") {
                                SectionHeader(title = stringResource(R.string.group_section_header))
                            }
                            folderBlocks.forEach { block ->
                                item(key = "folder_${block.folder.id}") {
                                    Box(Modifier.animateItem(placementSpec = tween(250))) {
                                    FolderCard(
                                        block = block,
                                        onToggle = {
                                            // Capture BEFORE the toggle — after it
                                            // the block still holds the old state.
                                            val willExpand = block.isCollapsed
                                            viewModel.toggleFolderCollapsed(block.folder.id)
                                            if (willExpand) {
                                                pendingExpandFolderId = block.folder.id
                                            }
                                        },
                                        onTogglePin = { viewModel.toggleFolderPin(block.folder.id) },
                                        onRename = { folderToRename = block.folder },
                                        onDissolve = { folderToDissolve = block.folder },
                                        onNewChatInGroup = {
                                            // iOS newChatInFolder: auto-expand
                                            // first so the new session doesn't
                                            // vanish into a collapsed group.
                                            if (block.isCollapsed) {
                                                viewModel.toggleFolderCollapsed(block.folder.id)
                                            }
                                            val sessionId = viewModel.createNewSession(
                                                folderId = block.folder.id,
                                            )
                                            if (sessionId != null) onNewChatGuarded(sessionId)
                                        },
                                        onDeleteWithSessions = {
                                            folderToDelete = block.folder to block.totalCount
                                        },
                                    )
                                    }
                                }
                                // Collapsed groups contribute no rows; the card
                                // still reports the real member count.
                                renderFolderRows(
                                    block.ids.mapNotNull { id -> sessions.firstOrNull { it.id == id } },
                                )
                            }
                        }

                        trailingDateGroups.forEach { (period, periodSessions) ->
                            item(key = "header_${period.name}") {
                                SectionHeader(title = stringResource(when (period) {
                                    DatePeriod.PINNED -> R.string.sessionlist_section_pinned
                                    DatePeriod.TODAY -> R.string.sessionlist_section_today
                                    DatePeriod.EARLIER -> R.string.sessionlist_section_earlier
                                }))
                            }
                            renderSessionGroup(period.name, periodSessions)
                        }
                    }
                }
            }

            // [T-android-folder-minibar] Floating quick-nav capsule (iOS
            // folderMiniBar): appears when the expanded group's header scrolls
            // off the top. Two interaction zones — icon+name jumps back to the
            // header, the circled chevron collapses the group. Split on
            // purpose: whole-bar-collapses made "where am I" and "close this"
            // the same target.
            run {
                var lastBar by remember { mutableStateOf<FolderGroupBlock?>(null) }
                miniBarBlock?.let { lastBar = it }
                val bar = lastBar
                AnimatedVisibility(
                    visible = miniBarBlock != null,
                    enter = slideInVertically(tween(200)) { -it } + fadeIn(tween(200)),
                    exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(200)),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    if (bar != null) {
                        val headerIdx = folderHeaderIndices[bar.folder.id]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 8.dp, start = 24.dp, end = 24.dp)
                                .widthIn(max = 320.dp)
                                .height(48.dp)
                                .shadow(8.dp, RoundedCornerShape(24.dp))
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .border(
                                    0.5.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(24.dp),
                                )
                                .padding(start = 10.dp, end = 8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable(enabled = headerIdx != null) {
                                        scope.launch {
                                            headerIdx?.let { listState.animateScrollToItem(it) }
                                        }
                                    },
                            ) {
                                FolderComposedIcon(
                                    category = bar.firstCategory,
                                    diameter = 30.dp,
                                )
                                // Folder names are user data — verbatim.
                                Text(
                                    bar.folder.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                    )
                                    .clickable {
                                        viewModel.toggleFolderCollapsed(bar.folder.id)
                                    },
                            ) {
                                Icon(
                                    com.openminis.app.ui.novex.NovexIcons.KeyboardArrowUp,
                                    contentDescription = stringResource(R.string.group_collapse),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Bottom area: dual FABs or selection toolbar (matching iOS fabRow / selectionToolbar)
            if (isSelecting) {
                // Selection toolbar at bottom (matching iOS: Export + Delete)
                SelectionToolbar(
                    selectedCount = selectedIds.size,
                    onExport = { /* TODO: export */ },
                    onMove = { viewModel.requestGroupPickerForSelection() },
                    onDelete = { showBulkDeleteDialog = true },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    // Single delete confirmation
    if (showDeleteDialog && deleteTargetId != null) {
        MinisAlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                deleteTargetId = null
            },
            title = stringResource(R.string.sessionlist_delete_one_title),
            text = stringResource(R.string.sessionlist_delete_message),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                deleteTargetId?.let { viewModel.deleteSession(it) }
                showDeleteDialog = false
                deleteTargetId = null
            },
        )
    }

    // Bulk delete confirmation
    if (showBulkDeleteDialog) {
        MinisAlertDialog(
            onDismissRequest = { showBulkDeleteDialog = false },
            title = stringResource(R.string.sessionlist_delete_n_title, selectedIds.size),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                viewModel.deleteSelected()
                showBulkDeleteDialog = false
            },
        )
    }

    // ─── Session groups ────────────────────────────────────────────────────
    // [T-android-session-grouping]

    groupPickerRequest?.let { request ->
        // [T-android-group-ai-suggest] Suggestion state lives on the VM, not
        // in the sheet, so an in-flight request survives recomposition (and
        // the sheet's own remembered state being torn down).
        val suggesting by viewModel.groupSuggesting.collectAsState()
        val suggestFailed by viewModel.groupSuggestFailed.collectAsState()
        val suggestion by viewModel.groupSuggestion.collectAsState()
        GroupPickerSheet(
            folders = folders,
            memberCounts = folderMemberCounts,
            sessionCount = request.sessionIds.size,
            anyFiled = request.anyFiled,
            onChoose = { viewModel.applyGroupChoice(it) },
            onDismiss = { viewModel.dismissGroupPicker() },
            suggesting = suggesting,
            suggestFailed = suggestFailed,
            suggestion = suggestion,
            // 需要 provider 运行时（sub model 调 LLM）：轻量启动面传 null，
            // GroupPickerSheet 内部会隐藏 AI Suggest 入口而不是留死按钮。
            onSuggest = if (providerRuntimeAvailable) ({ viewModel.suggestGroup() }) else null,
        )
    }

    folderToRename?.let { folder ->
        // Both fields are SEEDED from the current group. The rename always
        // writes the description through, so an unseeded field would silently
        // wipe a description the user never touched.
        var name by remember(folder.id) { mutableStateOf(folder.name) }
        var desc by remember(folder.id) { mutableStateOf(folder.description.orEmpty()) }
        // A plain AlertDialog rather than MinisAlertDialog: this one needs two
        // text fields, and MinisAlertDialog is a title/text/buttons component.
        // Widening it for a single caller would push layout complexity into
        // every other dialog in the app.
        com.openminis.app.ui.novex.AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text(stringResource(R.string.group_rename)) },
            text = {
                Column {
                    // SectionTextField is built for settings screens: it draws
                    // NO border and uses horizontal contentPadding = 0, because
                    // there its parent (SettingsCardBlock) supplies both the
                    // 16dp inset and the card surface that bounds it. A dialog
                    // has neither, so used bare the glyphs sat flush against
                    // the fill and the two fields read as one block. Wrap each
                    // one the way a settings card would, plus a hairline border
                    // so the input edge is visible on the dialog's own surface.
                    DialogTextFieldFrame {
                        SectionTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = stringResource(R.string.group_name_hint),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    DialogTextFieldFrame {
                        SectionTextField(
                            value = desc,
                            onValueChange = { desc = it.take(FolderEntity.DESC_MAX_CHARS) },
                            placeholder = stringResource(R.string.group_desc_hint),
                        )
                    }
                }
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    viewModel.renameFolder(folder.id, name, desc)
                    folderToRename = null
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { folderToRename = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    folderToDissolve?.let { folder ->
        val count = folderMemberCounts[folder.id] ?: 0
        MinisAlertDialog(
            onDismissRequest = { folderToDissolve = null },
            title = stringResource(R.string.group_dissolve_confirm_title),
            // Spells out that nothing is deleted — dissolve is deliberately NOT
            // styled destructive, because it touches no user data.
            text = stringResource(R.string.group_dissolve_confirm_message, count),
            confirmText = stringResource(R.string.group_dissolve),
            onConfirm = {
                viewModel.dissolveFolder(folder.id)
                folderToDissolve = null
            },
        )
    }

    // iOS "Delete Group & N Sessions" confirmation — the one destructive
    // folder action, so isDestructive here where dissolve deliberately isn't.
    folderToDelete?.let { (folder, count) ->
        MinisAlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = stringResource(R.string.group_delete_confirm_title),
            text = stringResource(R.string.group_delete_confirm_message, count),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                viewModel.deleteFolderWithSessions(folder.id)
                folderToDelete = null
            },
        )
    }

    // Edit Title & Category sheet (matching iOS SessionEditSheet)
    editSession?.let { session ->
        // Track the live DB-backed row for this session so a Regenerate-Title
        // run (which writes title/category to the DB) flows back into the sheet
        // without the user reopening it. displayedSessions observes the DB.
        val liveSession = sessions.firstOrNull { it.id == session.id } ?: session
        SessionEditSheet(
            session = session,
            liveSession = liveSession,
            isRegenerating = session.id in regeneratingIds,
            // 轻量启动面无 provider 运行时：隐藏 Regenerate 按钮。
            canRegenerate = providerRuntimeAvailable,
            onRegenerate = { viewModel.regenerateTitle(session.id) },
            onDismiss = { editSession = null },
            onSave = { title, category ->
                viewModel.updateTitleAndCategory(session.id, title, category)
                editSession = null
            },
        )
    }

}

// ─── Selection Toolbar (matching iOS selectionToolbar) ──────────────────────

@Composable
private fun SelectionToolbar(
    selectedCount: Int,
    onExport: () -> Unit,
    /** [T-android-session-grouping] Bulk-file the selection into a group. */
    onMove: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        // Export button (matching iOS)
        MinisTextButton(
            onClick = onExport,
            enabled = selectedCount > 0,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    com.openminis.app.ui.novex.NovexIcons.Share,
                    contentDescription = stringResource(R.string.sessionlist_export),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.sessionlist_export), fontSize = 11.sp)
            }
        }

        // Move to Group button
        MinisTextButton(
            onClick = onMove,
            enabled = selectedCount > 0,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    com.openminis.app.ui.novex.NovexIcons.Folder,
                    contentDescription = stringResource(R.string.group_move_action),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.group_move_action), fontSize = 11.sp)
            }
        }

        // Delete button (matching iOS)
        MinisTextButton(
            onClick = onDelete,
            enabled = selectedCount > 0,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    com.openminis.app.ui.novex.NovexIcons.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = if (selectedCount > 0) MaterialTheme.colorScheme.error else Color.Gray,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.delete),
                    fontSize = 11.sp,
                    color = if (selectedCount > 0) MaterialTheme.colorScheme.error else Color.Gray,
                )
            }
        }
    }
}


@Composable
private fun SessionInlineSearchField(
    valueFlow: StateFlow<String>,
    searchingFlow: StateFlow<Boolean>,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val value by valueFlow.collectAsState()
    val searching by searchingFlow.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = 12.dp, end = 4.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_phosphor_search),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            if (value.isBlank()) {
                Text(
                    stringResource(R.string.search_chats_placeholder),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
        if (searching) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.padding(end = 11.dp).size(17.dp),
                strokeWidth = 2.dp,
            )
        } else {
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(com.openminis.app.ui.novex.NovexIcons.Close, contentDescription = stringResource(R.string.sessionlist_dismiss))
            }
        }
    }
}


@Composable
private fun SectionHeader(title: String) {
    // T172: title may now be a localized string, so compare against the
    // localized "Pinned" rather than the hardcoded enum label.
    val isPinned = title == stringResource(R.string.sessionlist_section_pinned)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp, bottom = 8.dp),
    ) {
        if (isPinned) {
            Icon(
                imageVector = com.openminis.app.ui.novex.NovexIcons.PushPin,
                contentDescription = null,
                tint = com.openminis.app.ui.noven.NovenColors.Secondary,
                modifier = Modifier
                    .size(13.dp)
                    .padding(end = 0.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = com.openminis.app.ui.noven.NovenColors.Secondary,
        )
    }
}

// ─── Session Item (context menu replaces swipe-to-delete, matching iOS) ─────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionItemContent(
    session: ChatSessionEntity,
    isSelecting: Boolean,
    selectedIds: Set<String>,
    onSessionClick: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    // [T-android-sessionlist-longpress-select] Context-menu Select: enters
    // selection mode with this row selected (distinct from onToggleSelect,
    // which only flips set membership while ALREADY selecting).
    onEnterSelect: (String) -> Unit,
    onPinToggle: (String) -> Unit,
    onEditRequest: (ChatSessionEntity) -> Unit,
    onExportRequest: (ChatSessionEntity, String) -> Unit,
    onRegenerateTitle: (String) -> Unit,
    /** 轻量启动面（无 provider 运行时）隐藏「重新生成标题」菜单项。 */
    canRegenerateTitle: Boolean = true,
    onDuplicate: (String) -> Unit,
    onDeleteRequest: (String) -> Unit,
    /** [T-android-session-grouping] Opens the group picker for this session. */
    onMoveToGroup: (String) -> Unit,
    /**
     * [T-android-session-grouping] True only when this session belongs to a group
     * that ACTUALLY EXISTS locally — not merely `folderId != null`.
     *
     * A dangling folder_id renders as ungrouped (see partitionByFolder), so
     * deciding the wording from the raw id alone made the row and its menu
     * disagree: the session sat in the date buckets while its menu offered
     * "更换分组". The caller resolves membership the same way the list does.
     */
    isFiled: Boolean,
    isRegenerating: Boolean = false,
    searchQuery: String = "",
    searchSnippet: String? = null,
    /**
     * [T-android-folder-card-ios-parity] Overrides the row's own surface
     * background. Folder members pass Transparent so the group container's
     * welded fill shows through; null keeps the default surface.
     */
    rowBackground: Color? = null,
    /** home-v2：解析好的卡片标签与 primary 缩略图。 */
    cardFace: com.openminis.app.ui.noven.SessionCardFace? = null,
    cardReader: novex.android.CardSessionModel? = null,
) {
    if (isSelecting) {
        val isSelected = session.id in selectedIds
        NovenSessionRow(
            session = session,
            onClick = { onToggleSelect(session.id) },
            onLongClick = null,
            searchQuery = searchQuery,
            searchSnippet = searchSnippet,
            rowBackground = rowBackground,
            cardFace = cardFace,
            cardReader = cardReader,
            leadingIcon = {
                Icon(
                    imageVector = if (isSelected) com.openminis.app.ui.novex.NovexIcons.CheckCircle else com.openminis.app.ui.novex.NovexIcons.Circle,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            },
        )
    } else {
        var showContextMenu by remember { mutableStateOf(false) }
        var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
        // [T-android-menu-press-side] Which HALF of the row the finger was on.
        // Pressing on the right used to left-anchor the menu at the finger,
        // overflow the window, and get clamped left — so the popup (and its
        // top-LEFT-origin scale animation) visually appeared to the left of
        // the finger. Right-half presses now anchor the menu's RIGHT edge at
        // the press point with a matching top-right animation origin, so the
        // menu hangs off the finger naturally on both sides.
        var menuAlignEnd by remember { mutableStateOf(false) }
        var rowWidthPx by remember { mutableFloatStateOf(0f) }
        val density = LocalDensity.current
        val isPinned = session.pinnedAt != null

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowWidthPx = it.width.toFloat() },
        ) {
            NovenSessionRow(
                session = session,
                onClick = { onSessionClick(session.id) },
                searchQuery = searchQuery,
                searchSnippet = searchSnippet,
                rowBackground = rowBackground,
                cardFace = cardFace,
                cardReader = cardReader,
                onLongClick = { offsetPx ->
                    pressOffset = with(density) {
                        DpOffset(offsetPx.x.toDp(), offsetPx.y.toDp())
                    }
                    menuAlignEnd = rowWidthPx > 0f && offsetPx.x > rowWidthPx / 2f
                    showContextMenu = true
                },
                // [T-android-sessionrow-overflow] 可见的 ⋮ 入口（用户决策 2026-09-14：
                // "做成三点菜单，点开就是置顶或者删除"）。置顶此前只藏在长按菜单里，
                // 没有可见入口，测试者以为功能没了。长按完整菜单保留不动。
                trailing = {
                    var rowMenuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { rowMenuOpen = true },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                com.openminis.app.ui.novex.NovexIcons.MoreVert,
                                contentDescription = stringResource(R.string.sessionlist_row_actions),
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        MinisMenu(
                            expanded = rowMenuOpen,
                            onDismissRequest = { rowMenuOpen = false },
                            alignEnd = true,
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (isPinned) R.string.sessionlist_unpin else R.string.sessionlist_pin)) },
                                onClick = { rowMenuOpen = false; onPinToggle(session.id) },
                                leadingIcon = {
                                    Icon(
                                        if (isPinned) com.openminis.app.ui.novex.NovexIcons.Close else com.openminis.app.ui.novex.NovexIcons.PushPin,
                                        contentDescription = null,
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                onClick = { rowMenuOpen = false; onDeleteRequest(session.id) },
                                leadingIcon = {
                                    Icon(
                                        com.openminis.app.ui.novex.NovexIcons.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                            )
                        }
                    }
                },
            )
            // Loading overlay when regenerating title
            if (isRegenerating) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            stringResource(R.string.sessionlist_regenerating_title),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            // Invisible zero-size anchor at the press position — DropdownMenu
            // will open from here so it follows the touch point.
            Box(
                modifier = Modifier
                    .offset(x = pressOffset.x, y = pressOffset.y)
                    .size(1.dp),
            ) {
                MinisMenu(
                    expanded = showContextMenu,
                    onDismissRequest = { showContextMenu = false },
                    alignEnd = menuAlignEnd,
                ) {
                // Pin / Unpin
                DropdownMenuItem(
                    text = { Text(stringResource(if (isPinned) R.string.sessionlist_unpin else R.string.sessionlist_pin)) },
                    onClick = {
                        showContextMenu = false
                        onPinToggle(session.id)
                    },
                    leadingIcon = {
                        Icon(
                            if (isPinned) com.openminis.app.ui.novex.NovexIcons.Close else com.openminis.app.ui.novex.NovexIcons.PushPin,
                            contentDescription = null,
                        )
                    },
                )
                // Export submenu (JSON / Plain Text)
                var showExportSub by remember { mutableStateOf(false) }
                DropdownMenuItem(
                    text = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.sessionlist_export))
                            Icon(com.openminis.app.ui.novex.NovexIcons.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    },
                    onClick = { showExportSub = !showExportSub },
                    leadingIcon = {
                        Icon(com.openminis.app.ui.novex.NovexIcons.Share, contentDescription = null)
                    },
                )
                if (showExportSub) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessionlist_export_json), modifier = Modifier.padding(start = 24.dp)) },
                        onClick = {
                            showContextMenu = false
                            onExportRequest(session, "json")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessionlist_export_plain), modifier = Modifier.padding(start = 24.dp)) },
                        onClick = {
                            showContextMenu = false
                            onExportRequest(session, "text")
                        },
                    )
                }
                // Edit Title & Category
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_edit_title_category)) },
                    onClick = {
                        showContextMenu = false
                        onEditRequest(session)
                    },
                    leadingIcon = {
                        Icon(com.openminis.app.ui.novex.NovexIcons.Edit, contentDescription = null)
                    },
                )
                // Regenerate Title —— 需要 provider 运行时（sub model/主模型
                // 调 LLM）。轻量启动面隐藏入口而不是放一个点了没反应的死按钮。
                if (canRegenerateTitle) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessionlist_regenerate_title)) },
                        onClick = {
                            showContextMenu = false
                            onRegenerateTitle(session.id)
                        },
                        leadingIcon = {
                            Icon(com.openminis.app.ui.novex.NovexIcons.Refresh, contentDescription = null)
                        },
                    )
                }
                // Duplicate
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_duplicate)) },
                    onClick = {
                        showContextMenu = false
                        onDuplicate(session.id)
                    },
                    leadingIcon = {
                        Icon(com.openminis.app.ui.novex.NovexIcons.ContentCopy, contentDescription = null)
                    },
                )
                // Move to / Change Group
                // [T-android-session-grouping] The wording follows membership:
                // a session already in a group is being MOVED BETWEEN groups,
                // not filed for the first time. Same idiom as Pin/Unpin.
                //
                // A single item opening a sheet, deliberately NOT an inline
                // submenu of group names — the menu body would then cost
                // O(groups) to compose on every open, and the group data would
                // have to be captured into the menu closure.
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (isFiled) R.string.group_change
                                else R.string.group_move_to,
                            ),
                        )
                    },
                    onClick = {
                        showContextMenu = false
                        onMoveToGroup(session.id)
                    },
                    leadingIcon = {
                        Icon(
                            if (isFiled) com.openminis.app.ui.novex.NovexIcons.DriveFileMove
                            else com.openminis.app.ui.novex.NovexIcons.Folder,
                            contentDescription = null,
                        )
                    },
                )
                // Select
                // [T-android-sessionlist-longpress-select] Must ENTER
                // selection mode, not just toggle the hidden set —
                // onToggleSelect alone never set isSelecting, so nothing
                // visibly happened and the id sat invisibly pre-selected.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_select_action)) },
                    onClick = {
                        showContextMenu = false
                        onEnterSelect(session.id)
                    },
                    leadingIcon = {
                        Icon(com.openminis.app.ui.novex.NovexIcons.ChecklistRtl, contentDescription = null)
                    },
                )
                MinisMenuDivider()
                // Delete
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showContextMenu = false
                        onDeleteRequest(session.id)
                    },
                    leadingIcon = {
                        Icon(
                            com.openminis.app.ui.novex.NovexIcons.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                )
                }
            }
        }
    }
}

/**
 * [T-android-session-grouping] The card that heads a group's block.
 *
 * Tapping it collapses/expands (accordion — opening one closes the others).
 * Long-pressing opens the management menu: pin, rename, dissolve. Dissolve is
 * deliberately NOT tinted destructive — it moves sessions back to the main list
 * and deletes nothing, and tinting it red would train the eye to read it as the
 * dangerous item.
 */
@OptIn(ExperimentalFoundationApi::class)
// ─── [T-android-folder-card-ios-parity] Folder container surface ────────────
//
// Port of iOS FolderSurface + FolderSegmentBorder (ContentView.swift): the
// folder group renders as ONE floating rounded container. Collapsed = a lone
// 16dp-radius card; expanded = the card becomes the TOP segment and each
// member row a MIDDLE/BOTTOM segment of the same surface, with the hairline
// border tiled around the outer perimeter only — no horizontal lines at row
// boundaries, so the pieces read as a single welded outline. Rows stay
// independent LazyColumn items (the iOS "never merge rows into one view"
// rule); only the background/border segmentation composes them.

private enum class FolderSegment { LONE, TOP, MIDDLE, BOTTOM }

/**
 * Edge highlight for the folder container: bright hairline in dark mode, a
 * subtle dark line in light mode (white would vanish on the light page) —
 * iOS `folderEdgeHighlight` verbatim.
 */
@Composable
private fun folderEdgeColor(): Color =
    if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.30f)
    else Color.Black.copy(alpha = 0.08f)

@Composable
private fun folderFillColor(): Color = MaterialTheme.colorScheme.surfaceContainerLow

private fun Modifier.folderSurface(
    segment: FolderSegment,
    fill: Color,
    edge: Color,
): Modifier = drawBehind {
    val r = 16.dp.toPx()
    val w = size.width
    val h = size.height
    val cr = CornerRadius(r, r)

    val fillPath = Path().apply {
        when (segment) {
            FolderSegment.LONE -> addRoundRect(RoundRect(0f, 0f, w, h, cr))
            FolderSegment.TOP -> addRoundRect(
                RoundRect(
                    rect = Rect(0f, 0f, w, h),
                    topLeft = cr, topRight = cr,
                    bottomLeft = CornerRadius.Zero, bottomRight = CornerRadius.Zero,
                ),
            )
            FolderSegment.MIDDLE -> addRect(Rect(0f, 0f, w, h))
            FolderSegment.BOTTOM -> addRoundRect(
                RoundRect(
                    rect = Rect(0f, 0f, w, h),
                    topLeft = CornerRadius.Zero, topRight = CornerRadius.Zero,
                    bottomLeft = cr, bottomRight = cr,
                ),
            )
        }
    }
    drawPath(fillPath, fill)

    // Border tiling (iOS FolderSegmentBorder): lone = full outline; top =
    // left edge up + top arcs + right edge down; middle = the two vertical
    // edges only; bottom = the mirror of top. Open paths — never a line
    // across a row boundary.
    val border = Path().apply {
        when (segment) {
            FolderSegment.LONE -> addRoundRect(RoundRect(0f, 0f, w, h, cr))
            FolderSegment.TOP -> {
                moveTo(0f, h)
                lineTo(0f, r)
                arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
                lineTo(w - r, 0f)
                arcTo(Rect(w - 2 * r, 0f, w, 2 * r), 270f, 90f, false)
                lineTo(w, h)
            }
            FolderSegment.MIDDLE -> {
                moveTo(0f, 0f); lineTo(0f, h)
                moveTo(w, 0f); lineTo(w, h)
            }
            FolderSegment.BOTTOM -> {
                moveTo(0f, 0f)
                lineTo(0f, h - r)
                arcTo(Rect(0f, h - 2 * r, 2 * r, h), 180f, -90f, false)
                lineTo(w - r, h)
                arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 90f, -90f, false)
                lineTo(w, 0f)
            }
        }
    }
    drawPath(border, edge, style = Stroke(width = 0.75.dp.toPx()))
}

/**
 * Port of iOS GroupGlyphShape: the "grouped list" glyph — two rounded-square
 * rings on the left, four list lines on the right — traced from the same
 * 1024-unit SVG. Rings are even-odd so the whole glyph is a single fill.
 */
private fun groupGlyphPath(side: Float): Path = Path().apply {
    fillType = PathFillType.EvenOdd
    val u = side / 1024f

    fun ring(x: Float, y: Float) {
        // Outer 325.8×325.8 with r 93; inner inset by the 46.5 stroke.
        val outer = Rect(x * u, y * u, (x + 325.8f) * u, (y + 325.8f) * u)
        addRoundRect(RoundRect(outer, CornerRadius(93f * u)))
        val inner = Rect(
            outer.left + 46.5f * u, outer.top + 46.5f * u,
            outer.right - 46.5f * u, outer.bottom - 46.5f * u,
        )
        addRoundRect(RoundRect(inner, CornerRadius(46.5f * u)))
    }

    fun line(cy: Float) {
        val rect = Rect(
            558.5f * u, (cy - 23.27f) * u,
            (558.5f + 325.8f) * u, (cy + 23.27f) * u,
        )
        addRoundRect(RoundRect(rect, CornerRadius(23.27f * u)))
    }

    ring(139.6f, 139.6f)
    ring(139.6f, 511.9f)
    line(209.5f)
    line(395.6f)
    line(581.8f)
    line(768.0f)
}

/**
 * Port of iOS FolderComposedIcon: the grouped-list glyph on the SAME circular
 * translucent tint the session rows use, at the same 44dp slot — a group icon
 * and a session icon are the same species at the same size. Tint borrows the
 * newest member's category color (gray when empty); 0.28 vs the session
 * icons' 0.18 so a group circle reads as a different kind of thing.
 */
@Composable
private fun FolderComposedIcon(category: String?, diameter: Dp = 44.dp) {
    val tint = categoryStyle(category).color
    Box(
        modifier = Modifier
            .size(diameter)
            .background(tint.copy(alpha = 0.28f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(diameter * 0.56f)) {
            drawPath(groupGlyphPath(size.width), tint)
        }
    }
}

@Composable
private fun FolderCard(
    block: FolderGroupBlock,
    onToggle: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDissolve: () -> Unit,
    /** iOS "New Chat in Group": start a chat that files into this folder. */
    onNewChatInGroup: () -> Unit,
    /** iOS "Delete Group & N Sessions": destructive, folder + all members. */
    onDeleteWithSessions: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // [T-android-menu-press-side] Anchor the long-press menu at the FINGER,
    // not the card. combinedClickable gave no press coordinates, so the menu
    // anchored to the whole card Box and always opened at the card's LEFT
    // edge — pressing the right side popped the menu on the left (captured
    // on-device: left-press and right-press produced pixel-identical menu
    // positions). Same press-point + half-side rule as SessionItemContent.
    var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
    var menuAlignEnd by remember { mutableStateOf(false) }
    val headerPressInteractions = remember { MutableInteractionSource() }
    val density = LocalDensity.current
    val expandLabel = stringResource(
        if (block.isCollapsed) R.string.group_expand else R.string.group_collapse,
    )
    // Expanded-with-members: the card is the container's TOP segment and
    // welds onto the first member row (no bottom gap). Collapsed or empty:
    // a lone floating card. Mirrors iOS FolderCardBackground.
    val isExpandedWithRows = !block.isCollapsed && block.ids.isNotEmpty()
    val chevronRotation by animateFloatAsState(
        targetValue = if (block.isCollapsed) -90f else 0f,
        animationSpec = tween(250),
        label = "folderChevron",
    )
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dateText = remember(block.latestUpdatedAt, ctx) {
        relativeDate(ctx, block.latestUpdatedAt)
    }
    val headerHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        modifier = Modifier
            // 6dp outer inset floats the rounded card inside the list width
            // (iOS: the inset frame is what separates it from the full-bleed
            // session rows at a glance). 6 outer + 10 inner = 16 — the folder
            // icon sits exactly on the session rows' alignment grid.
            .padding(
                start = 6.dp, end = 6.dp, top = 4.dp,
                bottom = if (isExpandedWithRows) 0.dp else 4.dp,
            )
            .folderSurface(
                segment = if (isExpandedWithRows) FolderSegment.TOP else FolderSegment.LONE,
                fill = folderFillColor(),
                edge = folderEdgeColor(),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Clip BEFORE the clickable so the press ripple takes the
                // card's own rounded shape — an unclipped ripple paints a
                // square highlight over the rounded surface. Expanded: only
                // the top corners are round (the card is the container's TOP
                // segment), so the ripple must stay square at the weld.
                .clip(
                    if (isExpandedWithRows) {
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    } else {
                        RoundedCornerShape(16.dp)
                    },
                )
                // detectTapGestures instead of combinedClickable so the
                // long-press OFFSET is available for menu anchoring; the
                // ripple is driven by hand through the InteractionSource
                // (same pattern as SessionRow's press indication).
                .indication(headerPressInteractions, LocalIndication.current)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { offset ->
                            val press = PressInteraction.Press(offset)
                            headerPressInteractions.emit(press)
                            val released = tryAwaitRelease()
                            headerPressInteractions.emit(
                                if (released) PressInteraction.Release(press)
                                else PressInteraction.Cancel(press),
                            )
                        },
                        onTap = { onToggle() },
                        onLongPress = { offset ->
                            // detectTapGestures gives no haptic of its own —
                            // see the SessionRow note; fired by hand so the
                            // group header matches every other long-press menu.
                            headerHaptics.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                            )
                            pressOffset = with(density) {
                                DpOffset(offset.x.toDp(), offset.y.toDp())
                            }
                            menuAlignEnd = offset.x > size.width / 2f
                            menuOpen = true
                        },
                    )
                }
                .semantics(mergeDescendants = true) {
                    onClick(label = expandLabel) { onToggle(); true }
                }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FolderComposedIcon(category = block.firstCategory)
            Spacer(Modifier.width(8.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // User data — rendered verbatim, never a string lookup.
                Text(
                    block.folder.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // totalCount, not ids.size — a collapsed group renders no
                    // rows but must still report its real membership. iOS
                    // summary line: "N chats · <newest member title>".
                    when {
                        block.totalCount > 0 && block.summaryTitle != null ->
                            stringResource(R.string.group_n_chats, block.totalCount) +
                                " · " + block.summaryTitle
                        block.totalCount > 0 ->
                            stringResource(R.string.group_n_chats, block.totalCount)
                        else -> stringResource(R.string.group_empty)
                    },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    dateText,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (block.folder.isPinned) {
                        Icon(
                            com.openminis.app.ui.novex.NovexIcons.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    Icon(
                        com.openminis.app.ui.novex.NovexIcons.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(chevronRotation),
                    )
                }
            }
        }
        // Invisible zero-size anchor at the press position — the menu opens
        // from the finger, right-edge-anchored when the press was on the
        // card's right half (see menuAlignEnd above).
        Box(
            modifier = Modifier
                .offset(x = pressOffset.x, y = pressOffset.y)
                .size(1.dp),
        ) {
            MinisMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                alignEnd = menuAlignEnd,
            ) {
                // [T-android-folder-menu-icons] One icon FAMILY and one frame
                // for every item: Outlined variants in a 20dp box. The old mix
                // (filled PushPin / filled Edit / filled FolderOff at default
                // 24dp) had three different visual weights and optical sizes
                // in a four-item menu.
                val menuIcon: @Composable (androidx.compose.ui.graphics.vector.ImageVector) -> Unit =
                    { image ->
                        Icon(
                            image,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (block.folder.isPinned) R.string.sessionlist_unpin
                                else R.string.sessionlist_pin,
                            ),
                        )
                    },
                    onClick = { menuOpen = false; onTogglePin() },
                    leadingIcon = { menuIcon(com.openminis.app.ui.novex.NovexIcons.PushPin) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_rename)) },
                    onClick = { menuOpen = false; onRename() },
                    leadingIcon = { menuIcon(com.openminis.app.ui.novex.NovexIcons.Edit) },
                )
                // iOS folder menu parity: "New Chat in Group" (plus.bubble)
                // sits between Rename and the divider.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_new_chat_in)) },
                    onClick = { menuOpen = false; onNewChatInGroup() },
                    leadingIcon = { menuIcon(com.openminis.app.ui.novex.NovexIcons.AddComment) },
                )
                MinisMenuDivider()
                // Dissolve is deliberately NOT destructive-tinted (iOS note):
                // it touches no user data — sessions move back to the main
                // list. Tinting it red would train the eye to read it as the
                // deleting item.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_dissolve)) },
                    onClick = { menuOpen = false; onDissolve() },
                    leadingIcon = { menuIcon(com.openminis.app.ui.novex.NovexIcons.FolderOff) },
                )
                MinisMenuDivider()
                // The one destructive item, last, with the count in the title
                // so the consequence is visible in the menu itself, not only
                // in the confirmation dialog (iOS parity).
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                R.string.group_delete_with_sessions, block.totalCount,
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = { menuOpen = false; onDeleteWithSessions() },
                    leadingIcon = {
                        Icon(
                            com.openminis.app.ui.novex.NovexIcons.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
            }
        }
    }
}

// ─── Session Row (matching iOS SessionRow) ──────────────────────────────────

/**
 * Card frame for a [SectionTextField] used inside a dialog.
 *
 * The settings screens get this for free from `SettingsCardBlock`: it supplies
 * the 16dp horizontal inset that SectionTextField deliberately omits (its
 * contentPadding is horizontal = 0 so glyphs align with sibling section rows —
 * T352) and the card surface that gives the input an edge. A dialog has no such
 * parent, so a bare SectionTextField renders as text jammed against its fill
 * with no visible boundary.
 *
 * Reuses the same tokens as the settings cards — [SectionDesign.CardShape] and
 * `cardColor()` — so a dialog input reads as the same control as the one on a
 * settings screen, plus a hairline outline: the dialog's surface sits close in
 * luminance to the card fill, and without the outline the field edge is
 * effectively invisible in dark mode.
 */
@Composable
private fun DialogTextFieldFrame(content: @Composable () -> Unit) {
    Surface(
        shape = SectionDesign.CardShape,
        color = SectionDesign.cardColor(),
        // Full-strength outlineVariant, not a faded one: the dialog's surface
        // and the card fill are close in luminance (both are surfaceContainer
        // shades), so anything dimmer than this reads as no border at all in
        // dark mode — verified on device.
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp)) { content() }
    }
}

/**
 * Spinning arc overlaid on the session icon while the agent loop is active.
 * Mirrors iOS `SpinningRing` (ContentView.swift:2405): 1.5dp stroke at 30%
 * opacity, 30% arc length, full rotation every ~1 second. Uses
 * `withFrameNanos` instead of an `animate*` API so recomposition across
 * onAppear calls does not stack multiple rotation animations.
 */
@Composable
// [T-launch-home-running-glow] internal：NovenSessionRow 复用（两条路径
// 同用 SessionListScreen）。运行光环问题见 2026-09-16 反馈。
internal fun SpinningRing(
    color: Color,
    modifier: Modifier = Modifier,
) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val startNanos = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val elapsedSec = (now - startNanos) / 1_000_000_000f
                angle = (elapsedSec * 360f) % 360f
            }
        }
    }
    Canvas(modifier = modifier.rotate(angle)) {
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        drawArc(
            color = color.copy(alpha = 0.8f),
            startAngle = 0f,
            sweepAngle = 360f * 0.3f,
            useCenter = false,
            size = Size(size.width, size.height),
            style = stroke,
        )
    }
}


// ─── 轻量启动面空态 ────────────────────────────────────────────────────────

/**
 * providerRuntimeAvailable == false（NovexHomeSurface 冷启动路径）时的普通
 * 空态：不渲染 onboarding 三步引导——那三步全都依赖 provider 运行时。
 * 「新建对话」由宿主接到 openLegacy，点按时才走 ensureRuntime 拉起运行时。
 * 视觉等价于已删除的 NovexConversationRoot 空态。
 */
@Composable
private fun SessionListEmptyState(onNewChat: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 80.dp),
    ) {
        Text(
            "还没有对话",
            color = com.openminis.app.ui.noven.NovenColors.Text,
            fontSize = com.openminis.app.ui.novex.novexScaledSp(18),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "从一个新的想法开始",
            color = com.openminis.app.ui.noven.NovenColors.Secondary,
            fontSize = com.openminis.app.ui.novex.novexScaledSp(14),
            modifier = Modifier.padding(top = 7.dp),
        )
        Text(
            "新建对话",
            color = com.openminis.app.ui.novex.NovexColors.Primary,
            fontSize = com.openminis.app.ui.novex.novexScaledSp(15),
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(top = 18.dp)
                .clickable(onClick = onNewChat)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

// ─── Onboarding Landing (iOS-style 3-step setup) ───────────────────────────

@Composable
private fun OnboardingLanding(
    hasProviders: Boolean,
    hasGroups: Boolean,
    onAddProvider: () -> Unit,
    onSelectModels: () -> Unit,
    onStartConversation: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.novex_logo_transparent),
                contentDescription = stringResource(R.string.novex_logo_description),
                modifier = Modifier.size(68.dp),
            )
            Spacer(Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.sessionlist_welcome_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.sessionlist_welcome_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SetupStepCard(
                    number = 1,
                    title = stringResource(R.string.sessionlist_welcome_step1_title),
                    subtitle = if (hasProviders) {
                        stringResource(R.string.sessionlist_welcome_step_done)
                    } else {
                        stringResource(R.string.sessionlist_welcome_step1_subtitle)
                    },
                    isDone = hasProviders,
                    isLocked = false,
                    onClick = { if (!hasProviders) onAddProvider() },
                )

                SetupStepCard(
                    number = 2,
                    title = stringResource(R.string.sessionlist_welcome_step2_title),
                    subtitle = when {
                        hasGroups -> stringResource(R.string.sessionlist_welcome_step_done)
                        hasProviders -> stringResource(R.string.sessionlist_welcome_step2_subtitle)
                        else -> stringResource(R.string.sessionlist_welcome_step2_locked)
                    },
                    isDone = hasGroups,
                    isLocked = !hasProviders,
                    onClick = { if (hasProviders && !hasGroups) onSelectModels() },
                )

                SetupStepCard(
                    number = 3,
                    title = stringResource(R.string.sessionlist_welcome_step3_title),
                    subtitle = if (hasGroups) {
                        stringResource(R.string.sessionlist_welcome_step3_subtitle)
                    } else {
                        stringResource(R.string.sessionlist_welcome_draft_hint)
                    },
                    isDone = false,
                    isLocked = false,
                    onClick = onStartConversation,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.novex_openminis_thanks),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                MinisTextButton(
                    onClick = { uriHandler.openUri("https://github.com/ccbili30-collab/novex-android") },
                ) {
                    Text(stringResource(R.string.novex_star_openminis))
                }
                MinisTextButton(
                    onClick = { uriHandler.openUri("https://github.com/ccbili30-collab/novex-android") },
                ) {
                    Text(stringResource(R.string.novex_star_novex))
                }
            }
        }
    }
}

@Composable
private fun SetupStepCard(
    number: Int,
    title: String,
    subtitle: String,
    isDone: Boolean,
    isLocked: Boolean,
    onClick: () -> Unit,
) {
    val isEnabled = !isDone && !isLocked

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(enabled = isEnabled, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    color = if (isDone) Color(0xFF34C759) else MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isDone) {
                Icon(
                    imageVector = com.openminis.app.ui.novex.NovexIcons.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(
                    text = "$number",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f).height(56.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isDone) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!isDone && isEnabled) {
            Icon(
                imageVector = com.openminis.app.ui.novex.NovexIcons.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

// ─── Edit Title & Category Sheet (matching iOS SessionEditSheet) ──────────

private val allCategories = listOf(
    "Code", "Writing", "Research", "Analysis",
    "Creative", "Chat", "Math", "Translation",
    "Health", "Finance", "Travel", "Education",
    "Design", "Productivity", "Support", "Other",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionEditSheet(
    session: ChatSessionEntity,
    onDismiss: () -> Unit,
    onSave: (title: String, category: String?) -> Unit,
    // [T-android-sessionedit-regenerate-button] Regenerate-Title support,
    // matching iOS SessionEditSheet. `liveSession` is the DB-backed row that
    // updates when regeneration writes a new title/category; `isRegenerating`
    // drives the button's loading/disabled state; `onRegenerate` reuses the
    // existing SessionListViewModel.regenerateTitle logic. Defaults make the
    // button a no-op when a caller doesn't wire them up.
    liveSession: ChatSessionEntity = session,
    isRegenerating: Boolean = false,
    onRegenerate: () -> Unit = {},
    /** 轻量启动面（无 provider 运行时）隐藏 Regenerate 区块。 */
    canRegenerate: Boolean = true,
) {
    var title by remember { mutableStateOf(session.title ?: "") }
    var selectedCategory by remember { mutableStateOf(session.category) }

    // [T-android-sessionedit-regenerate-button] When a regeneration run writes a
    // new title/category to the DB, `liveSession` updates — mirror those values
    // into the sheet's local edit state so the Title field and Category grid
    // refresh in place (iOS reads the fresh ChatStore session on completion).
    LaunchedEffect(liveSession.title, liveSession.category) {
        liveSession.title?.let { title = it }
        selectedCategory = liveSession.category
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
        ) {
            // Title bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MinisTextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                Text(
                    "Edit Session",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                MinisTextButton(
                    onClick = { onSave(title.ifBlank { "New Chat" }, selectedCategory) },
                ) { Text("Save") }
            }

            Spacer(Modifier.height(16.dp))

            // Title field
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(20.dp))

            Text(
                "Category",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // Category grid (4 columns, matching iOS LazyVGrid)
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(240.dp),
            ) {
                items(allCategories) { cat ->
                    val isSelected = selectedCategory?.equals(cat, ignoreCase = true) == true
                    val style = categoryStyle(cat.lowercase())
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) style.color.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .clickable {
                                selectedCategory = if (isSelected) null else cat.lowercase()
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = style.icon,
                                contentDescription = null,
                                tint = style.color,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                cat,
                                fontSize = 11.sp,
                                color = if (isSelected) style.color
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // [T-android-sessionedit-regenerate-button] Regenerate Title —
            // matches iOS SessionEditSheet's dedicated section below Category.
            // Reuses SessionListViewModel.regenerateTitle; shows a spinner and
            // disables while running (regeneratingIds) to prevent double taps.
            // canRegenerate=false（轻量启动面无 provider 运行时）时整块隐藏。
            if (canRegenerate) {
            OutlinedButton(
                onClick = onRegenerate,
                enabled = !isRegenerating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isRegenerating) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sessionlist_regenerating_title))
                } else {
                    Icon(
                        com.openminis.app.ui.novex.NovexIcons.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sessionlist_regenerate_title))
                }
            }
            }
        }
    }
}

// ─── Export Session ────────────────────────────────────────────────────────

/**
 * Long-chat export (T-export-optimize b443b54d, iOS sister c9d1087d).
 *
 * Pre-fix: this loaded every [MessageEntity] for the session at once,
 * built the whole JSON / TXT payload in memory, and shoved it into
 * [Intent.EXTRA_TEXT]. Hundreds of messages caused jank, "ghost" frames
 * and OOM crashes — see linked feedback.
 *
 * Now: hand off to [com.openminis.app.share.ChatExporter] which paginates
 * (50 rows / batch) on [kotlinx.coroutines.Dispatchers.IO], streams to a
 * staging file under `cacheDir/export-staging/`, then zips into
 * `cacheDir/shared/` and hands the resulting [android.net.Uri] to the
 * share sheet as a real file attachment. Peak memory stays bounded by
 * batch size regardless of session length.
 */
private fun exportSession(
    context: Context,
    session: ChatSessionEntity,
    chatRepository: ChatRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    format: String,
) {
    scope.launch {
        try {
            val (uri, _) = com.openminis.app.share.ChatExporter.exportToZip(
                context = context,
                session = session,
                repository = chatRepository,
                format = format,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_SUBJECT, session.title ?: "Conversation")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(
                intent,
                context.getString(R.string.sessionlist_export),
            ).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            context.startActivity(chooser)
        } catch (t: Throwable) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.export_progress_failed),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
}
