package novex.android.logkit

import android.content.Context
import android.util.Log
import com.openminis.app.diagnostics.EnvironmentBanner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志内核：按天滚动的文件日志 + stdout/stderr 截流 + logcat 尾
 * 随（[LogcatTailPipe]）。旧路径 [com.openminis.app.logging.AppLogger]
 * 门面把全应用约五十处调用点转发到这里。
 *
 * 持久化事实面（冻结）：目录 `filesDir/logs`、日文件名
 * `minis-<yyyy-MM-dd>.log`、行格式 `[HH:mm:ss.SSS] [LEVEL] [cat] msg`
 * 与截流行 `[HH:mm:ss.SSS] [STDOUT|STDERR] line`、logcat 行前缀
 * `[LOGCAT] `、logcat 标签前缀 `Minis.<cat>`、开关持久化于
 * SharedPreferences `logging_prefs` 的 `logging_enabled` 键、15 天保
 * 留期。日志管理屏按文件名排序读这些文件，名字与格式动不得。
 */
object RunLog {

    private const val TAG = "RunLog"
    private const val DIR_NAME = "logs"
    private const val RETENTION_DAYS = 15
    private const val PREF_NAME = "logging_prefs"
    private const val PREF_KEY_ON = "logging_enabled"

    /** 这些类别的 debug 直接丢弃（每帧/每 token 级的噪音源）。 */
    private val silencedDebug = setOf("ChatScrollFollow")

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val clockFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** init 落定的写侧目录；null = 未初始化。 */
    private var writeDir: File? = null

    /**
     * 尽早抓一份应用 Context——安全模式的进程不走 init，但只读接口
     * （列文件/读文件/统计）仍要能凭它找到 logs 目录；那恰恰是用户
     * 去翻崩溃记录的一次启动，读不出来等于白崩。
     */
    @Volatile
    private var readContext: Context? = null

    private var captureOn = false
    private var fileLoggingOn = false

    private var realOut: PrintStream? = null
    private var realErr: PrintStream? = null
    private var tailPipe: LogcatTailPipe? = null

    /** 按天滚动的写出器：记住当天 key，跨天首写自动换文件。 */
    private val sink = DailySink()

    // ── 装配与开关 ───────────────────────────────────────────────────

    /** Application.onCreate 最顶上调用：只记 Context，零副作用。 */
    fun prime(context: Context) {
        if (readContext == null) readContext = context.applicationContext
    }

    /** 完整初始化：建目录、读开关、按开关决定是否开始截流。 */
    fun boot(context: Context) {
        prime(context)
        writeDir = File(context.filesDir, DIR_NAME).also { it.mkdirs() }
        fileLoggingOn = prefs(context).getBoolean(PREF_KEY_ON, false)
        sweepExpired()
        if (fileLoggingOn) beginCapture()
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_KEY_ON, false)

    fun setEnabled(context: Context, on: Boolean) {
        fileLoggingOn = on
        prefs(context).edit().putBoolean(PREF_KEY_ON, on).apply()
        if (on) beginCapture() else endCapture()
    }

    // ── 记录接口 ─────────────────────────────────────────────────────

    fun info(category: String, message: String) = put("INFO", category, message)

    fun warning(category: String, message: String) = put("WARN", category, message)

    fun error(category: String, message: String) = put("ERROR", category, message)

    fun debug(category: String, message: String) {
        if (category in silencedDebug) return
        put("DEBUG", category, message)
    }

    /** logcat 尾随回灌的一行：已由 [put] 记过的（Minis.* 标签）跳过。 */
    fun ingestLogcatLine(raw: String) {
        if (!fileLoggingOn) return
        // 行形 "MM-DD HH:MM:SS.mmm L/Tag(pid): msg"；抽 Tag 防自回环。
        val slash = raw.indexOf('/')
        val paren = if (slash >= 0) raw.indexOf('(', slash) else -1
        if (slash >= 0 && paren > slash) {
            val tag = raw.substring(slash + 1, paren).trim()
            if (tag.startsWith("Minis.") || tag == "RunLog") return
        }
        try {
            sink.append(dayFormat.format(Date()), "[LOGCAT] $raw")
        } catch (_: Exception) {
            // 吞掉：回灌写失败不得再进 logcat，否则死循环。
        }
    }

    // ── 只读接口（日志管理屏） ───────────────────────────────────────

    data class FileFacts(val name: String, val bytes: Long, val modifiedAt: Long)

    fun listFiles(prefix: String = "", limit: Int = Int.MAX_VALUE): List<FileFacts> {
        val dir = readDir() ?: return emptyList()
        val found = dir.listFiles { f ->
            f.extension == "log" && (prefix.isEmpty() || f.name.startsWith(prefix))
        } ?: return emptyList()
        // 先排序截断再逐个 stat：length/lastModified 是独立系统调用，
        // 尾部的不必碰。
        return found.sortedByDescending { it.name }
            .take(limit)
            .map { FileFacts(it.name, it.length(), it.lastModified()) }
    }

    /** 历史接口：返回真实 File 对象（新→旧）。当前无生产调用方。 */
    fun listRawFiles(): List<File> =
        readDir()?.listFiles { f -> f.extension == "log" }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    fun readWholeFile(name: String): String? {
        val target = File(readDir() ?: return null, name)
        return if (target.exists()) target.readText() else null
    }

    fun bytesTotal(): Long = readDir()?.listFiles()?.sumOf { it.length() } ?: 0L

    /** 清空全部日志。删除与句柄重置在写出器同一把锁下，防写半行。 */
    fun wipeFiles() {
        sink.wipe()
    }

    // ── 内部机制 ─────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 只读路径的目录解析：写目录优先，退回由 Context 推导；绝不新建。 */
    private fun readDir(): File? =
        writeDir ?: readContext?.let { File(it.filesDir, DIR_NAME) }

    private fun put(level: String, category: String, message: String) {
        val now = Date()
        when (level) {
            "ERROR" -> Log.e("Minis.$category", message)
            "WARN" -> Log.w("Minis.$category", message)
            "DEBUG" -> Log.d("Minis.$category", message)
            else -> Log.i("Minis.$category", message)
        }
        if (!fileLoggingOn) return
        try {
            val line = "[${clockFormat.format(now)}] [$level] [$category] $message"
            sink.append(dayFormat.format(now), line)
        } catch (e: Exception) {
            Log.w(TAG, "file write failed: ${e.message}")
        }
    }

    @Synchronized
    private fun beginCapture() {
        if (captureOn) return
        if (writeDir == null) return
        if (realOut == null) realOut = System.out
        if (realErr == null) realErr = System.err
        System.setOut(PrintStream(LineTee(realOut!!, "STDOUT"), true))
        System.setErr(PrintStream(LineTee(realErr!!, "STDERR"), true))
        // 先起 logcat 尾随再落会话标记，标记自身即可证明链路通。
        tailPipe = LogcatTailPipe { line -> ingestLogcatLine(line) }.also { it.begin() }
        captureOn = true
        info(TAG, "logging session begins — stdout/stderr + logcat tail")
        // 每个记录会话开头留一份设备/ROM/堆身份，野外日志不再靠猜机型。
        readContext?.let { EnvironmentBanner.log(it) }
    }

    @Synchronized
    private fun endCapture() {
        if (!captureOn) return
        realOut?.let { System.setOut(it) }
        realErr?.let { System.setErr(it) }
        tailPipe?.halt()
        tailPipe = null
        captureOn = false
        sink.reset() // 刷掉缓冲，下次写入重开
    }

    private fun sweepExpired() {
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24L * 60 * 60 * 1000
        writeDir?.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    /**
     * 日写出器：持 PrintWriter，按天 key 换文件。所有文件写路径
     * （追加 / 清空）共用本实例的监视器，互相排斥。
     */
    private class DailySink {
        private var openDay: String = ""
        private var pen: PrintWriter? = null

        @Synchronized
        fun append(day: String, line: String) {
            if (day != openDay || pen == null) {
                reopen(day)
            }
            pen?.println(line)
            pen?.flush()
        }

        /** 删除全部日志文件并重置句柄（FileWriter 攥着已删 inode 会一直写进"僵尸文件"）。 */
        @Synchronized
        fun wipe() {
            writeDir?.listFiles()?.forEach { it.delete() }
            dropPen()
        }

        @Synchronized
        fun reset() {
            dropPen()
        }

        private fun dropPen() {
            try {
                pen?.close()
            } catch (_: Exception) {}
            pen = null
            openDay = ""
        }

        private fun reopen(day: String) {
            dropPen()
            val dir = writeDir ?: throw IllegalStateException("RunLog not booted")
            pen = PrintWriter(FileWriter(File(dir, "minis-$day.log"), true))
            openDay = day
        }
    }

    /**
     * stdout/stderr 的行截流：字节先无条件转发原流（转发不能因记录
     * 失败而丢输出），攒到换行再整行落盘。
     */
    private class LineTee(
        private val downstream: PrintStream,
        private val channel: String,
    ) : OutputStream() {
        private val pending = ByteArrayOutputStream(256)

        override fun write(byte: Int) {
            downstream.write(byte)
            if (byte == '\n'.code) emit() else pending.write(byte)
        }

        override fun write(bytes: ByteArray, from: Int, length: Int) {
            downstream.write(bytes, from, length)
            var lineStart = from
            val end = from + length
            for (i in from until end) {
                if (bytes[i] == '\n'.code.toByte()) {
                    if (i > lineStart) pending.write(bytes, lineStart, i - lineStart)
                    emit()
                    lineStart = i + 1
                }
            }
            if (lineStart < end) pending.write(bytes, lineStart, end - lineStart)
        }

        override fun flush() = downstream.flush()

        private fun emit() {
            val text = try {
                pending.toString("UTF-8")
            } catch (_: Exception) {
                pending.toString()
            }
            pending.reset()
            if (text.isEmpty()) return // 空行不配占一条时间戳
            try {
                val now = Date()
                sink.append(
                    dayFormat.format(now),
                    "[${clockFormat.format(now)}] [$channel] $text",
                )
            } catch (_: Exception) {
                // 截流失败不能打断宿主的 stdout。
            }
        }
    }
}
