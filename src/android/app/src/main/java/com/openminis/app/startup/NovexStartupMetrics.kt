package com.openminis.app.startup

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Four stable markers used by the automated launch harness. Values use the
 * process monotonic clock, so wall-clock changes cannot corrupt a run.
 */
object NovexStartupMetrics {
    private const val TAG = "NovexStartupMetric"
    private val processStartedAt = SystemClock.elapsedRealtime()
    private val appFrameReported = AtomicBoolean(false)
    private val homeReadyReported = AtomicBoolean(false)
    private val runtimeReadyReported = AtomicBoolean(false)

    fun reportProcessStart() = report("process_start", 0L)

    fun reportAppFrame() {
        if (appFrameReported.compareAndSet(false, true)) {
            report("app_frame", elapsed())
        }
    }

    fun reportHomeInteractive() {
        if (homeReadyReported.compareAndSet(false, true)) {
            report("home_interactive", elapsed())
        }
    }

    fun reportRuntimeReady() {
        if (runtimeReadyReported.compareAndSet(false, true)) {
            report("runtime_ready", elapsed())
        }
    }

    /** Fine-grained diagnostics; the four public budget markers above remain stable. */
    fun reportStage(marker: String) = report(marker, elapsed())

    private fun elapsed(): Long = SystemClock.elapsedRealtime() - processStartedAt

    private fun report(marker: String, elapsedMs: Long) {
        Log.i(TAG, "marker=$marker elapsed_ms=$elapsedMs")
    }
}
