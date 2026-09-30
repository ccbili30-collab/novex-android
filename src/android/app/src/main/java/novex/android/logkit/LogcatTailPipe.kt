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
    private var readerThread: Thread? = null

    @Volatile
    private var halting = false

    @Synchronized
    fun begin() {
        if (child != null) return
        halting = false
        try {
            val pid = android.os.Process.myPid().toString()
            val proc = ProcessBuilder(
                "logcat", "-v", "time", "-T", "1", "--pid=$pid",
                "View:S", "ViewRootImpl:S", "BLASTBufferQueue_Java:S", "*:V",
            ).redirectErrorStream(true).start()
            child = proc
            val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
            readerThread = Thread({
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (halting) break
                        if (line.startsWith("---------")) continue // 头部横幅
                        try { onLine(line) } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {
                    // halt() 关流或子进程退出，正常收场。
                } finally {
                    try { reader.close() } catch (_: Throwable) {}
                }
            }, "LogcatTailPipe").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            Log.w("LogcatTailPipe", "logcat spawn failed: ${e.message}")
            child = null
            readerThread = null
        }
    }

    @Synchronized
    fun halt() {
        halting = true
        try { child?.destroy() } catch (_: Throwable) {}
        child = null
        readerThread = null
    }
}
