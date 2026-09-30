package novex.android.logkit

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * logcat 尾随子进程：`logcat --pid=<自身>` 的输出逐行喂给回调。
 * `Log.d/i/w/e` 走 liblog 直通，不经 JVM stdout，System.setOut 截
 * 流看不见——日志要进文件只能开这条管子。只读自家 PID 在 Android 8+
 * 不需要 READ_LOGS 权限。
 *
 * 命令行参数是调过的（冻结）：`-v time -T 1` 从最新一行起读；三个
 * `Tag:S` 静噪高频框架标签（在一块 117MB 的日样本里三家占了约 76%
 * 的行数：View 的 dVRR 每帧刷屏、ViewRootImpl 的帧率档位提示、
 * BLASTBufferQueue 的缓冲队列管理），`*:V` 通配兜底保证其余照常
 * 通过、本应用 `Minis.*` 行不受影响。在命令行层面拦，读线程连字节
 * 都见不到。
 */
internal class LogcatTailPipe(private val onLine: (String) -> Unit) {

    private var child: java.lang.Process? = null
    private var pump: Thread? = null

    @Volatile
    private var halting = false

    @Synchronized
    fun begin() {
        if (child != null) return
        halting = false
        try {
            val proc = ProcessBuilder(commandLine())
                .redirectErrorStream(true)
                .start()
            child = proc
            pump = threadReading(proc).also { it.start() }
        } catch (e: Exception) {
            Log.w("LogcatTailPipe", "logcat spawn failed: ${e.message}")
            child = null
            pump = null
        }
    }

    @Synchronized
    fun halt() {
        halting = true
        try { child?.destroy() } catch (_: Throwable) {}
        child = null
        pump = null
    }

    private fun commandLine(): List<String> = buildList {
        add("logcat")
        add("-v"); add("time")   // MM-DD HH:MM:SS.mmm L/Tag(pid): msg
        add("-T"); add("1")      // 从最新一行起，跳过积压
        add("--pid=${android.os.Process.myPid()}") // 只读自家，防泄他应用日志
        addAll(listOf("View:S", "ViewRootImpl:S", "BLASTBufferQueue_Java:S")) // 高频静噪
        add("*:V")               // 通配兜底：其余标签照常
    }

    private fun threadReading(proc: java.lang.Process): Thread = Thread(
        {
            proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                pumpLines(reader)
            }
        },
        "LogcatTailPipe",
    ).apply { isDaemon = true }

    /** 读到流尾或 halt 置位为止；头部的 --------- 横幅跳过；回调抛错不影响后续行。 */
    private fun pumpLines(reader: BufferedReader) {
        try {
            while (!halting) {
                val line = reader.readLine() ?: break
                if (line.startsWith("---------")) continue
                runCatching { onLine(line) }
            }
        } catch (_: Throwable) {
            // halt() 关流或子进程退出，正常收场。
        }
    }
}
