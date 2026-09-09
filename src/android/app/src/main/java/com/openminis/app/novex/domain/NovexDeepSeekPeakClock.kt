package com.openminis.app.novex.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** Official schedule checked 2026-09-09, always evaluated in Beijing time. */
object NovexDeepSeekPeakClock {
    const val SOURCE = "https://api-docs.deepseek.com/zh-cn/quick_start/pricing"
    private val beijing = ZoneId.of("Asia/Shanghai")
    enum class Phase { PEAK, OFF_PEAK }
    fun phase(at: Instant): Phase {
        val local = at.atZone(beijing)
        val weekday = local.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        val minute = local.hour * 60 + local.minute
        return if (weekday && (minute in 540 until 720 || minute in 840 until 1080)) Phase.PEAK else Phase.OFF_PEAK
    }
    fun explanation(at: Instant): String =
        "深度求索峰谷计时 · ${if (phase(at) == Phase.PEAK) "峰时" else "谷时"}（北京时间；仅供官方时段参考）"
}
