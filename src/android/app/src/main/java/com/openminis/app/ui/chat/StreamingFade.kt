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
 * [T-android-stream-fade] 流式 markdown 的逐词淡入（血统清剿 P3.7 就地真
 * 重写；时间常量、缓动曲线与 CompositionLocal 名为行为冻结面）。
 *
 * iOS TextFadeAnimator 的镜像：新追加的字符以 α=0 起渲，在 [FADE_DURATION_MS]
 * 内缓到 α=1，词界之间带一点错峰延迟——观感是「词挨个落定」，不是「一整
 * 块闪现」。只有流式尾块经 [LocalAppendOnlyFade] 选择性加入；其余（历史、
 * 冷加载会话、已完成消息）全不透明渲染。
 *
 * 实现要点：
 *  - 置位的每个 MdText 持一个 [FadeController]，追踪上一次 plainText 前
 *    缀。新 plainText 是其延伸时，后缀切成词区间，各挂
 *    (起始纳秒, 错峰毫秒) 一对；
 *  - 组合内单个 `withFrameNanos` 循环推进动画进度、把当前 alpha 写进快照
 *    态 map。MdText 组装 AnnotatedString 覆盖层时读它——每帧只有这一个
 *    MdText 重组，兄弟块纹丝不动；
 *  - 全部区间到 α=1 后循环挂起，等下一次追加再醒，空闲成本为零；
 *  - 护栏：在飞词数超 [MAX_FADE_WORDS] 的突发直接全不透明放行，对齐 iOS
 *    TextFadeAnimator 的 `maxAnimatedWords = 160` 短路——1k token 的重排
 *    不许把帧预算吃满。
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

private data class FadeRange(
    val start: Int,
    val end: Int,
    val staggerMs: Long,
)

internal class FadeController {
    /** 已见过的 plainText 前缀——超出它的都是新鲜的。 */
    var lastPlainText: String = ""
        private set

    /** 在飞的动画区间；α=1 的冻结区间每拍移除。 */
    private val rangesState: SnapshotStateList<FadeRange> = mutableListOf<FadeRange>().toMutableStateList()

    /** 各区间的起始纳秒（与 rangesState 平行同下标）。 */
    private val rangeStartNanos = ArrayDeque<Long>()

    /** 各区间当前 alpha，逐帧更新；[overlay] 读。 */
    val alphas: SnapshotStateMap<Int, Float> = SnapshotStateMap()

    /** 还有区间未到 α=1 时为 true——帧循环的驱动位。 */
    val hasActiveRanges: Boolean get() = rangesState.isNotEmpty()

    fun ingest(newPlainText: String) {
        if (newPlainText == lastPlainText) return
        // 硬重置（文本缩短或不再是指定前缀的延伸）：清空全部在飞区间——
        // 调用方在渲染一个全新的块。
        if (!newPlainText.startsWith(lastPlainText)) {
            dropAllRanges()
            lastPlainText = newPlainText
            return
        }
        val base = lastPlainText.length
        val suffix = newPlainText.substring(base)
        lastPlainText = newPlainText
        if (suffix.isEmpty()) return

        val words = splitIntoWordRuns(suffix)
        // 全空白后缀：没有可见的东西可淡——跳过。
        if (words.isEmpty()) return

        val totalWords = words.size + rangesState.size
        if (totalWords > MAX_FADE_WORDS) {
            // 在飞太多——全部冲到 α=1、新区间不建，别花帧去渲染一堵隐
            // 形墙。
            dropAllRanges()
            return
        }
        // 错峰预算对齐 iOS：窗口固定，词越多每词错峰越短；单词有上限。
        val perWordStaggerMs = minOf(PER_WORD_STAGGER_MS, STAGGER_WINDOW_MS / words.size.coerceAtLeast(1))

        words.forEachIndexed { idx, word ->
            rangesState.add(FadeRange(base + word.first, base + word.last + 1, idx * perWordStaggerMs))
            rangeStartNanos.addLast(System.nanoTime())
        }
    }

    private fun dropAllRanges() {
        rangesState.clear()
        rangeStartNanos.clear()
        alphas.clear()
    }

    /**
     * 后缀切成「空白分隔的词样跑段」。标点跟着前一个词走（iOS 同款）——
     * 保持「词在落定」的节奏，而不是「每个字形各自落定」。
     */
    private fun splitIntoWordRuns(suffix: String): List<IntRange> {
        val words = mutableListOf<IntRange>()
        var wordStart = -1
        for (i in suffix.indices) {
            val whitespace = suffix[i].isWhitespace()
            if (!whitespace && wordStart < 0) {
                wordStart = i
            } else if (whitespace && wordStart >= 0) {
                words.add(wordStart until i)
                wordStart = -1
            }
        }
        if (wordStart >= 0) words.add(wordStart until suffix.length)
        return words
    }

    /**
     * 依 [nowNanos] 推进每个区间到当前 alpha。无区间仍在动画时返回 false
     * （调用方可挂起循环）。
     */
    fun tick(nowNanos: Long): Boolean {
        if (rangesState.isEmpty()) return false
        val settled = mutableListOf<Int>()
        for (i in rangesState.indices) {
            val range = rangesState[i]
            val startedAt = rangeStartNanos.elementAt(i)
            val visibleMs = (nowNanos - startedAt) / 1_000_000L - range.staggerMs
            val alpha = when {
                visibleMs <= 0 -> 0f
                visibleMs >= FADE_DURATION_MS -> 1f
                else -> {
                    val t = visibleMs.toFloat() / FADE_DURATION_MS
                    // 缓出三次方 1 - (1-t)^3（iOS 动画器同曲线）。
                    val inv = 1f - t
                    1f - inv * inv * inv
                }
            }
            alphas[range.start] = alpha
            if (alpha >= 1f) settled.add(i)
        }
        // 从尾摘除已收区间，下标漂移可控。
        for (i in settled.asReversed()) {
            val range = rangesState.removeAt(i)
            rangeStartNanos.removeAt(i)
            alphas.remove(range.start)
        }
        return rangesState.isNotEmpty()
    }

    /**
     * 构建一条把每个活跃区间按当前 alpha 重新着色的 AnnotatedString。非活
     * 跃（α=1）区间随 [tick] 移除自然退出；周围文本与原有 span 原样保留。
     */
    fun overlay(base: AnnotatedString, baseColor: Color): AnnotatedString {
        if (rangesState.isEmpty()) return base
        return buildAnnotatedString {
            append(base)
            for (range in rangesState) {
                val alpha = alphas[range.start] ?: 0f
                if (range.end > base.length) continue
                addStyle(SpanStyle(color = baseColor.copy(alpha = alpha)), range.start, range.end)
            }
        }
    }
}

@Composable
internal fun rememberFadeController(): FadeController =
    remember { FadeController() }

/**
 * 为 [controller] 驱动逐帧 tick。无动画时挂起；[controller.hasActiveRanges]
 * 翻回 true 时复醒。每个 MdText 单实例——各动画块互相独立运转。
 */
@Composable
internal fun FadeFrameDriver(controller: FadeController) {
    // active 在 withFrameNanos 内被读到——没有活跃区间时循环体重新挂起；
    // 对 hasActiveRanges 的一次状态读把它重新点火。
    val active = controller.hasActiveRanges
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            val stillActive = withFrameNanos { now -> controller.tick(now) }
            if (!stillActive) break
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
