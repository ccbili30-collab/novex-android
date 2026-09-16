package com.openminis.app.ui.chat

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.novex.domain.PlaythroughState
import com.openminis.app.ui.novex.NovexColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Fixed bookmark palette — muted, distinguishable on both themes. */
private val BookmarkPalette = listOf(
    androidx.compose.ui.graphics.Color(0xFFE8B4B8), androidx.compose.ui.graphics.Color(0xFFB4CDE8),
    androidx.compose.ui.graphics.Color(0xFFB8E0C8), androidx.compose.ui.graphics.Color(0xFFE8D3B4),
    androidx.compose.ui.graphics.Color(0xFFCBB4E8), androidx.compose.ui.graphics.Color(0xFFB4D8E8),
    androidx.compose.ui.graphics.Color(0xFFE8C8B4), androidx.compose.ui.graphics.Color(0xFFC8E8B4),
    androidx.compose.ui.graphics.Color(0xFFE8B4D8), androidx.compose.ui.graphics.Color(0xFFD8D8B4),
)

/** Rail id of the playthrough handle (UUIDs can never contain NUL). */
private const val HUD_ID = "\u0000hud"

/**
 * [T-side-naming] 书签条短标签：显示侧边编号——新命名「侧边 N」与旧命名
 * 「主对话标题·侧N」都提取数字；其余标题回落首字。条身半出屏后可见区
 * 窄，只放数字（完整名字在侧边页标题与列表里看）。
 */
internal fun bookmarkTabLabel(title: String?): String {
    val t = title.orEmpty()
    Regex("·侧(\\d+)\\s*$").find(t)?.let { return it.groupValues[1] }
    Regex("侧边\\s*(\\d+)").find(t)?.let { return it.groupValues[1] }
    return t.trim().take(1).ifEmpty { "侧" }
}

/** State-handle participation: null = no playthrough state → no handle. */
internal data class NovexEdgeHandleSpec(
    val state: PlaythroughState,
    val update: NovexDataUpdateEvent?,
    val onDismissUpdate: () -> Unit,
)

/**
 * 侧边组件轨（2026-09-15 第三轮重排；同日按用户反馈改横条+淡化）：
 * - 书签 = 左缘**横向**长条（外端尖角），**跨主线↔侧边页常驻**（railSessionId
 *   恒为主线）；当前所在侧边的书签变长变厚；新书签排最下；拖动仅排序。
 * - 状态把手 = 右缘淡半圆（仅主线页），按下即拖、只沿边上下，位置按会话记住；
 *   点按展开面板（L 拐角缩放、尺寸记住）。
 * - 淡化（仅主线页，2026-09-15 用户决策"不做收起做淡化"）：整列/把手原地
 *   透明度归零，布局零跳动；淡化后不拦截点击（点到的就是聊天内容），任何
 *   触摸由 ChatScreen 统一唤回。侧边页常驻不淡化。
 */
@Composable
internal fun NovexSideConversations(
    railSessionId: String,
    currentSessionId: String,
    isSidePage: Boolean,
    chatRepository: ChatRepository,
    requestNewSide: Boolean,
    onNewSideConsumed: () -> Unit,
    onOpenSide: (String) -> Unit,
    handle: NovexEdgeHandleSpec?,
    chromeCollapsed: Boolean,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var sides by remember(railSessionId) { mutableStateOf(listOf<ChatSessionEntity>()) }
    var sidesLoaded by remember(railSessionId) { mutableStateOf(false) }
    var order by remember(railSessionId) { mutableStateOf<List<String>>(emptyList()) }
    val savedHandleFraction = remember(railSessionId) {
        NovexEdgePrefs.readFraction(context, EDGE_PREFS_HUD, railSessionId)
    }
    var handleFraction by remember(railSessionId) { mutableFloatStateOf(savedHandleFraction) }
    var expandedPanel by rememberSaveable(railSessionId) { mutableStateOf(false) }
    val savedPanelSize = remember(railSessionId) {
        NovexEdgePrefs.readSize(context, EDGE_PREFS_HUD, "panel:$railSessionId")
    }
    var panelWidth by remember(railSessionId) { mutableStateOf(savedPanelSize?.first?.toFloat()?.dp ?: PanelDefaultWidth) }
    var panelHeight by remember(railSessionId) { mutableStateOf(savedPanelSize?.second?.toFloat()?.dp) }
    // Drag state — one component at a time, never hides the others.
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(railSessionId) {
        sides = runCatching { chatRepository.listSideSessions(railSessionId) }.getOrDefault(emptyList())
        val stored = NovexEdgePrefs.readOrder(context, EDGE_PREFS_SIDES, "order:$railSessionId").orEmpty()
        // Stored order first, then any sides never ordered (new ones appended at
        // the bottom = 往下排), deleted ids dropped silently.
        order = stored.filter { id -> sides.any { it.id == id } } + sides.map { it.id }.filter { it !in stored }
        sidesLoaded = true
    }
    LaunchedEffect(requestNewSide) {
        if (!requestNewSide) return@LaunchedEffect
        onNewSideConsumed()
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

    val collapsed = chromeCollapsed && !isSidePage
    if (!sidesLoaded && handle == null) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val screenW = with(density) { maxWidth.toPx() }
        val screenH = with(density) { maxHeight.toPx() }
        val bottomReserve = with(density) { 170.dp.toPx() }
        // [T-bookmark-under-topbar] 顶栏悬浮盖在内容上（chrome-fade 改造），
        // 书签列起点必须让出「顶栏高度 + 余量」，否则整列被顶栏埋住
        // （2026-09-16 用户反馈）。本容器 y=0 已在状态栏之下。
        val topInsetPx = with(density) {
            (chatTopBarExpandedHeightDp(density.fontScale).dp + 8.dp).toPx()
        }

        // ── Bookmark rail (left edge), top-down stacking with cumulative slots ──
        fun stripSize(id: String): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> =
            if (id == currentSessionId) BookmarkStripCurrentLength to BookmarkStripCurrentThickness
            else BookmarkStripLength to BookmarkStripThickness

        /** 横条：second = 厚度（垂直堆叠方向上的步进）。 */
        fun stripThicknessPx(id: String): Float = with(density) { stripSize(id).second.toPx() }
        val gapPx = with(density) { BookmarkStripGap.toPx() }

        /** Slot Y of each bookmark in the CURRENT order (dragged one excluded). */
        fun slotYs(exclude: String?): List<Pair<String, Float>> = buildList {
            var y = topInsetPx
            order.forEach { id ->
                if (id == exclude) return@forEach
                add(id to y)
                y += stripThicknessPx(id) + gapPx
            }
        }

        // [T-chrome-fade] 原地淡化：透明度动画到 0，位置尺寸一律不动；淡出到
        // 0 后不再参与组合（overlay 定位，无布局副作用），也不拦截任何点击。
        val railAlpha by animateFloatAsState(
            targetValue = if (collapsed) 0f else 1f,
            animationSpec = tween(220),
            label = "edgeRailAlpha",
        )

        if (railAlpha > 0.01f) sides.forEach { side ->
            if (side.id !in order) return@forEach
            val isCurrent = side.id == currentSessionId
            val (stripLength, stripThickness) = stripSize(side.id)
            val dragging = draggingId == side.id
            // While dragging, the OTHERS keep their slots (stable, no shifting);
            // the dragged strip reads its own slot only to seed dragY.
            val baseY = slotYs(exclude = null).firstOrNull { it.first == side.id }?.second ?: topInsetPx
            NovexEdgeGestureSurface(
                shape = bookmarkTabShape(),
                width = stripLength,
                height = stripThickness,
                fill = colorFor(context, side.id),
                rim = novexEdgeRim(),
                elevation = if (railAlpha >= 0.99f) 3.dp else 0.dp,
                modifier = Modifier
                    .graphicsLayer { alpha = railAlpha }
                    .offset {
                        // [T-bookmark-half-out] 半出屏：左半段（含圆头）永久
                        // 留在屏幕外，屏内只露右半段与尖角，像书签从屏幕外探出；
                        // 拖动排序时同样不整条滑出（2026-09-16 用户决策）。
                        IntOffset(
                            -(stripLength.toPx() / 2f).roundToInt(),
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
                        dragY = (dragY + delta.y).coerceIn(topInsetPx, (screenH - bottomReserve - stripThicknessPx(side.id)).coerceAtLeast(topInsetPx))
                    }

                    override fun onDragEnd() {
                        // Reorder: insertion index by drop position among the others.
                        val others = slotYs(exclude = side.id)
                        val draggedCenter = dragY + stripThicknessPx(side.id) / 2f
                        val index = others.count { (id, y) -> y + stripThicknessPx(id) / 2f <= draggedCenter }
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
                    color = androidx.compose.ui.graphics.Color(0xCC1C1C1E),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    // 右端避开尖角斜面（tip = 16% 条长），文字落在屏内可见半段。
                    modifier = Modifier.padding(end = stripLength * 0.16f + 4.dp),
                )
            }
        }

        // ── State handle (right edge, main pages only): pale semicircle. ──
        if (handle != null && !expandedPanel && railAlpha > 0.01f) {
            val handleWpx = with(density) { StateHandleWidth.toPx() }
            val handleHpx = with(density) { StateHandleHeight.toPx() }
            val maxHandleY = (screenH - bottomReserve - handleHpx).coerceAtLeast(topInsetPx)
            val handleBaseY = topInsetPx + handleFraction * (maxHandleY - topInsetPx)
            NovexEdgeGestureSurface(
                shape = semicircleShape(),
                floatingShape = RoundedCornerShape(50),
                width = StateHandleWidth,
                height = StateHandleHeight,
                fill = novexEdgeFill(),
                rim = novexEdgeRim(),
                floating = draggingId == HUD_ID,
                elevation = if (railAlpha >= 0.99f) 3.dp else 0.dp,
                // 平面贴右缘：x = 屏宽 − 把手宽（layout 期读取）。
                modifier = Modifier
                    .graphicsLayer { alpha = railAlpha }
                    .offset {
                        IntOffset(
                            (screenW - handleWpx).roundToInt(),
                            (if (draggingId == HUD_ID) dragY else handleBaseY).roundToInt(),
                        )
                    },
                onTap = if (collapsed) null else ({ expandedPanel = true }),
                drag = if (collapsed) null else object : NovexEdgeDragCallbacks {
                    override fun onDragStart() {
                        draggingId = HUD_ID
                        dragY = handleBaseY
                    }

                    override fun onDrag(delta: Offset, change: PointerInputChange) {
                        dragY = (dragY + delta.y).coerceIn(topInsetPx, maxHandleY)
                    }

                    override fun onDragEnd() {
                        handleFraction = ((dragY - topInsetPx) / (maxHandleY - topInsetPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        draggingId = null
                        NovexEdgePrefs.writeFraction(context, EDGE_PREFS_HUD, railSessionId, handleFraction)
                    }

                    override fun onDragCancel() { draggingId = null }
                },
            ) {
                Icon(
                    com.openminis.app.ui.novex.NovexIcons.KeyboardArrowLeft,
                    contentDescription = "本局状态",
                    tint = NovexColors.SecondaryText,
                )
                if (handle.update != null) {
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 2.dp, top = 6.dp)
                            .size(6.dp)
                            .background(NovexColors.Primary, RoundedCornerShape(50)),
                    )
                }
            }
        }

        // ── Playthrough panel: right-anchored, resizable, size remembered. ──
        if (handle != null && expandedPanel && !collapsed) {
            val handleHpx = with(density) { StateHandleHeight.toPx() }
            val maxHandleY = (screenH - bottomReserve - handleHpx).coerceAtLeast(topInsetPx)
            val anchorY = topInsetPx + handleFraction * (maxHandleY - topInsetPx)
            val maxPanelHeight = with(density) {
                ((screenH - anchorY - 120.dp.toPx()).coerceAtLeast(200.dp.toPx())).toDp()
            }
            val maxPanelWidth = maxWidth - 16.dp
            NovexPlaythroughPanel(
                state = handle.state,
                update = handle.update,
                onDismissUpdate = handle.onDismissUpdate,
                onCollapse = { expandedPanel = false },
                width = panelWidth.coerceIn(PanelMinWidth, maxPanelWidth),
                height = panelHeight?.coerceIn(PanelMinHeight, maxPanelHeight),
                maxHeight = maxPanelHeight,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = with(density) { anchorY.toDp() }),
                onResize = { dw, dh ->
                    panelWidth = (panelWidth + dw.dp).coerceIn(PanelMinWidth, maxPanelWidth)
                    panelHeight = ((panelHeight ?: PanelMinHeight) + dh.dp).coerceIn(PanelMinHeight, maxPanelHeight)
                },
                onResizeEnd = {
                    NovexEdgePrefs.writeSize(
                        context, EDGE_PREFS_HUD, "panel:$railSessionId",
                        panelWidth.value.roundToInt(), panelHeight?.value?.roundToInt() ?: PanelMinHeight.value.roundToInt(),
                    )
                },
            )
        }
    }
}

private fun colorFor(context: Context, sideId: String): androidx.compose.ui.graphics.Color {
    val prefs = context.getSharedPreferences(EDGE_PREFS_SIDES, Context.MODE_PRIVATE)
    var index = prefs.getInt("color:$sideId", -1)
    if (index < 0) {
        index = (sideId.hashCode().let { if (it < 0) -it else it }) % BookmarkPalette.size
        prefs.edit().putInt("color:$sideId", index).apply()
    }
    return BookmarkPalette[index]
}
