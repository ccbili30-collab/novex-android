package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NovexLearningCoordinatorTest {
    @Test
    fun longRunningLearningStartsOnlyFromAConfirmationBoundToTheCurrentPreflight() {
        val preflight = NovexLearningPreflight.prepare(request())
        val coordinator = NovexLearningCoordinator()

        assertThrows(IllegalArgumentException::class.java) {
            coordinator.start(preflight, confirmation = null)
        }

        val task = coordinator.start(preflight, confirmation(preflight))

        assertEquals(preflight.id, task.preflightId)
        assertEquals(NovexLearningTaskStatus.INDEXING, task.status)
        assertEquals(0, task.usage.usedInputTokens)
    }

    @Test
    fun learningTaskCanPauseResumeAndCancelWithoutLosingItsUsageLedger() {
        val preflight = NovexLearningPreflight.prepare(request())
        val coordinator = NovexLearningCoordinator()
        val started = coordinator.start(preflight, confirmation(preflight))
            .recordUsage(inputTokens = 12_000, outputTokens = 1_200)

        val paused = started.pause()
        val resumed = paused.resume()
        val cancelled = resumed.cancel()

        assertEquals(NovexLearningTaskStatus.PAUSED, paused.status)
        assertEquals(NovexLearningTaskStatus.INDEXING, resumed.status)
        assertEquals(NovexLearningTaskStatus.CANCELLED, cancelled.status)
        assertEquals(12_000, cancelled.usage.usedInputTokens)
        assertThrows(IllegalStateException::class.java) {
            cancelled.recordUsage(inputTokens = 1, outputTokens = 0)
        }
    }

    private fun request() = NovexLearningPreflightRequest(
        collectionRef = NovexResourceRef("novex://source-collections/large"),
        sources = listOf(
            NovexLearningSourceEstimate(
                ref = NovexResourceRef("novex://documents/long"),
                estimatedTokens = 90_000,
            ),
        ),
        modelId = "model-a",
        modelProviderName = "测试模型提供商",
        effectiveContextTokens = 200_000,
        occupiedContextTokens = 20_000,
        directReadBudgetTokens = 12_000,
        proposedBudget = NovexLearningTokenBudget(120_000, 12_000),
    )

    private fun confirmation(preflight: NovexLearningPreflightSnapshot) = NovexLearningConfirmation(
        preflightId = preflight.id,
        modelId = preflight.modelId,
        sourceRefs = preflight.sourceRefs,
        maxInputTokens = preflight.confirmedBudget.inputTokens,
        maxOutputTokens = preflight.confirmedBudget.outputTokens,
        confirmedAtMillis = 1_000,
    )
}
