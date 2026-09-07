package com.openminis.app.novex.domain

import java.security.MessageDigest
import kotlin.math.ceil

data class NovexLearningTokenBudget(
    val inputTokens: Int,
    val outputTokens: Int,
) {
    init {
        require(inputTokens > 0) { "学习输入词元预算必须大于零" }
        require(outputTokens > 0) { "学习输出词元预算必须大于零" }
    }
}

data class NovexLearningSourceEstimate(
    val ref: NovexResourceRef,
    val estimatedTokens: Int,
    val pageCount: Int? = null,
    val imageCount: Int = 0,
    val requiresOcr: Boolean = false,
    val requiresNetwork: Boolean = false,
    val unsupportedReason: String? = null,
) {
    init {
        require(estimatedTokens >= 0) { "资料估算词元不能为负数" }
        require(pageCount == null || pageCount >= 0) { "资料页数不能为负数" }
        require(imageCount >= 0) { "资料图片数不能为负数" }
        require(unsupportedReason == null || unsupportedReason.isNotBlank()) { "不支持原因不能为空" }
    }
}

data class NovexLearningPreflightRequest(
    val collectionRef: NovexResourceRef,
    val sources: List<NovexLearningSourceEstimate>,
    val modelId: String,
    val modelProviderName: String = "当前模型提供商",
    val effectiveContextTokens: Int?,
    val occupiedContextTokens: Int,
    val directReadBudgetTokens: Int,
    val proposedBudget: NovexLearningTokenBudget,
    val sourcePlanFingerprint: String? = null,
    val modelMaxOutputTokens: Int = 4096,
    /** Parsed local snapshots keyed by source ref; never sent by preflight itself. */
    val sourceDocuments: Map<NovexResourceRef, NovexDocumentSnapshot> = emptyMap(),
) {
    init {
        require(collectionRef.value.startsWith("novex://source-collections/")) { "学习预检必须属于资料集" }
        require(sources.isNotEmpty()) { "学习预检至少需要一项资料" }
        require(sources.map { it.ref }.distinct().size == sources.size) { "学习预检不能包含重复引用" }
        require(modelId.isNotBlank()) { "学习模型编号不能为空" }
        require(modelProviderName.isNotBlank()) { "模型提供商名称不能为空" }
        require(effectiveContextTokens == null || effectiveContextTokens > 0) { "有效上下文必须大于零" }
        require(occupiedContextTokens >= 0) { "已占用上下文不能为负数" }
        require(directReadBudgetTokens > 0) { "直接读取预算必须大于零" }
        require(sourcePlanFingerprint == null || sourcePlanFingerprint.isNotBlank()) { "资料计划指纹不能为空" }
    }
}

enum class NovexLearningRoute {
    DIRECT_READ,
    CONFIRMATION_REQUIRED,
}

enum class NovexLearningTaskStatus {
    NOT_STARTED,
    INDEXING,
    REVIEWING,
    SYNTHESIZING,
    PAUSED,
    PAUSED_BUDGET_REACHED,
    CANCELLED,
    PARTIAL_FAILURE,
    COMPLETE,
}

data class NovexLearningRisk(val code: String, val message: String)

data class NovexLearningCostEstimate(
    val currencyCode: String,
    val minimumMinorUnits: Long,
    val maximumMinorUnits: Long,
) {
    init {
        require(currencyCode.isNotBlank()) { "费用币种不能为空" }
        require(minimumMinorUnits >= 0) { "最低费用不能为负数" }
        require(maximumMinorUnits >= minimumMinorUnits) { "最高费用不能低于最低费用" }
    }
}

data class NovexLearningDurationEstimate(
    val minimumMinutes: Int,
    val maximumMinutes: Int,
) {
    init {
        require(minimumMinutes > 0) { "最短整理时间必须大于零" }
        require(maximumMinutes >= minimumMinutes) { "最长整理时间不能短于最短时间" }
    }
}

data class NovexLearningDataExposure(
    val destination: String,
    val sourceContentMayLeaveDevice: Boolean,
    val contentScope: String = "batch_source_excerpts_and_notes",
) {
    init {
        require(destination.isNotBlank()) { "学习资料处理目标不能为空" }
        require(contentScope.isNotBlank()) { "学习资料处理范围不能为空" }
    }
}

data class NovexLearningPreflightSnapshot(
    val id: String,
    val collectionRef: NovexResourceRef,
    val sourceRefs: List<NovexResourceRef>,
    val modelId: String,
    val modelProviderName: String,
    val route: NovexLearningRoute,
    val sourceCount: Int,
    val estimatedSourceTokens: Int,
    val estimatedModelRounds: Int,
    val pageCount: Int,
    val imageCount: Int,
    val ocrSourceCount: Int,
    val networkSourceCount: Int,
    val estimatedCost: NovexLearningCostEstimate?,
    val estimatedDuration: NovexLearningDurationEstimate,
    val dataExposure: NovexLearningDataExposure,
    val plannedSteps: List<String>,
    val confirmedBudget: NovexLearningTokenBudget,
    val risks: List<NovexLearningRisk>,
    val unsupportedSources: Map<NovexResourceRef, String>,
    val taskStatus: NovexLearningTaskStatus = NovexLearningTaskStatus.NOT_STARTED,
    val prohibitedOutcomes: Set<String> = setOf(
        "create_world",
        "create_character",
        "create_interactive_fiction",
        "modify_original_source",
    ),
    val sourcePlanFingerprint: String? = null,
    val modelLimits: NovexLearningModelLimits? = null,
    val reviewBatchCount: Int? = null,
    val reviewInputReservationTokens: Int? = null,
    val documentRevisions: Map<NovexResourceRef, String> = emptyMap(),
) {
    val requiresConfirmation: Boolean get() = route == NovexLearningRoute.CONFIRMATION_REQUIRED
}

object NovexLearningPreflight {
    private const val TOKENS_PER_MODEL_ROUND = 32_000
    private const val MAX_DIRECT_FILE_COUNT = 3

    fun prepare(request: NovexLearningPreflightRequest, progress: NovexLearningState? = null): NovexLearningPreflightSnapshot {
        require(progress == null || progress.collection.ref == request.collectionRef) { "学习进度不属于当前资料集" }
        val limits = NovexLearningModelLimits(request.effectiveContextTokens, request.modelMaxOutputTokens)
        val documents = request.sources.mapNotNull { request.sourceDocuments[it.ref] }.distinctBy { it.ref }
        for (document in documents) {
            val expectedRevision = progress?.task?.preflight?.documentRevisions?.get(document.ref)
                ?: progress?.notes?.firstNotNullOfOrNull { it.sourceRevisions[document.ref] }
            require(expectedRevision == null || expectedRevision == NovexSourceReadEvidence.documentRevision(document)) {
                "来源解析修订已变化；请读取原任务采用的修订后继续，不能把旧笔记覆盖套用到新解析。"
            }
            require(expectedRevision != null || progress?.notes.orEmpty().none { document.ref in it.sourceDocumentRefs }) {
                "旧笔记未记录来源解析修订；请保留旧成果并重新核对资料，不能将它们计为新解析已整理。"
            }
        }
        val readRanges = progress?.notes.orEmpty().flatMap { it.readRanges }
        val continuing = progress?.task != null || progress?.previousTasks?.isNotEmpty() == true || progress?.notes?.isNotEmpty() == true ||
            (progress?.reviewLedger?.reviewedBlocks ?: 0) > 0
        val fullPlan = if (request.effectiveContextTokens != null && documents.isNotEmpty()) documents.filter {
            it.status in setOf(NovexDocumentStatus.READY, NovexDocumentStatus.TRUNCATED)
        }.flatMap { document ->
            val reviewed = progress?.reviewLedger?.reviewedBlocksByDocument?.get(document.ref).orEmpty()
            NovexLearningBatchPlanner.reviewRequests(request.collectionRef,
                document.copy(blocks = document.blocks.filter { it.id !in reviewed }), limits, readRanges = readRanges)
        } else null
        val reviewReservation = fullPlan?.sumOf { it.estimatedInputTokens.toLong() }?.saturatedInt()
        val totalTokens = request.sources.sumOf { it.estimatedTokens.toLong() }.saturatedInt()
        val hasIncompleteParsing = documents.any { it.status == NovexDocumentStatus.TRUNCATED }
        val hasExpensiveCapability = hasIncompleteParsing || request.sources.any {
            it.requiresNetwork || it.requiresOcr || it.unsupportedReason != null
        }
        val contextRoom = request.effectiveContextTokens?.let {
            (it.toLong() - request.occupiedContextTokens - minOf(4096, request.modelMaxOutputTokens) - 512L)
                .coerceAtLeast(0).saturatedInt()
        }
        val directBudget = listOfNotNull(request.directReadBudgetTokens, contextRoom).minOrNull()
            ?: request.directReadBudgetTokens
        val canReadDirectly = !continuing && contextRoom != null && request.sources.size <= MAX_DIRECT_FILE_COUNT &&
            totalTokens <= directBudget &&
            !hasExpensiveCapability
        val risks = buildList {
            val prior = progress?.task ?: progress?.previousTasks?.lastOrNull()
            if (prior != null) add(NovexLearningRisk("learning.carried_usage",
                "保留既有累计用量：输入 ${prior.usage.usedInputTokens}、输出 ${prior.usage.usedOutputTokens} 词元；此前使用 ${prior.preflight.modelProviderName} 的 ${prior.preflight.modelId}。" +
                    if (progress?.task == null) "旧笔记保留在历史成果中；本次重新核对当前解析，重新计算整理覆盖。"
                    else "已经提交的笔记和覆盖继续使用；尚未提交的批次换模型后可能重新请求，之前的返回和消耗仍保留。"))
            if (continuing) add(NovexLearningRisk("learning.resuming_saved_progress",
                "本次从已保存的阅读进度继续，已读内容不重复计入剩余通读批次；确认预算是任务累计上限，不是额外可用额度"))
            if (hasIncompleteParsing) add(NovexLearningRisk("learning.incomplete_source",
                "部分资料解析被截断，只能整理已经解析的部分；完成后仍会标记缺失范围，不能宣称通读全文"))
            if (totalTokens > request.directReadBudgetTokens) add(
                NovexLearningRisk(
                    code = "learning.high_token_use",
                    message = "资料规模超过单回合直接读取预算，需要分批通读并持续记账",
                ),
            )
            if (request.sources.any { it.requiresNetwork }) add(
                NovexLearningRisk(
                    code = "learning.network_access",
                    message = "部分资料需要联网获取，确认前不会开始批量下载",
                ),
            )
            if (request.sources.any { it.requiresOcr }) add(
                NovexLearningRisk(
                    code = "learning.ocr_required",
                    message = "部分资料需要光学字符识别，可能增加时间与模型消耗",
                ),
            )
            if (request.effectiveContextTokens == null) add(
                NovexLearningRisk(
                    code = "learning.model_window_unknown",
                    message = "当前模型上下文上限未知，不能直接承诺一次读完",
                ),
            )
            add(
                NovexLearningRisk(
                    code = "learning.reservation_not_billing",
                    message = "本地输入预算按实际请求文字的字节数保守预留，不是精确词元计数或账单；实际消耗以提供商返回为准。综合轮数与时间是估算，长笔记可能需要额外分层综合",
                ),
            )
            add(
                NovexLearningRisk(
                    code = "learning.cost_unknown",
                    message = "当前模型价格无法可靠估算，实际费用可能较高",
                ),
            )
            add(
                NovexLearningRisk(
                    code = "learning.model_data_transfer",
                    message = "资料正文片段与整理笔记会交给${request.modelProviderName}处理，可能离开本设备",
                ),
            )
        }
        val plannedSteps = if (canReadDirectly) {
            listOf("direct_read")
        } else {
            buildList {
                add("local_parse")
                if (request.sources.any { it.requiresNetwork }) add("fetch_network_sources")
                if (request.sources.any { it.requiresOcr }) add("optical_character_recognition")
                add("batch_review")
                add("deduplicate_classify")
                add("synthesize_notes")
            }
        }
        val canonical = buildString {
            append(request.collectionRef.value).append('\n')
            append(request.modelId).append('\n')
            append(request.modelProviderName).append('\n')
            append(request.modelMaxOutputTokens).append('\n')
            append(request.effectiveContextTokens).append(':')
                .append(request.directReadBudgetTokens).append('\n')
            append(request.proposedBudget.inputTokens).append(':')
                .append(request.proposedBudget.outputTokens).append('\n')
            append(request.sourcePlanFingerprint.orEmpty()).append('\n')
            (progress?.previousTasks.orEmpty() + listOfNotNull(progress?.task)).forEach { task ->
                append(task.preflight.id).append(':').append(task.status).append(':')
                    .append(task.usage.usedInputTokens).append(':').append(task.usage.usedOutputTokens).append('\n')
            }
            progress?.reviewLedger?.reviewedBlocksByDocument?.entries?.sortedBy { it.key.value }?.forEach { (ref, ids) ->
                append(ref.value).append(':').append(ids.sorted().joinToString(",")).append('\n')
            }
            progress?.notes.orEmpty().forEach { note ->
                append(note.ref.value).append(':').append(sha256(note.body)).append('\n')
                note.readRanges.forEach { range ->
                    append(range.documentRef.value).append(':').append(range.blockId).append(':')
                        .append(range.start).append(':').append(range.end).append('\n')
                }
            }
            documents.forEach { document ->
                append(document.ref.value).append(':').append(document.sha256).append(':').append(document.parserVersion).append('\n')
                append(sha256(NovexLearningPrompt.review(document.title, document.blocks).user)).append('\n')
            }
            request.sources.forEach { source ->
                append(source.ref.value).append('|')
                    .append(source.estimatedTokens).append('|')
                    .append(source.pageCount).append('|')
                    .append(source.imageCount).append('|')
                    .append(source.requiresOcr).append('|')
                    .append(source.requiresNetwork).append('|')
                    .append(source.unsupportedReason.orEmpty()).append('\n')
            }
        }
        val estimatedModelRounds = if (canReadDirectly) 1 else fullPlan?.let { it.size.toLong().plus(1).saturatedInt() }
            ?: ceil(totalTokens.toDouble() / TOKENS_PER_MODEL_ROUND).toInt().coerceAtLeast(1)
        val minimumMinutes = (estimatedModelRounds.toLong() +
            request.sources.count { it.requiresNetwork } +
            request.sources.count { it.requiresOcr } * 2L).saturatedInt()
        val maximumMinutes = (estimatedModelRounds * 4L +
            request.sources.count { it.requiresNetwork } * 5 +
            request.sources.count { it.requiresOcr } * 10L).saturatedInt()
        return NovexLearningPreflightSnapshot(
            id = "preflight_" + sha256(canonical).take(24),
            collectionRef = request.collectionRef,
            sourceRefs = request.sources.map { it.ref },
            modelId = request.modelId,
            modelProviderName = request.modelProviderName,
            route = if (canReadDirectly) NovexLearningRoute.DIRECT_READ else NovexLearningRoute.CONFIRMATION_REQUIRED,
            sourceCount = request.sources.size,
            estimatedSourceTokens = totalTokens,
            estimatedModelRounds = estimatedModelRounds,
            pageCount = request.sources.sumOf { (it.pageCount ?: 0).toLong() }.saturatedInt(),
            imageCount = request.sources.sumOf { it.imageCount.toLong() }.saturatedInt(),
            ocrSourceCount = request.sources.count { it.requiresOcr },
            networkSourceCount = request.sources.count { it.requiresNetwork },
            estimatedCost = null,
            estimatedDuration = NovexLearningDurationEstimate(minimumMinutes, maximumMinutes),
            dataExposure = NovexLearningDataExposure(
                destination = request.modelProviderName,
                sourceContentMayLeaveDevice = true,
            ),
            plannedSteps = plannedSteps,
            confirmedBudget = request.proposedBudget,
            risks = risks,
            unsupportedSources = request.sources.mapNotNull { source ->
                source.unsupportedReason?.let { source.ref to it }
            }.toMap(),
            sourcePlanFingerprint = request.sourcePlanFingerprint,
            modelLimits = NovexLearningModelLimits(request.effectiveContextTokens, request.modelMaxOutputTokens),
            reviewBatchCount = fullPlan?.size,
            reviewInputReservationTokens = reviewReservation,
            documentRevisions = documents.associate { it.ref to NovexSourceReadEvidence.documentRevision(it) },
        )
    }

    private fun Long.saturatedInt(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

data class NovexLearningConfirmation(
    val preflightId: String,
    val modelId: String,
    val sourceRefs: List<NovexResourceRef>,
    val maxInputTokens: Int,
    val maxOutputTokens: Int,
    val confirmedAtMillis: Long,
) {
    init {
        require(preflightId.isNotBlank()) { "学习预检编号不能为空" }
        require(modelId.isNotBlank()) { "学习模型编号不能为空" }
        require(sourceRefs.isNotEmpty()) { "学习确认必须绑定资料范围" }
        require(maxInputTokens > 0 && maxOutputTokens > 0) { "学习确认预算必须大于零" }
        require(confirmedAtMillis >= 0) { "学习确认时间不能为负数" }
    }
}

enum class NovexLearningAuthorization {
    DIRECT_READ_ONLY,
    CONFIRMATION_REQUIRED,
    STALE_CONFIRMATION,
    BUDGET_EXPANDED,
    AUTHORIZED,
}

object NovexLearningGate {
    fun requireExecutionContext(
        preflight: NovexLearningPreflightSnapshot,
        modelId: String,
        providerName: String,
        limits: NovexLearningModelLimits?,
    ) {
        require(preflight.modelLimits?.contextTokens != null) {
            "旧学习任务缺少模型窗口记录，请在资料与整理计划中重新核对来源；已保存笔记仍可读取"
        }
        require(modelId == preflight.modelId && providerName == preflight.modelProviderName && limits == preflight.modelLimits) {
            "学习模型、提供商或窗口配置已变化。请恢复原配置后继续，或在资料与整理计划中按当前模型续接；已保存进度和用量会保留"
        }
    }

    fun authorize(
        preflight: NovexLearningPreflightSnapshot,
        confirmation: NovexLearningConfirmation?,
    ): NovexLearningAuthorization {
        if (!preflight.requiresConfirmation) return NovexLearningAuthorization.DIRECT_READ_ONLY
        confirmation ?: return NovexLearningAuthorization.CONFIRMATION_REQUIRED
        if (
            confirmation.preflightId != preflight.id ||
            confirmation.modelId != preflight.modelId ||
            confirmation.sourceRefs != preflight.sourceRefs
        ) return NovexLearningAuthorization.STALE_CONFIRMATION
        if (
            confirmation.maxInputTokens > preflight.confirmedBudget.inputTokens ||
            confirmation.maxOutputTokens > preflight.confirmedBudget.outputTokens
        ) return NovexLearningAuthorization.BUDGET_EXPANDED
        return NovexLearningAuthorization.AUTHORIZED
    }
}

class NovexLearningUsageLedger private constructor(
    val preflightId: String,
    val maxInputTokens: Int,
    val maxOutputTokens: Int,
    val usedInputTokens: Int,
    val usedOutputTokens: Int,
    val status: NovexLearningTaskStatus,
    val containsEstimates: Boolean = false,
    val containsUnknownLegacyUsage: Boolean = false,
) {
    fun canConsume(inputTokens: Int, outputTokens: Int): Boolean {
        require(inputTokens >= 0 && outputTokens >= 0) { "学习任务词元用量不能为负数" }
        return status != NovexLearningTaskStatus.PAUSED_BUDGET_REACHED &&
            usedInputTokens.toLong() + inputTokens <= maxInputTokens &&
            usedOutputTokens.toLong() + outputTokens <= maxOutputTokens
    }

    fun record(inputTokens: Int, outputTokens: Int): NovexLearningUsageLedger {
        require(canConsume(inputTokens, outputTokens)) { "本次调用会超过用户确认的学习预算" }
        return recordObserved(inputTokens, outputTokens)
    }

    /** Completed provider work is a fact, even when its reported usage exceeded a reservation. */
    fun recordObserved(inputTokens: Int, outputTokens: Int, estimated: Boolean = false): NovexLearningUsageLedger {
        require(inputTokens >= 0 && outputTokens >= 0) { "实际模型用量不能为负数" }
        val nextInput = (usedInputTokens.toLong() + inputTokens).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val nextOutput = (usedOutputTokens.toLong() + outputTokens).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val reachesLimit = nextInput >= maxInputTokens || nextOutput >= maxOutputTokens
        return NovexLearningUsageLedger(
            preflightId = preflightId,
            maxInputTokens = maxInputTokens,
            maxOutputTokens = maxOutputTokens,
            usedInputTokens = nextInput,
            usedOutputTokens = nextOutput,
            containsEstimates = containsEstimates || estimated,
            containsUnknownLegacyUsage = containsUnknownLegacyUsage,
            status = if (reachesLimit) {
                NovexLearningTaskStatus.PAUSED_BUDGET_REACHED
            } else {
                status
            },
        )
    }

    companion object {
        fun start(
            preflight: NovexLearningPreflightSnapshot,
            confirmation: NovexLearningConfirmation,
        ): NovexLearningUsageLedger {
            require(NovexLearningGate.authorize(preflight, confirmation) == NovexLearningAuthorization.AUTHORIZED) {
                "学习任务尚未获得与当前预检匹配的用户确认"
            }
            return NovexLearningUsageLedger(
                preflightId = preflight.id,
                maxInputTokens = confirmation.maxInputTokens,
                maxOutputTokens = confirmation.maxOutputTokens,
                usedInputTokens = 0,
                usedOutputTokens = 0,
                status = NovexLearningTaskStatus.INDEXING,
            )
        }

        internal fun restore(
            preflightId: String,
            maxInputTokens: Int,
            maxOutputTokens: Int,
            usedInputTokens: Int,
            usedOutputTokens: Int,
            status: NovexLearningTaskStatus,
            containsEstimates: Boolean = false,
            containsUnknownLegacyUsage: Boolean = false,
        ): NovexLearningUsageLedger {
            require(usedInputTokens >= 0 && usedOutputTokens >= 0) { "实际模型用量不能为负数" }
            return NovexLearningUsageLedger(
                preflightId = preflightId,
                maxInputTokens = maxInputTokens,
                maxOutputTokens = maxOutputTokens,
                usedInputTokens = usedInputTokens,
                usedOutputTokens = usedOutputTokens,
                containsEstimates = containsEstimates,
                containsUnknownLegacyUsage = containsUnknownLegacyUsage,
                status = if (usedInputTokens >= maxInputTokens || usedOutputTokens >= maxOutputTokens)
                    NovexLearningTaskStatus.PAUSED_BUDGET_REACHED else status,
            )
        }
    }
}
