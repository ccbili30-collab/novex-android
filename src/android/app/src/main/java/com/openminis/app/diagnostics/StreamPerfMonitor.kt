package com.openminis.app.diagnostics

import android.os.Debug
import com.openminis.app.logging.AppLogger
import java.util.Locale

/**
 * [T-android-stream-pipeline-incremental] 逐流式轮次的渲染管线聚合器
 * （血统清剿 P3.7 就地真重写；汇总行格式冻结）。
 *
 * 存在的目的：拿数字量化「冻结前缀/活体增量」扁平化铺开对 ANR 基线
 * （minis-2026-06-10 崩溃循环：GC 每秒释放 130–180MB、buildFlatChatItems
 * 高达 100 秒、主线程正则卡死）的改进。
 *
 * 零开销契约（监视器绝不能自己变成性能问题）：
 *  - 热路径 [tick] 只做原始字段算术——零分配、零建串、零日志、零锁
 *    （单写者：管线的 collect 循环）。
 *  - 轮次边界 [turnStart]/[turnEnd] 各做两次 [Debug.getRuntimeStat] 读 +
 *    至多一行 AppLogger。运行时统计是 ART 维护的廉价计数器，每轮读两次
 *    可忽略。
 *  - 无活动轮次时，每次调用在一个布尔判断上空转。
 *
 * 每轮一行汇总供前后对照：
 *   [StreamPerf] sid=… ticks=N flattenAvgUs=… flattenMaxMs=… frozenHits=N-1/N
 *   rowsLast=total(frozen+live) gcCount=+x gcFreedMB=+y.z turnS=…
 * 基线等价物来自既有 PerfLongCtx 慢构建行与 logcat GC 行，旧日志仍可比。
 */
object StreamPerfMonitor {

    private const val TAG = "StreamPerf"

    /** 单写者线程独占的可变账本——不加锁是刻意的（见类注释零开销契约）。 */
    private var inTurn = false
    private var sid = ""
    private var turnOpenedNs = 0L
    private var ticks = 0L
    private var flattenNsSum = 0L
    private var flattenNsPeak = 0L
    private var frozenReuseTicks = 0L
    private var frozenRowsLast = 0
    private var liveRowsLast = 0
    private var gcCountAtOpen = 0L
    private var gcBytesAtOpen = 0L

    /** ART 运行时统计；老设备/统计改名时取 0。 */
    private fun artStat(key: String): Long =
        Debug.getRuntimeStat(key)?.toLongOrNull() ?: 0L

    /**
     * 开始聚合一轮流式。同会话幂等；换会话意味着上一轮的收集器在流中被取消
     * （会话切换）——先把它的汇总冲掉再开新轮。
     */
    fun turnStart(sessionId: String) {
        if (inTurn && sessionId == sid) return
        if (inTurn) turnEnd()
        inTurn = true
        sid = sessionId
        turnOpenedNs = System.nanoTime()
        ticks = 0
        flattenNsSum = 0
        flattenNsPeak = 0
        frozenReuseTicks = 0
        frozenRowsLast = 0
        liveRowsLast = 0
        gcCountAtOpen = artStat("art.gc.gc-count")
        gcBytesAtOpen = artStat("art.gc.bytes-freed")
    }

    /**
     * 管线一拍（一次采样的扁平化+发布）。[flattenNanos] 是本拍离主线程的
     * 扁平化耗时；[frozenReused] 为真表示冻结前缀缓存原样复用（期望的
     * 稳态）。
     */
    fun tick(flattenNanos: Long, frozenReused: Boolean, frozenRows: Int, liveRows: Int) {
        if (!inTurn) return
        ticks++
        flattenNsSum += flattenNanos
        if (flattenNanos > flattenNsPeak) flattenNsPeak = flattenNanos
        if (frozenReused) frozenReuseTicks++
        frozenRowsLast = frozenRows
        liveRowsLast = liveRows
    }

    /** 收轮并落一行汇总；未开轮时空转。 */
    fun turnEnd() {
        if (!inTurn) return
        inTurn = false
        val turnMs = (System.nanoTime() - turnOpenedNs) / 1_000_000
        val gcTurns = artStat("art.gc.gc-count") - gcCountAtOpen
        val gcFreedMb = (artStat("art.gc.bytes-freed") - gcBytesAtOpen) / 1_000_000.0
        val avgUs = if (ticks > 0) flattenNsSum / ticks / 1_000 else 0
        AppLogger.info(
            TAG,
            "turn sid=$sid ticks=$ticks flattenAvgUs=$avgUs " +
                "flattenMaxMs=${flattenNsPeak / 1_000_000} frozenHits=$frozenReuseTicks/$ticks " +
                "rowsLast=${frozenRowsLast + liveRowsLast}(frozen=$frozenRowsLast,live=$liveRowsLast) " +
                "gcCount=+$gcTurns gcFreedMB=+${String.format(Locale.US, "%.1f", gcFreedMb)} " +
                "turnS=${turnMs / 1000}",
        )
    }
}
