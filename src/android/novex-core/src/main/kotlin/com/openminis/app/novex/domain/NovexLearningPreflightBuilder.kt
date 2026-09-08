package com.openminis.app.novex.domain

data class NovexLearningPlanningModel(val id: String, val providerName: String, val contextTokens: Int?,
    val maxOutputTokens: Int, val occupiedContextTokens: Int)

/** Source inspection, revision selection and budget planning shared by every start/continue entry. */
class NovexLearningPreflightBuilder(private val documents: NovexDocumentSnapshotStore) {
    fun build(state: NovexLearningState, model: NovexLearningPlanningModel,
        proposedBudget: NovexLearningTokenBudget? = null, sourcePlanFingerprint: String? = null): NovexLearningPreflightSnapshot {
        val sourceDocuments = state.collection.sources.mapNotNull { source ->
            source.documentRef?.let { ref ->
                val revision = state.task?.preflight?.documentRevisions?.get(ref)
                if (revision == null) documents.find(ref) else documents.findRevision(ref, revision)
            }?.let { source.ref to it }
        }.toMap()
        val sourceEstimates = state.collection.sources.map { source ->
            val snapshot = sourceDocuments[source.ref]
            val estimatedTokens = snapshot?.let {
                com.openminis.app.novex.domain.NovexLearningBudgetPolicy.inputReservation(
                    com.openminis.app.novex.domain.NovexLearningPrompt.review(it.title, it.blocks),
                )
            } ?: 0
            val pages = snapshot?.blocks.orEmpty()
                .mapNotNull { block -> block.source.page }
                .distinct()
                .size
                .takeIf { it > 0 }
            val unsupportedReason = when (snapshot?.status) {
                null -> source.failureCode ?: "找不到可读取的解析资料，请重新导入来源"
                NovexDocumentStatus.UNSUPPORTED -> "当前版本不支持此文档格式"
                NovexDocumentStatus.PASSWORD_REQUIRED -> "文档需要密码"
                NovexDocumentStatus.DAMAGED -> "文档已损坏"
                NovexDocumentStatus.EMPTY -> "文档没有可读取内容"
                else -> null
            }
            NovexLearningSourceEstimate(
                ref = source.ref,
                estimatedTokens = estimatedTokens,
                pageCount = pages,
                imageCount = snapshot?.blocks.orEmpty().count { it.kind == NovexDocumentBlockKind.IMAGE },
                requiresOcr = snapshot?.status == NovexDocumentStatus.OCR_REQUIRED,
                requiresNetwork = false,
                unsupportedReason = unsupportedReason,
            )
        }
        val totalEstimatedTokens = sourceEstimates.sumOf { it.estimatedTokens.toLong() }
        val budget = proposedBudget ?: NovexLearningTokenBudget(
            inputTokens = (totalEstimatedTokens * 2)
                .coerceIn(16_000L, 2_000_000L)
                .toInt(),
            outputTokens = (totalEstimatedTokens / 5)
                .coerceIn(8_000L, 128_000L)
                .toInt(),
        )
        val preflight = NovexLearningPreflight.prepare(
            NovexLearningPreflightRequest(
                collectionRef = state.collection.ref,
                sources = sourceEstimates,
                modelId = model.id,
                modelProviderName = model.providerName,
                effectiveContextTokens = model.contextTokens,
                occupiedContextTokens = model.occupiedContextTokens,
                directReadBudgetTokens = 12_000,
                proposedBudget = budget,
                sourceDocuments = sourceDocuments,
                sourcePlanFingerprint = sourcePlanFingerprint,
                modelMaxOutputTokens = model.maxOutputTokens,
            ),
            progress = state,
        )
        return preflight
    }
}
