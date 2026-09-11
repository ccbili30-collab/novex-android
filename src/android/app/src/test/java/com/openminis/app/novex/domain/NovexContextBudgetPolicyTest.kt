package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class NovexContextBudgetPolicyTest {
    @Test
    fun remainingWindowIsAvailableAfterHistoryAndOutput() {
        assertEquals(
            104_000,
            NovexContextBudgetPolicy.moduleBudget(
                effectiveWindowTokens = 200_000,
                occupiedTokens = 80_000,
                reservedOutputTokens = 16_000,
            ),
        )
    }

    @Test
    fun millionWindowIsNotSilentlyCappedAt128K() {
        assertEquals(
            868_000,
            NovexContextBudgetPolicy.moduleBudget(
                effectiveWindowTokens = 1_000_000,
                occupiedTokens = 100_000,
                reservedOutputTokens = 32_000,
            ),
        )
    }

    @Test
    fun existingConversationAndOutputReservationAlwaysWinOverModuleRecall() {
        assertEquals(
            4_000,
            NovexContextBudgetPolicy.moduleBudget(
                effectiveWindowTokens = 200_000,
                occupiedTokens = 190_000,
                reservedOutputTokens = 6_000,
            ),
        )
    }
}
