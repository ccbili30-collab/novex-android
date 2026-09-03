package com.openminis.app.ui.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.novex.domain.NovexWorkspace
import com.openminis.app.ui.novex.NovexArtwork
import com.openminis.app.ui.novex.NovexArtworkKind
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexFilterTabs
import com.openminis.app.ui.novex.NovexIconAction
import com.openminis.app.ui.novex.NovexRootHeader
import com.openminis.app.ui.novex.NovexSearchField
import com.openminis.app.ui.novex.NovexSectionTitle
import com.openminis.app.ui.novex.NovexTextActionRow
import com.openminis.app.ui.settings.existingMediaFile
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    onCreationPlaceholder: () -> Unit,
    onInteractive: () -> Unit,
    onContentLoaded: () -> Unit,
    onRootNavigationVisibilityChange: (Boolean) -> Unit,
) {
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
    val visibleSessions = remember(sessions, selectedFilter, appliedQuery) {
        sessions
            .forHomeFilter(selectedFilter)
            .filter { session ->
                appliedQuery.isBlank() || listOfNotNull(
                    session.title,
                    session.lastMessage,
                    session.assistantDisplayName,
                    session.playerDisplayName,
                ).any { it.contains(appliedQuery, ignoreCase = true) }
            }
            .sortedByDescending(ChatSessionEntity::updatedAt)
    }
    val today = remember(visibleSessions) {
        visibleSessions.filter { sessionHomeRecency(it.updatedAt) == SessionHomeRecency.TODAY }
    }
    val earlier = remember(visibleSessions) {
        visibleSessions.filter { sessionHomeRecency(it.updatedAt) == SessionHomeRecency.EARLIER }
    }
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NovexColors.Background)
            .statusBarsPadding(),
    ) {
        NovexRootHeader(
            title = "Novex",
            leading = {
                NovexIconAction(
                    icon = R.drawable.ic_phosphor_gear,
                    contentDescription = "设置",
                    onClick = onOpenSettings,
                )
            },
            actions = {
                NovexIconAction(
                    icon = R.drawable.ic_phosphor_search,
                    contentDescription = if (searching) "关闭搜索" else "搜索对话",
                    onClick = {
                        searching = !searching
                        if (!searching) searchState.clear()
                    },
                )
                NovexIconAction(
                    icon = R.drawable.ic_phosphor_sparkle,
                    contentDescription = "帮我创作",
                    onClick = onCreationPlaceholder,
                )
            },
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
                    SessionHomeFilter.GENERAL -> "通用"
                    SessionHomeFilter.CREATION -> "创作"
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

            selectedFilter == SessionHomeFilter.CREATION -> NovexCreationPlaceholder(onCreationPlaceholder)

            visibleSessions.isEmpty() -> NovexConversationEmptyState(
                searching = appliedQuery.isNotBlank(),
                world = selectedFilter == SessionHomeFilter.RECENT,
                onNewConversation = onNewConversation,
                onOpenWorlds = onOpenWorlds,
            )

            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                fun section(label: String, rows: List<ChatSessionEntity>) {
                    if (rows.isEmpty()) return
                    item(key = "section_$label") {
                        NovexSectionTitle(label)
                    }
                    items(rows, key = ChatSessionEntity::id) { session ->
                        NovexConversationRow(
                            session = session,
                            world = session.worldId?.let(catalog.worlds::get)
                                ?: worldNameFromSnapshot(session.worldSnapshotJson)?.let {
                                    ConversationWorldMeta(name = it, imagePath = null)
                                },
                            version = session.characterVersionId?.let(catalog.versions::get),
                            onClick = { onOpenSession(session.id) },
                        )
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
                                text = { Text("通用对话") },
                                onClick = {
                                    menuExpanded = false
                                    onNewConversation()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("世界角色对话") },
                                onClick = {
                                    menuExpanded = false
                                    onOpenWorlds()
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
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        NovexArtwork(
            kind = if (world != null) NovexArtworkKind.WORLD else NovexArtworkKind.CHARACTER,
            seed = session.worldId ?: session.characterVersionId ?: session.id,
            imageModel = (world?.imagePath ?: session.assistantAvatarPath).existingMediaFile(),
            contentDescription = "${session.title ?: "对话"}缩略图",
            modifier = Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 13.dp),
        ) {
            Text(
                session.title?.takeIf(String::isNotBlank) ?: "新对话",
                color = NovexColors.Text,
                fontSize = 16.sp,
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
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (versionLine != null) {
                Text(
                    versionLine,
                    color = NovexColors.SecondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        Text(
            conversationTime(session.updatedAt),
            color = NovexColors.SecondaryText,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 8.dp, top = 1.dp),
        )
    }
}

@Composable
private fun NovexConversationEmptyState(
    searching: Boolean,
    world: Boolean,
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
                world -> "还没有世界内会话"
                else -> "还没有通用会话"
            },
            color = NovexColors.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (!searching) {
            Text(
                if (world) "先选择一个世界和角色版本" else "从一个新的想法开始",
                color = NovexColors.SecondaryText,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 7.dp),
            )
            Text(
                if (world) "前往世界" else "新建通用对话",
                color = NovexColors.Primary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(top = 18.dp)
                    .clickable(onClick = if (world) onOpenWorlds else onNewConversation)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun NovexCreationPlaceholder(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onClick)
            .padding(horizontal = 32.dp, vertical = 80.dp),
    ) {
        Text("创作空间即将开放", color = NovexColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "首页入口已经预留，后续会与世界内的“帮我创作”统一。",
            color = NovexColors.SecondaryText,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
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
