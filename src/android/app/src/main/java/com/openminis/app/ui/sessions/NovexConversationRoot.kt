package com.openminis.app.ui.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import com.openminis.app.ui.novex.DropdownMenu
import com.openminis.app.ui.novex.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.novex.domain.NovexWorkspace
import com.openminis.app.ui.novex.NovexArtwork
import com.openminis.app.ui.novex.NovexArtworkKind
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexDimensions
import com.openminis.app.ui.novex.NovexPageTone
import com.openminis.app.ui.novex.color
import com.openminis.app.ui.novex.novexPagePadding
import com.openminis.app.ui.novex.NovexFilterTabs
import com.openminis.app.ui.novex.NovexSearchField
import com.openminis.app.ui.novex.NovexSectionTitle
import com.openminis.app.ui.novex.NovexTextActionRow
import com.openminis.app.ui.settings.existingMediaFile
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private data class ConversationWorldMeta(
    val name: String,
    val imagePath: String?,
)

private data class ConversationVersionMeta(
    val characterName: String,
    val versionLabel: String,
)

private data class ConversationHomeCatalog(
    val worlds: Map<String, ConversationWorldMeta>,
    val versions: Map<String, ConversationVersionMeta>,
)

/**
 * The launch-safe conversation home. It deliberately depends only on Room,
 * the Novex domain interface and small Compose primitives; provider, model,
 * tool, browser and sandbox objects stay behind the runtime hand-off.
 */
@Composable
fun NovexConversationRoot(
    chatRepository: ChatRepository,
    workspace: NovexWorkspace,
    preparingRuntime: Boolean,
    onOpenSession: (String) -> Unit,
    onNewConversation: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWorlds: () -> Unit,
    onStartCreationTool: () -> Unit,
    onInteractive: () -> Unit,
    onContentLoaded: () -> Unit,
    onRootNavigationVisibilityChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val orderStore = remember(context) { NovexManualOrderStore(context) }
    var manualOrderIds by remember {
        mutableStateOf(orderStore.read(NovexManualOrderKind.CONVERSATIONS))
    }
    val sessionsOrNull by produceState<List<ChatSessionEntity>?>(initialValue = null, chatRepository) {
        chatRepository.observeSessions().collect { value = it }
    }
    val catalogOrNull by produceState<ConversationHomeCatalog?>(initialValue = null, workspace) {
        val worlds = workspace.worlds().associate { card ->
            card.world.id to ConversationWorldMeta(card.world.name, card.image?.managedPath)
        }
        val versions = workspace.characters().flatMap { card ->
            card.character.allVersions.map { version ->
                version.id to ConversationVersionMeta(
                    characterName = card.character.character.name,
                    versionLabel = version.label,
                )
            }
        }.toMap()
        value = ConversationHomeCatalog(worlds = worlds, versions = versions)
    }
    val availability = sessionHomeAvailability(
        sessionsLoaded = sessionsOrNull != null,
        worldNamesLoaded = catalogOrNull != null,
    )
    LaunchedEffect(Unit) {
        onRootNavigationVisibilityChange(availability.controlsInteractive)
        onInteractive()
    }
    LaunchedEffect(availability.contentReady) {
        if (availability.contentReady) onContentLoaded()
    }

    var searching by rememberSaveable { mutableStateOf(false) }
    var filterName by rememberSaveable { mutableStateOf(SessionHomeFilter.RECENT.name) }
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    val searchState = rememberNovexLibrarySearchState()
    val appliedQuery by searchState.applied.collectAsState()
    val selectedFilter = SessionHomeFilter.valueOf(filterName)

    BackHandler(enabled = searching) {
        searching = false
        searchState.clear()
    }

    val sessions = sessionsOrNull.orEmpty()
    val catalog = catalogOrNull ?: ConversationHomeCatalog(emptyMap(), emptyMap())
    val visibleSessions = remember(sessions, selectedFilter, appliedQuery, manualOrderIds) {
        val byId = sessions.associateBy(ChatSessionEntity::id)
        mergeNovexManualOrder(
            sourceIds = sessions.map(ChatSessionEntity::id),
            savedIds = manualOrderIds,
        ).mapNotNull(byId::get)
            .forHomeFilter(selectedFilter)
            .filter { session ->
                appliedQuery.isBlank() || listOfNotNull(
                    session.title,
                    session.lastMessage,
                    session.assistantDisplayName,
                    session.playerDisplayName,
                ).any { it.contains(appliedQuery, ignoreCase = true) }
            }
    }
    val today = remember(visibleSessions) {
        visibleSessions.filter { sessionHomeRecency(it.updatedAt) == SessionHomeRecency.TODAY }
    }
    val earlier = remember(visibleSessions) {
        visibleSessions.filter { sessionHomeRecency(it.updatedAt) == SessionHomeRecency.EARLIER }
    }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromId = (from.key as? String)?.removePrefix("conversation:")
            ?: return@rememberReorderableLazyListState
        val toId = (to.key as? String)?.removePrefix("conversation:")
            ?: return@rememberReorderableLazyListState
        val completeIds = mergeNovexManualOrder(
            sourceIds = sessions.map(ChatSessionEntity::id),
            savedIds = manualOrderIds,
        )
        val fromIndex = completeIds.indexOf(fromId)
        val toIndex = completeIds.indexOf(toId)
        if (fromIndex !in completeIds.indices || toIndex !in completeIds.indices) {
            return@rememberReorderableLazyListState
        }
        manualOrderIds = moveNovexOrderedId(completeIds, fromIndex, toIndex)
        orderStore.write(NovexManualOrderKind.CONVERSATIONS, manualOrderIds)
    }

    val headerHost = LocalNovexRootHeaderHost.current
    val createItems = remember(onNewConversation, onOpenWorlds, onStartCreationTool) {
            novexConversationCreateMenu().map { start ->
                when (start) {
                    NovexConversationStart.EMPTY -> NovexCreateMenuItem("新建对话", onNewConversation)
                    NovexConversationStart.WORLD_CONTEXT -> NovexCreateMenuItem("从世界开始", onOpenWorlds)
                    NovexConversationStart.CREATION_TOOL -> NovexCreateMenuItem("帮我创作", onStartCreationTool)
                }
            }
        }
    RegisterNovexRootHeader(
        NovexRootSpace.CONVERSATIONS,
        NovexRootHeaderConfig(
            searching = searching,
            searchDescription = "搜索对话",
            onSettings = onOpenSettings,
            onSearchToggle = {
                searching = !searching
                if (!searching) searchState.clear()
            },
            createItems = createItems,
        ),
    )
    Column(
        modifier = (if (headerHost == null) Modifier.statusBarsPadding() else Modifier)
            .fillMaxSize()
            .background(NovexPageTone.CONVERSATION.color),
    ) {
        if (headerHost == null) NovexRootPageHeader(
            space = NovexRootSpace.CONVERSATIONS,
            searching = searching,
            searchDescription = "搜索对话",
            onSettings = onOpenSettings,
            onSearchToggle = {
                searching = !searching
                if (!searching) searchState.clear()
            },
            createItems = createItems,
        )

        if (searching) {
            NovexConversationSearchInput(searchState)
        }

        NovexFilterTabs(
            items = SessionHomeFilter.entries,
            selected = selectedFilter,
            label = { filter ->
                when (filter) {
                    SessionHomeFilter.RECENT -> "最近"
                    SessionHomeFilter.CONTEXT_FREE -> "通用"
                    SessionHomeFilter.WITH_CONTEXT -> "设定"
                }
            },
            onSelect = { filterName = it.name },
        )
        Spacer(Modifier.height(12.dp))

        when {
            !availability.contentReady -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = NovexColors.Primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp),
                )
            }

            visibleSessions.isEmpty() -> NovexConversationEmptyState(
                searching = appliedQuery.isNotBlank(),
                filter = selectedFilter,
                onNewConversation = onNewConversation,
                onOpenWorlds = onOpenWorlds,
            )

            else -> LazyColumn(
                state = listState,
                contentPadding = novexPagePadding(bottom = NovexDimensions.RootBottomInset),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                fun section(label: String, rows: List<ChatSessionEntity>) {
                    if (rows.isEmpty()) return
                    item(key = "section_$label") {
                        NovexSectionTitle(label)
                    }
                    items(rows, key = { "conversation:${it.id}" }) { session ->
                        ReorderableItem(reorderState, key = "conversation:${session.id}") { _ ->
                            NovexConversationRow(
                                session = session,
                                world = session.worldId?.let(catalog.worlds::get)
                                    ?: worldNameFromSnapshot(session.worldSnapshotJson)?.let {
                                        ConversationWorldMeta(name = it, imagePath = null)
                                    },
                                version = session.characterVersionId?.let(catalog.versions::get),
                                onClick = { onOpenSession(session.id) },
                                modifier = Modifier.longPressDraggableHandle(),
                            )
                        }
                    }
                }
                section("今天", today)
                section("更早", earlier)
                item(key = "new_conversation") {
                    Box {
                        NovexTextActionRow(
                            label = "新建对话",
                            onClick = { menuExpanded = true },
                        )
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("新建对话") },
                                onClick = {
                                    menuExpanded = false
                                    onNewConversation()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("从世界开始") },
                                onClick = {
                                    menuExpanded = false
                                    onOpenWorlds()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("帮我创作") },
                                onClick = {
                                    menuExpanded = false
                                    onStartCreationTool()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (preparingRuntime) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                CircularProgressIndicator(
                    color = NovexColors.Primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp),
                )
                Text("正在准备对话能力…", modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

/** Isolates immediate typing from the grouped conversation list. */
@Composable
private fun NovexConversationSearchInput(state: NovexLibrarySearchState) {
    val value by state.input.collectAsState()
    NovexSearchField(
        value = value,
        onValueChange = state::update,
        placeholder = "搜索对话",
    )
}

@Composable
private fun NovexConversationRow(
    session: ChatSessionEntity,
    world: ConversationWorldMeta?,
    version: ConversationVersionMeta?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        NovexConversationThumbnailView(
            thumbnail = resolveConversationThumbnail(
                conversationId = session.id,
                title = session.title,
                characterAvatarPath = session.assistantAvatarPath.existingMediaFile()?.absolutePath,
                worldImagePath = world?.imagePath.existingMediaFile()?.absolutePath,
            ),
            title = session.title ?: "对话",
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 13.dp),
        ) {
            Text(
                session.title?.takeIf(String::isNotBlank) ?: "新对话",
                color = NovexColors.Text,
                fontSize = com.openminis.app.ui.novex.novexScaledSp(16),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val worldLine = world?.name?.let { "世界：$it" }
            val characterName = version?.characterName ?: session.assistantDisplayName
            val versionLine = characterName?.takeIf(String::isNotBlank)?.let { name ->
                val label = version?.versionLabel?.takeIf(String::isNotBlank)
                "角色：$name${label?.let { " · $it" }.orEmpty()}"
            }
            (worldLine ?: session.lastMessage?.takeIf(String::isNotBlank))?.let { preview ->
                Text(
                    preview,
                    color = NovexColors.SecondaryText,
                    fontSize = com.openminis.app.ui.novex.novexScaledSp(13),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (versionLine != null) {
                Text(
                    versionLine,
                    color = NovexColors.SecondaryText,
                    fontSize = com.openminis.app.ui.novex.novexScaledSp(12),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        Text(
            conversationTime(session.updatedAt),
            color = NovexColors.SecondaryText,
            fontSize = com.openminis.app.ui.novex.novexScaledSp(12),
            modifier = Modifier.padding(start = 8.dp, top = 1.dp),
        )
    }
}

@Composable
private fun NovexConversationEmptyState(
    searching: Boolean,
    filter: SessionHomeFilter,
    onNewConversation: () -> Unit,
    onOpenWorlds: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 80.dp),
    ) {
        Text(
            when {
                searching -> "没有找到匹配的对话"
                filter == SessionHomeFilter.RECENT -> "还没有对话"
                filter == SessionHomeFilter.CONTEXT_FREE -> "还没有通用对话"
                else -> "还没有带设定的对话"
            },
            color = NovexColors.Text,
            fontSize = com.openminis.app.ui.novex.novexScaledSp(18),
            fontWeight = FontWeight.SemiBold,
        )
        if (!searching) {
            Text(
                if (filter == SessionHomeFilter.WITH_CONTEXT) {
                    "从世界、角色版本和玩家身份开始"
                } else {
                    "从一个新的想法开始"
                },
                color = NovexColors.SecondaryText,
                fontSize = com.openminis.app.ui.novex.novexScaledSp(14),
                modifier = Modifier.padding(top = 7.dp),
            )
            Text(
                if (filter == SessionHomeFilter.WITH_CONTEXT) "前往世界" else "新建对话",
                color = NovexColors.Primary,
                fontSize = com.openminis.app.ui.novex.novexScaledSp(15),
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(top = 18.dp)
                    .clickable(
                        onClick = if (filter == SessionHomeFilter.WITH_CONTEXT) onOpenWorlds else onNewConversation,
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

private val ConversationInitialColors = listOf(
    Color(0xFFE86B7A),
    Color(0xFFDA8B45),
    Color(0xFFB29A42),
    Color(0xFF52A875),
    Color(0xFF4D9BB7),
    Color(0xFF5D7FD1),
    Color(0xFF8A70C8),
    Color(0xFFB8679A),
)

@Composable
private fun NovexConversationThumbnailView(
    thumbnail: NovexConversationThumbnail,
    title: String,
) {
    val modifier = Modifier.size(54.dp).clip(RoundedCornerShape(8.dp))
    when (thumbnail) {
        is NovexConversationThumbnail.Image -> NovexArtwork(
            kind = when (thumbnail.kind) {
                NovexConversationImageKind.CHARACTER -> NovexArtworkKind.CHARACTER
                NovexConversationImageKind.WORLD -> NovexArtworkKind.WORLD
            },
            seed = thumbnail.path,
            imageModel = thumbnail.path.existingMediaFile(),
            contentDescription = "$title 缩略图",
            modifier = modifier,
        )

        is NovexConversationThumbnail.Initial -> Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.background(ConversationInitialColors[thumbnail.colorIndex]),
        ) {
            Text(
                thumbnail.text,
                color = Color.White,
                fontSize = com.openminis.app.ui.novex.novexScaledSp(21),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun worldNameFromSnapshot(snapshot: String?): String? = runCatching {
    JSONObject(snapshot.orEmpty()).optString("name").takeIf(String::isNotBlank)
}.getOrNull()

private fun conversationTime(timestamp: Long): String =
    SimpleDateFormat(
        if (sessionHomeRecency(timestamp) == SessionHomeRecency.TODAY) "HH:mm" else "M/d",
        Locale.getDefault(),
    ).format(Date(timestamp))
