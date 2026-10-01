package com.openminis.app.diagnostics

import com.openminis.app.logging.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 「点会话卡片 → ChatScreen 首帧」重入路径的专用面包屑流（血统清剿 P3.7
 * 就地真重写；[Perf][LongCtx] 行格式与 step 命名冻结——现场日志靠单串 grep
 * 取回整条时间线，与 [T-HANG-DIAG] 刻意分流）。
 *
 * 每次打点输出一行：
 *   [Perf][LongCtx] step=loadSession.enter session=abc elapsedMs=12 sinceClickMs=180 javaHeapMB=148 … extra=…
 *
 * - `elapsedMs`：距同会话上一个 step；
 * - `sinceClickMs`：距同会话 [click]（用户感知的重入起点）；
 * - 堆数字为 O(1) 廉价读，每步都采样——GC 风暴区间不用另配 tracer 就能
 *   在迹线上现形。
 *
 * 线程模型：点击/上步时间戳按 sessionId 存 AtomicLong 级并发容器。不同
 * 会话并发重入互不踩踏；同会话并发 loadSession 理论上会赛跑 lastNs，但
 * 重入即一次性进入，非真实场景。
 */
object PerfLongCtx {

    private const val TAG = "Perf"

    /** LazyColumn 行组装里程：第 10/50/200 行各打一行汇总（密集工具会话一行顶 50 行日志）。 */
    private val ROW_MILESTONES = longArrayOf(10, 50, 200)

    private val clickNsBySession = ConcurrentHashMap<String, Long>()
    private val lastNsBySession = ConcurrentHashMap<String, Long>()

    /** 行组装计数器族：会话 → (总计数起点, 按行类型计数)。 */
    private val rowTotalBySession = ConcurrentHashMap<String, AtomicLong>()
    private val rowStartNsBySession = ConcurrentHashMap<String, Long>()
    private val rowByType = ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>>()

    /** 全局单调序号，点击事件上带出，用于跨行对齐同一轮重入。 */
    private val seq = AtomicLong(0)

    /**
     * 用户点了会话卡：重置该会话的时间线。从卡片手势处理器调用，抓住用户
     * 等待的第一个时间戳。
     */
    fun click(sessionId: String) {
        val ns = System.nanoTime()
        clickNsBySession[sessionId] = ns
        lastNsBySession[sessionId] = ns
        emit(sessionId, "click", elapsedMs = 0, extra = "seq=${seq.incrementAndGet()}")
    }

    /**
     * 通用时间线面包屑。`extra` 原样拼接，调用点可自带
     * `count=405 totalChars=1234567` 之类。
     */
    fun step(sessionId: String, name: String, extra: String = "") {
        val ns = System.nanoTime()
        val prev = lastNsBySession[sessionId] ?: clickNsBySession[sessionId] ?: ns
        lastNsBySession[sessionId] = ns
        emit(sessionId, name, (ns - prev) / 1_000_000, extra)
    }

    /**
     * 重入时间线收尾（通常是 loadSession EXIT 或末条消息 onPlaced）。点击
     * 时间戳刻意保留——迟到的 step 仍有可读的 sinceClickMs；下次 click()
     * 自然重置。
     */
    fun end(sessionId: String, name: String = "end", extra: String = "") {
        step(sessionId, name, extra)
        lastNsBySession.remove(sessionId)
    }

    /**
     * LazyColumn 每个条目的 compose lambda 里调用。O(1) 廉价——每会话只在
     * 第 10/50/200 行落一行日志。按行类型分别计数（如
     * `AssistantToolUse=18 UserBubble=4 AssistantText=8`），工具卡撑爆的
     * 会话与 markdown 块撑爆的会话一眼可辨。
     */
    fun maybeReportRowComposed(sessionId: String, itemClassName: String) {
        val total = rowTotalBySession.computeIfAbsent(sessionId) { AtomicLong(0) }
        rowByType.computeIfAbsent(sessionId) { ConcurrentHashMap() }
            .computeIfAbsent(itemClassName) { AtomicLong(0) }
            .incrementAndGet()
        val nth = total.incrementAndGet()
        when {
            nth == 1L -> rowStartNsBySession[sessionId] = System.nanoTime()
            ROW_MILESTONES.contains(nth) -> reportRowMilestone(sessionId, nth)
        }
    }

    private fun reportRowMilestone(sessionId: String, nth: Long) {
        val startNs = rowStartNsBySession[sessionId] ?: return
        val sinceFirstMs = (System.nanoTime() - startNs) / 1_000_000
        val byType = rowByType[sessionId]
            ?.entries
            ?.joinToString(",") { (type, count) -> "$type=${count.get()}" }
            .orEmpty()
        step(sessionId, "rowsCompose.milestone", "rows=$nth sinceFirstRowMs=$sinceFirstMs byType=$byType")
    }

    private fun emit(sessionId: String, name: String, elapsedMs: Long, extra: String) {
        val sinceClickMs = clickNsBySession[sessionId]
            ?.let { (System.nanoTime() - it) / 1_000_000 }
            ?: -1L
        // [T-android-mem-probe-trust] 堆数字曾直接用
        // Debug.getNativeHeapAllocatedSize() 的「nativeHeapMB=…」——2026-08-15
        // 现场（vivo）读出 9744 MB（6 GB 手机、17 条消息的会话），该数字不
        // 追踪任何工作量，却被当成 OOM 铁证带偏了整轮排查。现在
        // MemorySnapshot 以内核 RSS 为主数，legacy 值改挂 `nativeRawMB` 供
        // 跨报告对比、不再被误读为真相。
        val mem = MemorySnapshot.capture()
        val extraPart = if (extra.isEmpty()) "" else " $extra"
        AppLogger.info(
            TAG,
            "[Perf][LongCtx] step=$name session=$sessionId elapsedMs=$elapsedMs " +
                "sinceClickMs=$sinceClickMs ${mem.toLogString()}$extraPart",
        )
    }
}
