package novex.android.crashguard

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 崩溃风暴检测：替代云端 Crashlytics 的本地降级方案。
 *
 * 启动时扫 `filesDir/logs/` 里最近一小时落盘的崩溃报告（ACRA 的
 * `crash-*.log` 与 NDK 信号处理器的 `native-crash-*.log`），达到
 * [BURST_THRESHOLD] 份即判定"风暴"，随后：
 *  - 挂安全模式（heavy 恢复路径短路，防止检测逻辑自己再崩）；
 *  - 记一段强制回落首页的宽限（哪怕用户关掉分享对话框，也别再落
 *    回可能就是崩溃源的那个会话）；
 *  - 存下文件清单，等下一个前台 Activity 弹分享流程（见
 *    [CrashShareFlow]）。
 *
 * 刻意零应用内依赖（不用 AppLogger、不碰任何仓库）：它必须能在
 * 半初始化的进程里活下来，每个入口都兜 Throwable。SharedPreferences
 * 名与键、时间窗与阈值是冻结面——老用户的存量标记必须继续生效。
 */
object CrashBurstGuard {

    private const val TAG = "CrashBurstGuard"

    /** 滚动判定窗：1 小时内的崩溃数达到阈值算风暴。 */
    private const val WINDOW_MS: Long = 60L * 60L * 1000L

    /** 风暴阈值：两份即触发（移动端崩溃日志成对出现很常见：主因+衍生）。 */
    private const val BURST_THRESHOLD = 2

    /** 强制回落首页的宽限：风暴触发后 1 小时内的冷启动都落首页。 */
    private const val FORCE_HOME_MS: Long = 60L * 60L * 1000L

    /** 用户点「暂不」后的硬抑制：24 小时内不再弹，与窗内新崩溃无关。 */
    private const val SUPPRESS_MS: Long = 24L * 60L * 60L * 1000L

    private const val PREFS = "crash_freq_prefs"
    private const val KEY_DISMISSED_AT = "dismissed_at"
    private const val KEY_FORCE_HOME_UNTIL = "force_home_until"
    private const val KEY_SUPPRESS_UNTIL = "suppress_until"

    /** 待分享的风暴文件清单；扫描未命中时为 null。 */
    @Volatile
    var pendingBurstFiles: List<File>? = null
        private set

    private val safeMode = AtomicBoolean(false)

    private val clearedCallbacks = CopyOnWriteArrayList<() -> Unit>()

    /**
     * 注册「安全模式解除」回调（ChatViewModel 等用它补做被跳过的
     * 初始化）。返回退订函数。回调在置否线程上同步执行，单个回调
     * 抛错不拖累其余。
     */
    @JvmStatic
    fun registerSafeModeCleared(listener: () -> Unit): () -> Unit {
        clearedCallbacks.add(listener)
        return { clearedCallbacks.remove(listener) }
    }

    @JvmStatic
    fun isSafeMode(): Boolean = safeMode.get()

    /**
     * 冷启动是否应强制落首页：风暴触发的宽限仍在有效期时为真。
     * 自过期，过期后用户的「启动进会话」偏好自动恢复主导。
     */
    @JvmStatic
    fun shouldForceHomeOnLaunch(context: Context): Boolean {
        val until = readLong(context, KEY_FORCE_HOME_UNTIL) ?: return false
        return until > 0 && System.currentTimeMillis() < until
    }

    /**
     * Application.onCreate 阶段的扫描入口。永不抛。
     */
    fun scanAtLaunch(app: Application) {
        try {
            val now = System.currentTimeMillis()
            val suppressedUntil = readLong(app, KEY_SUPPRESS_UNTIL) ?: 0L
            if (suppressedUntil > 0 && now < suppressedUntil) {
                pendingBurstFiles = null
                Log.i(
                    TAG,
                    "scanAtLaunch: suppressed (user opted out; " +
                        "${(suppressedUntil - now) / 60_000L} min left)",
                )
                return
            }

            val recent = collectRecentCrashFiles(app, now)
            if (recent == null) return // 目录不可读：保持现状（清单不动）
            Log.i(
                TAG,
                "scanAtLaunch: recent=${recent.size} threshold=$BURST_THRESHOLD",
            )
            if (recent.size < BURST_THRESHOLD) {
                pendingBurstFiles = null
                return
            }

            pendingBurstFiles = recent
            // 先落安全模式再让任何 Activity/VM 恢复会话——顺序反了就会
            // 在用户看到对话框之前再崩一次。
            flipSafeMode(true)
            writeLong(app, KEY_FORCE_HOME_UNTIL, now + FORCE_HOME_MS)
            Log.w(
                TAG,
                "crash burst: ${recent.size} within the hour — safe-mode ON, " +
                    "force-home grace armed, share prompt awaits first Activity",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "scanAtLaunch failed: ${t.message}")
        }
    }

    /** 用户已看到选择器：以此刻为界，之后的崩溃才算下一轮。 */
    internal fun stampDismissCheckpoint(context: Context) {
        writeLong(context, KEY_DISMISSED_AT, System.currentTimeMillis())
    }

    /** 分享流程开走前取走清单（一次性，防二次 resume 重弹）。 */
    internal fun clearPendingBurst() {
        pendingBurstFiles = null
    }

    /** 用户明确「暂不」：硬抑制 24 小时。 */
    internal fun armSuppressWindow(context: Context) {
        val until = System.currentTimeMillis() + SUPPRESS_MS
        writeLong(context, KEY_SUPPRESS_UNTIL, until)
        Log.i(TAG, "user opted out — suppressing prompts until ts=$until")
    }

    /** 分享流程收尾（或异常退出）：解除安全模式并广播之。 */
    internal fun finishSafeMode() {
        flipSafeMode(false)
    }

    /** 单一写入点：ON→OFF 边沿恰好发一次回调。 */
    private fun flipSafeMode(on: Boolean) {
        val wasOn = safeMode.getAndSet(on)
        if (wasOn && !on) {
            Log.i(TAG, "safe-mode OFF, notifying ${clearedCallbacks.size} listener(s)")
            clearedCallbacks.toList().forEach { runCatching { it() } }
        }
    }

    /**
     * 按窗与「用户已看过」界标过滤崩溃文件；目录缺失时返回 null（与
     * "扫了但不够数"区分开——后者要清清单，前者什么都不做）。
     */
    private fun collectRecentCrashFiles(app: Application, now: Long): List<File>? {
        val logsDir = File(app.filesDir, "logs")
        if (!logsDir.isDirectory) {
            pendingBurstFiles = null
            return null
        }
        val candidates = logsDir.listFiles { f -> isCrashLogName(f.name) } ?: return null
        // 界标只在窗内有效：界标本身过了 1 小时就忽略——活窗里的文件
        // 按定义比用户上次看到的更新，不能因一枚旧界标漏掉新风暴。
        val rawDismissed = readLong(app, KEY_DISMISSED_AT) ?: 0L
        val dismissedAfter =
            if (rawDismissed > 0 && now - rawDismissed <= WINDOW_MS) rawDismissed else 0L
        return candidates.filter { file ->
            val mtime = file.lastModified()
            mtime > dismissedAfter && now - mtime <= WINDOW_MS
        }.sortedByDescending { it.lastModified() }
    }

    internal fun isCrashLogName(name: String): Boolean =
        (name.startsWith("crash-") || name.startsWith("native-crash-")) && name.endsWith(".log")

    // ── SharedPreferences 读写（全部兜错，读失败按缺省） ─────────────

    private fun readLong(context: Context, key: String): Long? =
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(key, 0L)
        } catch (t: Throwable) {
            Log.w(TAG, "read $key failed: ${t.message}")
            null
        }

    private fun writeLong(context: Context, key: String, value: Long) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(key, value).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "write $key failed: ${t.message}")
        }
    }
}
