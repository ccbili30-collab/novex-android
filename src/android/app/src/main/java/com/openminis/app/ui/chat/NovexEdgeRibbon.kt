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
import kotlin.math.min

/**
 * 侧边组件家族（2026-09-14 用户决策 14）：书签与本局状态把手是同一种"缎带"——
 * 矩形主体 + 一个朝屏幕内侧的小尖角，平面贴齐停靠边；拖动逻辑同一套
 * （长按拖出、松手吸附最近边、沿边锚点持久化）。本文件只提供形状、几何与
 * 手势；锚点布局由各使用方持有，缎带本体不持久化任何状态。
 */
internal enum class NovexEdgeDock { LEFT, RIGHT, TOP }

/** 缎带尖角占包围盒长轴的比例；内容区自动避开这一段。 */
private const val RIBBON_TIP_FRACTION = 0.32f

/** 拖动事件由使用方接管（书签拖动的是整列锚点，状态把手拖动自己）。 */
internal interface NovexEdgeDragCallbacks {
    fun onDragStart()
    fun onDrag(delta: Offset, change: PointerInputChange)
    fun onDragEnd()
    fun onDragCancel()
}

/**
 * 停靠几何：把 [NovexEdgeDock] + 沿边比例换算成缎带包围盒的左上角，并把
 * 任意拖动落点吸附回最近的边。bottomReserve 是输入区/导航保留高度，缎带
 * 永不驻留其中；topReserve 是状态栏保留高度。
 */
internal class NovexEdgeDockGeometry(
    private val screenW: Float,
    private val screenH: Float,
    private val ribbonW: Float,
    private val ribbonH: Float,
    bottomReserve: Float,
    private val topReserve: Float = 0f,
) {
    private val maxX = (screenW - ribbonW).coerceAtLeast(1f)
    private val maxY = (screenH - ribbonH - bottomReserve).coerceAtLeast(1f)

    fun anchor(dock: NovexEdgeDock, fraction: Float): Offset = when (dock) {
        NovexEdgeDock.LEFT -> Offset(0f, fraction.coerceIn(0f, 1f) * maxY)
        NovexEdgeDock.RIGHT -> Offset(maxX, fraction.coerceIn(0f, 1f) * maxY)
        NovexEdgeDock.TOP -> Offset(fraction.coerceIn(0f, 1f) * maxX, topReserve)
    }

    fun snap(center: Offset): Pair<NovexEdgeDock, Float> {
        val toLeft = center.x
        val toRight = screenW - center.x
        val toTop = center.y - topReserve
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

/** 停靠锚点持久化（dock:fraction），各使用方自带 prefs 名与键。 */
internal object NovexEdgePlacement {
    fun read(
        context: Context,
        prefsName: String,
        key: String,
        default: Pair<NovexEdgeDock, Float> = NovexEdgeDock.RIGHT to 0.35f,
    ): Pair<NovexEdgeDock, Float> {
        val raw = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(key, null)
            ?: return default
        val parts = raw.split(':')
        val dock = parts.getOrNull(0)?.let { runCatching { NovexEdgeDock.valueOf(it) }.getOrNull() } ?: default.first
        val fraction = parts.getOrNull(1)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: default.second
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
                        // Positional args: the start-callback's NAME differs
                        // across foundation versions (onDragStart vs
                        // onDragStarted) but the (start, end, cancel, drag)
                        // ORDER is stable — positional avoids both.
                        .pointerInput(dock) {
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
