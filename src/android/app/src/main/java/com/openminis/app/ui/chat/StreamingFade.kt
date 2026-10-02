package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * [T-android-stream-fade] 流式 markdown 的逐词淡入（4.0 闭源收尾轮整体
 * 重组；时间常量、缓出曲线、CompositionLocal 名与 FadeController 公开面
 * 为行为冻结面）。
 *
 * iOS TextFadeAnimator 的镜像：新追加的字符以 α=0 起渲，在
 * [FADE_DURATION_MS] 内缓到 α=1，词界之间带错峰延迟——观感是「词挨个
 * 落定」，不是「一整块闪现」。只有流式尾块经 [LocalAppendOnlyFade]
 * 选择性加入；其余（历史、冷加载会话、已完成消息）全不透明渲染。
 *
 * 本版组织方式（与前身直译版刻意不同）：
 *  - 在飞状态只有一个列表，元素自带出生时间戳——不再维护「区间表 + 平行
 *    下标时间戳数组」的双下标不变量，下标漂移类缺陷从结构上消失；
 *  - [ingest] 两阶段：先归一化输入（识别可信后缀，其余整块重置），再把
 *    后缀一次性规划成错峰区间表；
 *  - [tick] 过滤式重建：每拍把「已到 α=1」的区间从表中滤除、重建列表，
 *    不做按下标摘除；
 *  - 区间透明度按阶段归类后单式求值（未揭 / 已满 / 缓出段）。
 *  - 护栏不变：在飞词数超 [MAX_FADE_WORDS] 的突发直接全不透明放行，
 *    对齐 iOS TextFadeAnimator 的 `maxAnimatedWords = 160` 短路——1k
 *    token 的重排不许把帧预算吃满。
 */

internal val LocalAppendOnlyFade = compositionLocalOf { false }

// [T-android-streaming-incremental-inline] 仅对**活的**流式尾块为 true。置
// 位时 RenderBlock 的 Paragraph 分支把行内/数学改走增量缓存（冻结闭合前缀
// + 新鲜未闭合后缀），不再每个节流拍都全段重扫。冻结/历史块一律 false，
// 保持逐块朴素缓存。
internal val LocalLiveIncremental = compositionLocalOf { false }

// [T-android-stream-fade] 双路冲洗 + 平滑滚动落地后淡入的可读性调整：一个
// 冲洗批次约 5–12 词，换行快路 100ms 内又能触发，100ms 的紧错峰窗会让整
// 批几乎同时亮起、逐词揭示完全不可见。错峰窗放宽到 300ms（每词上限 90ms）
// 后，即便下一批很快到，批内词也拉开成清晰的从左到右次序落定；每词 350ms
// 的淡入时长不变（再长就开始显拖沓）。
private const val FADE_DURATION_MS = 350L
private const val STAGGER_WINDOW_MS = 300L
private const val PER_WORD_STAGGER_MS = 90L
private const val MAX_FADE_WORDS = 160

/** 逻辑区间（冻结面：三字段即行为契约）。 */
private data class FadeRange(
    val start: Int,
    val end: Int,
    val staggerMs: Long,
)

/** 在飞条目：区间 + 它自己的出生纳秒。时间戳与区间同记录，无平行数组。 */
private class FadeSpanInFlight(val range: FadeRange, val bornNanos: Long)

internal class FadeController {
    /** 已见过的 plainText 前缀——超出它的都是新鲜的。 */
    var lastPlainText: String = ""
        private set

    /** 在飞条目表（快照态：增删即驱动重组）；α=1 的条目每拍滤除。 */
    private val inFlight: SnapshotStateList<FadeSpanInFlight> =
        mutableListOf<FadeSpanInFlight>().toMutableStateList()

    /** 各区间当前 alpha，逐帧更新；[overlay] 读。 */
    val alphas: SnapshotStateMap<Int, Float> = SnapshotStateMap()

    /** 还有区间未到 α=1 时为 true——帧循环的驱动位。 */
    val hasActiveRanges: Boolean get() = inFlight.isNotEmpty()

    /**
     * 阶段一·输入归一化。只有「旧前缀的纯延伸」才存在可信后缀；文本缩短
     * 或分叉说明调用方在渲染全新内容——整块重置后即回。幂等：原文重来
     * 直接短路。
     */
    fun ingest(newPlainText: String) {
        val prefix = lastPlainText
        if (newPlainText == prefix) return
        val grownSuffix = newPlainText.also { lastPlainText = it }
            .takeIf { it.startsWith(prefix) }?.substring(prefix.length)
        if (grownSuffix == null) { clearAll(); return }
        planFadeIn(prefix.length, grownSuffix)
    }

    /**
     * 阶段二·一次性规划：可信后缀切成词样跑段、套错峰预算、整批建表。
     * 全空白后缀没有可淡的东西，静默跳过；在飞总量爆表则整块放行为不透
     * 明，新区间不建。
     */
    private fun planFadeIn(base: Int, suffix: String) {
        val freshRuns = wordRunsOf(suffix)
        if (freshRuns.isEmpty()) return
        val burstExceedsCap = freshRuns.size + inFlight.size > MAX_FADE_WORDS
        if (burstExceedsCap) { clearAll(); return }
        val strideMs = minOf(PER_WORD_STAGGER_MS, STAGGER_WINDOW_MS / freshRuns.size)
        val stampNanos = System.nanoTime()
        freshRuns.forEachIndexed { ordinal, run ->
            inFlight += FadeSpanInFlight(
                FadeRange(base + run.first, base + run.last + 1, ordinal * strideMs),
                stampNanos,
            )
        }
    }

    private fun clearAll() {
        inFlight.clear(); alphas.clear()
    }

    /**
     * 后缀切成「空白分隔的词样跑段」。标点跟着前一个词走（iOS 同款）——
     * 保持「词在落定」的节奏，而不是「每个字形各自落定」。
     */
    private fun wordRunsOf(text: String): List<IntRange> = buildList {
        var openFrom = -1
        for (idx in text.indices) {
            if (text[idx].isWhitespace()) {
                if (openFrom >= 0) {
                    add(openFrom until idx)
                    openFrom = -1
                }
            } else if (openFrom < 0) {
                openFrom = idx
            }
        }
        if (openFrom >= 0) add(openFrom until text.length)
    }

    /**
     * 依 [nowNanos] 推进每个在飞区间，过滤式重建在飞表。无区间仍在动画时
     * 返回 false（调用方可挂起循环）。
     */
    fun tick(nowNanos: Long): Boolean {
        if (inFlight.isEmpty()) return false
        val stillFlying = ArrayList<FadeSpanInFlight>(inFlight.size)
        for (item in inFlight) {
            val alpha = currentAlpha(item, nowNanos)
            if (alpha < 1f) {
                alphas[item.range.start] = alpha
                stillFlying += item
            } else {
                alphas.remove(item.range.start)
            }
        }
        if (stillFlying.size != inFlight.size) {
            inFlight.clear()
            inFlight.addAll(stillFlying)
        }
        return stillFlying.isNotEmpty()
    }

    /**
     * 区间阶段归类后单式求值：揭幕毫秒 ≤0 → 未揭（α=0）；≥ 淡入时长 →
     * 已满（α=1）；其间落在缓出段。
     */
    private fun currentAlpha(item: FadeSpanInFlight, nowNanos: Long): Float {
        val revealMs = (nowNanos - item.bornNanos) / 1_000_000L - item.range.staggerMs
        return when {
            revealMs <= 0L -> 0f
            revealMs >= FADE_DURATION_MS -> 1f
            else -> 1f - easeOutComplement(revealMs / FADE_DURATION_MS.toFloat())
        }
    }

    /** 缓出三次方补数 (1-t)³——`1 - 该值` 即 iOS 动画器同曲线（冻结面）。 */
    private fun easeOutComplement(progress: Float): Float {
        val remaining = 1f - progress
        return remaining * remaining * remaining
    }

    /**
     * 构建一条把每个活跃区间按当前 alpha 重新着色的 AnnotatedString。非活
     * 跃（α=1）区间随 [tick] 滤除自然退出；周围文本与原有 span 原样保留。
     */
    fun overlay(base: AnnotatedString, baseColor: Color): AnnotatedString {
        val paintable = inFlight.filter { it.range.end <= base.length }
        if (paintable.isEmpty()) return base
        val repainted = buildAnnotatedString {
            append(base)
            for (item in paintable) {
                addStyle(SpanStyle(baseColor.copy(alpha = alphas[item.range.start] ?: 0f)), item.range.start, item.range.end)
            }
        }
        return repainted
    }
}

@Composable
internal fun rememberFadeController(): FadeController = remember { FadeController() }

/**
 * 为 [controller] 驱动逐帧 tick。无动画时整个驱动分支退出组合、零成本；
 * [controller.hasActiveRanges] 置位后进入帧循环，每个帧时钟拍推进一次，
 * 直到没有区间仍在飞。每个 MdText 单实例——各动画块互相独立运转。
 */
@Composable
internal fun FadeFrameDriver(controller: FadeController) {
    if (controller.hasActiveRanges) {
        LaunchedEffect(controller) {
            while (controller.tick(withFrameNanos { frameNanos -> frameNanos })) {
                // 一拍一推进；tick 报 false 时动画收尾，效果体随之结束。
            }
        }
    }
}

/**
 * 为最近的基础色持一个稳定可变壳，overlay() 就不必让 MdText 每次经组合
 * 传色。当前无外部使用者，留作未来淡入扩展（色偏、提速）的挂点——它们
 * 都会依赖周围的主题色。
 */
internal data class FadeColorHolder(var color: Color = Color.Unspecified) {
    val state = mutableStateOf(color)
}
