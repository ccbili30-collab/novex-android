package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class NovexContextUsageCodecTest {
    @Test
    fun historicalUsageKeepsTheSelectedPersonaSnapshotRatherThanOnlyItsPresetId() {
        val record = ContextUsageRecord(
            id = "usage-persona",
            requestMessageId = "request-1",
            branchId = "branch-1",
            answerIdentity = AnswerIdentity.PersonaPreset(
                presetId = "historian",
                label = "历史共创者",
                instructions = "区分历史事实与架空推演。\n保留不同意见，不代替玩家决定。",
            ),
            includedSources = emptyList(),
            usedTokens = 0,
            effectiveWindowTokens = 200_000,
        )

        val restored = NovexContextUsageCodec.decode(NovexContextUsageCodec.encode(record))

        assertEquals(record, restored)
    }

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
