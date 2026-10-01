package com.openminis.app.diagnostics

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主线程卡顿看门狗（生产线断路器，血统清剿 P3.7 就地真重写）。
 *
 * 工作模型：主 looper 上按固定节拍自续的心跳 + 后台守望线程量「距上次心跳
 * 的时距」。时距越过 [HANG_TRIGGER_MS] 即判一次卡顿发作（episode）：计数
 * 落 prefs、追加 `stall-<日期>.log` 采样；发作期间每 [RESAMPLE_STEP_MS]
 * 再采一帧主线程栈，用多帧对比区分「卡在一帧」与「同族帧打转」；心跳恢复
 * 时补一帧 post-recovery 快照收口。
 *
 * 两级断路（消费方）：
 * 1. 渲染降级（[renderBreakerActive]，发作数 ≥ [RENDER_DEGRADE_N]）：流式
 *    markdown 降级纯文本——刻意比启动断路器早一档，因为 ANR 基线日志显示
 *    系统在第 2~3 次卡顿之间就杀进程，计数 3 的闸门永远赶不上现场。
 * 2. 启动改落地首页（计数 ≥ [LAUNCH_BREAKER_N]，冷启时导航层读取）：把用户
 *    「续上次会话/直接新聊」的偏好改判为回首页，避免每次冷启都落回那个
 *    一进就卡的会话。
 *
 * 计数自愈：健康 UI 面（聊天屏）周期性调 [markHealthyTick]，距上次卡顿安静
 * 满 [QUIET_RESET_MS] 即清零；设置页也可手动清零。
 */
object HangDetector {

    private const val TAG = "HangDetector"

    // -- 节拍与判定阈值（行为冻结面） ------------------------------------
    private const val HEARTBEAT_PERIOD_MS = 1_000L
    private const val HANG_TRIGGER_MS = 3_000L

    /**
     * 发作中重采样主栈的步长。后台进程冻结解冻时会带着巨大心跳时距回来但主
     * 线程其实已闲——单点快照常拍到空转栈（nativePollOnce），多帧采样才能
     * 钉住真凶；也保证采样落在活栈期间。
     */
    private const val RESAMPLE_STEP_MS = 3_000L

    private const val LAUNCH_BREAKER_N = 3
    private const val RENDER_DEGRADE_N = 2
    private const val QUIET_RESET_MS = 10_000L

    // -- 持久化契约（prefs 名与键冻结） ----------------------------------
    private const val PREFS_NAME = "hang_detector_prefs"
    private const val KEY_HANG_COUNT = "hang_count"
    private const val KEY_LAST_HANG_AT = "last_hang_at_ms"

    // -- stall 日志文件契约（文件名与行格式冻结） -------------------------
    private const val STALL_LOG_DIR = "logs"
    private const val STALL_LOG_PREFIX = "stall-"
    private val DAY_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val CLOCK_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)

    /** 主线程心跳时间戳（心跳任务自身维护）。 */
    private val beatAt = AtomicLong(0L)

    private var appContext: Context? = null

    private val _renderBreakerActive = MutableStateFlow(false)

    /**
     * 渲染降级活信号：true 时流式 markdown 以纯文本呈现。start() 时用持久化
     * 计数播种——ANR 循环里进程活不到攒满两次进程内卡顿，但计数跨重启存活，
     * 半路重启直接以降级态起，先别再卡一次。
     */
    val renderBreakerActive: StateFlow<Boolean> = _renderBreakerActive.asStateFlow()

    /** 幂等启动；MinisApp.onCreate 调用安全。 */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        appContext = context.applicationContext
        beatAt.set(System.currentTimeMillis())
        seedRenderBreakerFromPersistedCount(context)
        armHeartbeat()
        // 非守护线程：进程还活着但被调度出去时看门狗不能被收割；JVM 收尾期
        // 守护线程提前死，会吞掉正要捕获的卡顿。
        thread(name = "HangDetector-watch", isDaemon = false) { watchForever() }
        echoStartBanner()
    }

    /**
     * 长寿健康 UI 面的确认心跳：计数为 0 时零开销早退；距最近一次卡顿安静满
     * [QUIET_RESET_MS] 则清零并解除渲染降级。
     */
    fun markHealthyTick() {
        val ctx = appContext ?: return
        val prefs = hangPrefs(ctx)
        if (prefs.getInt(KEY_HANG_COUNT, 0) == 0) return
        val lastAt = prefs.getLong(KEY_LAST_HANG_AT, 0L)
        if (lastAt > 0 && System.currentTimeMillis() - lastAt < QUIET_RESET_MS) return
        clearCount(prefs)
        _renderBreakerActive.value = false
        Log.i(TAG, "hang count reset after quiet period")
    }

    /**
     * 冷启动断路器闸门：true 时导航层应无视「续上次会话/新聊」偏好直接落
     * 首页——前几次启动都在卡的时候，首页是唯一安全落点。
     */
    fun shouldForceHomeOnLaunch(context: Context): Boolean =
        hangPrefs(context).getInt(KEY_HANG_COUNT, 0) >= LAUNCH_BREAKER_N

    /** 手动清零（设置页「重置卡顿计数」），同步解除渲染降级。 */
    fun resetHangCount(context: Context) {
        clearCount(hangPrefs(context))
        _renderBreakerActive.value = false
    }

    fun currentHangCount(context: Context): Int =
        hangPrefs(context).getInt(KEY_HANG_COUNT, 0)

    // -- 心跳侧 ------------------------------------------------------------

    /** 主线程自续心跳：每拍刷新时间戳再排下一拍。 */
    private fun armHeartbeat() {
        mainHandler.postDelayed({
            beatAt.set(System.currentTimeMillis())
            armHeartbeat()
        }, HEARTBEAT_PERIOD_MS)
    }

    // -- 守望侧 ------------------------------------------------------------

    /**
     * 发作状态机：idle →（时距越限）onset（计数+首帧采样）→（持续越限）
     * 每 RESAMPLE_STEP_MS 补采样 →（心跳恢复）收口帧回到 idle。
     * 「计数」每发作一次、「采样」发作内多次，两者解耦。
     */
    private class Episode(
        var peakGapMs: Long,
        var sampledAt: Long,
        var resamples: Int,
    )

    private fun watchForever() {
        // start() 只证明线程拉起来了；这行证明循环真的进去了。
        echo("[T-HANG-DIAG] HangDetector watchLoop entered")
        var loopCount = 0L
        var episode: Episode? = null
        while (true) {
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                return
            }
            loopCount++
            val now = System.currentTimeMillis()
            val gapMs = now - beatAt.get()
            // 守望线程自身存活 ping，约 4 行/分钟，刻意压低音量。
            if (loopCount % 30L == 0L) {
                echo("[T-HANG-DIAG] HangDetector tick=$loopCount sinceHeartbeat=${gapMs}ms")
            }
            episode = if (gapMs < HANG_TRIGGER_MS) {
                episode?.let { closeEpisode(it) } ?: episode
                null
            } else {
                episode?.let { continueEpisode(it, now, gapMs) } ?: openEpisode(now, gapMs)
            }
        }
    }

    /** 发作开始：计数一次 + 首帧采样。 */
    private fun openEpisode(now: Long, gapMs: Long): Episode {
        val fresh = Episode(peakGapMs = gapMs, sampledAt = now, resamples = 0)
        bumpPersistedHangCount(gapMs)
        return fresh
    }

    /** 发作持续：刷新峰值，到步长就补一帧。 */
    private fun continueEpisode(state: Episode, now: Long, gapMs: Long): Episode {
        state.peakGapMs = maxOf(state.peakGapMs, gapMs)
        if (now - state.sampledAt >= RESAMPLE_STEP_MS) {
            state.sampledAt = now
            state.resamples++
            captureSample("mid-hang", gapMs, state.resamples)
        }
        return state
    }

    /** 发作结束：一帧标注 post-recovery 的收口快照（预期闲栈，记录恢复时刻与峰值）。 */
    private fun closeEpisode(state: Episode) {
        captureSample("post-recovery", state.peakGapMs, state.resamples)
        echo(
            "[T-HANG-DIAG] hang episode ENDED peak=${state.peakGapMs}ms midHangSamples=${state.resamples + 1}",
        )
    }

    /**
     * 抓当前主线程栈并双通道落档：完整约 25 帧进 `stall-<日期>.log`；紧凑
     * top-5 行走 stdout+logcat（进 AppLogger 日报），[JankDiag] 标签供 grep。
     * 挂死线程上 getStackTrace 安全且便宜（VM 只暂停目标线程走栈）；本函数
     * 无计数/断路副作用，那在 [bumpPersistedHangCount]。
     */
    private fun captureSample(label: String, durationMs: Long, escalation: Int) {
        val ctx = appContext ?: return
        val mainStack = currentMainThreadStack()
        val renderFields = ContentDiag.currentRenderLogFields()
        val top5 = mainStack.take(5).joinToString(" <- ") { frame ->
            "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
        }
        echo(
            "[T-HANG-DIAG][JankDiag] sample=$label escalation=$escalation duration=${durationMs}ms top5: $top5$renderFields",
        )
        appendStallFile(ctx, label, durationMs, escalation, mainStack)
    }

    private fun currentMainThreadStack(): Array<StackTraceElement> = try {
        Looper.getMainLooper().thread.stackTrace
    } catch (t: Throwable) {
        emptyArray()
    }

    /** stall 文件追加一段采样记录（文件名与各行格式为排障契约，冻结）。 */
    private fun appendStallFile(
        ctx: Context,
        label: String,
        durationMs: Long,
        escalation: Int,
        mainStack: Array<StackTraceElement>,
    ) {
        val now = Date()
        val clock = CLOCK_FMT.format(now)
        val day = DAY_FMT.format(now)
        val body = buildString {
            append("===== HANG @ $clock (duration ~${durationMs}ms) sample=$label escalation=$escalation =====\n")
            append("thread: main\n")
            for (frame in mainStack.take(25)) append("  at $frame\n")
            append("\n")
        }
        try {
            val dir = File(ctx.filesDir, STALL_LOG_DIR).also { it.mkdirs() }
            File(dir, "$STALL_LOG_PREFIX$day.log").appendText(body)
        } catch (t: Throwable) {
            val msg = "[T-HANG-DIAG] FAILED to write stall log: ${t.javaClass.simpleName}: ${t.message}"
            echo(msg)
            Log.w(TAG, msg)
        }
    }

    /** 一次发作的计数与断路副作用。 */
    private fun bumpPersistedHangCount(durationMs: Long) {
        val ctx = appContext ?: return
        // 起报那帧本身就是一次 mid-hang 采样（心跳已 3s 未落且主线程仍卡）。
        captureSample("mid-hang", durationMs, escalation = 0)

        val prefs = hangPrefs(ctx)
        val count = prefs.getInt(KEY_HANG_COUNT, 0) + 1
        prefs.edit()
            .putInt(KEY_HANG_COUNT, count)
            .putLong(KEY_LAST_HANG_AT, System.currentTimeMillis())
            .apply()
        // 比 ANR 击杀提前一次触发渲染降级（基线显示 #2~#3 之间进程即死）。
        if (count >= RENDER_DEGRADE_N && !_renderBreakerActive.value) {
            _renderBreakerActive.value = true
            Log.w(TAG, "render breaker TRIPPED at hang count=$count — streaming markdown degrades to plain text")
        }
        val summary = "hang detected duration=${durationMs}ms count=$count " +
            "breakerActive=${count >= LAUNCH_BREAKER_N}"
        echo("[T-HANG-DIAG] $summary")
        Log.w(TAG, summary)
    }

    // -- 小工具 ------------------------------------------------------------

    private fun hangPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun clearCount(prefs: android.content.SharedPreferences) {
        prefs.edit()
            .putInt(KEY_HANG_COUNT, 0)
            .putLong(KEY_LAST_HANG_AT, 0L)
            .apply()
    }

    private fun seedRenderBreakerFromPersistedCount(context: Context) {
        if (currentHangCount(context) >= RENDER_DEGRADE_N) {
            _renderBreakerActive.value = true
            Log.w(TAG, "render breaker seeded ACTIVE from persisted hang count")
        }
    }

    private fun echoStartBanner() {
        // stdout+logcat 双通道：设置里没开日志时 adb logcat 仍可见；开了日志
        // AppLogger 会接管 System.out 落文件。captureSample 同理。
        val banner = "[T-HANG-DIAG] HangDetector started: threshold=${HANG_TRIGGER_MS}ms " +
            "interval=${HEARTBEAT_PERIOD_MS}ms limit=$LAUNCH_BREAKER_N"
        echo(banner)
        Log.i(TAG, banner)
    }

    private fun echo(line: String) = println(line)
}
