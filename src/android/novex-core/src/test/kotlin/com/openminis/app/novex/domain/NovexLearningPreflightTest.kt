package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexLearningPreflightTest {
    private val collectionRef = NovexResourceRef("novex://source-collections/research-1")

    @Test
    fun smallLocalSourceCanBeReadDirectlyWithoutStartingALearningTask() {
        val preflight = NovexLearningPreflight.prepare(
            request(
                sources = listOf(source("novex://documents/short", estimatedTokens = 2_400)),
            ),
        )

        assertEquals(NovexLearningRoute.DIRECT_READ, preflight.route)
        assertFalse(preflight.requiresConfirmation)
        assertEquals(NovexLearningTaskStatus.NOT_STARTED, preflight.taskStatus)
    }

    @Test
    fun largeOrNetworkSourceWaitsForConfirmationWithoutExecutingWork() {
        val preflight = NovexLearningPreflight.prepare(
            request(
                sources = listOf(
                    source("novex://documents/long-a", 80_000),
                    source("novex://documents/long-b", 75_000),
                    source("novex://wiki-pages/42", 30_000, requiresNetwork = true),
                ),
            ),
        )

        assertEquals(NovexLearningRoute.CONFIRMATION_REQUIRED, preflight.route)
        assertTrue(preflight.requiresConfirmation)
        assertEquals(NovexLearningTaskStatus.NOT_STARTED, preflight.taskStatus)
        assertTrue(preflight.risks.any { it.code == "learning.high_token_use" })
        assertTrue(preflight.risks.any { it.code == "learning.network_access" })
    }

    @Test
    fun changingFilesOrModelInvalidatesThePreviousConfirmationSnapshot() {
        val initial = NovexLearningPreflight.prepare(
            request(sources = listOf(source("novex://documents/long-a", 90_000))),
        )
        val confirmation = NovexLearningConfirmation(
            preflightId = initial.id,
            modelId = initial.modelId,
            sourceRefs = initial.sourceRefs,
            maxInputTokens = initial.confirmedBudget.inputTokens,
            maxOutputTokens = initial.confirmedBudget.outputTokens,
            confirmedAtMillis = 1_000,
        )
        val changed = NovexLearningPreflight.prepare(
            request(
                modelId = "model-b",
                sources = listOf(
                    source("novex://documents/long-a", 90_000),
                    source("novex://documents/new", 10_000),
                ),
            ),
        )

        assertNotEquals(initial.id, changed.id)
        assertEquals(
            NovexLearningAuthorization.STALE_CONFIRMATION,
            NovexLearningGate.authorize(changed, confirmation),
        )
    }

    @Test
    fun confirmedUsageBudgetPausesBeforeAnyUnapprovedOverrun() {
        val preflight = NovexLearningPreflight.prepare(
            request(sources = listOf(source("novex://documents/long-a", 90_000))),
        )
        val confirmation = NovexLearningConfirmation(
            preflightId = preflight.id,
            modelId = preflight.modelId,
            sourceRefs = preflight.sourceRefs,
            maxInputTokens = 100_000,
            maxOutputTokens = 10_000,
            confirmedAtMillis = 1_000,
        )
        val ledger = NovexLearningUsageLedger.start(preflight, confirmation)
            .record(inputTokens = 96_000, outputTokens = 8_000)
            .record(inputTokens = 4_000, outputTokens = 2_000)

        assertEquals(NovexLearningTaskStatus.PAUSED_BUDGET_REACHED, ledger.status)
        assertFalse(ledger.canConsume(inputTokens = 1, outputTokens = 0))
        assertEquals(100_000, ledger.usedInputTokens)
        assertEquals(10_000, ledger.usedOutputTokens)
    }

    private fun request(
        sources: List<NovexLearningSourceEstimate>,
        modelId: String = "model-a",
    ) = NovexLearningPreflightRequest(
        collectionRef = collectionRef,
        sources = sources,
        modelId = modelId,
        effectiveContextTokens = 200_000,
        occupiedContextTokens = 20_000,
        directReadBudgetTokens = 12_000,
        proposedBudget = NovexLearningTokenBudget(inputTokens = 220_000, outputTokens = 30_000),
    )

    private fun source(
        ref: String,
        estimatedTokens: Int,
        requiresNetwork: Boolean = false,
    ) = NovexLearningSourceEstimate(
        ref = NovexResourceRef(ref),
        estimatedTokens = estimatedTokens,
        pageCount = 1,
        imageCount = 0,
        requiresNetwork = requiresNetwork,
    )
}
