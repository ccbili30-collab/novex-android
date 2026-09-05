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
        assertEquals(listOf("direct_read"), preflight.plannedSteps)
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
    fun preflightDisclosesRealScopeProviderAndUnknownCostInsteadOfInventingAZeroEstimate() {
        val preflight = NovexLearningPreflight.prepare(
            request(
                sources = listOf(
                    source("novex://documents/long-a", 80_000),
                    source(
                        ref = "novex://wiki-pages/42",
                        estimatedTokens = 30_000,
                        requiresNetwork = true,
                        requiresOcr = true,
                    ),
                ),
                modelProviderName = "测试模型提供商",
            ),
        )

        assertEquals(2, preflight.sourceCount)
        assertEquals(2, preflight.pageCount)
        assertEquals(1, preflight.ocrSourceCount)
        assertEquals(1, preflight.networkSourceCount)
        assertEquals("测试模型提供商", preflight.modelProviderName)
        assertEquals(null, preflight.estimatedCost)
        assertEquals(7, preflight.estimatedDuration.minimumMinutes)
        assertEquals(31, preflight.estimatedDuration.maximumMinutes)
        assertEquals("测试模型提供商", preflight.dataExposure.destination)
        assertTrue(preflight.dataExposure.sourceContentMayLeaveDevice)
        assertEquals("batch_source_excerpts_and_notes", preflight.dataExposure.contentScope)
        assertTrue(preflight.risks.any { it.code == "learning.cost_unknown" })
        assertTrue(preflight.risks.any { it.code == "learning.model_data_transfer" })
        assertTrue(preflight.plannedSteps.containsAll(
            listOf(
                "local_parse",
                "fetch_network_sources",
                "optical_character_recognition",
                "batch_review",
                "deduplicate_classify",
                "synthesize_notes",
            ),
        ))
        assertTrue("create_world" in preflight.prohibitedOutcomes)
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
    fun normalConversationGrowthDoesNotInvalidateAnOtherwiseIdenticalPreflight() {
        val initial = NovexLearningPreflight.prepare(
            request(
                sources = listOf(source("novex://documents/long-a", 90_000)),
                occupiedContextTokens = 20_000,
            ),
        )
        val afterExplanation = NovexLearningPreflight.prepare(
            request(
                sources = listOf(source("novex://documents/long-a", 90_000)),
                occupiedContextTokens = 24_000,
            ),
        )

        assertEquals(initial.id, afterExplanation.id)
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
        modelProviderName: String = "当前模型提供商",
        occupiedContextTokens: Int = 20_000,
    ) = NovexLearningPreflightRequest(
        collectionRef = collectionRef,
        sources = sources,
        modelId = modelId,
        modelProviderName = modelProviderName,
        effectiveContextTokens = 200_000,
        occupiedContextTokens = occupiedContextTokens,
        directReadBudgetTokens = 12_000,
        proposedBudget = NovexLearningTokenBudget(inputTokens = 220_000, outputTokens = 30_000),
    )

    private fun source(
        ref: String,
        estimatedTokens: Int,
        requiresNetwork: Boolean = false,
        requiresOcr: Boolean = false,
    ) = NovexLearningSourceEstimate(
        ref = NovexResourceRef(ref),
        estimatedTokens = estimatedTokens,
        pageCount = 1,
        imageCount = 0,
        requiresNetwork = requiresNetwork,
        requiresOcr = requiresOcr,
    )
}
