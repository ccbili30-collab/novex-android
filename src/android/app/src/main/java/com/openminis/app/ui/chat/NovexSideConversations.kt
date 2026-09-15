package com.openminis.app.ui.chat

import android.content.Context
import androidx.compose.animation.core.animateDpAsState
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

/** 书签列/把手从容器顶部的起步距离（容器已在顶栏下方，只留呼吸间距）。 */
private val RailTopInset = 16.dp

/** State-handle participation: null = no playthrough state → no handle. */
internal data class NovexEdgeHandleSpec(
    val state: PlaythroughState,
    val update: NovexDataUpdateEvent?,
    val onDismissUpdate: () -> Unit,
)

/**
 * 侧边组件轨（2026-09-15 第三轮重排）：
 * - 书签 = 左缘细长条，**跨主线↔侧边页常驻**（railSessionId 恒为主线）；当前
 *   所在侧边的书签变粗变长，主线页全部常规收缩态；新书签排最下；拖动仅排序。
 * - 状态把手 = 右缘淡半圆（仅主线页），按下即拖、只沿边上下，位置按会话记住；
 *   点按展开面板（L 拐角缩放、尺寸记住）。
 * - 收起（仅主线页）：整列/把手滑向所在边缘留 6dp 微边，点微边唤出；微边上
 *   保留状态徽点。侧边页轨常驻不收起。
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
    onExpandChrome: () -> Unit,
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
                .onSuccess { side -> onOpenSide(side.id) }
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
        val topInsetPx = with(density) { RailTopInset.toPx() }

        // ── Bookmark rail (left edge), top-down stacking with cumulative slots ──
        fun stripSize(id: String): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> =
            if (id == currentSessionId) BookmarkStripCurrentWidth to BookmarkStripCurrentHeight
            else BookmarkStripWidth to BookmarkStripHeight

        fun stripHeightPx(id: String): Float = with(density) { stripSize(id).second.toPx() }
        val gapPx = with(density) { BookmarkStripGap.toPx() }

        /** Slot Y of each bookmark in the CURRENT order (dragged one excluded). */
        fun slotYs(exclude: String?): List<Pair<String, Float>> = buildList {
            var y = topInsetPx
            order.forEach { id ->
                if (id == exclude) return@forEach
                add(id to y)
                y += stripHeightPx(id) + gapPx
            }
        }

        val railSlide by animateDpAsState(
            targetValue = if (collapsed) -(BookmarkStripWidth - EdgeCollapsedSliver) else 0.dp,
            label = "bookmarkRailSlide",
        )

        sides.forEach { side ->
            if (side.id !in order) return@forEach
            val isCurrent = side.id == currentSessionId
            val (stripWidth, stripHeight) = stripSize(side.id)
            val dragging = draggingId == side.id
            // While dragging, the OTHERS keep their slots (stable, no shifting);
            // the dragged strip reads its own slot only to seed dragY.
            val baseY = slotYs(exclude = null).firstOrNull { it.first == side.id }?.second ?: topInsetPx
            NovexEdgeGestureSurface(
                shape = bookmarkStripShape(),
                width = stripWidth,
                height = stripHeight,
                fill = colorFor(context, side.id),
                rim = NovexEdgeColors.rim,
                modifier = Modifier.offset {
                    IntOffset(
                        railSlide.roundToPx(),
                        (if (dragging) dragY else baseY).roundToInt(),
                    )
                },
                contentAlignment = Alignment.TopCenter,
                onTap = {
                    if (collapsed) {
                        onExpandChrome()
                    } else if (!isCurrent) {
                        onOpenSide(side.id)
                    }
                },
                drag = if (collapsed) null else object : NovexEdgeDragCallbacks {
                    override fun onDragStart() {
                        draggingId = side.id
                        dragY = baseY
                    }

                    override fun onDrag(delta: Offset, change: PointerInputChange) {
                        dragY = (dragY + delta.y).coerceIn(topInsetPx, (screenH - bottomReserve - stripHeightPx(side.id)).coerceAtLeast(topInsetPx))
                    }

                    override fun onDragEnd() {
                        // Reorder: insertion index by drop position among the others.
                        val others = slotYs(exclude = side.id)
                        val draggedCenter = dragY + stripHeightPx(side.id) / 2f
                        val index = others.count { (id, y) -> y + stripHeightPx(id) / 2f <= draggedCenter }
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
                    side.title.orEmpty().trim().take(1).ifEmpty { "侧" },
                    color = androidx.compose.ui.graphics.Color(0xCC1C1C1E),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        // ── State handle (right edge, main pages only): pale semicircle. ──
        if (handle != null && !expandedPanel) {
            val handleWpx = with(density) { StateHandleWidth.toPx() }
            val handleHpx = with(density) { StateHandleHeight.toPx() }
            val maxHandleY = (screenH - bottomReserve - handleHpx).coerceAtLeast(topInsetPx)
            val handleBaseY = topInsetPx + handleFraction * (maxHandleY - topInsetPx)
            val handleSlide by animateDpAsState(
                targetValue = if (collapsed) StateHandleWidth - EdgeCollapsedSliver else 0.dp,
                label = "handleSlide",
            )
            NovexEdgeGestureSurface(
                shape = semicircleShape(),
                floatingShape = RoundedCornerShape(50),
                width = StateHandleWidth,
                height = StateHandleHeight,
                fill = NovexEdgeColors.fill,
                rim = NovexEdgeColors.rim,
                floating = draggingId == HUD_ID,
                // 平面贴右缘：x = 屏宽 − 把手宽 + 收起滑出量（layout 期读取）。
                modifier = Modifier.offset {
                    IntOffset(
                        (screenW - handleWpx + handleSlide.roundToPx()).roundToInt(),
                        (if (draggingId == HUD_ID) dragY else handleBaseY).roundToInt(),
                    )
                },
                onTap = {
                    if (collapsed) onExpandChrome() else expandedPanel = true
                },
                drag = object : NovexEdgeDragCallbacks {
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
                if (collapsed) {
                    // Sliver keeps the update dot visible (收起不漏变更).
                    if (handle.update != null) {
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 1.dp)
                                .size(5.dp)
                                .background(NovexColors.Primary, RoundedCornerShape(50)),
                        )
                    }
                } else {
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
