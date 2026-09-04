package com.openminis.app.novex.domain

import java.util.Locale

data class NovexContextCandidate(
    val sourceId: String,
    val label: String,
    val content: String,
    val kind: ContextSourceKind = ContextSourceKind.BACKGROUND_MODULE,
    val aliases: Set<String> = emptySet(),
    val relatedSourceIds: Set<String> = emptySet(),
    val alwaysInclude: Boolean = false,
    val position: Int = 0,
) {
    init {
        require(sourceId.isNotBlank()) { "上下文候选来源编号不能为空" }
        require(label.isNotBlank()) { "上下文候选来源名称不能为空" }
        require(aliases.none(String::isBlank)) { "上下文候选别名不能为空" }
        require(relatedSourceIds.none(String::isBlank)) { "上下文关联来源编号不能为空" }
    }
}

data class NovexContextFragment(
    val kind: ContextSourceKind,
    val sourceId: String,
    val label: String,
    val text: String,
    val tokenCount: Int,
    val partial: Boolean = false,
)

data class NovexContextComposition(
    val fragments: List<NovexContextFragment>,
    val omissions: List<ContextSourceOmission>,
    val usedTokens: Int,
) {
    fun toUsageRecord(
        id: String,
        requestMessageId: String,
        branchId: String,
        answerIdentity: AnswerIdentity,
        effectiveWindowTokens: Int,
        responseMessageId: String? = null,
        createdAt: Long = 0L,
    ): ContextUsageRecord = ContextUsageRecord(
        id = id,
        requestMessageId = requestMessageId,
        responseMessageId = responseMessageId,
        branchId = branchId,
        answerIdentity = answerIdentity,
        includedSources = fragments.map { fragment ->
            ContextSourceUsage(
                kind = fragment.kind,
                sourceId = fragment.sourceId,
                label = fragment.label,
                tokenCount = fragment.tokenCount,
            )
        },
        omittedSources = omissions,
        usedTokens = usedTokens,
        effectiveWindowTokens = effectiveWindowTokens,
        createdAt = createdAt,
    )
}

/**
 * Selects structured Novex content for one model request.
 *
 * Selection is deliberately pure: it neither reads storage nor executes tools. Callers provide
 * already-authorized candidates, so managed subjects cannot accidentally become model context.
 */
object NovexContextComposer {
    private data class RankedCandidate(
        val value: NovexContextCandidate,
        val rank: Int,
        val recallScore: Int,
        val inputIndex: Int,
        val matchedTerms: Set<String>,
    )

    fun compose(
        query: String,
        tokenBudget: Int,
        candidates: List<NovexContextCandidate>,
        estimateTokens: (String) -> Int = ::estimateTokens,
    ): NovexContextComposition {
        require(tokenBudget >= 0) { "上下文模块预算不能为负数" }
        require(candidates.map(NovexContextCandidate::sourceId).distinct().size == candidates.size) {
            "上下文候选来源编号不能重复"
        }

        val normalizedQuery = query.normalizedForRecall()
        val indexed = candidates.withIndex().associateBy { it.value.sourceId }
        val queryTerms = recallQueryTerms(normalizedQuery)
        val direct = candidates.withIndex().mapNotNull { indexedCandidate ->
            val explicitTerms = indexedCandidate.value.recallTerms()
                .filterTo(linkedSetOf()) { term -> normalizedQuery.contains(term.normalizedForRecall()) }
            val normalizedContent = indexedCandidate.value.content.normalizedForRecall()
            val bodyTerms = queryTerms.filterTo(linkedSetOf()) { term -> normalizedContent.contains(term) }
            val terms = explicitTerms + bodyTerms
            if (terms.isEmpty()) {
                null
            } else {
                val score = explicitTerms.sumOf { it.length } * 100 + bodyTerms.sumOf { it.length }
                indexedCandidate.value.sourceId to (terms to score)
            }
        }.toMap()
        val oneHopIds = direct.keys
            .flatMapTo(linkedSetOf()) { id -> indexed[id]?.value?.relatedSourceIds.orEmpty() }
            .filterTo(linkedSetOf()) { relatedId -> relatedId in indexed && relatedId !in direct }

        val ranked = candidates.withIndex().mapNotNull { indexedCandidate ->
            val candidate = indexedCandidate.value
            val rank = when {
                candidate.alwaysInclude -> 0
                candidate.sourceId in direct -> 1
                candidate.sourceId in oneHopIds -> 2
                else -> return@mapNotNull null
            }
            RankedCandidate(
                value = candidate,
                rank = rank,
                recallScore = direct[candidate.sourceId]?.second ?: 0,
                inputIndex = indexedCandidate.index,
                matchedTerms = direct[candidate.sourceId]?.first.orEmpty(),
            )
        }.sortedWith(
            compareBy<RankedCandidate> { it.rank }
                .thenByDescending { it.recallScore }
                .thenBy { it.value.position }
                .thenBy { it.inputIndex }
                .thenBy { it.value.sourceId },
        )

        val fragments = mutableListOf<NovexContextFragment>()
        val omissions = mutableListOf<ContextSourceOmission>()
        var remaining = tokenBudget

        ranked.forEach { rankedCandidate ->
            val candidate = rankedCandidate.value
            val content = candidate.content.trim()
            if (content.isEmpty()) return@forEach

            val fullTokens = estimateTokens(content).coerceAtLeast(0)
            if (fullTokens <= remaining) {
                fragments += candidate.toFragment(content, fullTokens, partial = false)
                remaining -= fullTokens
                return@forEach
            }

            val excerpt = selectSemanticExcerpt(
                content = content,
                query = normalizedQuery,
                matchedTerms = rankedCandidate.matchedTerms,
                tokenBudget = remaining,
                estimateTokens = estimateTokens,
            )
            if (excerpt != null) {
                val excerptTokens = estimateTokens(excerpt).coerceAtLeast(0)
                fragments += candidate.toFragment(excerpt, excerptTokens, partial = true)
                remaining -= excerptTokens
                omissions += candidate.toOmission("模块内容超过本轮预算，仅引用了相关部分")
            } else {
                omissions += candidate.toOmission("模块内容超过本轮共享预算，未引用")
            }
        }

        return NovexContextComposition(
            fragments = fragments,
            omissions = omissions,
            usedTokens = tokenBudget - remaining,
        )
    }

    private fun selectSemanticExcerpt(
        content: String,
        query: String,
        matchedTerms: Set<String>,
        tokenBudget: Int,
        estimateTokens: (String) -> Int,
    ): String? {
        if (tokenBudget <= 0) return null
        val paragraphs = content
            .split(Regex("\\n\\s*\\n+"))
            .map(String::trim)
            .filter(String::isNotEmpty)
        val terms = (matchedTerms + query)
            .map { value -> value.normalizedForRecall() }
            .filter(String::isNotEmpty)
            .toSet()
        val rankedParagraphs = paragraphs.withIndex().sortedWith(
            compareByDescending<IndexedValue<String>> { paragraph ->
                terms.count { term -> paragraph.value.normalizedForRecall().contains(term) }
            }.thenBy { it.index },
        )

        val selected = mutableListOf<IndexedValue<String>>()
        var remaining = tokenBudget
        rankedParagraphs.forEach { paragraph ->
            val tokens = estimateTokens(paragraph.value).coerceAtLeast(0)
            if (tokens <= remaining) {
                selected += paragraph
                remaining -= tokens
            }
        }
        if (selected.isNotEmpty()) {
            return selected.sortedBy(IndexedValue<String>::index).joinToString("\n\n") { it.value }
        }

        // Paragraph boundaries are preferred. Sentence fallback exists only when one paragraph is
        // itself too large; it still preserves complete semantic units instead of hard character cuts.
        val bestParagraph = rankedParagraphs.firstOrNull()?.value ?: return null
        return bestParagraph
            .split(Regex("(?<=[。！？!?])"))
            .map(String::trim)
            .filter(String::isNotEmpty)
            .firstOrNull { sentence -> estimateTokens(sentence).coerceAtLeast(0) <= tokenBudget }
    }

    private fun NovexContextCandidate.recallTerms(): Set<String> = buildSet {
        add(label)
        addAll(aliases)
    }.filterTo(linkedSetOf()) { it.isNotBlank() }

    private fun NovexContextCandidate.toFragment(
        text: String,
        tokenCount: Int,
        partial: Boolean,
    ) = NovexContextFragment(
        kind = kind,
        sourceId = sourceId,
        label = label,
        text = text,
        tokenCount = tokenCount,
        partial = partial,
    )

    private fun NovexContextCandidate.toOmission(reason: String) = ContextSourceOmission(
        kind = kind,
        sourceId = sourceId,
        label = label,
        reason = reason,
    )

    private fun String.normalizedForRecall(): String = trim().lowercase(Locale.ROOT)

    private fun recallQueryTerms(query: String): Set<String> = buildSet {
        Regex("[\\p{L}\\p{N}_-]+").findAll(query).forEach { match ->
            val value = match.value
            val containsHan = value.any { it.code in 0x3400..0x9FFF }
            if (containsHan) {
                for (size in 2..minOf(4, value.length)) {
                    for (start in 0..value.length - size) add(value.substring(start, start + size))
                }
            } else if (value.length >= 3) {
                add(value)
            }
        }
    }.filterTo(linkedSetOf()) { it !in STOP_TERMS }

    private fun estimateTokens(value: String): Int = ((value.length + 2) / 3).coerceAtLeast(1)

    private val STOP_TERMS = setOf(
        "什么", "哪个", "这个", "那个", "怎么", "是否", "可以", "需要", "时候", "一下",
    )
}
