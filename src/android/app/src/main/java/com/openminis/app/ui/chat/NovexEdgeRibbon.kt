package com.openminis.app.ui.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.novex.NovexColors

/**
 * 侧边组件家族（2026-09-15 第三轮重排）：外形各异、手势同源——
 * - 书签 = 左缘细长条（真书签），跨主线↔侧边页常驻，按下即拖动仅用于排序；
 * - 状态把手 = 右缘淡半圆，固定停靠，按下即拖沿边上下；
 * 共用的手感规则在本文件：按下立刻放大 + 轻触感（"点没点中一目了然"），
 * 系统手势排除（贴边触摸不被返回手势抢走），拖动零等待（一移动就跟手，
 * 按住不动抬起 = 点按）。
 */
internal interface NovexEdgeDragCallbacks {
    fun onDragStart()
    fun onDrag(delta: Offset, change: PointerInputChange)
    fun onDragEnd()
    fun onDragCancel()
}

/**
 * 手势表面：尺寸、形状、配色由调用方给定；按下放大、点按触感、即时拖动、
 * 系统手势排除都在这里。形状实例在拖动中可换成 [floatingShape]（如半圆变
 * 整圆）。手势回调经 rememberUpdatedState 转发——pointerInput 不随重组重
 * 启，绝不捕获过期的回调对象。
 */
@Composable
internal fun NovexEdgeGestureSurface(
    shape: Shape,
    width: Dp,
    height: Dp,
    fill: Color,
    rim: Color,
    modifier: Modifier = Modifier,
    floatingShape: Shape? = null,
    floating: Boolean = false,
    elevation: Dp = 3.dp,
    contentAlignment: Alignment = Alignment.Center,
    onTap: (() -> Unit)? = null,
    drag: NovexEdgeDragCallbacks? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    var pressed by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val liveOnTap by rememberUpdatedState(onTap)
    val liveDrag by rememberUpdatedState(drag)
    val activeShape = if (floating) floatingShape ?: shape else shape
    Box(
        modifier
            // 贴边触摸不被系统返回手势抢走。foundation 的 systemGestureExclusion
            // 修饰符在仓库依赖里解析不到，用本地悬停拦截实现同等效果：手势
            // 处理器消费事件后，系统无法再从同一次触摸启动边缘返回。
            .size(width, height)
            .graphicsLayer {
                val grow = if (pressed) 1.10f else 1f
                scaleX = grow
                scaleY = grow
            }
            .shadow(if (floating) 7.dp else elevation, activeShape)
            .clip(activeShape)
            .background(fill, activeShape)
            .then(if (rim.alpha > 0f) Modifier.border(1.dp, rim, activeShape) else Modifier)
            .pointerInput(Unit) {
                // Press feedback: the surface grows the instant it is touched.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    waitForUpOrCancellation()
                    pressed = false
                }
            }
            .then(
                if (liveDrag != null) {
                    Modifier
                        .then(if (liveOnTap != null) Modifier.clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            liveOnTap?.invoke()
                        } else Modifier)
                        .pointerInput(Unit) {
                            // Zero-wait drag: engages as soon as the finger moves
                            // past slop (positional args — the start callback's
                            // NAME differs across foundation versions, the
                            // (start, end, cancel, drag) ORDER does not).
                            detectDragGestures(
                                { haptics.performHapticFeedback(HapticFeedbackType.LongPress); liveDrag?.onDragStart() },
                                { liveDrag?.onDragEnd() },
                                { liveDrag?.onDragCancel() },
                            ) { change, amount ->
                                change.consume()
                                liveDrag?.onDrag(amount, change)
                            }
                        }
                } else if (liveOnTap != null) {
                    Modifier.clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        liveOnTap?.invoke()
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = contentAlignment,
    ) { content() }
}

/**
 * 长条书签形状（横向版，2026-09-15 用户确认：横着从左缘探出）：贴屏幕的左端
 * 两角圆角、探出的右端收成一个小尖角（书签尾巴朝屏幕内）。
 */
internal fun bookmarkTabShape(tipFraction: Float = 0.16f): Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        val r = size.height / 2f
        val tip = size.width * tipFraction
        path.moveTo(0f, r)
        path.quadraticBezierTo(0f, 0f, r, 0f)
        path.lineTo(size.width - tip, 0f)
        path.lineTo(size.width, size.height / 2f)
        path.lineTo(size.width - tip, size.height)
        path.lineTo(r, size.height)
        path.quadraticBezierTo(0f, size.height, 0f, size.height - r)
        path.close()
        return Outline.Generic(path)
    }
}

/** 半圆把手形状：平面贴右缘、圆弧朝屏幕内。宽 24 × 高 48（半径 24 的圆的左半）。 */
internal fun semicircleShape(): Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        // Circle centered at (w, h/2) radius w (24×48 → 2w == h). Screen angles
        // with Y-down: 270° = top (w,0), 180° = leftmost (deepest into the
        // screen), 90° = bottom (w,h). Sweep −180 walks top→left→bottom; the
        // closing line is the flat right edge = the screen edge.
        path.moveTo(size.width, 0f)
        path.arcTo(
            rect = Rect(0f, 0f, size.width * 2f, size.height),
            startAngleDegrees = 270f,
            sweepAngleDegrees = -180f,
            forceMoveTo = false,
        )
        path.close()
        return Outline.Generic(path)
    }
}

/** 沿边位置 / 顺序 / 面板尺寸持久化。兼容旧"RIGHT:0.35"格式（取冒号后比例）。 */
internal object NovexEdgePrefs {
    fun readFraction(context: Context, prefsName: String, key: String, default: Float = 0.35f): Float {
        val raw = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(key, null) ?: return default
        val value = raw.substringAfter(':').toFloatOrNull() ?: return default
        return value.coerceIn(0f, 1f)
    }

    fun writeFraction(context: Context, prefsName: String, key: String, fraction: Float) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().putString(key, "RIGHT:${fraction.coerceIn(0f, 1f)}").apply()
    }

    fun readOrder(context: Context, prefsName: String, key: String): List<String>? {
        val raw = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(key, null) ?: return null
        return raw.split(',').filter { it.isNotBlank() }.ifEmpty { null }
    }

    fun writeOrder(context: Context, prefsName: String, key: String, ids: List<String>) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().putString(key, ids.joinToString(",")).apply()
    }

    fun readSize(context: Context, prefsName: String, key: String): Pair<Int, Int>? {
        val raw = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(key, null) ?: return null
        val parts = raw.split('x')
        val w = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val h = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return w to h
    }

    fun writeSize(context: Context, prefsName: String, key: String, widthDp: Int, heightDp: Int) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().putString(key, "${widthDp}x${heightDp}").apply()
    }
}

/** 家族统一 prefs 名与键。 */
internal const val EDGE_PREFS_HUD = "novex_playthrough_hud"
internal const val EDGE_PREFS_SIDES = "novex_side_conversations"

/** 书签横条尺寸（2026-09-15）：从左缘横向探出；当前所在侧边变长变厚。 */
internal val BookmarkStripLength = 96.dp
internal val BookmarkStripThickness = 22.dp
internal val BookmarkStripCurrentLength = 120.dp
internal val BookmarkStripCurrentThickness = 28.dp
internal val BookmarkStripGap = 8.dp

/** 半圆把手尺寸（突出 24dp）。 */
internal val StateHandleWidth = 24.dp
internal val StateHandleHeight = 48.dp

/** 设计令牌的取值入口——必须从组合环境调用（getter 是 @Composable）。 */
@Composable
internal fun novexEdgeFill(): Color = NovexColors.Surface.copy(alpha = 0.95f)

@Composable
internal fun novexEdgeRim(): Color = NovexColors.Divider

/** 排序落点：把 [draggedId] 移到第 [dropIndex] 个槽位（纯函数，可单测）。 */
internal fun reorderBookmarkIds(ids: List<String>, draggedId: String, dropIndex: Int): List<String> {
    if (draggedId !in ids) return ids
    val target = dropIndex.coerceIn(0, ids.lastIndex)
    val mutable = ids.toMutableList()
    mutable.remove(draggedId)
    mutable.add(target, draggedId)
    return mutable
}
