package com.openminis.app.ui.sessions
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.items
import novex.android.ui.DropdownMenuItem
import com.openminis.app.ui.noven.novenSessionGroupRowShape
import androidx.compose.material3.Surface
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.SectionTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import novex.android.ui.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import novex.android.data.chat.SessionRow
import novex.android.data.chat.SessionFolderRow
import com.openminis.app.data.character.WorldEntity
import novex.android.data.model.ProviderConfig
import novex.android.data.model.hasUsableNovexModel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import novex.android.ui.rememberNovexWorkspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.openminis.app.ui.components.MinisTextButton

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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
    val novexWorkspace = rememberNovexWorkspace()
    var worlds by remember { mutableStateOf<List<WorldEntity>>(emptyList()) }
    var worldsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        worlds = novexWorkspace.worlds().map { it.world }
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
    var folderToRename by remember { mutableStateOf<SessionFolderRow?>(null) }
    var folderToDissolve by remember { mutableStateOf<SessionFolderRow?>(null) }
    // iOS "Delete Group & N Sessions" — pair carries the member count so the
    // confirmation can restate the consequence.
    var folderToDelete by remember { mutableStateOf<Pair<SessionFolderRow, Int>?>(null) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var editSession by remember { mutableStateOf<SessionRow?>(null) }

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
                                        Icon(novex.android.ui.NovexIcons.ChecklistRtl, contentDescription = null)
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
                    novex.android.ui.NovexConversationCardLookup(onSessionClickGuarded)
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
                        fun sessionRowContent(session: SessionRow) {
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
                            rows: List<SessionRow>,
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
                            rows: List<SessionRow>,
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
                                    novex.android.ui.NovexIcons.KeyboardArrowUp,
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
        novex.android.ui.AlertDialog(
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
                            onValueChange = { desc = it.take(SessionFolderRow.DESCRIPTION_MAX_CHARS) },
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
