package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexContextComposerTest {
    private val oneTokenPerChar: (String) -> Int = { it.length }

    @Test
    fun deterministicNameRecallIncludesOneRelationshipHopOnly() {
        val result = NovexContextComposer.compose(
            query = "青龙会在哪个地区出现？",
            tokenBudget = 200,
            candidates = listOf(
                candidate("core", "云岚世界 · 概述", "修行者与灵兽共存。", always = true),
                candidate("faction", "势力 · 青龙会", "青龙会守护东湖。", aliases = setOf("青龙会"), related = setOf("region")),
                candidate("region", "地区 · 东湖", "东湖位于云岚东南。", aliases = setOf("东湖"), related = setOf("deep")),
                candidate("deep", "事件 · 沉船", "不应通过第二层关系被召回。", aliases = setOf("沉船")),
            ),
            estimateTokens = oneTokenPerChar,
        )

        assertEquals(listOf("core", "faction", "region"), result.fragments.map { it.sourceId })
        assertFalse(result.fragments.any { it.sourceId == "deep" })
    }

    @Test
    fun everyModuleSharesOneBudgetAndRelevantOverflowIsRecorded() {
        val result = NovexContextComposer.compose(
            query = "青龙会",
            tokenBudget = 11,
            candidates = listOf(
                candidate("core", "概述", "12345", always = true),
                candidate("first", "青龙会规则", "123456", aliases = setOf("青龙会")),
                candidate("second", "青龙会历史", "123456", aliases = setOf("青龙会"), position = 2),
            ),
            estimateTokens = oneTokenPerChar,
        )

        assertEquals(11, result.usedTokens)
        assertEquals(listOf("core", "first"), result.fragments.map { it.sourceId })
        assertEquals("second", result.omissions.single().sourceId)
        assertTrue(result.omissions.single().reason.contains("预算"))
    }

    @Test
    fun anOversizedModuleSelectsRelevantSemanticParagraphsInsteadOfHardCuttingTheDocument() {
        val result = NovexContextComposer.compose(
            query = "青龙会",
            tokenBudget = 18,
            candidates = listOf(
                candidate(
                    "history",
                    "世界历史",
                    "五百年前建立了北部王国。\n\n青龙会在东湖成立。\n\n南方港口于去年重建。",
                    aliases = setOf("青龙会", "世界历史"),
                ),
            ),
            estimateTokens = oneTokenPerChar,
        )

        assertEquals(1, result.fragments.size)
        assertEquals("青龙会在东湖成立。", result.fragments.single().text)
        assertTrue(result.fragments.single().partial)
        assertTrue(result.omissions.single().reason.contains("部分"))
    }

    @Test
    fun usageRecordKeepsExactIncludedAndOmittedModulesForTheRequestBranch() {
        val composition = NovexContextComposer.compose(
            query = "青龙会",
            tokenBudget = 20,
            candidates = listOf(candidate("faction", "青龙会", "守护东湖", aliases = setOf("青龙会"))),
            estimateTokens = oneTokenPerChar,
        )

        val record = composition.toUsageRecord(
            id = "usage-1",
            requestMessageId = "user-1",
            branchId = "user-1",
            answerIdentity = AnswerIdentity.Nova,
            effectiveWindowTokens = 200_000,
        )

        assertEquals("user-1", record.requestMessageId)
        assertEquals(listOf("faction"), record.includedSources.map { it.sourceId })
        assertEquals(composition.usedTokens, record.usedTokens)
    }

    @Test
    fun structuredModuleBodyCanBeRecalledWithoutRepeatingItsTitle() {
        val result = NovexContextComposer.compose(
            query = "北部王国是什么时候建立的？",
            tokenBudget = 100,
            candidates = listOf(
                candidate("history", "时间线", "五百年前建立了北部王国。"),
                candidate("unrelated", "地区", "南方港口盛产香料。", position = 1),
            ),
            estimateTokens = oneTokenPerChar,
        )

        assertEquals(listOf("history"), result.fragments.map { it.sourceId })
    }

    private fun candidate(
        id: String,
        label: String,
        content: String,
        aliases: Set<String> = emptySet(),
        related: Set<String> = emptySet(),
        always: Boolean = false,
        position: Int = 0,
    ) = NovexContextCandidate(
        sourceId = id,
        label = label,
        content = content,
        aliases = aliases,
        relatedSourceIds = related,
        alwaysInclude = always,
        position = position,
    )
}
