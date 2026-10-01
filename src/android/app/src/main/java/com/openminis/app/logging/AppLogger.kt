package com.openminis.app.logging

import android.content.Context
import java.io.File
import novex.android.logkit.RunLog

/**
 * 应用日志门面（P3.5b 重写）。日文件滚动、stdout/stderr 截流与
 * logcat 尾随在 [novex.android.logkit.RunLog]（+ LogcatTailPipe）；
 * 全仓约五十个调用点（含 LogManagementScreen 的嵌套类型引用）经本
 * 门面原名转发。
 *
 * 持久化事实面（冻结，见实现侧 KDoc）：目录 filesDir/logs、日文件
 * 名 minis-<date>.log、prefs logging_prefs/logging_enabled、行格式与
 * logcat 标签前缀 Minis.*。
 */
object AppLogger {

    /** 日志管理屏直接引用的行元数据类型；字段形状冻结。 */
    data class LogFileMeta(
        val name: String,
        val sizeBytes: Long,
        val lastModified: Long,
    )

    fun primeContext(context: Context) = RunLog.prime(context)

    fun init(context: Context) = RunLog.boot(context)

    fun isEnabled(context: Context): Boolean = RunLog.isEnabled(context)

    fun setEnabled(context: Context, value: Boolean) = RunLog.setEnabled(context, value)

    fun info(category: String, message: String) = RunLog.info(category, message)

    fun warning(category: String, message: String) = RunLog.warning(category, message)

    fun error(category: String, message: String) = RunLog.error(category, message)

    fun debug(category: String, message: String) = RunLog.debug(category, message)

    fun listLogFiles(): List<File> = RunLog.listRawFiles()

    fun listLogFileMetas(prefix: String, limit: Int): List<LogFileMeta> =
        RunLog.listFiles(prefix, limit).map { LogFileMeta(it.name, it.bytes, it.modifiedAt) }

    fun readLog(filename: String): String? = RunLog.readWholeFile(filename)

    fun clearLogs() = RunLog.wipeFiles()

    fun totalSize(): Long = RunLog.bytesTotal()
}
