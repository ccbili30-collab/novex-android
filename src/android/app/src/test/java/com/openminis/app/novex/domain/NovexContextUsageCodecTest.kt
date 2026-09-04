package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class NovexContextUsageCodecTest {
    @Test
    fun exactIncludedAndOmittedSourcesSurvivePersistence() {
        val source = ContextUsageRecord(
            id = "usage-1",
            requestMessageId = "user-1",
            responseMessageId = "assistant-1",
            branchId = "user-1",
            answerIdentity = AnswerIdentity.CharacterVersion("version-1"),
            includedSources = listOf(
                ContextSourceUsage(ContextSourceKind.ANSWER_IDENTITY, "profile-1", "苏晚晴 · 本体", 42),
                ContextSourceUsage(ContextSourceKind.BACKGROUND_MODULE, "module-1", "世界 · 势力", 64),
            ),
            omittedSources = listOf(
                ContextSourceOmission(ContextSourceKind.BACKGROUND_MODULE, "module-2", "世界 · 历史", "超过预算"),
            ),
            usedTokens = 106,
            effectiveWindowTokens = 200_000,
            createdAt = 99,
        )

        assertEquals(source, NovexContextUsageCodec.decode(NovexContextUsageCodec.encode(source)))
    }
}
