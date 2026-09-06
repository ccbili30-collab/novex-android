package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexContextUsageLedgerTest {
    @Test
    fun `later tool reads join the user request while remaining on their own reply branches`() {
        val first = record("initial").copy(responseMessageId = "first", branchId = "first",
            includedSources = listOf(ContextSourceUsage(ContextSourceKind.ANSWER_IDENTITY, "nova", "诺瓦", 20)))
        val read = NovexSourceRead("rules", "帝议", "one", 0, 50, 100)
        val initial = NovexContextUsageLedger.open(NovexContextUsageLedgerSnapshot("chat", listOf(first)))
            .withSourceReads("message-1", "first", listOf(read), setOf("message-1", "first"), AnswerIdentity.Nova, 4096, 1)
        val extended = initial.withSourceReads("message-1", "second", listOf(read.copy(start = 50, end = 100)),
            setOf("message-1", "first", "second"), AnswerIdentity.Nova, 4096, 2)
        val complete = extended.latestByRequestForActivePath(setOf("message-1", "first", "second")).getValue("message-1")
        assertEquals(first.includedSources, complete.includedSources)
        assertEquals(2, complete.sourceReads.size)
        assertTrue(NovexSourceReadCoverage.from(complete.sourceReads).single().complete)
        val alternate = extended.latestByRequestForActivePath(setOf("message-1", "first", "other-reply")).getValue("message-1")
        assertEquals(listOf(read), alternate.sourceReads)
    }

    @Test
    fun `context usage records keep the exact sources for the branch that produced them`() {
        val recorded = ContextUsageRecord(
            id = "usage-1",
            requestMessageId = "message-1",
            responseMessageId = "message-2",
            branchId = "branch-1",
            answerIdentity = AnswerIdentity.CharacterVersion("version-1"),
            includedSources = listOf(
                ContextSourceUsage(
                    kind = ContextSourceKind.BACKGROUND_MODULE,
                    sourceId = "world-1:timeline-1",
                    label = "云岚书院 · 时间线",
                    tokenCount = 420,
                ),
            ),
            omittedSources = listOf(
                ContextSourceOmission(
                    kind = ContextSourceKind.BACKGROUND_MODULE,
                    sourceId = "world-1:history-9",
                    label = "云岚书院 · 远古史",
                    reason = "超出本轮上下文预算",
                ),
            ),
            usedTokens = 18_000,
            effectiveWindowTokens = 200_000,
        )

        val ledger = NovexContextUsageLedger.empty("conversation-1")
            .record(recorded)

        assertEquals(listOf(recorded), ledger.recordsForBranch("branch-1"))
        assertEquals(emptyList<ContextUsageRecord>(), ledger.recordsForBranch("branch-2"))
    }

    @Test
    fun `stored context ledger rejects duplicate immutable record identifiers`() {
        val record = record("usage-1")
        assertThrows(IllegalArgumentException::class.java) {
            NovexContextUsageLedger.open(
                NovexContextUsageLedgerSnapshot(
                    conversationId = "conversation-1",
                    records = listOf(record, record),
                ),
            )
        }
    }

    @Test
    fun `active reply sibling selects only the usage record that produced that branch`() {
        val first = record("usage-1").copy(responseMessageId = "assistant-1", branchId = "assistant-1")
        val second = record("usage-2").copy(responseMessageId = "assistant-2", branchId = "assistant-2")
        val ledger = NovexContextUsageLedger.open(
            NovexContextUsageLedgerSnapshot("conversation-1", listOf(first, second)),
        )

        assertEquals(
            mapOf("message-1" to second),
            ledger.latestByRequestForActivePath(setOf("message-1", "assistant-2")),
        )
    }

    private fun record(id: String) = ContextUsageRecord(
        id = id,
        requestMessageId = "message-1",
        branchId = "branch-1",
        answerIdentity = AnswerIdentity.Nova,
        includedSources = emptyList(),
        usedTokens = 1_000,
        effectiveWindowTokens = 200_000,
    )
}
