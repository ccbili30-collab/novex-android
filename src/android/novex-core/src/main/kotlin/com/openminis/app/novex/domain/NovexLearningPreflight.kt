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
    val effectiveContextTokens: Int?,
    val occupiedContextTokens: Int,
    val directReadBudgetTokens: Int,
    val proposedBudget: NovexLearningTokenBudget,
) {
    init {
        require(collectionRef.value.startsWith("novex://source-collections/")) { "学习预检必须属于资料集" }
        require(sources.isNotEmpty()) { "学习预检至少需要一项资料" }
        require(sources.map { it.ref }.distinct().size == sources.size) { "学习预检不能包含重复引用" }
        require(modelId.isNotBlank()) { "学习模型编号不能为空" }
        require(effectiveContextTokens == null || effectiveContextTokens > 0) { "有效上下文必须大于零" }
        require(occupiedContextTokens >= 0) { "已占用上下文不能为负数" }
        require(directReadBudgetTokens > 0) { "直接读取预算必须大于零" }
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

data class NovexLearningPreflightSnapshot(
    val id: String,
    val collectionRef: NovexResourceRef,
    val sourceRefs: List<NovexResourceRef>,
    val modelId: String,
    val route: NovexLearningRoute,
    val estimatedSourceTokens: Int,
    val estimatedModelRounds: Int,
    val pageCount: Int,
    val imageCount: Int,
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
) {
    val requiresConfirmation: Boolean get() = route == NovexLearningRoute.CONFIRMATION_REQUIRED
}

object NovexLearningPreflight {
    private const val TOKENS_PER_MODEL_ROUND = 32_000
    private const val MAX_DIRECT_FILE_COUNT = 3

    fun prepare(request: NovexLearningPreflightRequest): NovexLearningPreflightSnapshot {
        val totalTokens = request.sources.sumOf { it.estimatedTokens }
        val hasExpensiveCapability = request.sources.any {
            it.requiresNetwork || it.requiresOcr || it.unsupportedReason != null
        }
        val contextRoom = request.effectiveContextTokens?.let {
            (it - request.occupiedContextTokens).coerceAtLeast(0)
        }
        val directBudget = listOfNotNull(request.directReadBudgetTokens, contextRoom).minOrNull()
            ?: request.directReadBudgetTokens
        val canReadDirectly = request.sources.size <= MAX_DIRECT_FILE_COUNT &&
            totalTokens <= directBudget &&
            !hasExpensiveCapability
        val risks = buildList {
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
        }
        val canonical = buildString {
            append(request.collectionRef.value).append('\n')
            append(request.modelId).append('\n')
            append(request.effectiveContextTokens).append(':')
                .append(request.occupiedContextTokens).append(':')
                .append(request.directReadBudgetTokens).append('\n')
            append(request.proposedBudget.inputTokens).append(':')
                .append(request.proposedBudget.outputTokens).append('\n')
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
        return NovexLearningPreflightSnapshot(
            id = "preflight_" + sha256(canonical).take(24),
            collectionRef = request.collectionRef,
            sourceRefs = request.sources.map { it.ref },
            modelId = request.modelId,
            route = if (canReadDirectly) NovexLearningRoute.DIRECT_READ else NovexLearningRoute.CONFIRMATION_REQUIRED,
            estimatedSourceTokens = totalTokens,
            estimatedModelRounds = ceil(totalTokens.toDouble() / TOKENS_PER_MODEL_ROUND).toInt().coerceAtLeast(1),
            pageCount = request.sources.sumOf { it.pageCount ?: 0 },
            imageCount = request.sources.sumOf { it.imageCount },
            confirmedBudget = request.proposedBudget,
            risks = risks,
            unsupportedSources = request.sources.mapNotNull { source ->
                source.unsupportedReason?.let { source.ref to it }
            }.toMap(),
        )
    }

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
) {
    fun canConsume(inputTokens: Int, outputTokens: Int): Boolean {
        require(inputTokens >= 0 && outputTokens >= 0) { "学习任务词元用量不能为负数" }
        return status != NovexLearningTaskStatus.PAUSED_BUDGET_REACHED &&
            usedInputTokens + inputTokens <= maxInputTokens &&
            usedOutputTokens + outputTokens <= maxOutputTokens
    }

    fun record(inputTokens: Int, outputTokens: Int): NovexLearningUsageLedger {
        require(canConsume(inputTokens, outputTokens)) { "本次调用会超过用户确认的学习预算" }
        val nextInput = usedInputTokens + inputTokens
        val nextOutput = usedOutputTokens + outputTokens
        val reachesLimit = nextInput == maxInputTokens || nextOutput == maxOutputTokens
        return NovexLearningUsageLedger(
            preflightId = preflightId,
            maxInputTokens = maxInputTokens,
            maxOutputTokens = maxOutputTokens,
            usedInputTokens = nextInput,
            usedOutputTokens = nextOutput,
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
    }
}
