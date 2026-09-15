package com.openminis.app.ui.chat

import android.content.Context
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.delay
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

/** Initial anchor when a component has no stored placement. */
private const val DEFAULT_ANCHOR_FRACTION = 0.35f

/** Rail id of the playthrough handle (UUIDs can never contain NUL). */
private const val HUD_ID = "\u0000hud"

/** State-handle participation: null = no playthrough state → no handle ribbon. */
internal data class NovexEdgeHandleSpec(
    val state: PlaythroughState,
    val update: NovexDataUpdateEvent?,
    val onDismissUpdate: () -> Unit,
)

/**
 * 侧边组件轨（2026-09-15 用户修订）：状态把手与每枚书签是同一种侧边组件——
 * 同一套拖动/停靠/磁吸逻辑、外形各异。每个组件**独立**持有停靠边与沿边位置：
 * 拖动一枚只动它自己（其余照常显示）；松手吸附最近边后，与同边已有组件的距
 * 离小于磁吸阈值才贴上去成列（[NovexEdgeRail]），否则各停各的位置。新落位的
 * 书签自动排进同边第一个空槽，机制上保证与把手及其它书签不重叠。上边停靠落
 * 在内容区顶端（宿主的 y=0 已在顶栏下方，不再叠加保留高度）。
 */
@Composable
internal fun NovexSideConversations(
    mainSessionId: String,
    chatRepository: ChatRepository,
    requestNewSide: Boolean,
    onNewSideConsumed: () -> Unit,
    onOpenSide: (String) -> Unit,
    handle: NovexEdgeHandleSpec?,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var sides by remember(mainSessionId) { mutableStateOf(listOf<ChatSessionEntity>()) }
    var sidesLoaded by remember(mainSessionId) { mutableStateOf(false) }
    // Per-component placements. The handle keeps its historical prefs key, so
    // positions from beta.48–50 survive; bookmarks are keyed per side id.
    val savedHandle = remember(mainSessionId) { NovexEdgePlacement.read(context, EDGE_PREFS_HUD, mainSessionId) }
    var handleDock by remember(mainSessionId) { mutableStateOf(savedHandle.first) }
    var handleFraction by remember(mainSessionId) { mutableFloatStateOf(savedHandle.second) }
    var placements by remember(mainSessionId) { mutableStateOf<Map<String, Pair<NovexEdgeDock, Float>>>(emptyMap()) }
    var expandedPanel by rememberSaveable(mainSessionId) { mutableStateOf(false) }
    // Drag state: only the dragged ribbon follows the finger (independent drag).
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var stretchId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mainSessionId) {
        sides = runCatching { chatRepository.listSideSessions(mainSessionId) }.getOrDefault(emptyList())
        placements = sides.mapNotNull { side ->
            NovexEdgePlacement.readOrNull(context, EDGE_PREFS_SIDES, "anchor:${side.id}")?.let { side.id to it }
        }.toMap()
        sidesLoaded = true
    }
    LaunchedEffect(stretchId) {
        if (stretchId != null) {
            delay(200)
            stretchId = null
        }
    }
    LaunchedEffect(requestNewSide) {
        if (!requestNewSide) return@LaunchedEffect
        onNewSideConsumed()
        scope.launch {
            runCatching { chatRepository.createSideSession(mainSessionId) }
                .onSuccess { side -> onOpenSide(side.id) }
                .onFailure { android.widget.Toast.makeText(context, it.message ?: "创建侧边对话失败", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    if (!sidesLoaded && handle == null) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val screenW = with(density) { maxWidth.toPx() }
        val screenH = with(density) { maxHeight.toPx() }
        val ribbonW = with(density) { NovexEdgeRibbonWidth.toPx() }
        val ribbonH = with(density) { NovexEdgeRibbonHeight.toPx() }
        val gap = with(density) { NovexEdgeRibbonGap.toPx() }
        val magnetPx = with(density) { NovexEdgeMagnetDistance.toPx() }
        val geometry = NovexEdgeDockGeometry(
            screenW = screenW, screenH = screenH,
            ribbonW = ribbonW, ribbonH = ribbonH,
            bottomReserve = with(density) { 170.dp.toPx() },
        )

        /** Column resolution shared by every ribbon on this screen (layout-time reads). */
        fun resolvedOffsets(): Map<String, Offset> {
            val entries = buildList {
                if (handle != null) add(NovexEdgeRailEntry(HUD_ID, handleDock, handleFraction))
                placements.forEach { (id, p) -> add(NovexEdgeRailEntry(id, p.first, p.second)) }
            }
            val out = mutableMapOf<String, Offset>()
            entries.groupBy { it.dock }.forEach { (dock, list) ->
                val pitch = if (dock == NovexEdgeDock.TOP) ribbonW + gap else ribbonH + gap
                NovexEdgeRail.resolveAlong(geometry.maxAlong(dock), pitch, magnetPx, list).forEach { (id, along) ->
                    out[id] = geometry.anchorAt(dock, along)
                }
            }
            return out
        }

        /** Independent drag for one component; snap on release, persist its own placement. */
        fun dragCallbacksFor(id: String, persist: (NovexEdgeDock, Float) -> Unit): NovexEdgeDragCallbacks =
            object : NovexEdgeDragCallbacks {
                override fun onDragStart() {
                    draggingId = id
                    val p = resolvedOffsets()[id] ?: Offset.Zero
                    dragX = p.x
                    dragY = p.y
                }

                override fun onDrag(delta: Offset, change: PointerInputChange) {
                    dragX = (dragX + delta.x).coerceIn(0f, screenW - ribbonW)
                    dragY = (dragY + delta.y).coerceIn(0f, screenH - ribbonH)
                }

                override fun onDragEnd() {
                    val snapped = geometry.snap(Offset(dragX + ribbonW / 2f, dragY + ribbonH / 2f))
                    draggingId = null
                    persist(snapped.first, snapped.second)
                }

                override fun onDragCancel() { draggingId = null }
            }

        // Auto-slot: a bookmark without a stored placement takes the first free
        // slot on the default edge (handle + other bookmarks count as occupied).
        LaunchedEffect(sidesLoaded, sides) {
            if (!sidesLoaded) return@LaunchedEffect
            val live = sides.map { it.id }.toSet()
            var next = placements.filterKeys { it in live }
            var changed = next.size != placements.size
            sides.forEach { side ->
                if (side.id !in next) {
                    val occupied = mutableListOf<Float>()
                    if (handle != null && handleDock == NovexEdgeDock.RIGHT) occupied += handleFraction
                    next.forEach { (_, p) -> if (p.first == NovexEdgeDock.RIGHT) occupied += p.second }
                    val free = NovexEdgeRail.firstFreeFraction(
                        geometry.maxAlong(NovexEdgeDock.RIGHT), ribbonH + gap, occupied, DEFAULT_ANCHOR_FRACTION,
                    )
                    next = next + (side.id to (NovexEdgeDock.RIGHT to free))
                    NovexEdgePlacement.write(context, EDGE_PREFS_SIDES, "anchor:${side.id}", NovexEdgeDock.RIGHT, free)
                    changed = true
                }
            }
            if (changed) placements = next
        }

        // ── State handle ribbon: same rail, its own look (chevron + badge). ──
        if (handle != null && !expandedPanel) {
            NovexEdgeRibbon(
                dock = handleDock,
                fill = NovexColors.Surface.copy(alpha = 0.95f),
                rim = NovexColors.Divider,
                width = NovexEdgeRibbonWidth,
                height = NovexEdgeRibbonHeight,
                modifier = Modifier.offset {
                    val p = if (draggingId == HUD_ID) Offset(dragX, dragY) else resolvedOffsets()[HUD_ID] ?: Offset.Zero
                    IntOffset(p.x.roundToInt(), p.y.roundToInt())
                },
                floating = draggingId == HUD_ID,
                badge = handle.update != null,
                onTap = { expandedPanel = true },
                drag = dragCallbacksFor(HUD_ID) { d, f ->
                    handleDock = d
                    handleFraction = f
                    NovexEdgePlacement.write(context, EDGE_PREFS_HUD, mainSessionId, d, f)
                },
            ) {
                // Chevron points toward the screen interior.
                val chevron = when (handleDock) {
                    NovexEdgeDock.RIGHT -> com.openminis.app.ui.novex.NovexIcons.KeyboardArrowLeft
                    NovexEdgeDock.LEFT -> com.openminis.app.ui.novex.NovexIcons.KeyboardArrowRight
                    NovexEdgeDock.TOP -> com.openminis.app.ui.novex.NovexIcons.KeyboardArrowDown
                }
                Icon(chevron, contentDescription = "本局状态", tint = NovexColors.SecondaryText)
            }
        }

        // ── Bookmark ribbons: independent placements, first-char + palette. ──
        sides.forEach { side ->
            val placement = placements[side.id] ?: return@forEach
            NovexEdgeRibbon(
                dock = placement.first,
                fill = colorFor(context, side.id),
                rim = NovexColors.Divider,
                width = NovexEdgeRibbonWidth,
                height = NovexEdgeRibbonHeight,
                modifier = Modifier.offset {
                    val p = if (draggingId == side.id) Offset(dragX, dragY) else resolvedOffsets()[side.id] ?: Offset.Zero
                    IntOffset(p.x.roundToInt(), p.y.roundToInt())
                },
                stretch = if (stretchId == side.id) 1.3f else 1f,
                floating = draggingId == side.id,
                onTap = {
                    stretchId = side.id
                    scope.launch {
                        delay(140)
                        onOpenSide(side.id)
                    }
                },
                drag = dragCallbacksFor(side.id) { d, f ->
                    placements = placements + (side.id to (d to f))
                    NovexEdgePlacement.write(context, EDGE_PREFS_SIDES, "anchor:${side.id}", d, f)
                },
            ) {
                Text(
                    side.title.orEmpty().trim().take(1).ifEmpty { "侧" },
                    color = androidx.compose.ui.graphics.Color(0xCC1C1C1E),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // ── Playthrough panel: anchored at the handle's resolved position. ──
        if (expandedPanel && handle != null) {
            val anchorPos = resolvedOffsets()[HUD_ID] ?: Offset.Zero
            val panelWpx = with(density) { PanelWidth.toPx() }
            val panelX = when (handleDock) {
                NovexEdgeDock.LEFT -> 0f
                NovexEdgeDock.RIGHT -> (screenW - panelWpx).coerceAtLeast(0f)
                NovexEdgeDock.TOP -> anchorPos.x.coerceIn(0f, (screenW - panelWpx).coerceAtLeast(0f))
            }
            val panelY = when (handleDock) {
                NovexEdgeDock.TOP -> 0f
                else -> anchorPos.y.coerceIn(0f, (screenH - with(density) { 170.dp.toPx() }).coerceAtLeast(0f))
            }
            val remainingPx = (screenH - panelY - with(density) { 120.dp.toPx() })
                .coerceAtLeast(with(density) { 96.dp.toPx() })
            NovexPlaythroughPanel(
                state = handle.state,
                update = handle.update,
                onDismissUpdate = handle.onDismissUpdate,
                onCollapse = { expandedPanel = false },
                modifier = Modifier.offset { IntOffset(panelX.roundToInt(), panelY.roundToInt()) },
                maxHeight = (remainingPx / density.density).dp,
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
