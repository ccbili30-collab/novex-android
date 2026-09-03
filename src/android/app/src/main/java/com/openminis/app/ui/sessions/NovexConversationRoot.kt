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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.novex.domain.NovexWorkspace
import com.openminis.app.ui.novex.NovexColors
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    onRootNavigationVisibilityChange: (Boolean) -> Unit,
) {
    val sessionsOrNull by produceState<List<ChatSessionEntity>?>(initialValue = null, chatRepository) {
        chatRepository.observeSessions().collect { value = it }
    }
    val worldNamesOrNull by produceState<Map<String, String>?>(initialValue = null, workspace) {
        value = workspace.worlds().associate { it.world.id to it.world.name }
    }
    val ready = sessionsOrNull != null && worldNamesOrNull != null
    LaunchedEffect(ready) {
        if (ready) {
            onRootNavigationVisibilityChange(true)
            onInteractive()
        }
    }

    var searching by rememberSaveable { mutableStateOf(false) }
    var searchInput by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(SessionHomeFilter.RECENT.name) }
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    val appliedQuery = searchInput
    val selectedFilter = SessionHomeFilter.valueOf(filterName)

    BackHandler(enabled = searching) {
        searching = false
        searchInput = ""
    }

    val sessions = sessionsOrNull.orEmpty()
    val worldNames = worldNamesOrNull.orEmpty()
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenSettings) {
                Icon(
                    painterResource(R.drawable.ic_phosphor_gear),
                    contentDescription = "设置",
                    modifier = Modifier.size(23.dp),
                )
            }
            Text(
                "Novex",
                color = NovexColors.Text,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                searching = !searching
                if (!searching) searchInput = ""
            }) {
                Icon(
                    painterResource(R.drawable.ic_phosphor_search),
                    contentDescription = if (searching) "关闭搜索" else "搜索对话",
                    modifier = Modifier.size(22.dp),
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        painterResource(R.drawable.ic_phosphor_plus),
                        contentDescription = "新建",
                        modifier = Modifier.size(23.dp),
                    )
                }
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
                    DropdownMenuItem(
                        text = { Text("创作模式（即将开放）") },
                        onClick = {
                            menuExpanded = false
                            onCreationPlaceholder()
                        },
                    )
                }
            }
        }

        if (searching) {
            NovexConversationSearchField(
                value = searchInput,
                onValueChange = { searchInput = it },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            SessionHomeFilter.entries.forEach { filter ->
                val label = when (filter) {
                    SessionHomeFilter.RECENT -> "最近"
                    SessionHomeFilter.GENERAL -> "通用"
                    SessionHomeFilter.CREATION -> "创作"
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(82.dp)
                        .clickable { filterName = filter.name }
                        .padding(top = 5.dp),
                ) {
                    Text(
                        label,
                        color = if (selectedFilter == filter) NovexColors.Text else NovexColors.SecondaryText,
                        fontSize = 15.sp,
                        fontWeight = if (selectedFilter == filter) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Box(
                        Modifier
                            .padding(top = 9.dp)
                            .width(22.dp)
                            .height(2.dp)
                            .background(if (selectedFilter == filter) NovexColors.Primary else Color.Transparent),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        when {
            !ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                        Text(
                            label,
                            color = NovexColors.Text,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 15.dp, bottom = 8.dp),
                        )
                    }
                    items(rows, key = ChatSessionEntity::id) { session ->
                        NovexConversationRow(
                            session = session,
                            worldName = session.worldId?.let(worldNames::get)
                                ?: worldNameFromSnapshot(session.worldSnapshotJson),
                            onClick = { onOpenSession(session.id) },
                        )
                    }
                }
                section("今天", today)
                section("更早", earlier)
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

@Composable
private fun NovexConversationSearchField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF1F2F5))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_phosphor_search),
            contentDescription = null,
            tint = NovexColors.SecondaryText,
            modifier = Modifier.size(18.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(NovexColors.Primary),
            textStyle = TextStyle(color = NovexColors.Text, fontSize = 15.sp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("搜索对话", color = NovexColors.SecondaryText, fontSize = 15.sp)
                    inner()
                }
            },
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
    }
}

@Composable
private fun NovexConversationRow(
    session: ChatSessionEntity,
    worldName: String?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(if (worldName != null) Color(0xFFE9EFF7) else Color(0xFFEDE9DD)),
        ) {
            Text(
                (session.assistantDisplayName ?: session.title ?: "会").take(1),
                color = if (worldName != null) NovexColors.Primary else Color(0xFF76663B),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
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
            session.lastMessage?.takeIf(String::isNotBlank)?.let { preview ->
                Text(
                    preview,
                    color = NovexColors.SecondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (worldName != null || !session.assistantDisplayName.isNullOrBlank()) {
                Text(
                    listOfNotNull(worldName, session.assistantDisplayName).joinToString(" · "),
                    color = NovexColors.Primary,
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
            modifier = Modifier.padding(start = 8.dp),
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
