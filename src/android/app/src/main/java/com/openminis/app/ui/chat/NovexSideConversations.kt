package com.openminis.app.ui.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexType
import com.openminis.app.ui.novex.TextButton
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Fixed bookmark palette — muted, distinguishable on both themes. */
private val BookmarkPalette = listOf(
    Color(0xFFE8B4B8), Color(0xFFB4CDE8), Color(0xFFB8E0C8), Color(0xFFE8D3B4),
    Color(0xFFCBB4E8), Color(0xFFB4D8E8), Color(0xFFE8C8B4), Color(0xFFC8E8B4),
    Color(0xFFE8B4D8), Color(0xFFD8D8B4),
)

private const val SIDE_PREFS = "novex_side_conversations"

/**
 * 侧边对话系统：主对话的并行分支。每条侧边对话是独立会话（分裂点拷贝历史、
 * 之后零同步），以贴边书签呈现；点书签开小窗（可放大/全屏），与主线并行生成；
 * 唯一回桥是"回传"——增量交接简报以带标记的消息进入主对话历史。
 */
@Composable
internal fun NovexSideConversations(
    mainSessionId: String,
    mainViewModel: ChatViewModel,
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    memoryRepository: MemoryRepository?,
    skillRepository: SkillRepository?,
    mcpRepository: MCPRepository?,
    appContext: android.content.Context,
    requestNewSide: Boolean,
    onNewSideConsumed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var sides by remember(mainSessionId) { mutableStateOf(listOf<com.openminis.app.data.db.ChatSessionEntity>()) }
    var openSideId by rememberSaveable(mainSessionId) { mutableStateOf<String?>(null) }
    var windowScale by rememberSaveable(mainSessionId) { mutableIntStateOf(0) } // 0 小窗 1 放大 2 全屏
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch { sides = runCatching { chatRepository.listSideSessions(mainSessionId) }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(mainSessionId) { refresh() }

    LaunchedEffect(requestNewSide) {
        if (!requestNewSide) return@LaunchedEffect
        onNewSideConsumed()
        scope.launch {
            runCatching { chatRepository.createSideSession(mainSessionId) }
                .onSuccess { refresh(); openSideId = it.id; windowScale = 0 }
                .onFailure { notice = it.message ?: "创建侧边对话失败" }
        }
    }

    // ── Bookmarks: docked chips on the right edge, stacked top-down ──
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val chipPx = with(density) { 44.dp.toPx() }
        val columnTopPx = with(density) { 90.dp.toPx() }
        sides.forEachIndexed { index, side ->
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(
                            0,
                            (columnTopPx + index * (chipPx + 6.dp.toPx())).roundToInt(),
                        )
                    }
                    .shadow(3.dp, CircleShape)
                    .background(colorFor(context, side.id), CircleShape)
                    .size(44.dp)
                    .clickable { openSideId = side.id; windowScale = 0 },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    side.title.orEmpty().trim().take(1).ifEmpty { "侧" },
                    color = Color(0xCC1C1C1E),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // ── The single small window (one at a time) ──
        val openSide = sides.firstOrNull { it.id == openSideId }
        if (openSide != null) {
            NovexSideConversationWindow(
                side = openSide,
                scale = windowScale,
                onScale = { windowScale = it },
                onClose = { openSideId = null },
                onRequestDelete = { confirmDelete = openSide.id },
                chatRepository = chatRepository,
                providerRepository = providerRepository,
                memoryRepository = memoryRepository,
                skillRepository = skillRepository,
                mcpRepository = mcpRepository,
                appContext = appContext,
                onHandoff = { brief ->
                    scope.launch {
                        runCatching {
                            chatRepository.appendMessage(
                                mainSessionId, "user",
                                buildHandoffParts(brief),
                            )
                        }.onSuccess {
                            notice = "交接简报已并入主对话"
                            mainViewModel.reloadActiveConversation()
                        }.onFailure { notice = it.message ?: "交接未写入主对话" }
                    }
                },
            )
        }
    }

    if (confirmDelete != null) {
        com.openminis.app.ui.novex.NovexContentDialog(
            "关闭这条侧边对话？",
            onDismiss = { confirmDelete = null },
            confirmButton = { TextButton(onClick = {
                val id = confirmDelete
                confirmDelete = null
                if (id != null) scope.launch {
                    runCatching { chatRepository.deleteSession(id) }
                    if (openSideId == id) openSideId = null
                    refresh()
                }
            }) { Text("关闭并删除", color = NovexColors.Danger) } },
        ) {
            Text("关闭后聊天记录将删除，且不可恢复。已回传主对话的交接简报不受影响。", style = NovexType.Body, color = NovexColors.Text)
        }
    }
    notice?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(2200)
            notice = null
        }
        Box(Modifier.fillMaxWidth().padding(top = 46.dp), contentAlignment = Alignment.TopCenter) {
            Text(message, Modifier.background(NovexColors.Surface.copy(alpha = 0.96f), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                style = NovexType.Metadata, color = NovexColors.Text)
        }
    }
}

/** One side conversation window: small → large → fullscreen (minimal chrome). */
@Composable
private fun NovexSideConversationWindow(
    side: com.openminis.app.data.db.ChatSessionEntity,
    scale: Int,
    onScale: (Int) -> Unit,
    onClose: () -> Unit,
    onRequestDelete: () -> Unit,
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    memoryRepository: MemoryRepository?,
    skillRepository: SkillRepository?,
    mcpRepository: MCPRepository?,
    appContext: android.content.Context,
    onHandoff: (String) -> Unit,
) {
    val store = remember(side.id) { ViewModelStore() }
    DisposableEffect(side.id) { onDispose { store.clear() } }
    val viewModel = remember(side.id) {
        ViewModelProvider(store, ChatViewModel.factory(
            sessionId = side.id,
            chatRepository = chatRepository,
            providerRepository = providerRepository,
            appContext = appContext,
            memoryRepository = memoryRepository,
            skillRepository = skillRepository,
            mcpRepository = mcpRepository,
        ))[ChatViewModel::class.java]
    }
    LaunchedEffect(viewModel) { viewModel.reloadActiveConversation() }
    val messages by viewModel.uiMessages.collectAsState()
    val streaming by viewModel.isStreaming.collectAsState()
    val modelName by viewModel.modelName.collectAsState()
    var input by remember(side.id) { mutableStateOf("") }
    var handoffBusy by remember { mutableStateOf(false) }
    var handoffBaseCount by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

    val fullscreen = scale == 2
    val widthFraction = if (fullscreen) 1f else if (scale == 1) 0.92f else 0.78f
    val heightFraction = if (fullscreen) 1f else if (scale == 1) 0.72f else 0.5f

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth * widthFraction
        val h = maxHeight * heightFraction
        Column(
            Modifier
                .align(if (fullscreen) Alignment.Center else Alignment.BottomEnd)
                .padding(if (fullscreen) 0.dp else 10.dp)
                .shadow(6.dp, RoundedCornerShape(if (fullscreen) 0.dp else 16.dp))
                .background(NovexColors.Surface, RoundedCornerShape(if (fullscreen) 0.dp else 16.dp))
                .width(w)
                .height(h),
        ) {
            // Chrome: full screen keeps only name · model · back.
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(side.title.orEmpty(), Modifier.weight(1f), style = NovexType.ItemTitle, fontWeight = FontWeight.SemiBold,
                    color = NovexColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (fullscreen) {
                    Text(modelName.ifEmpty { "默认模型" }, style = NovexType.Metadata, color = NovexColors.SecondaryText,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 6.dp).widthIn(max = 110.dp))
                }
                TextButton(onClick = { onScale((scale + 1) % 3) }) { Text(if (fullscreen) "缩小" else if (scale == 1) "全屏" else "放大") }
                TextButton(onClick = onClose) { Text(if (fullscreen) "返回" else "收起") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp), state = listState) {
                items(messages, key = { it.id }) { message ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = if (message.role == "user") Arrangement.End else Arrangement.Start) {
                        Text(
                            message.content.ifBlank { if (message.role == "assistant") "（工具执行）" else "" },
                            Modifier
                                .widthIn(max = if (fullscreen) 640.dp else 300.dp)
                                .background(
                                    if (message.role == "user") NovexColors.PrimarySoft else NovexColors.SurfaceMuted,
                                    RoundedCornerShape(12.dp),
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            style = NovexType.Body,
                            color = NovexColors.Text,
                            fontSize = if (fullscreen) 15.sp else 13.sp,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicInputField(input, { input = it }, Modifier.weight(1f), streaming)
                TextButton(onClick = {
                    if (input.isNotBlank() && !streaming) { viewModel.sendMessage(input); input = "" }
                }) { Text(if (streaming) "生成中" else "发送") }
                TextButton(enabled = !streaming && !handoffBusy, onClick = {
                    handoffBaseCount = messages.size
                    handoffBusy = true
                    val watermark = readWatermark(appContext, side.id)
                    viewModel.sendMessage(handoffInstruction(watermark))
                }) { Text("回传") }
            }
        }
    }
    // Handoff capture: the brief is the first assistant text AFTER the instruction turn.
    LaunchedEffect(handoffBusy, streaming, messages.size) {
        if (handoffBusy && !streaming && messages.size > handoffBaseCount) {
            val last = messages.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
            if (last != null) {
                onHandoff(last.content)
                writeWatermark(appContext, side.id, last.content)
            }
            handoffBusy = false
        }
    }
}

@Composable
private fun BasicInputField(value: String, onValueChange: (String) -> Unit, modifier: Modifier, streaming: Boolean) {
    Box(
        modifier
            .background(NovexColors.SurfaceMuted, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(if (streaming) "排队中…" else "戏外讨论…", style = NovexType.Body, color = NovexColors.TertiaryText)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = NovexType.Body.copy(color = NovexColors.Text),
        )
    }
}

private fun colorFor(context: Context, sideId: String): Color =
    BookmarkPalette[(readColorIndex(context, sideId))]

private fun readColorIndex(context: Context, sideId: String): Int {
    val prefs = context.getSharedPreferences(SIDE_PREFS, Context.MODE_PRIVATE)
    var index = prefs.getInt("color:$sideId", -1)
    if (index < 0) {
        index = (sideId.hashCode().let { if (it < 0) -it else it }) % BookmarkPalette.size
        prefs.edit().putInt("color:$sideId", index).apply()
    }
    return index
}

/** v1 delivery: the brief lands as a header-marked user message (collapsed-marker rendering ships next). */
private fun buildHandoffParts(brief: String): String {
    val safe = org.json.JSONArray().put(org.json.JSONObject().put("type", "text")
        .put("value", "〔侧边结论 · 已并入〕\n$brief"))
    return safe.toString()
}

private fun handoffInstruction(previous: String?): String =
    (previous?.takeIf { it.isNotBlank() }?.let { "上一次已交接的内容：\n$it\n\n" } ?: "") +
        "请把本次讨论中上次交接之后的新结论，压缩成一份交接简报：只列确定的事实、设定变更和接下来要做的事，" +
        "不要复述剧情，不要空话，500 字以内。直接输出简报正文。"

private fun readWatermark(context: Context, sideId: String): String? =
    context.getSharedPreferences(SIDE_PREFS, Context.MODE_PRIVATE).getString("brief:$sideId", null)

private fun writeWatermark(context: Context, sideId: String, brief: String) {
    context.getSharedPreferences(SIDE_PREFS, Context.MODE_PRIVATE).edit().putString("brief:$sideId", brief).apply()
}
