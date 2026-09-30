package com.openminis.app.ui.chat

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import novex.android.data.chat.SessionRow
import com.openminis.app.data.repository.ChatRepository
import novex.core.PlaythroughState
import novex.core.PlaythroughValue
import novex.android.ui.NovexColors
import novex.android.ui.NovexType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Rail id of the playthrough status ear (UUIDs can never contain NUL). */
private const val HUD_ID = "\u0000hud"

/**
 * [T-side-naming] 书签凸耳短标签：显示侧边编号——新命名「侧边 N」与旧命名
 * 「主对话标题·侧N」都提取数字；其余标题回落首字。凸耳半出屏后可见区
 * 窄，只放数字（完整名字在侧边页标题与管理面板里看）。
 */
internal fun bookmarkTabLabel(title: String?): String {
    val t = title.orEmpty()
    Regex("·侧(\\d+)\\s*$").find(t)?.let { return it.groupValues[1] }
    Regex("侧边\\s*(\\d+)").find(t)?.let { return it.groupValues[1] }
    return t.trim().take(1).ifEmpty { "侧" }
}

/** State-ear participation: null = no playthrough state → no ear. */
internal data class NovexEdgeHandleSpec(
    val state: PlaythroughState,
    val update: NovexDataUpdateEvent?,
    val onDismissUpdate: () -> Unit,
)

/**
 * 侧边组件轨（edge-v1 定稿）：
 * - 书签凸耳 = 左缘半出屏白色小卡（屏内只露编号），跨主线↔侧边页常驻；
 *   当前所在侧边薄荷填充；新书签排最下；拖动仅排序。点「侧边对话」菜单
 *   打开左侧管理面板（名称 + 最近消息 + 时间 + 新建）。
 * - 状态凸耳 = 右缘迷你状态条（仅主线页）：数值状态竖排堆叠成格，越多
 *   越长；该格数值变化时闪薄荷底，有未读更新时顶部亮绿点；按下即拖、
 *   只沿边上下，位置按会话记住；点按展开右缘全量面板（固定尺寸、内容
 *   内部滚动——手机上不给抽屉做窗口管理）。
 * - 淡化（仅主线页）：整列/凸耳原地透明度归零，布局零跳动；淡化后不
 *   拦截点击，任何触摸由 ChatScreen 统一唤回。侧边页常驻不淡化。
 */
@Composable
internal fun NovexSideConversations(
    railSessionId: String,
    currentSessionId: String,
    isSidePage: Boolean,
    chatRepository: ChatRepository,
    requestNewSide: Boolean,
    onNewSideConsumed: () -> Unit,
    requestSidePanel: Boolean = false,
    onSidePanelConsumed: () -> Unit = {},
    onOpenSide: (String) -> Unit,
    handle: NovexEdgeHandleSpec?,
    chromeCollapsed: Boolean,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var sides by remember(railSessionId) { mutableStateOf(listOf<SessionRow>()) }
    var sidesLoaded by remember(railSessionId) { mutableStateOf(false) }
    var order by remember(railSessionId) { mutableStateOf<List<String>>(emptyList()) }
    val savedHandleFraction = remember(railSessionId) {
        NovexEdgePrefs.readFraction(context, EDGE_PREFS_HUD, railSessionId)
    }
    var handleFraction by remember(railSessionId) { mutableFloatStateOf(savedHandleFraction) }
    var expandedPanel by rememberSaveable(railSessionId) { mutableStateOf(false) }
    var showSidePanel by rememberSaveable(railSessionId) { mutableStateOf(false) }
    // Drag state — one component at a time, never hides the others.
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(railSessionId) {
        sides = runCatching { chatRepository.sideSessionsOf(railSessionId) }.getOrDefault(emptyList())
        val stored = NovexEdgePrefs.readOrder(context, EDGE_PREFS_SIDES, "order:$railSessionId").orEmpty()
        // Stored order first, then any sides never ordered (new ones appended at
        // the bottom = 往下排), deleted ids dropped silently.
        order = stored.filter { id -> sides.any { it.id == id } } + sides.map { it.id }.filter { it !in stored }
        sidesLoaded = true
    }

    fun createSide() {
        scope.launch {
            runCatching { chatRepository.createSideSession(railSessionId) }
                .onSuccess { side ->
                    // [T-side-snapshot] 分裂即定格：记下主线当前活跃路径的消息 ID，
                    // 侧边后续请求按此清单只读拼接主线历史；主线再怎么走都不影响。
                    runCatching {
                        SideSnapshotStore.write(
                            context, side.id, railSessionId,
                            chatRepository.loadActiveConversation(railSessionId).activeMessages.map { it.id },
                        )
                    }
                    onOpenSide(side.id)
                }
                .onFailure { android.widget.Toast.makeText(context, it.message ?: "创建侧边对话失败", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    LaunchedEffect(requestNewSide) {
        if (!requestNewSide) return@LaunchedEffect
        onNewSideConsumed()
        createSide()
    }
    LaunchedEffect(requestSidePanel) {
        if (!requestSidePanel) return@LaunchedEffect
        onSidePanelConsumed()
        showSidePanel = true
    }

    val collapsed = chromeCollapsed && !isSidePage
    if (!sidesLoaded && handle == null) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val screenW = with(density) { maxWidth.toPx() }
        val screenH = with(density) { maxHeight.toPx() }
        val bottomReserve = with(density) { 170.dp.toPx() }
        // [T-bookmark-under-topbar] 顶栏悬浮盖在内容上，凸耳列起点必须让出
        // 「顶栏高度 + 余量」，否则整列被顶栏埋住。
        val topInsetPx = with(density) {
            (chatTopBarExpandedHeightDp(density.fontScale).dp + 8.dp).toPx()
        }

        // ── Bookmark ears (left edge), top-down stacking with cumulative slots ──
        fun earSize(id: String): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> =
            if (id == currentSessionId) BookmarkEarCurrentLength to BookmarkEarCurrentHeight
            else BookmarkEarLength to BookmarkEarHeight

        /** second = 高度（垂直堆叠方向上的步进）。 */
        fun earHeightPx(id: String): Float = with(density) { earSize(id).second.toPx() }
        val gapPx = with(density) { BookmarkEarGap.toPx() }

        /** Slot Y of each ear in the CURRENT order (dragged one excluded). */
        fun slotYs(exclude: String?): List<Pair<String, Float>> = buildList {
            var y = topInsetPx
            order.forEach { id ->
                if (id == exclude) return@forEach
                add(id to y)
                y += earHeightPx(id) + gapPx
            }
        }

        // [T-chrome-fade] 原地淡化：透明度动画到 0，位置尺寸一律不动；淡出到
        // 0 后不再参与组合，也不拦截任何点击。
        val railAlpha by animateFloatAsState(
            targetValue = if (collapsed) 0f else 1f,
            animationSpec = tween(220),
            label = "edgeRailAlpha",
        )

        if (railAlpha > 0.01f) sides.forEach { side ->
            if (side.id !in order) return@forEach
            val isCurrent = side.id == currentSessionId
            val (earLength, earHeight) = earSize(side.id)
            val dragging = draggingId == side.id
            // While dragging, the OTHERS keep their slots (stable, no shifting);
            // the dragged ear reads its own slot only to seed dragY.
            val baseY = slotYs(exclude = null).firstOrNull { it.first == side.id }?.second ?: topInsetPx
            NovexEdgeGestureSurface(
                shape = bookmarkEarShape(),
                width = earLength,
                height = earHeight,
                fill = if (isCurrent) NovexColors.Primary else novexEdgeFill(),
                rim = novexEdgeRim(),
                elevation = if (railAlpha >= 0.99f) 3.dp else 0.dp,
                modifier = Modifier
                    .graphicsLayer { alpha = railAlpha }
                    .offset {
                        // 半出屏：左半段永久留在屏幕外，屏内只露右半段与编号。
                        IntOffset(
                            -(earLength.toPx() / 2f).roundToInt(),
                            (if (dragging) dragY else baseY).roundToInt(),
                        )
                    },
                contentAlignment = Alignment.CenterEnd,
                onTap = if (collapsed) null else ({
                    if (!isCurrent) {
                        onOpenSide(side.id)
                    }
                }),
                drag = if (collapsed) null else object : NovexEdgeDragCallbacks {
                    override fun onDragStart() {
                        draggingId = side.id
                        dragY = baseY
                    }

                    override fun onDrag(delta: Offset, change: PointerInputChange) {
                        dragY = (dragY + delta.y).coerceIn(topInsetPx, (screenH - bottomReserve - earHeightPx(side.id)).coerceAtLeast(topInsetPx))
                    }

                    override fun onDragEnd() {
                        // Reorder: insertion index by drop position among the others.
                        val others = slotYs(exclude = side.id)
                        val draggedCenter = dragY + earHeightPx(side.id) / 2f
                        val index = others.count { (id, y) -> y + earHeightPx(id) / 2f <= draggedCenter }
                        val next = reorderBookmarkIds(order, side.id, index)
                        if (next != order) {
                            order = next
                            NovexEdgePrefs.writeOrder(context, EDGE_PREFS_SIDES, "order:$railSessionId", next)
                        }
                        draggingId = null
                    }

                    override fun onDragCancel() { draggingId = null }
                },
            ) {
                Text(
                    bookmarkTabLabel(side.title),
                    color = if (isCurrent) NovexColors.Canvas else NovexColors.Text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    // 文字落在屏内可见半段（左半段在屏外）。
                    modifier = Modifier.padding(end = 10.dp),
                )
            }
        }

        // ── Status ear (right edge, main pages only): mini status bar. ──
        if (handle != null && !expandedPanel && railAlpha > 0.01f) {
            val earEntries = remember(handle.state.values) {
                handle.state.values.entries
                    .filter { it.value is PlaythroughValue.Number || it.value is PlaythroughValue.Flag }
                    .sortedBy { it.key }
            }
            val visibleCells = earEntries.take(StatusEarMaxCells)
            val overflow = earEntries.size - visibleCells.size
            val cellCount = visibleCells.size + (if (overflow > 0) 1 else 0)
            // 纯文本状态（无数值/标记）时凸耳退化成一格「≡」入口。
            val earHeight = StatusEarCellHeight * cellCount.coerceAtLeast(1)
            val earWpx = with(density) { StatusEarWidth.toPx() }
            val earHpx = with(density) { earHeight.toPx() }
            val maxEarY = (screenH - bottomReserve - earHpx).coerceAtLeast(topInsetPx)
            val earBaseY = topInsetPx + handleFraction * (maxEarY - topInsetPx)
            NovexEdgeGestureSurface(
                shape = statusEarShape(),
                floatingShape = RoundedCornerShape(50),
                width = StatusEarWidth,
                height = earHeight,
                fill = novexEdgeFill(),
                rim = novexEdgeRim(),
                floating = draggingId == HUD_ID,
                elevation = if (railAlpha >= 0.99f) 3.dp else 0.dp,
                modifier = Modifier
                    .graphicsLayer { alpha = railAlpha }
                    .offset {
                        IntOffset(
                            (screenW - earWpx).roundToInt(),
                            (if (draggingId == HUD_ID) dragY else earBaseY).roundToInt(),
                        )
                    },
                onTap = if (collapsed) null else ({ expandedPanel = true }),
                drag = if (collapsed) null else object : NovexEdgeDragCallbacks {
                    override fun onDragStart() {
                        draggingId = HUD_ID
                        dragY = earBaseY
                    }

                    override fun onDrag(delta: Offset, change: PointerInputChange) {
                        dragY = (dragY + delta.y).coerceIn(topInsetPx, maxEarY)
                    }

                    override fun onDragEnd() {
                        handleFraction = ((dragY - topInsetPx) / (maxEarY - topInsetPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        draggingId = null
                        NovexEdgePrefs.writeFraction(context, EDGE_PREFS_HUD, railSessionId, handleFraction)
                    }

                    override fun onDragCancel() { draggingId = null }
                },
            ) {
                Column(Modifier.fillMaxSize()) {
                    visibleCells.forEach { (key, value) ->
                        StatusEarCell(
                            key = key,
                            text = when (value) {
                                is PlaythroughValue.Number ->
                                    if (value.value % 1.0 == 0.0) value.value.toLong().toString() else value.value.toString()
                                is PlaythroughValue.Flag -> if (value.value) "✓" else "–"
                                is PlaythroughValue.Text -> value.value.take(2)
                            },
                            highlighted = handle.update?.changes?.any { it.key == key } == true,
                            modifier = Modifier.height(StatusEarCellHeight),
                        )
                        if (key != visibleCells.last().key || overflow > 0) {
                            Box(
                                Modifier
                                    .padding(horizontal = 8.dp)
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(NovexColors.Divider.copy(alpha = 0.5f)),
                            )
                        }
                    }
                    if (overflow > 0) {
                        Box(
                            Modifier.fillMaxWidth().height(StatusEarCellHeight),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("+$overflow", color = NovexColors.TertiaryText, fontSize = 10.sp)
                        }
                    }
                    if (earEntries.isEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().height(StatusEarCellHeight),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("≡", color = NovexColors.SecondaryText, fontSize = 13.sp)
                        }
                    }
                }
                if (handle.update != null) {
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 4.dp, top = 4.dp)
                            .size(6.dp)
                            .background(NovexColors.Primary, RoundedCornerShape(50)),
                    )
                }
            }
        }

        // ── Scrim：面板展开时点击外部关闭。 ──
        if ((expandedPanel || showSidePanel) && !collapsed) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(NovexColors.Text.copy(alpha = 0.18f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        expandedPanel = false
                        showSidePanel = false
                    },
            )
        }

        // ── Playthrough panel: right-anchored, fixed width, content scrolls. ──
        if (handle != null && expandedPanel && !collapsed) {
            val earHpx = with(density) { StatusEarCellHeight.toPx() * 2f }
            val maxEarY = (screenH - bottomReserve - earHpx).coerceAtLeast(topInsetPx)
            val anchorY = topInsetPx + handleFraction * (maxEarY - topInsetPx)
            val maxPanelHeight = with(density) {
                ((screenH - anchorY - 120.dp.toPx()).coerceAtLeast(200.dp.toPx())).toDp()
            }
            NovexPlaythroughPanel(
                state = handle.state,
                update = handle.update,
                onDismissUpdate = handle.onDismissUpdate,
                onCollapse = { expandedPanel = false },
                maxHeight = maxPanelHeight,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = with(density) { anchorY.toDp() }),
            )
        }

        // ── Side-conversation management panel: left-anchored card. ──
        if (showSidePanel && !collapsed) {
            val orderedSides = order.mapNotNull { id -> sides.firstOrNull { it.id == id } }
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 0.dp, top = 24.dp, bottom = 24.dp)
                    .shadow(6.dp, statusEarShape(14.dp))
                    .clip(statusEarShape(14.dp))
                    .background(NovexColors.Surface)
                    .width(280.dp)
                    .heightIn(max = maxHeight - 48.dp),
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 6.dp),
                    ) {
                        Text(
                            "侧边对话",
                            Modifier.weight(1f),
                            style = NovexType.ItemTitle,
                            fontWeight = FontWeight.SemiBold,
                            color = NovexColors.Text,
                        )
                        novex.android.ui.TextButton(onClick = { showSidePanel = false }) {
                            Text("收起")
                        }
                    }
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    ) {
                        // 主线入口行：回到主对话。
                        SidePanelRow(
                            title = "主对话",
                            preview = null,
                            timeMs = null,
                            current = !isSidePage,
                            onClick = {
                                showSidePanel = false
                                if (isSidePage) onOpenSide(railSessionId)
                            },
                        )
                        orderedSides.forEach { side ->
                            SidePanelRow(
                                title = side.title?.takeIf { it.isNotBlank() }
                                    ?: "侧边 ${orderedSides.indexOf(side) + 1}",
                                preview = side.lastMessage,
                                timeMs = side.updatedAt,
                                current = side.id == currentSessionId,
                                onClick = {
                                    showSidePanel = false
                                    if (side.id != currentSessionId) onOpenSide(side.id)
                                },
                            )
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                showSidePanel = false
                                createSide()
                            }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "＋ 新建侧边",
                            style = NovexType.Body,
                            color = NovexColors.Primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

/** 侧边管理面板一行：编号名 + 最近消息预览 + 时间；当前行左缘薄荷条。 */
@Composable
private fun SidePanelRow(
    title: String,
    preview: String?,
    timeMs: Long?,
    current: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(if (current) Modifier.background(NovexColors.PrimarySoft.copy(alpha = 0.5f)) else Modifier)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
    ) {
        if (current) {
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .width(3.dp)
                    .height(14.dp)
                    .background(NovexColors.Primary, RoundedCornerShape(2.dp)),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = NovexType.Body,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = NovexColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                preview?.replace('\n', ' ')?.takeIf { it.isNotBlank() },
                timeMs?.let { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it)) },
            ).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = NovexType.Metadata,
                    color = NovexColors.TertiaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** 状态凸耳一格：键名首字 + 数值；本轮变更的格子闪薄荷底。 */
@Composable
private fun StatusEarCell(key: String, text: String, highlighted: Boolean, modifier: Modifier = Modifier) {
    val flash = animateFloatAsState(
        targetValue = if (highlighted) 1f else 0f,
        animationSpec = tween(500),
        label = "earFlash",
    )
    Box(
        modifier
            .fillMaxWidth()
            .background(NovexColors.Primary.copy(alpha = 0.16f * flash.value)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(key.take(1), color = NovexColors.TertiaryText, fontSize = 8.sp, maxLines = 1)
            Text(
                text,
                color = NovexColors.Text,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
