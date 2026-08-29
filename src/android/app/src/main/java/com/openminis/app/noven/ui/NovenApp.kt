package com.openminis.app.noven.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.noven.runtime.NovenRuntime
import com.openminis.app.noven.runtime.NovenSession
import com.openminis.app.noven.runtime.NovenWorld
import com.openminis.app.noven.runtime.PreviewNovenRuntime
import com.openminis.app.noven.runtime.StoryNode
import com.openminis.app.noven.runtime.WorldPermission
import kotlinx.coroutines.launch

private val Ink = Color(0xFF1D1B1A)
private val Paper = Color(0xFFFAF8F3)
private val PaperMuted = Color(0xFFF0ECE3)
private val Moss = Color(0xFF3C5A49)
private val Gold = Color(0xFFB88645)
private val Hairline = Color(0xFFE2DDD3)

private sealed interface Page {
    data object Root : Page
    data class World(val id: String) : Page
    data object Chat : Page
    data object Settings : Page
}

@Composable
fun NovenApp(runtime: NovenRuntime = remember { PreviewNovenRuntime() }) {
    var page: Page by remember { mutableStateOf(Page.Root) }
    var homePage by remember { mutableStateOf(1) }

    BackHandler(enabled = page !is Page.Root) {
        page = Page.Root
    }

    when (val current = page) {
        Page.Root -> RootScaffold(
            runtime = runtime,
            onWorld = { page = Page.World(it) },
            onSession = { runtime.openSession(it); page = Page.Chat },
            onNewSession = { runtime.createBlankSession(); page = Page.Chat },
            onSettings = { page = Page.Settings },
            initialPage = homePage,
            onPageChange = { homePage = it },
        )
        is Page.World -> WorldDetail(
            world = runtime.worlds.collectAsState().value.first { it.id == current.id },
            onBack = { page = Page.Root },
            onStart = { runtime.startWorld(current.id); page = Page.Chat },
        )
        Page.Chat -> PlayerChat(runtime = runtime, onBack = {
            page = Page.Root
        })
        Page.Settings -> SettingsScreen(onBack = { page = Page.Root })
    }
}

@Composable
private fun RootScaffold(
    runtime: NovenRuntime,
    onWorld: (String) -> Unit,
    onSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onSettings: () -> Unit,
    initialPage: Int,
    onPageChange: (Int) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { 2 })
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(pagerState.currentPage) {
        onPageChange(pagerState.currentPage)
    }
    Scaffold(
        containerColor = Paper,
        topBar = {
            HomeHeader(
                selectedPage = pagerState.currentPage,
                onSelectPage = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
                onSettings = onSettings,
            )
        },
        floatingActionButton = {
            if (pagerState.currentPage == 1) {
                FloatingActionButton(onClick = onNewSession, containerColor = Ink, contentColor = Color.White) {
                    Icon(Icons.Rounded.Add, contentDescription = "新建故事")
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
            beyondViewportPageCount = 1,
        ) { pageIndex ->
            when (pageIndex) {
                0 -> DiscoverScreen(runtime, Modifier.fillMaxSize(), onWorld, onSession)
                else -> SessionsScreen(runtime, Modifier.fillMaxSize(), onSession)
            }
        }
    }
}

@Composable
private fun HomeHeader(selectedPage: Int, onSelectPage: (Int) -> Unit, onSettings: () -> Unit) {
    Surface(color = Paper, shadowElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("诺文", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, contentDescription = "设置") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                HomeTab("世界", selectedPage == 0, Modifier.weight(1f)) { onSelectPage(0) }
                HomeTab("对话记录", selectedPage == 1, Modifier.weight(1f)) { onSelectPage(1) }
            }
            Divider(color = Hairline)
        }
    }
}

@Composable
private fun HomeTab(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier.clickable(onClick = onClick).padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = if (selected) Ink else Ink.copy(.42f), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        Box(
            Modifier.padding(top = 10.dp).width(30.dp).height(3.dp).clip(CircleShape)
                .background(if (selected) Moss else Color.Transparent)
        )
    }
}

@Composable
private fun DiscoverScreen(runtime: NovenRuntime, modifier: Modifier, onWorld: (String) -> Unit, onSession: (String) -> Unit) {
    val worlds by runtime.worlds.collectAsState()
    val sessions by runtime.sessions.collectAsState()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            Text(
                "选一个世界，故事会从你进入的那一刻开始。",
                color = Ink.copy(.56f),
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            )
        }
        sessions.firstOrNull()?.let { session ->
            item {
                SectionTitle("继续上次的故事")
                ContinueCard(session = session, onClick = { onSession(session.id) })
                Spacer(Modifier.height(26.dp))
            }
        }
        item { SectionTitle("为你推荐") }
        items(worlds, key = { it.id }) { world ->
            WorldCard(world = world, onClick = { onWorld(world.id) })
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ContinueCard(session: NovenSession, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Ink),
        shape = RoundedCornerShape(24.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(176.dp)) {
            session.coverRes?.let {
                Image(painterResource(it), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Ink.copy(.92f), Ink.copy(.18f)))))
            }
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                Text("继续游玩", color = Color.White.copy(.68f), fontSize = 12.sp)
                Text(session.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text(session.preview, color = Color.White.copy(.78f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun WorldCard(world: NovenWorld, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        if (world.coverRes != null) {
            Image(painterResource(world.coverRes), null, Modifier.fillMaxWidth().height(190.dp), contentScale = ContentScale.Crop)
        } else {
            Box(
                Modifier.fillMaxWidth().height(132.dp).background(
                    Brush.linearGradient(listOf(Color(0xFF26352F), Color(0xFF6F746C), Color(0xFFC5AE8B)))
                )
            )
        }
        Column(Modifier.padding(18.dp)) {
            Text(world.title, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(world.subtitle, color = Ink.copy(.62f), lineHeight = 22.sp, modifier = Modifier.padding(top = 4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(top = 12.dp)) {
                items(world.tags) { Tag(it) }
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    Surface(color = PaperMuted, shape = RoundedCornerShape(100.dp)) {
        Text(text, color = Ink.copy(.66f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

@Composable
private fun SessionsScreen(runtime: NovenRuntime, modifier: Modifier, onSession: (String) -> Unit) {
    val sessions by runtime.sessions.collectAsState()
    Column(modifier.fillMaxSize()) {
        Text(
            "继续上次的故事，或从一个新念头开始。",
            color = Ink.copy(.56f),
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
            items(sessions, key = { it.id }) { session ->
                SessionRow(session, onClick = { onSession(session.id) })
            }
        }
    }
}

@Composable
private fun SessionRow(session: NovenSession, onClick: () -> Unit, compact: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = if (compact) 14.dp else 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StoryAvatar(session.coverRes, if (compact) 48.dp else 58.dp)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(session.title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 15.sp else 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(session.timeLabel, color = Ink.copy(.38f), fontSize = 12.sp)
            }
            Text(session.preview, color = Ink.copy(.55f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun StoryAvatar(res: Int?, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(16.dp)).background(
            Brush.linearGradient(listOf(Color(0xFF40564B), Color(0xFFC4AA83)))
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (res != null) Image(painterResource(res), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.AutoStories, null, tint = Color.White.copy(.9f))
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = Paper,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(58.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                Text("设置", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "人工智能如何陪你进入故事",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                color = Ink.copy(.48f),
                fontSize = 13.sp,
            )
            SettingsGroup(
                "诺文",
                listOf(
                    "人格" to "定义它的身份、语气与写作倾向",
                    "记忆" to "管理跨会话保留的世界与偏好",
                    "技能" to "选择创作、检索与整理能力",
                ),
            )
        }
    }
}

@Composable
private fun SettingsGroup(title: String, rows: List<Pair<String, String>>) {
    Text(title, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = Ink.copy(.48f), fontSize = 13.sp)
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(20.dp)) {
        rows.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth().clickable { }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.first, color = Ink, fontWeight = FontWeight.Medium)
                    Text(row.second, color = Ink.copy(.48f), fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                }
                Text("›", color = Ink.copy(.35f), fontSize = 24.sp)
            }
            if (index != rows.lastIndex) Divider(Modifier.padding(start = 18.dp), color = Hairline)
        }
    }
}

@Composable
private fun WorldDetail(world: NovenWorld, onBack: () -> Unit, onStart: () -> Unit) {
    Scaffold(
        containerColor = Paper,
        bottomBar = {
            Surface(color = Paper, shadowElevation = 10.dp) {
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp).height(54.dp),
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink),
                ) { Text("进入世界", fontSize = 17.sp) }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Box(Modifier.fillMaxWidth().height(330.dp)) {
                    if (world.coverRes != null) Image(painterResource(world.coverRes), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF35473F), Color(0xFFB9A17D)))))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(.08f), Color.Black.copy(.72f)))))
                    IconButton(onClick = onBack, Modifier.statusBarsPadding().padding(8.dp).background(Color.Black.copy(.35f), CircleShape)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Color.White)
                    }
                    IconButton(onClick = {}, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).background(Color.Black.copy(.35f), CircleShape)) {
                        Icon(Icons.Rounded.MoreHoriz, "更多", tint = Color.White)
                    }
                    Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                        Text(world.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                        Text(world.subtitle, color = Color.White.copy(.8f), lineHeight = 22.sp)
                    }
                }
            }
            item {
                Column(Modifier.padding(20.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { world.tags.forEach { Tag(it) } }
                    Text("世界介绍", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 26.dp, bottom = 10.dp))
                    Text(world.description, color = Ink.copy(.78f), lineHeight = 27.sp, fontSize = 16.sp)
                    PermissionNote(world.permission)
                    Text("进入后，人工智能将读取作者提供的开始模板，并在这个世界框架下向你说出第一句话。", color = Ink.copy(.55f), lineHeight = 22.sp, modifier = Modifier.padding(top = 24.dp))
                }
            }
        }
    }
}

@Composable
private fun PermissionNote(permission: WorldPermission) {
    val text = when (permission) {
        WorldPermission.READ_ONLY -> "仅供阅读 · 作者未开放游玩改编"
        WorldPermission.STORY_ONLY -> "世界设定不可改动 · 可自由编排你的剧情"
        WorldPermission.OPEN -> "开放世界 · 可自由改造和衍生"
    }
    Surface(color = PaperMuted, shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(top = 18.dp)) {
        Text(text, color = Ink.copy(.7f), modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp), fontSize = 13.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerChat(runtime: NovenRuntime, onBack: () -> Unit) {
    val sessions by runtime.sessions.collectAsState()
    val activeId by runtime.activeSessionId.collectAsState()
    val nodes by runtime.storyNodes.collectAsState()
    val generating by runtime.isGenerating.collectAsState()
    val active = sessions.firstOrNull { it.id == activeId }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var draft by remember(activeId) { mutableStateOf("") }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(.88f), drawerContainerColor = Paper) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("会话", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        runtime.createBlankSession()
                        scope.launch { drawer.close() }
                    }) { Icon(Icons.Rounded.Add, "新建故事") }
                    IconButton(onClick = { scope.launch { drawer.close() } }) { Icon(Icons.Rounded.Close, "关闭") }
                }
                Divider(color = Hairline)
                LazyColumn(Modifier.fillMaxHeight()) {
                    item {
                        TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Icon(Icons.Rounded.Explore, null)
                            Spacer(Modifier.width(8.dp))
                            Text("返回发现", color = Ink)
                        }
                    }
                    items(sessions, key = { it.id }) { session ->
                        Surface(color = if (session.id == activeId) PaperMuted else Color.Transparent, shape = RoundedCornerShape(18.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
                            SessionRow(session, compact = true, onClick = {
                                runtime.openSession(session.id)
                                scope.launch { drawer.close() }
                            })
                        }
                    }
                }
            }
        },
    ) {
        Scaffold(
            containerColor = Paper,
            topBar = {
                Surface(color = Paper.copy(alpha = .97f), shadowElevation = 0.dp) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().height(58.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Rounded.Menu, "打开会话") }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(active?.title ?: "新的故事", color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("玩家模式", color = Ink.copy(.42f), fontSize = 11.sp)
                        }
                        IconButton(onClick = {}) { Icon(Icons.Rounded.MoreHoriz, "故事资料") }
                    }
                }
            },
            bottomBar = {
                Composer(
                    draft = draft,
                    onDraft = { draft = it },
                    generating = generating,
                    onStop = runtime::stop,
                    onSend = {
                        if (draft.isNotBlank()) {
                            runtime.send(draft.trim())
                            draft = ""
                        }
                    },
                )
            },
        ) { padding ->
            if (nodes.isEmpty()) {
                BlankConversation(Modifier.padding(padding), onPrompt = { draft = it })
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(nodes, key = { it.id }) { node ->
                        StoryNodeView(node, onAction = { draft = it })
                    }
                }
            }
        }
    }
}

@Composable
private fun BlankConversation(modifier: Modifier, onPrompt: (String) -> Unit) {
    Column(modifier.fillMaxSize().padding(horizontal = 28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(color = PaperMuted, shape = CircleShape) {
            Icon(Icons.Rounded.AutoStories, null, tint = Moss, modifier = Modifier.padding(18.dp).size(28.dp))
        }
        Text("从一句话开始", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp))
        Text("说出脑中突然出现的灵感，人工智能会接住并开始构造。", color = Ink.copy(.56f), lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        TextButton(onClick = { onPrompt("我想到一个世界：") }, modifier = Modifier.padding(top = 10.dp)) { Text("写下第一个念头", color = Moss) }
    }
}

@Composable
private fun StoryNodeView(node: StoryNode, onAction: (String) -> Unit) {
    when (node) {
        is StoryNode.UserMessage -> UserMessageNode(node)
        is StoryNode.Narrative -> NarrativeNode(node)
        is StoryNode.Character -> CharacterNode(node)
        is StoryNode.Actions -> ActionsNode(node, onAction)
    }
}

@Composable
private fun UserMessageNode(node: StoryNode.UserMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = Moss,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomEnd = 20.dp, bottomStart = 20.dp),
            modifier = Modifier.fillMaxWidth(.82f),
        ) {
            Text(
                node.text,
                color = Color.White,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun NarrativeNode(node: StoryNode.Narrative) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier.padding(top = 5.dp).size(28.dp).clip(CircleShape).background(PaperMuted),
            contentAlignment = Alignment.Center,
        ) {
            Text("诺", color = Moss, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            if (node.eyebrow != null || node.title != null) {
                Column(
                    Modifier.fillMaxWidth().background(PaperMuted, RoundedCornerShape(16.dp)).padding(15.dp)
                ) {
                    node.eyebrow?.let {
                        Text(it, color = Gold, fontSize = 11.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
                    }
                    node.title?.let {
                        Text(
                            it,
                            color = Ink,
                            fontSize = 20.sp,
                            lineHeight = 28.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = if (node.eyebrow == null) 0.dp else 5.dp),
                        )
                    }
                }
                Spacer(Modifier.height(9.dp))
            }
            node.paragraphs.forEach { paragraph ->
                Text(paragraph, color = Ink.copy(.88f), fontSize = 16.sp, lineHeight = 27.sp, modifier = Modifier.padding(top = 7.dp))
            }
        }
    }
}

@Composable
private fun CharacterNode(node: StoryNode.Character) {
    var expanded by remember(node.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(Ink),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.width(88.dp).height(112.dp).clip(RoundedCornerShape(20.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFFD0B58C), Color(0xFF53675B)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Person, null, tint = Color.White.copy(.92f), modifier = Modifier.size(45.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("人物进入故事", color = Color(0xFFD7B47B), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(node.name, color = Color.White, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                    Text(node.identity, color = Color.White.copy(.58f), fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 3.dp))
                    Text(
                        if (expanded) "收起角色卡 ↑" else "查看角色卡 ›",
                        color = Color.White.copy(.78f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 13.dp),
                    )
                }
            }
            Surface(color = Color.White, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
                Column(Modifier.fillMaxWidth().padding(17.dp)) {
                    Text("此刻的她", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(node.publicFace, color = Ink.copy(.76f), lineHeight = 22.sp, modifier = Modifier.padding(top = 5.dp))
                    if (expanded) {
                        Divider(Modifier.padding(vertical = 13.dp), color = Hairline)
                        Text("你已经知道", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(node.secret, color = Ink, lineHeight = 22.sp, modifier = Modifier.padding(top = 5.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(top = 13.dp)) {
                            items(node.traits) { Tag(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionsNode(node: StoryNode.Actions, onAction: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(node.prompt, color = Ink.copy(.62f), fontSize = 13.sp, modifier = Modifier.padding(bottom = 9.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(node.actions) { action ->
                Surface(
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Hairline),
                    shape = RoundedCornerShape(100.dp),
                    modifier = Modifier.clickable { onAction(action) },
                ) {
                    Text(action, color = Ink, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp))
                }
            }
        }
        Text("点击填入输入框", color = Ink.copy(.38f), fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    generating: Boolean,
    onStop: () -> Unit,
    onSend: () -> Unit,
) {
    Surface(color = Color.White, shadowElevation = 10.dp) {
        Row(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = {}) { Icon(Icons.Rounded.Add, "添加附件", tint = Ink.copy(.72f)) }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f),
                placeholder = { Text("你想做什么……", color = Ink.copy(.35f)) },
                shape = RoundedCornerShape(22.dp),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                keyboardActions = KeyboardActions(),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = if (generating) onStop else onSend,
                enabled = generating || draft.isNotBlank(),
                modifier = Modifier.background(if (generating || draft.isNotBlank()) Ink else PaperMuted, CircleShape),
            ) {
                Icon(if (generating) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send, if (generating) "停止" else "发送", tint = if (generating || draft.isNotBlank()) Color.White else Ink.copy(.3f))
            }
        }
    }
}
