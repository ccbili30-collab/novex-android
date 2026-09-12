package com.openminis.app.crash

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.Executors

/** Recover system evidence after a native abort; never run disk or binder work on the UI thread. */
object ProcessExitEvidence {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "process-exit-evidence").apply { isDaemon = true }
    }
    private val events = Executors.newSingleThreadExecutor { task ->
        Thread(task, "navigation-evidence").apply { isDaemon = true }
    }
    private val collected = java.util.concurrent.atomic.AtomicBoolean(false)
    fun record(context: Context, event: String) {
        val app = context.applicationContext
        events.execute {
            runCatching {
                val dir = File(app.filesDir, "logs").apply { mkdirs() }
                val file = File(dir, "navigation-lifecycle.log")
                if (file.length() > 256 * 1024) {
                    val previous = File(dir, "navigation-lifecycle-previous.log")
                    previous.delete()
                    file.renameTo(previous)
                }
                file.appendText("${System.currentTimeMillis()} pid=${android.os.Process.myPid()} ${event.replace('\n', ' ').take(400)}\n")
            }
        }
    }
    fun collect(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !collected.compareAndSet(false, true)) return
        val app = context.applicationContext
        worker.execute {
            runCatching {
                val manager = app.getSystemService(ActivityManager::class.java)
                val dir = File(app.filesDir, "logs").apply { mkdirs() }
                manager.getHistoricalProcessExitReasons(app.packageName, 0, 8).forEach { exit ->
                    val file = File(dir, "process-exit-${exit.timestamp}-${exit.pid}.log")
                    if (!file.exists()) {
                        val body = "timestamp=${exit.timestamp}\npid=${exit.pid}\nprocess=${exit.processName}\nreason=${exit.reason}\nstatus=${exit.status}\nimportance=${exit.importance}\npssKb=${exit.pss}\nrssKb=${exit.rss}\ndescription=${exit.description}\n"
                        val temporary = File(dir, file.name + ".tmp")
                        temporary.writeText(body)
                        if (!temporary.renameTo(file)) temporary.delete()
                    }
                    // Native traces can be binary protobufs. Preserve bytes; do not decode as text.
                    val trace = File(dir, "process-exit-${exit.timestamp}-${exit.pid}-trace.log")
                    if (!trace.exists()) runCatching {
                        exit.traceInputStream?.use { input ->
                            val temporary = File(dir, trace.name + ".tmp")
                            try {
                                temporary.outputStream().use { output ->
                                    val buffer = ByteArray(8192)
                                    var remaining = 4 * 1024 * 1024
                                    while (remaining > 0) {
                                        val n = input.read(buffer, 0, minOf(buffer.size, remaining))
                                        if (n < 0) break
                                        output.write(buffer, 0, n)
                                        remaining -= n
                                    }
                                }
                                if (!temporary.renameTo(trace)) temporary.delete()
                                file.appendText("traceByteLimit=4194304; trace may be truncated at this limit\n")
                            } finally { temporary.delete() }
                        }
                    }
                }
                dir.listFiles()?.filter { it.name.startsWith("process-exit-") }
                    ?.sortedByDescending { it.lastModified() }?.drop(32)?.forEach { it.delete() }
            }
        }
    }
}
