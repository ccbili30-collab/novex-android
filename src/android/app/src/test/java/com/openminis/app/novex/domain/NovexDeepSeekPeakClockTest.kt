package com.openminis.app.novex.domain

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class NovexDeepSeekPeakClockTest {
    private fun phase(beijing: String) = NovexDeepSeekPeakClock.phase(LocalDateTime.parse(beijing).atZone(ZoneId.of("Asia/Shanghai")).toInstant())
    @Test fun weekdayBoundariesUseInclusiveStartAndExclusiveEnd() {
        val peak = NovexDeepSeekPeakClock.Phase.PEAK
        val off = NovexDeepSeekPeakClock.Phase.OFF_PEAK
        mapOf("08:59:59" to off, "09:00:00" to peak, "11:59:59" to peak, "12:00:00" to off,
            "13:59:59" to off, "14:00:00" to peak, "17:59:59" to peak, "18:00:00" to off).forEach { (time, expected) ->
            assertEquals(time, expected, phase("2026-09-09T$time"))
        }
    }
    @Test fun bothWeekendDaysAreEntirelyOffPeak() {
        listOf("2026-09-12", "2026-09-13").forEach { day -> (0..23).forEach { hour ->
            assertEquals(NovexDeepSeekPeakClock.Phase.OFF_PEAK, phase("${day}T${hour.toString().padStart(2, '0')}:30:00"))
        } }
        assertEquals(NovexDeepSeekPeakClock.Phase.PEAK, phase("2026-09-14T09:00:00"))
    }
    @Test fun deviceTimeZoneDoesNotMoveOfficialBillingPeriods() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals(NovexDeepSeekPeakClock.Phase.PEAK, NovexDeepSeekPeakClock.phase(Instant.parse("2026-09-09T01:00:00Z")))
        } finally { TimeZone.setDefault(previous) }
    }
}
