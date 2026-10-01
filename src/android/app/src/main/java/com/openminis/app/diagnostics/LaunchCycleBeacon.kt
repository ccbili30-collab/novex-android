package com.openminis.app.diagnostics

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 进程生命周期信标（血统清剿 P3.7 就地真重写，文件与行格式契约冻结）。
 *
 * 每次启动在 `filesDir/logs/launch-beacon.log` 追加一行信标，使下次启动能把
 * 上一轮周期归因到四类之一：
 * - `clean_exit`——上一轮显式退出留了标记；
 * - `crash_or_stall (…)`——窗口内出现过 crash-/native-crash-/stall- 工件；
 * - `silent_kill (uptime_was=…)`——既无干净退出也无崩溃工件，指向 LMK /
   MIUI 后台清理；
 * - `first_launch` / `no_prior_launch`——无历史可判。
 *
 * 信标行很小且不滚动——长历史正好用来横跨数日观察 MIUI 击杀模式。
 *
 * 消费方：聊天恢复横幅（[lastCycleWasCrash] 时要求二次确认再续载，防止
 * 「一键续上刚才杀死进程的那份负载」）；启动断路器
 * （[shouldForceHomeOnLaunch]）。文件名、行格式、判词串、[Perf][LongCtx]
 * 结构化行均为现场排障契约，逐字保留。
 */
object LaunchCycleBeacon {

    private const val FILE_NAME = "launch-beacon.log"
    private const val TAG = "LaunchBeacon"

    /** 信标尾部读取窗口：16 KB 足够覆盖最近若干次启动记录。 */
    private const val TAIL_BYTES = 16 * 1024

    private val SESSION_ID_IN_STALL = Regex("session[=:]\\s*([0-9a-fA-F-]{8,})")

    /**
     * 上一轮周期是否以 crash_or_stall 收场（[recordLaunch] 时定一次）。
     * 聊天恢复横幅消费：ANR 重启循环里，无守卫的「继续」按钮会把用户直接
     * 送回杀死上一轮进程的那份加载——有此旗标后先警告、要点第二次才继续。
     */
    @Volatile
    var lastCycleWasCrash: Boolean = false
        private set

    /**
     * 最近一次 [recordLaunch] 算得的滚动重启计数（launches − clean_exits，
     * 仅 crash_or_stall 分支写入）。进程作用域：非 crash 判词（含日常占比
     * 最高的 silent_kill）下一次启动即归零，恢复正常自动恢复——崩溃循环后
     * 干净退出一次（甚至被 MIUI 杀一次）的用户，再点开就是正常行为了。
     */
    @Volatile
    var lastRestartCount: Int = 0
        private set

    /**
     * 「跳过自动恢复」断路器阈值：上一轮 crash_or_stall 且计数**严格大于**
     * 此值时，启动落会话列表而非自动续载上一个聊天。规格里的「> 3」即第四
     * 个连续坏周期才触发，给用户留三次自由重试（瞬时 OOM 之类可能不再现）。
     */
    const val RESTART_COUNT_FORCE_HOME_THRESHOLD: Int = 3

    /**
     * 与 HangDetector / CrashFrequencyDetector 的断路器并联（任一翻转即落
     * 首页，其余互不干扰）。clean_exit / silent_kill / first_launch /
     * no_prior_launch 用户恒 false（计数只在 crash_or_stall 分支写）。
     */
    fun shouldForceHomeOnLaunch(): Boolean =
        lastCycleWasCrash && lastRestartCount > RESTART_COUNT_FORCE_HOME_THRESHOLD

    /** MinisApp.onCreate 记一行信标并归因上一周期。 */
    fun recordLaunch(context: Context) {
        val beacon = beaconFile(context)
        val tail = tailOf(beacon)
        val now = System.currentTimeMillis()

        val verdict = classifyPriorCycle(tail, context, now)
        val crashed = verdict.startsWith(VERDICT_CRASH)
        lastCycleWasCrash = crashed
        AppLogger.info(TAG, "launch verdict for previous cycle: $verdict")

        if (crashed) {
            noteCrashCycle(verdict, tail, context)
        } else {
            // 非 crash 判词显式归零：命中过断路器的用户只要有一个正常周期，
            // 下次启动就回到正常自动恢复。
            lastRestartCount = 0
        }

        appendBeacon(beacon, "[${isoLocal(now)}] launch pid=${android.os.Process.myPid()}")
    }

    /** 显式退出路径（onDestroy 等）留干净退出标记。 */
    fun recordCleanExit(context: Context) {
        appendBeacon(
            beaconFile(context),
            "[${isoLocal(System.currentTimeMillis())}] clean_exit pid=${android.os.Process.myPid()}",
        )
    }

    // -- crash 分支的富化 ---------------------------------------------------

    /**
     * crash_or_stall 分支：滚动计数 + 结构化 Perf 行。低内存 ANR 复现可拿它
     * 与恢复循环对时——计数爬升就是「恢复一直栽在同一个会话上」的特征。
     */
    private fun noteCrashCycle(verdict: String, tail: String, context: Context) {
        lastRestartCount = recentBadCycles(tail)
        val sessionId = latestStallSessionId(context)
        AppLogger.warning(
            TAG,
            "[Perf][LongCtx] step=launchBeacon.crashOrStall " +
                "reason=${verdict.substringBefore(' ')} " +
                "detail=${verdict.substringAfter('(', "").substringBefore(')')} " +
                "restartCount=$lastRestartCount lastSessionId=$sessionId",
        )
    }

    /**
     * 尾部信标里「launch 行数 − 其后紧跟 clean_exit 的行数」的粗粒度连续坏
     * 周期估计。不精确，但跨启动看趋势足够——上升值就是恢复循环的旗。
     */
    private fun recentBadCycles(tail: String): Int {
        val rows = tail.split('\n').filter { it.isNotBlank() }
        val launches = rows.count { " launch " in it }
        val exits = rows.count { " clean_exit " in it }
        return (launches - exits).coerceAtLeast(0)
    }

    /**
     * 尽力而为读最近一份 stall-*.log 头部的会话 id（HangDetector 会把会话
     * id 写进 stall 报告）；读不到返回 "unknown"，保日志行字段在位不抛。
     */
    private fun latestStallSessionId(context: Context): String = try {
        val stall = File(context.filesDir, "logs").listFiles()
            ?.filter { it.name.startsWith("stall-") }
            ?.maxByOrNull { it.lastModified() }
            ?: return "unknown"
        val head = stall.bufferedReader().use { it.readText().take(2000) }
        SESSION_ID_IN_STALL.find(head)?.groupValues?.get(1) ?: "unknown"
    } catch (_: Throwable) {
        "unknown"
    }

    // -- 上一周期归因 -------------------------------------------------------

    private const val VERDICT_CRASH = "crash_or_stall"

    private fun classifyPriorCycle(tail: String, context: Context, now: Long): String {
        if (tail.isBlank()) return "first_launch"
        val rows = tail.split('\n').filter { it.isNotBlank() }
        val lastLaunchRow = rows.lastOrNull { " launch " in it } ?: return "no_prior_launch"

        // 最近一次 launch 之后若跟着 clean_exit，则上一周期干净收场。
        val cleanAfterLaunch = rows
            .takeLast(rows.size - (rows.indexOf(lastLaunchRow) + 1))
            .any { " clean_exit " in it }
        if (cleanAfterLaunch) return "clean_exit"

        // 在「上次 launch → 现在」窗口里找崩溃/卡顿工件。
        val launchAt = timestampOf(lastLaunchRow) ?: return "ambiguous_no_timestamp"
        val logsDir = File(context.filesDir, "logs")
        val artefacts = logsDir.listFiles().orEmpty().filter { f ->
            val n = f.name
            (n.startsWith("crash-") || n.startsWith("native-crash-") || n.startsWith("stall-")) &&
                f.lastModified() in launchAt..now
        }
        if (artefacts.isNotEmpty()) {
            return "crash_or_stall (${artefacts.joinToString { it.name }})"
        }
        // 无工件也无干净退出：OOM / LMK / MIUI 后台清理之属。
        return "silent_kill (uptime_was=${now - launchAt}ms)"
    }

    // -- 信标文件 IO --------------------------------------------------------

    private fun beaconFile(context: Context): File =
        File(File(context.filesDir, "logs").also { it.mkdirs() }, FILE_NAME)

    /** 尾部 16 KB，找上一条 launch 行足够。 */
    private fun tailOf(file: File): String {
        if (!file.exists() || file.length() == 0L) return ""
        return try {
            val len = file.length()
            val from = (len - TAIL_BYTES).coerceAtLeast(0)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(from)
                val buf = ByteArray((len - from).toInt())
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "tail read failed: ${t.message}")
            ""
        }
    }

    private fun appendBeacon(file: File, line: String) {
        try {
            file.appendText(line + "\n")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "append failed: ${t.message}")
        }
    }

    // -- 时间戳 -------------------------------------------------------------

    /** 信标行形如 `[2026-05-13T01:23:45.678] launch pid=…`。 */
    private fun timestampOf(line: String): Long? {
        val close = line.indexOf(']')
        if (!line.startsWith("[") || close <= 0) return null
        return try {
            localIsoFormat().parse(line.substring(1, close))?.time
        } catch (_: Throwable) {
            null
        }
    }

    private fun isoLocal(ms: Long): String = localIsoFormat().format(Date(ms))

    private fun localIsoFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
        .apply { timeZone = TimeZone.getDefault() }
}
