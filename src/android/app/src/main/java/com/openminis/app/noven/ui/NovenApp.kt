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
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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

private enum class RootTab { DISCOVER, SESSIONS, MINE }
private sealed interface Page {
    data object Root : Page
    data class World(val id: String) : Page
    data object Chat : Page
}

@Composable
fun NovenApp(runtime: NovenRuntime = remember { PreviewNovenRuntime() }) {
    var rootTab by remember { mutableStateOf(RootTab.DISCOVER) }
    var page: Page by remember { mutableStateOf(Page.Root) }

    BackHandler(enabled = page !is Page.Root) {
        page = Page.Root
        rootTab = RootTab.SESSIONS
    }

    when (val current = page) {
        Page.Root -> RootScaffold(
            runtime = runtime,
            tab = rootTab,
            onTabChange = { rootTab = it },
            onWorld = { page = Page.World(it) },
            onSession = { runtime.openSession(it); page = Page.Chat },
            onNewSession = { runtime.createBlankSession(); page = Page.Chat },
        )
        is Page.World -> WorldDetail(
            world = runtime.worlds.collectAsState().value.first { it.id == current.id },
            onBack = { page = Page.Root },
            onStart = { runtime.startWorld(current.id); page = Page.Chat },
        )
        Page.Chat -> PlayerChat(runtime = runtime, onBack = {
            page = Page.Root
            rootTab = RootTab.SESSIONS
        })
    }
}

@Composable
private fun RootScaffold(
    runtime: NovenRuntime,
    tab: RootTab,
    onTabChange: (RootTab) -> Unit,
    onWorld: (String) -> Unit,
    onSession: (String) -> Unit,
    onNewSession: () -> Unit,
) {
    Scaffold(
        containerColor = Paper,
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = tab == RootTab.DISCOVER,
                    onClick = { onTabChange(RootTab.DISCOVER) },
                    icon = { Icon(Icons.Rounded.Explore, null) },
                    label = { Text("发现") },
                )
                NavigationBarItem(
                    selected = tab == RootTab.SESSIONS,
                    onClick = { onTabChange(RootTab.SESSIONS) },
                    icon = { Icon(Icons.Rounded.ChatBubbleOutline, null) },
                    label = { Text("会话") },
                )
                NavigationBarItem(
                    selected = tab == RootTab.MINE,
                    onClick = { onTabChange(RootTab.MINE) },
                    icon = { Icon(Icons.Rounded.Person, null) },
                    label = { Text("我的") },
                )
            }
        },
        floatingActionButton = {
            if (tab == RootTab.SESSIONS) {
                FloatingActionButton(onClick = onNewSession, containerColor = Ink, contentColor = Color.White) {
                    Icon(Icons.Rounded.Add, contentDescription = "新建故事")
                }
            }
        },
    ) { padding ->
        when (tab) {
            RootTab.DISCOVER -> DiscoverScreen(runtime, Modifier.padding(padding), onWorld, onSession)
            RootTab.SESSIONS -> SessionsScreen(runtime, Modifier.padding(padding), onSession)
            RootTab.MINE -> MineScreen(Modifier.padding(padding))
        }
    }
}

@Composable
private fun ScreenHeader(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 29.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = Ink.copy(alpha = .55f), fontSize = 13.sp)
        }
        trailing?.invoke()
    }
}

@Composable
private fun DiscoverScreen(runtime: NovenRuntime, modifier: Modifier, onWorld: (String) -> Unit, onSession: (String) -> Unit) {
    val worlds by runtime.worlds.collectAsState()
    val sessions by runtime.sessions.collectAsState()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            ScreenHeader("诺文", "进入一个世界，让故事从你开始") {
                IconButton(onClick = {}) { Icon(Icons.Rounded.Search, contentDescription = "搜索世界") }
            }
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
        ScreenHeader("会话", "每段故事都保留在这里")
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
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
private fun MineScreen(modifier: Modifier) {
    Column(modifier.fillMaxSize()) {
        ScreenHeader("我的", "模型、联网与显示设置")
        SettingsGroup("人工智能服务", listOf("模型提供方", "联网与浏览", "用量记录"))
        SettingsGroup("应用", listOf("外观与字体", "本地存储", "关于诺文"))
    }
}

@Composable
private fun SettingsGroup(title: String, rows: List<String>) {
    Text(title, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = Ink.copy(.48f), fontSize = 13.sp)
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp), colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(20.dp)) {
        rows.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth().clickable { }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row, color = Ink, modifier = Modifier.weight(1f))
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
        is StoryNode.Narrative -> NarrativeNode(node)
        is StoryNode.Character -> CharacterNode(node)
        is StoryNode.Actions -> ActionsNode(node, onAction)
    }
}

@Composable
private fun NarrativeNode(node: StoryNode.Narrative) {
    Column(Modifier.fillMaxWidth()) {
        node.eyebrow?.let { Text(it.uppercase(), color = Gold, fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold) }
        node.title?.let { Text(it, color = Ink, fontSize = 23.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 5.dp, bottom = 8.dp)) }
        node.paragraphs.forEach { paragraph ->
            Text(paragraph, color = Ink.copy(.88f), fontSize = 17.sp, lineHeight = 29.sp, modifier = Modifier.padding(top = 9.dp))
        }
    }
}

@Composable
private fun CharacterNode(node: StoryNode.Character) {
    Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(22.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Hairline)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Color(0xFFB49A77), Color(0xFF3E5048)))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Person, null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text("角色卡", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(node.name, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(node.identity, color = Ink.copy(.52f), fontSize = 13.sp)
                }
                Text("查看 ›", color = Moss, fontSize = 13.sp)
            }
            Divider(Modifier.padding(vertical = 14.dp), color = Hairline)
            Text(node.publicFace, color = Ink.copy(.72f), lineHeight = 22.sp)
            Text("秘密 · ${node.secret}", color = Ink, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(top = 13.dp)) { node.traits.forEach { Tag(it) } }
        }
    }
}

@Composable
private fun ActionsNode(node: StoryNode.Actions, onAction: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(node.prompt, color = Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        node.actions.forEach { action ->
            Surface(
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, Hairline),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onAction(action) },
            ) {
                Text(action, color = Ink, modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp))
            }
        }
        Text("点击只会填入输入框，不会直接发送", color = Ink.copy(.4f), fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
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
