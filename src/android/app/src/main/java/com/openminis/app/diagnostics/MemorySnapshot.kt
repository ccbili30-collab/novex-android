package com.openminis.app.diagnostics

import android.os.Debug
import java.io.File

/**
 * [T-android-mem-probe-trust] 可信的进程内存读数（血统清剿 P3.7 就地真
 * 重写；字段名与 toLogString 输出为日志契约冻结面）。
 *
 * ## 为什么要有它
 *
 * 2026-08-15 的现场日志报出 `nativeHeapMB=9744`（9.7 GB），来自
 * `Debug.getNativeHeapAllocatedSize()`，设备 6 GB 内存、512 MB 堆顶。17 条
 * 消息（23 KB 文本）与 1528 条消息的会话读数同为数 GB——数字完全不追踪
 * 工作量。它被读成「native 堆爆炸 → OOM 击杀」，据此立项的请求体序列化
 * 修复整轮带偏。
 *
 * 该 API 下探平台分配器，部分厂商 ROM（现场为 vivo，logcat 尾部有
 * `/proc/vivo_rsc/…`）返回的是累计值或地址空间值而非存活字节；Pixel 4a
 * 同调用在更大负载下只报 23 MB——跨设备不可比，不能作为日志里的唯一数字。
 *
 * ## 现在报什么
 *
 * `/proc/self/status` 是内核自己的账本，跨厂商一致：[rssMB] 是常驻内存，
 * 回答「进程是否大到被 LMK 击杀」就看它。Java 堆来自 `Runtime`，处处可靠。
 * legacy native 数字仍输出，但显式改名 `nativeRawMB`，没人能再把它当真相。
 *
 * 全部读数廉价：两次 `Runtime` 调用 + 一个小 proc 文件，除解析出的 long 外
 * 零分配。按时间线逐步采样安全。
 */
data class MemorySnapshot(
    /** 存活 Java 堆 MB（`totalMemory - freeMemory`）。可靠。 */
    val javaHeapMB: Long,
    /** Java 堆顶 MB（`maxMemory`）。Java 侧 OOM 线。 */
    val javaHeapMaxMB: Long,
    /** /proc/self/status 的常驻集 MB。内核口径；不可读时 -1。 */
    val rssMB: Long,
    /** 峰值 RSS（VmHWM）MB——回落不丢峰，尖刺仍可见。 */
    val rssPeakMB: Long,
    /** 虚拟大小（VmSize）MB。偏大是常态，不是泄漏信号。 */
    val vmSizeMB: Long,
    /** `Debug.getNativeHeapAllocatedSize()` 的原始值。**不可信**，见类注释。 */
    val nativeHeapRawMB: Long,
) {
    /** Java 堆顶占用比 0..1。真正的 OOM 先导指标。 */
    val javaHeapUsedFraction: Double
        get() = if (javaHeapMaxMB > 0) javaHeapMB.toDouble() / javaHeapMaxMB else 0.0

    /**
     * 紧凑日志形。legacy native 值刻意命名 `nativeRaw` 而非 `nativeHeapMB`，
     * 防止旧 grep 静默命中、重演当初的误读。
     */
    fun toLogString(): String =
        "javaHeapMB=$javaHeapMB/$javaHeapMaxMB (${(javaHeapUsedFraction * 100).toInt()}%) " +
            "rssMB=$rssMB peakRssMB=$rssPeakMB vmSizeMB=$vmSizeMB nativeRawMB=$nativeHeapRawMB"

    companion object {
        /** Java 堆占用超过此比例即算有压力。 */
        const val HEAP_PRESSURE_FRACTION = 0.80

        fun capture(): MemorySnapshot {
            val vm = readProcStatus()
            val rt = Runtime.getRuntime()
            val mb = 1024L * 1024L
            return MemorySnapshot(
                javaHeapMB = (rt.totalMemory() - rt.freeMemory()) / mb,
                javaHeapMaxMB = rt.maxMemory() / mb,
                rssMB = vm.rssKb.kbToMb(),
                rssPeakMB = vm.peakKb.kbToMb(),
                vmSizeMB = vm.sizeKb.kbToMb(),
                nativeHeapRawMB = Debug.getNativeHeapAllocatedSize() / mb,
            )
        }

        /** 三项都缺读时保持 -1（MB 口径同缺省值）。 */
        private fun Long.kbToMb(): Long = if (this >= 0) this / 1024 else -1

        private class ProcStatus(val rssKb: Long, val peakKb: Long, val sizeKb: Long)

        private fun readProcStatus(): ProcStatus {
            var rss = -1L
            var peak = -1L
            var size = -1L
            try {
                // 单遍扫描，三项齐了即停。VmHWM 与 VmRSS 在文件顶部相邻，
                // 实际只读几行。
                File("/proc/self/status").useLines { lines ->
                    for (line in lines) {
                        when {
                            line.startsWith("VmRSS:") -> rss = kbValue(line)
                            line.startsWith("VmHWM:") -> peak = kbValue(line)
                            line.startsWith("VmSize:") -> size = kbValue(line)
                        }
                        if (rss >= 0 && peak >= 0 && size >= 0) break
                    }
                }
            } catch (_: Throwable) {
                // 部分加固 ROM 限制 /proc/self：带 -1 落下去，别把 Java 堆
                // 数字一起丢了。
            }
            return ProcStatus(rss, peak, size)
        }

        /** "VmRSS:\t  123456 kB" → 123456。 */
        private fun kbValue(line: String): Long =
            line.filter { it.isDigit() }.toLongOrNull() ?: -1L
    }
}
