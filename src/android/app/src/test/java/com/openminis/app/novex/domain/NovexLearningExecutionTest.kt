package com.openminis.app.novex.domain

import com.openminis.app.tools.ToolExecutionResult
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovexLearningExecutionTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun `learning starts through readonly approval and free modes without another confirmation`() = runBlocking {
        val ref = NovexResourceRef("novex://source-collections/large")
        val source = NovexResourceRef("novex://sources/a")
        val collection = NovexSourceCollection(ref, NovexResourceRef("novex://conversation-branches/a"), "长资料",
            listOf(NovexCollectionSource(source, "资料", "a".repeat(64), NovexSourceStatus.READY,
                NovexResourceRef("novex://documents/a"), listOf("block_a"))), 0, 0)
        val repo = FileNovexLearningRepository(File(files.root, "learning"))
        repo.save(NovexLearningState(collection, NovexReviewLedger.start(collection)))
        val plans = NovexLearningExecutionPlans(repo, NovexDocumentSnapshotStore { null }, File(files.root, "plans"))
        val build: (NovexLearningState, NovexLearningTokenBudget?, String?) -> NovexLearningPreflightSnapshot = { state, budget, fingerprint ->
            NovexLearningPreflight.prepare(NovexLearningPreflightRequest(ref, listOf(NovexLearningSourceEstimate(source, 90_000)),
                "model", effectiveContextTokens = 200_000, occupiedContextTokens = 0, directReadBudgetTokens = 12_000,
                proposedBudget = budget ?: NovexLearningTokenBudget(180_000, 24_000), sourcePlanFingerprint = fingerprint), state)
        }
        val p = plans.prepare("chat", ref, NovexLearningPlanAction.START, { it == ref }, build)
        val operation = NovexToolOperation("chat", "reply", "call", "learning_start",
            JSONObject().put("collection_ref", ref.value).put("preflight_id", p.id).toString(), "启动资料整理",
            plans.review("chat", ref, p.id) { it == ref })
        val journal = NovexOperationJournal(File(files.root, "operations"))
        val gate = NovexToolExecution(journal)
        var scheduled = 0
        val effect: suspend () -> ToolExecutionResult = {
            plans.commit("chat", ref, p.id, { it == ref }, build) {}
            assertNotNull(repo.find(ref)?.task) // Must persist before any scheduling/model work.
            scheduled++
            ToolExecutionResult("整理任务已保存并启动", true, toolTitle = "启动资料整理")
        }
        assertFalse(gate.execute(operation, { NovexExecutionMode.READ_ONLY }, effect).success)
        assertEquals(0, scheduled)
        assertNull(repo.find(ref)?.task)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { gate.execute(operation, { NovexExecutionMode.APPROVAL }, effect) }
        assertEquals(operation.id, gate.pending.value.single().id)
        assertTrue(gate.pending.value.single().reviewDetails.contains("180000"))
        assertNull(repo.find(ref)?.task)
        gate.decide(operation, true)
        assertTrue(waiting.await().success)
        assertEquals(1, scheduled)
        assertTrue(gate.pending.value.isEmpty())
        assertTrue(NovexToolExecution(journal).execute(operation, { NovexExecutionMode.FREE }, effect).success)
        assertEquals(1, scheduled)
        assertEquals(1, NovexLearningExecutionPlans.exportPlans(File(files.root, "plans"), "chat").size)
        assertTrue(NovexLearningExecutionPlans.exportPlans(File(files.root, "plans"), "other").isEmpty())
    }
}
