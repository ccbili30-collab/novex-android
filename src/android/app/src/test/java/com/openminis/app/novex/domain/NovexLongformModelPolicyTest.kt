package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexLongformModelPolicyTest {
    @Test
    fun unknownWindowNeverPretendsLongformSupportIsVerified() {
        val status = NovexLongformModelPolicy.evaluate(null)

        assertEquals(NovexLongformModelTier.UNKNOWN, status.tier)
        assertFalse(status.meetsMinimum)
        assertEquals(0, status.moduleBudgetTokens)
        assertTrue(status.guidance.contains("未确认"))
    }

    @Test
    fun oneHundredTwentyEightThousandWindowHasAnExplicitDowngrade() {
        val status = NovexLongformModelPolicy.evaluate(
            effectiveWindowTokens = 128_000,
            occupiedTokens = 32_000,
            reservedOutputTokens = 16_000,
        )

        assertEquals(NovexLongformModelTier.LIMITED, status.tier)
        assertFalse(status.meetsMinimum)
        assertEquals(38_400, status.moduleBudgetTokens)
        assertTrue(status.guidance.contains("不足 200K"))
        assertTrue(status.guidance.contains("更早蒸馏"))
    }

    @Test
    fun twoHundredThousandAndOneMillionWindowsReportDifferentCapabilities() {
        val standard = NovexLongformModelPolicy.evaluate(
            effectiveWindowTokens = 200_000,
            occupiedTokens = 80_000,
            reservedOutputTokens = 16_000,
        )
        val extended = NovexLongformModelPolicy.evaluate(
            effectiveWindowTokens = 1_000_000,
            occupiedTokens = 200_000,
            reservedOutputTokens = 32_000,
        )

        assertEquals(NovexLongformModelTier.STANDARD, standard.tier)
        assertTrue(standard.meetsMinimum)
        assertEquals(60_000, standard.moduleBudgetTokens)
        assertEquals(NovexLongformModelTier.EXTENDED, extended.tier)
        assertTrue(extended.meetsMinimum)
        assertEquals(128_000, extended.moduleBudgetTokens)
    }
}
