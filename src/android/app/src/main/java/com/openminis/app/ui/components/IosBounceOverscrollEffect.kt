package com.openminis.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 竖向滚动容器的 iOS 风格弹簧回弹（血统清剿 P3.7 就地真重写；橡胶带公式、
 * 弹簧参数与嵌套滚动消费序为行为冻结面）。
 *
 * Android 原生 overscroll 要么是边缘光晕（API <31）要么是一下短拉伸
 * （API 31+），都仿不出 iOS UIScrollView 的橡皮筋拖拽与弹性回弹。本效果：
 *
 *   - 过边拖拽时内容按**橡胶带**比例平移（拖得越长压得越狠、渐近封顶），
 *     页面明显跟着手指越过上下缘；
 *   - 松手/甩动时以 [Spring.DampingRatioMediumBouncy] + [Spring.StiffnessLow]
 *     把偏移动画回 0——iOS 表格与滚动视图回弹用的正是这组参数（≈0.5 阻
 *     尼、缓慢回复力、肉眼可见的过冲）。
 *
 * 接线：`Modifier.verticalScroll(state).overscroll(effect)`。
 * `Modifier.overscroll` 注册 NestedScrollConnection，每次拖拽的外缘分量进
 * [applyToScroll]、甩动余量进 [applyToFling]——即便真正消费滚动增量的
 * 是内层 `verticalScroll`。用 [rememberIosBounceOverscrollEffect] 保证
 * Animatable 跨重组复用。
 */
@OptIn(ExperimentalFoundationApi::class)
class IosBounceOverscrollEffect(
    private val scope: CoroutineScope,
    /**
     * 「无限拉力」下的最大视觉拉伸像素。iOS UIScrollView 按视口尺寸封顶；
     * Pixel 级密度上 600px 手感合适，也不至于让一次误甩把页面撕出屏。
     */
    private val maxOverscrollPx: Float = 600f,
) : OverscrollEffect {

    private val offset = Animatable(0f)

    override val isInProgress: Boolean
        get() = offset.value != 0f

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        // 已过边（offset != 0）且用户开始向 0 回拉时：先吃掉手势里的同向
        // 分量把橡皮筋松开，内层滚动器一粒都不见。对齐 UIScrollView 的
        // 「先放弹、再恢复滚动」。
        val stretched = offset.value
        val unwindBy = if (stretched != 0f && sign(delta.y) != sign(stretched)) {
            (-stretched).coerceAtMost(abs(delta.y)) * sign(delta.y)
        } else 0f
        val preConsumed = Offset(0f, unwindBy)
        if (unwindBy != 0f) scope.launch { offset.snapTo(stretched + unwindBy) }

        val unconsumed = delta - preConsumed
        val innerConsumed = performScroll(unconsumed)
        val pastEdge = unconsumed - innerConsumed

        if (pastEdge.y != 0f) {
            // 拖过边了——上橡胶带。
            val afterUnwind = stretched + unwindBy
            scope.launch { offset.snapTo(afterUnwind + rubberBand(pastEdge.y, afterUnwind)) }
        }

        return preConsumed + innerConsumed
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        val leftover = performFling(velocity)
        // iOS 弹感回弹：mediumBouncy + stiffnessLow ≈ 0.5 阻尼、缓慢回复力，
        // 产出 iOS 用户预期的可见过冲。
        val bounceBack = spring<Float>(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        )
        offset.animateTo(targetValue = 0f, animationSpec = bounceBack, initialVelocity = leftover.y)
    }

    /**
     * UIScrollView 式橡胶带：overscroll = delta × (1 − |currentOffset|/max)。
     * 指数 0.55 对齐 UIKit `_UIRubberBandClampedDistance` 的曲线。
     */
    private fun rubberBand(delta: Float, currentOffset: Float): Float {
        val pull = abs(currentOffset).coerceAtMost(maxOverscrollPx)
        val resistance = (1f - (pull / maxOverscrollPx)).coerceAtLeast(0f).pow(0.55f)
        return delta * resistance
    }

    /**
     * 按当前 overscroll 偏移平移内容。`Modifier.overscroll(effect)` 用它渲
     * 染橡胶带——只竖向挪已布局内容、不改测量尺寸，内层滚动器的测量大小
     * 得以保全。
     */
    override val effectModifier: Modifier = Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val laidWidth = placeable.width
        val laidHeight = placeable.height
        layout(laidWidth, laidHeight) { placeable.placeRelative(0, offset.value.toInt()) }
    }
}

/**
 * 记忆化工厂：绑定局部 [rememberCoroutineScope] 的
 * [IosBounceOverscrollEffect]。实例在组合生命周期内稳定，调用方可放心
 * 交给 `Modifier.overscroll(effect)`。
 */
@Composable
fun rememberIosBounceOverscrollEffect(): IosBounceOverscrollEffect {
    val scope = rememberCoroutineScope()
    return remember(scope) { IosBounceOverscrollEffect(scope) }
}
