package com.openminis.app.ui.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.openminis.app.ui.novex.NovexColors
import kotlin.math.abs
import kotlin.math.min

/**
 * 侧边组件家族（用户决策，2026-09-15 修订）：状态把手与每枚书签是**同一种侧边
 * 组件**——同一套拖动/停靠/磁吸逻辑，外形各异。每个组件独立持有自己的停靠边与
 * 沿边位置；渲染时同一条边上的组件按"距离小于阈值才磁吸成列、否则各自独立、
 * 永不重叠"的规则解析落点（见 [NovexEdgeRail]）。
 */
internal enum class NovexEdgeDock { LEFT, RIGHT, TOP }

/** 缎带尖角占包围盒长轴的比例；内容区自动避开这一段。 */
private const val RIBBON_TIP_FRACTION = 0.32f

/** 家族统一尺寸与间距（缎带等宽等高，磁吸成列时才能严丝合缝）。 */
internal val NovexEdgeRibbonWidth = 34.dp
internal val NovexEdgeRibbonHeight = 46.dp
internal val NovexEdgeRibbonGap = 8.dp

/** 磁吸阈值：与上一枚的落点距离小于此值才贴上来，否则保持独立。 */
internal val NovexEdgeMagnetDistance = 24.dp

/** 两个家族的锚点存储（沿用旧键名，老位置直接迁移）。 */
internal const val EDGE_PREFS_HUD = "novex_playthrough_hud"
internal const val EDGE_PREFS_SIDES = "novex_side_conversations"

/** 拖动事件由宿主接管；每个组件一份回调，各自更新自己的位置。 */
internal interface NovexEdgeDragCallbacks {
    fun onDragStart()
    fun onDrag(delta: Offset, change: PointerInputChange)
    fun onDragEnd()
    fun onDragCancel()
}

/**
 * 停靠几何：把 [NovexEdgeDock] + 沿边位置换算成缎带包围盒的左上角，并把任意
 * 拖动落点吸附回最近的边。上边停靠落在内容区 y=0——宿主容器（Scaffold 内容
 * 区）的 y=0 本就在顶栏下方，这里**不得**再叠加顶部保留（beta.50 的"吸附在
 * 半空中"就是把保留高度算了两遍）。bottomReserve 是输入区/导航保留高度。
 */
internal class NovexEdgeDockGeometry(
    private val screenW: Float,
    private val screenH: Float,
    private val ribbonW: Float,
    private val ribbonH: Float,
    bottomReserve: Float,
) {
    private val maxX = (screenW - ribbonW).coerceAtLeast(1f)
    private val maxY = (screenH - ribbonH - bottomReserve).coerceAtLeast(1f)

    fun maxAlong(dock: NovexEdgeDock): Float = if (dock == NovexEdgeDock.TOP) maxX else maxY

    fun anchor(dock: NovexEdgeDock, fraction: Float): Offset = anchorAt(dock, fraction.coerceIn(0f, 1f) * maxAlong(dock))

    fun anchorAt(dock: NovexEdgeDock, alongPx: Float): Offset = when (dock) {
        NovexEdgeDock.LEFT -> Offset(0f, alongPx.coerceIn(0f, maxY))
        NovexEdgeDock.RIGHT -> Offset(maxX, alongPx.coerceIn(0f, maxY))
        NovexEdgeDock.TOP -> Offset(alongPx.coerceIn(0f, maxX), 0f)
    }

    fun snap(center: Offset): Pair<NovexEdgeDock, Float> {
        val toLeft = center.x
        val toRight = screenW - center.x
        val toTop = center.y
        val dock = when {
            toTop <= toLeft && toTop <= toRight -> NovexEdgeDock.TOP
            toLeft <= toRight -> NovexEdgeDock.LEFT
            else -> NovexEdgeDock.RIGHT
        }
        val fraction = when (dock) {
            NovexEdgeDock.TOP -> (center.x - ribbonW / 2f) / maxX
            else -> (center.y - ribbonH / 2f) / maxY
        }
        return dock to fraction.coerceIn(0f, 1f)
    }
}

internal data class NovexEdgeRailEntry(val id: String, val dock: NovexEdgeDock, val fraction: Float)

/**
 * 落点解析（纯函数，可单测）：同一条边上的组件按期望位置排序后顺序走一遍——
 * 期望位置落在"上一枚已解析位置 + [magnet]"以内（含重叠）就贴到上一枚的下一
 * 个间距槽（磁吸成列）；更远则保持独立位置。结果永不重叠，远者互不牵连。
 */
internal object NovexEdgeRail {
    fun resolveAlong(maxAlong: Float, pitch: Float, magnet: Float, entries: List<NovexEdgeRailEntry>): Map<String, Float> {
        val out = mutableMapOf<String, Float>()
        var cursor = Float.NEGATIVE_INFINITY
        entries.sortedBy { it.fraction }.forEach { entry ->
            val desired = entry.fraction.coerceIn(0f, 1f) * maxAlong
            val pos = if (desired <= cursor + magnet) cursor + pitch else desired
            out[entry.id] = pos
            cursor = pos
        }
        return out
    }

    /** 新组件的初始落位：从基准比例出发，跳过同边已占的间距槽。 */
    fun firstFreeFraction(maxAlong: Float, pitch: Float, occupied: List<Float>, baseFraction: Float): Float {
        val occupiedPx = occupied.map { it.coerceIn(0f, 1f) * maxAlong }.sorted()
        var px = baseFraction.coerceIn(0f, 1f) * maxAlong
        while (occupiedPx.any { abs(it - px) < pitch * 0.99f }) px += pitch
        return (px / maxAlong).coerceIn(0f, 1f)
    }
}

/** 缎带形状：矩形主体 + 停靠边对侧单个小尖角，停靠边两角圆角。 */
internal fun ribbonShape(dock: NovexEdgeDock, tipFraction: Float = RIBBON_TIP_FRACTION): Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        // Corner radius on the flush edge, relative to size only (density-free).
        val r = min(size.width, size.height) * 0.18f
        when (dock) {
            NovexEdgeDock.RIGHT -> {
                val tip = size.width * tipFraction
                path.moveTo(size.width - r, 0f)
                path.quadraticBezierTo(size.width, 0f, size.width, r)
                path.lineTo(size.width, size.height - r)
                path.quadraticBezierTo(size.width, size.height, size.width - r, size.height)
                path.lineTo(tip, size.height)
                path.lineTo(0f, size.height / 2f)
                path.lineTo(tip, 0f)
            }
            NovexEdgeDock.LEFT -> {
                val tip = size.width * tipFraction
                path.moveTo(r, 0f)
                path.quadraticBezierTo(0f, 0f, 0f, r)
                path.lineTo(0f, size.height - r)
                path.quadraticBezierTo(0f, size.height, r, size.height)
                path.lineTo(size.width - tip, size.height)
                path.lineTo(size.width, size.height / 2f)
                path.lineTo(size.width - tip, 0f)
            }
            NovexEdgeDock.TOP -> {
                val tip = size.height * tipFraction
                path.moveTo(0f, r)
                path.quadraticBezierTo(0f, 0f, r, 0f)
                path.lineTo(size.width - r, 0f)
                path.quadraticBezierTo(size.width, 0f, size.width, r)
                path.lineTo(size.width, size.height - tip)
                path.lineTo(size.width / 2f, size.height)
                path.lineTo(0f, size.height - tip)
            }
        }
        path.close()
        return Outline.Generic(path)
    }
}

/** 停靠锚点持久化（dock:fraction），各组件自带 prefs 名与键。 */
internal object NovexEdgePlacement {
    fun read(
        context: Context,
        prefsName: String,
        key: String,
        default: Pair<NovexEdgeDock, Float> = NovexEdgeDock.RIGHT to 0.35f,
    ): Pair<NovexEdgeDock, Float> = readOrNull(context, prefsName, key) ?: default

    fun readOrNull(context: Context, prefsName: String, key: String): Pair<NovexEdgeDock, Float>? {
        val raw = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(key, null) ?: return null
        val parts = raw.split(':')
        val dock = parts.getOrNull(0)?.let { runCatching { NovexEdgeDock.valueOf(it) }.getOrNull() } ?: return null
        val fraction = parts.getOrNull(1)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: return null
        return dock to fraction
    }

    fun write(context: Context, prefsName: String, key: String, dock: NovexEdgeDock, fraction: Float) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().putString(key, "${dock.name}:${fraction.coerceIn(0f, 1f)}").apply()
    }
}

/**
 * 缎带本体：纯视觉 + 手势。定位（offset）由使用方修饰；点按走 [onTap]，
 * 长按拖动转发给 [drag]（不吞点按：拖动检测器只在长按阈值之后消费指针）。
 * floating=true 为拖出中的形态（全圆角胶囊、更高投影）；stretch>1 为点按
 * 切换时的拉长提示，只作用在长轴上。尖角端占用包围盒的 RIBBON_TIP_FRACTION
 * 段，内容自动避开该段、在矩形主体内居中。
 */
@Composable
internal fun NovexEdgeRibbon(
    dock: NovexEdgeDock,
    fill: Color,
    rim: Color,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    stretch: Float = 1f,
    floating: Boolean = false,
    badge: Boolean = false,
    badgeColor: Color = NovexColors.Primary,
    elevation: Dp = 3.dp,
    onTap: (() -> Unit)? = null,
    drag: NovexEdgeDragCallbacks? = null,
    content: @Composable () -> Unit = {},
) {
    val shape = if (floating) RoundedCornerShape(50) else ribbonShape(dock)
    // Live references for the gesture coroutines: pointerInput(dock) must NOT
    // restart on recomposition (that would cancel an in-flight drag), so it can
    // only ever see these rememberUpdatedState reads — never a captured stale
    // callback object (the beta.44/45 stale-closure lesson).
    val liveOnTap by rememberUpdatedState(onTap)
    val liveDrag by rememberUpdatedState(drag)
    Box(
        modifier
            .size(width, height)
            .graphicsLayer {
                when (dock) {
                    NovexEdgeDock.LEFT, NovexEdgeDock.RIGHT -> scaleY = stretch
                    NovexEdgeDock.TOP -> scaleX = stretch
                }
            }
            .shadow(if (floating) 7.dp else elevation, shape)
            .clip(shape)
            .background(fill, shape)
            .then(if (rim.alpha > 0f) Modifier.border(1.dp, rim, shape) else Modifier)
            .then(
                if (liveDrag != null) {
                    Modifier
                        .then(if (liveOnTap != null) Modifier.clickable { liveOnTap?.invoke() } else Modifier)
                        .pointerInput(dock) {
                            // Positional args: the start-callback's NAME differs
                            // across foundation versions (onDragStart vs
                            // onDragStarted) but the (start, end, cancel, drag)
                            // ORDER is stable — positional avoids both.
                            detectDragGesturesAfterLongPress(
                                { liveDrag?.onDragStart() },
                                { liveDrag?.onDragEnd() },
                                { liveDrag?.onDragCancel() },
                            ) { change, amount ->
                                change.consume()
                                liveDrag?.onDrag(amount, change)
                            }
                        }
                } else if (liveOnTap != null) {
                    Modifier.clickable { liveOnTap?.invoke() }
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .then(
                    when (dock) {
                        NovexEdgeDock.LEFT -> Modifier.padding(start = width * RIBBON_TIP_FRACTION)
                        NovexEdgeDock.RIGHT -> Modifier.padding(end = width * RIBBON_TIP_FRACTION)
                        NovexEdgeDock.TOP -> Modifier.padding(top = height * RIBBON_TIP_FRACTION)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) { content() }
        if (badge && !floating) {
            // Small dot at the pointed tip — the most salient spot on a ribbon.
            Box(
                Modifier
                    .align(
                        when (dock) {
                            NovexEdgeDock.TOP -> Alignment.BottomCenter
                            NovexEdgeDock.RIGHT -> Alignment.CenterStart
                            NovexEdgeDock.LEFT -> Alignment.CenterEnd
                        },
                    )
                    .padding(3.dp)
                    .background(badgeColor, RoundedCornerShape(50))
                    .size(7.dp),
            )
        }
    }
}
