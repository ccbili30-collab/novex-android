package com.openminis.app.ui.chat

import android.content.Context
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
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

private const val SIDE_PREFS = "novex_side_conversations"
private val RibbonWidth = 34.dp
private val RibbonHeight = 46.dp
private val RibbonGap = 8.dp

/**
 * 侧边对话书签列（2026-09-14 决策 12/15）：每条侧边对话一枚缎带书签（首字 +
 * 随机色），磁吸成列贴边停靠。点按 = 拉长提示后全屏进入该侧边对话；长按拖动
 * 整列（拖动的书签跟随手指成胶囊，其余隐藏，松手吸附最近边重组成列，锚点
 * 持久化）。新建走主对话 ⋮ 菜单，创建后直接进入。删除只在侧边页右上角。
 */
@Composable
internal fun NovexSideConversations(
    mainSessionId: String,
    chatRepository: ChatRepository,
    requestNewSide: Boolean,
    onNewSideConsumed: () -> Unit,
    onOpenSide: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var sides by remember(mainSessionId) { mutableStateOf(listOf<ChatSessionEntity>()) }
    val savedAnchor = remember(mainSessionId) {
        NovexEdgePlacement.read(context, SIDE_PREFS, "anchor:$mainSessionId")
    }
    var anchorDock by remember(mainSessionId) { mutableStateOf(savedAnchor.first) }
    var anchorFraction by remember(mainSessionId) { mutableFloatStateOf(savedAnchor.second) }
    // Drag state: index of the ribbon being dragged; the rest of the column
    // hides until it re-forms at the released anchor (磁吸成列).
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var stretchId by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch { sides = runCatching { chatRepository.listSideSessions(mainSessionId) }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(mainSessionId) { refresh() }
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

    if (sides.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val screenW = with(density) { maxWidth.toPx() }
        val screenH = with(density) { maxHeight.toPx() }
        val ribbonW = with(density) { RibbonWidth.toPx() }
        val ribbonH = with(density) { RibbonHeight.toPx() }
        val gap = with(density) { RibbonGap.toPx() }
        // Same reserve contract as the state handle: never park in the composer
        // zone, keep clear of the top bar.
        val geometry = NovexEdgeDockGeometry(
            screenW = screenW, screenH = screenH,
            ribbonW = ribbonW, ribbonH = ribbonH,
            bottomReserve = with(density) { 170.dp.toPx() },
            topReserve = with(density) { 90.dp.toPx() },
        )

        fun slotOffset(index: Int): Offset {
            val base = geometry.anchor(anchorDock, anchorFraction)
            return when (anchorDock) {
                NovexEdgeDock.LEFT, NovexEdgeDock.RIGHT -> Offset(base.x, base.y + index * (ribbonH + gap))
                NovexEdgeDock.TOP -> Offset(base.x + index * (ribbonW + gap), base.y)
            }
        }

        sides.forEachIndexed { index, side ->
            if (draggingIndex >= 0 && index != draggingIndex) return@forEachIndexed
            val dragging = index == draggingIndex
            val slot = if (dragging) Offset(dragX, dragY) else slotOffset(index)
            NovexEdgeRibbon(
                dock = anchorDock,
                fill = colorFor(context, side.id),
                rim = NovexColors.Divider,
                width = RibbonWidth,
                height = RibbonHeight,
                modifier = Modifier.offset { IntOffset(slot.x.roundToInt(), slot.y.roundToInt()) },
                stretch = if (stretchId == side.id) 1.3f else 1f,
                floating = dragging,
                onTap = {
                    stretchId = side.id
                    scope.launch {
                        delay(140)
                        onOpenSide(side.id)
                    }
                },
                drag = object : NovexEdgeDragCallbacks {
                    override fun onDragStart() {
                        draggingIndex = index
                        dragX = slotOffset(index).x
                        dragY = slotOffset(index).y
                    }

                    override fun onDrag(delta: Offset, change: androidx.compose.ui.input.pointer.PointerInputChange) {
                        dragX = (dragX + delta.x).coerceIn(0f, screenW - ribbonW)
                        dragY = (dragY + delta.y).coerceIn(0f, screenH - ribbonH)
                    }

                    override fun onDragEnd() {
                        val snapped = geometry.snap(Offset(dragX + ribbonW / 2f, dragY + ribbonH / 2f))
                        anchorDock = snapped.first
                        anchorFraction = snapped.second
                        draggingIndex = -1
                        NovexEdgePlacement.write(context, SIDE_PREFS, "anchor:$mainSessionId", anchorDock, anchorFraction)
                    }

                    override fun onDragCancel() { draggingIndex = -1 }
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
    }
}

private fun colorFor(context: Context, sideId: String): androidx.compose.ui.graphics.Color {
    val prefs = context.getSharedPreferences(SIDE_PREFS, Context.MODE_PRIVATE)
    var index = prefs.getInt("color:$sideId", -1)
    if (index < 0) {
        index = (sideId.hashCode().let { if (it < 0) -it else it }) % BookmarkPalette.size
        prefs.edit().putInt("color:$sideId", index).apply()
    }
    return BookmarkPalette[index]
}
