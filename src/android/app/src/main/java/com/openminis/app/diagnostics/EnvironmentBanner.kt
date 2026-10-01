package com.openminis.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.openminis.app.logging.AppLogger

/**
 * [T-android-mem-probe-trust] 设备/环境一次性横幅（血统清剿 P3.7 就地真
 * 重写；[Env] 行格式冻结）。每个日志会话发一次。
 *
 * ## 为什么要有它
 *
 * 2026-08-15 的现场日志里没有任何设备身份。唯一的硬件线索是 logcat 尾部
 * 一行顺带提到的 `/proc/vivo_rsc/…`。没有横幅，排障时把那份日志错拿去和
 * Pixel 4a 复现对照，还从错配里下了结论——厂商 ROM 恰是最大的承重变量，
 * 而它不可见。
 *
 * 每份现场报告都应仅凭日志回答：什么设备、什么 ROM、多少内存、堆顶多高、
 * 机器是否已处于内存压力。这些决定了在其他硬件上复现出来到底有没有意义。
 *
 * 刻意不含 IMEI/序列号/账号数据——只有机型与 ROM 身份。
 */
object EnvironmentBanner {

    private const val TAG = "Env"

    fun log(context: Context) {
        try {
            emitIdentity()
            emitMemoryProfile(context)
            emitCurrentMemory()
        } catch (t: Throwable) {
            // 诊断件绝不能拖垮启动。
            AppLogger.warning(TAG, "[Env] banner failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun emitIdentity() {
        AppLogger.info(
            TAG,
            "[Env] device=${Build.MANUFACTURER}/${Build.BRAND}/${Build.MODEL} " +
                "device_codename=${Build.DEVICE} product=${Build.PRODUCT} " +
                "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} " +
                "rom=${Build.DISPLAY} " +
                "abi=${Build.SUPPORTED_ABIS.joinToString("|")}",
        )
    }

    private fun emitMemoryProfile(context: Context) {
        val rt = Runtime.getRuntime()
        val mb = 1024L * 1024L

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }

        // memoryClass = 不带 largeHeap 的堆顶；largeMemoryClass = 带
        // android:largeHeap="true" 的堆顶。我们发布 largeHeap=true，所以
        // largeMemoryClass 才是真上限——且它按厂商不同，正是需要知道的那项。
        val memClass = am?.memoryClass ?: -1
        val largeMemClass = am?.largeMemoryClass ?: -1

        AppLogger.info(
            TAG,
            "[Env] heapCeilingMB=${rt.maxMemory() / mb} " +
                "memoryClassMB=$memClass largeMemoryClassMB=$largeMemClass " +
                "deviceTotalRamMB=${mi.totalMem / mb} deviceAvailRamMB=${mi.availMem / mb} " +
                "lowMemThresholdMB=${mi.threshold / mb} deviceLowMemory=${mi.lowMemory} " +
                "cpuCores=${rt.availableProcessors()}",
        )
    }

    private fun emitCurrentMemory() {
        AppLogger.info(TAG, "[Env] memory ${MemorySnapshot.capture().toLogString()}")
    }
}
