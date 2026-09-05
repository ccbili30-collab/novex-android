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

    @Test
    fun budgetStoppedTaskContinuesOnlyAfterAConfirmedLargerPreflightWithoutLosingUsage() {
        val coordinator = NovexLearningCoordinator()
        val initial = NovexLearningPreflight.prepare(
            request(budget = NovexLearningTokenBudget(20_000, 2_000)),
        )
        val stopped = coordinator.start(initial, confirmation(initial))
            .recordUsage(inputTokens = 20_000, outputTokens = 2_000)
        val expanded = NovexLearningPreflight.prepare(
            request(budget = NovexLearningTokenBudget(50_000, 5_000)),
        )

        val resumed = coordinator.extendBudget(
            task = stopped,
            preflight = expanded,
            confirmation = confirmation(expanded),
        )

        assertEquals(NovexLearningTaskStatus.INDEXING, resumed.status)
        assertEquals(expanded.id, resumed.preflightId)
        assertEquals(20_000, resumed.usage.usedInputTokens)
        assertEquals(2_000, resumed.usage.usedOutputTokens)
        assertEquals(50_000, resumed.usage.maxInputTokens)
        assertEquals(5_000, resumed.usage.maxOutputTokens)
    }

    private fun request(
        budget: NovexLearningTokenBudget = NovexLearningTokenBudget(120_000, 12_000),
    ) = NovexLearningPreflightRequest(
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
        proposedBudget = budget,
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
