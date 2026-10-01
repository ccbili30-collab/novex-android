package com.openminis.app.crash

import android.content.Context
import org.acra.ReportField
import org.acra.config.CoreConfiguration
import org.acra.data.CrashReportData
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * JVM 崩溃落盘发送器（ACRA SPI 件）。
 *
 * 设计约束（血统清剿 P3.7 就地重写，契约面冻结）：
 * - 类名与包路径由 `META-INF/services/org.acra.sender.ReportSenderFactory`
 *   以全限定名登记，**不可改名不可挪包**——ACRA 在独立 `:acra` 进程里经
 *   ServiceLoader 反射实例化 [CrashFileSenderFactory]，编译期无人直引。
 * - 落盘文件名前缀 `crash-` 与扩展名 `.log` 是 logkit.RunLog 文件契约的
 *   一部分（RunLogFileContractTest 钉死），改名会让报告从日志管理屏消失。
 * - 报告正文各行格式保持逐字不变：这是用户可读的崩溃报告契约，跨版本
 *   对比排障时格式漂移会制造噪音。
 * - 纯本地文件输出，不联网、不引 HTTP sender。
 */
class CrashFileSender : ReportSender {

    override fun send(context: Context, errorContent: CrashReportData) {
        val reportDir = reportDirectory(context)
        val stamp = timeStamp()
        reportDir.resolve("crash-$stamp.log").writeText(composeReport(stamp, errorContent))
    }

    /** 报告目录：`filesDir/logs`，随建随用。 */
    private fun reportDirectory(context: Context): File =
        File(context.filesDir, LOGS_DIRNAME).apply { mkdirs() }

    /** 拼装整份文本报告：头部摘要 → 堆栈 → logcat 尾巴（有才附）。 */
    private fun composeReport(stamp: String, report: CrashReportData): String {
        val sections = mutableListOf<String>()
        sections += headerSection(stamp, report)
        sections += stackSection(report)
        logcatSection(report)?.let { sections += it }
        return sections.joinToString("\n\n") + "\n"
    }

    /** 头部五行摘要（节内不加尾换行，分节统一由 [composeReport] 的空行分隔）。 */
    private fun headerSection(stamp: String, report: CrashReportData): String = listOf(
        "=== Minis Java/Kotlin Crash ===",
        "Time: $stamp",
        "Version: ${report.valueOf(ReportField.APP_VERSION_NAME)} " +
            "(${report.valueOf(ReportField.APP_VERSION_CODE)})",
        "Android: ${report.valueOf(ReportField.ANDROID_VERSION)} " +
            "(SDK ${report.valueOf(ReportField.BUILD)})",
        "Device: ${report.valueOf(ReportField.PHONE_MODEL)} " +
            "(${report.valueOf(ReportField.BRAND)})",
    ).joinToString("\n")

    private fun stackSection(report: CrashReportData): String = buildString {
        appendLine("--- Stack Trace ---")
        append(report.valueOf(ReportField.STACK_TRACE))
    }

    /** ACRA 未采到 logcat 时整节省略，不输出空标题。 */
    private fun logcatSection(report: CrashReportData): String? {
        val tail = report.valueOf(ReportField.LOGCAT) ?: return null
        if (tail.isBlank()) return null
        return "--- Logcat (last 200 lines) ---\n$tail"
    }

    private fun CrashReportData.valueOf(field: ReportField): String? = getString(field)

    private fun timeStamp(): String =
        SimpleDateFormat(STAMP_PATTERN, Locale.US).format(Date())

    private companion object {
        const val LOGS_DIRNAME = "logs"

        /** 与 logkit 日报 `minis-YYYY-MM-DD.log` 同族命名，日志管理屏按名倒序自然混排。 */
        const val STAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss"
    }
}

/**
 * ACRA 经 ServiceLoader 发现的发送器工厂（`:acra` 进程内实例化）。
 * 全限定名登记在 META-INF/services，改名即断链。
 */
class CrashFileSenderFactory : ReportSenderFactory {
    override fun create(context: Context, config: CoreConfiguration): ReportSender =
        CrashFileSender()

    override fun enabled(config: CoreConfiguration): Boolean = true
}
