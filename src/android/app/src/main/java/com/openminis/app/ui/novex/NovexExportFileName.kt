package com.openminis.app.ui.novex

import java.util.Calendar

/**
 * 导出文件名（用户决策 2026-09-14：什么时候优化导出作品名称 → 本批修）：
 * 「标题_YYYYMMDD.novex.zip」，替代写死的"作品.novex.zip"。标题清洗掉
 * 文件系统非法字符与首尾空白；为空回退"作品"。日期用本地时区当天。
 */
object NovexExportFileName {
    fun build(title: String?, extension: String = ".novex.zip", nowMillis: Long = System.currentTimeMillis()): String {
        val cleaned = title.orEmpty()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000]"), "")
            .trim()
            .take(60)
            .ifEmpty { "作品" }
        val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val date = "%04d%02d%02d".format(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
        return "${cleaned}_$date$extension"
    }
}
