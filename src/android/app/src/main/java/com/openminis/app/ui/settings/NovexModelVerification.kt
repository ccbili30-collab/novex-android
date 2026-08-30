package com.openminis.app.ui.settings

internal enum class NovexProbeStage { CHAT, TOOL }

internal data class NovexModelFailure(
    val modelId: String,
    val stage: NovexProbeStage,
    val detail: String,
)

internal data class NovexModelWarning(
    val modelId: String,
    val stage: NovexProbeStage,
    val detail: String,
)

internal data class NovexModelVerification(
    val availableModels: List<String>,
    val failures: List<NovexModelFailure>,
    val warnings: List<NovexModelWarning> = emptyList(),
)

/**
 * Checks every selected model. A failure removes only that model from the next
 * phase; it never aborts verification of the remaining selections.
 */
internal suspend fun verifyNovexModels(
    modelIds: List<String>,
    repetitions: Int = 1,
    onProgress: (NovexProbeStage, Int, Int, String) -> Unit = { _, _, _, _ -> },
    chatProbe: suspend (String) -> String?,
    toolProbe: suspend (String) -> String?,
): NovexModelVerification {
    val models = modelIds.map(String::trim).filter(String::isNotEmpty).distinct()
    require(repetitions >= 1) { "repetitions must be positive" }
    val failures = mutableListOf<NovexModelFailure>()
    val warnings = mutableListOf<NovexModelWarning>()
    val chatPassed = mutableListOf<String>()
    val requiredPasses = repetitions / 2 + 1

    for ((modelIndex, modelId) in models.withIndex()) {
        val errors = mutableListOf<String>()
        repeat(repetitions) { attempt ->
            onProgress(NovexProbeStage.CHAT, modelIndex, models.size, modelId)
            val error = runCatching { chatProbe(modelId) }
                .getOrElse { it.message ?: it.javaClass.simpleName }
            if (error != null) errors += "第 ${attempt + 1}/$repetitions 轮：$error"
        }
        val passedCount = repetitions - errors.size
        if (passedCount >= requiredPasses) {
            chatPassed += modelId
            if (errors.isNotEmpty()) warnings += NovexModelWarning(
                modelId,
                NovexProbeStage.CHAT,
                "通过 $passedCount/$repetitions；${errors.joinToString("；")}",
            )
        } else {
            failures += NovexModelFailure(
                modelId,
                NovexProbeStage.CHAT,
                "仅通过 $passedCount/$repetitions；${errors.joinToString("；")}",
            )
        }
    }

    val available = mutableListOf<String>()
    for ((modelIndex, modelId) in chatPassed.withIndex()) {
        val errors = mutableListOf<String>()
        repeat(repetitions) { attempt ->
            onProgress(NovexProbeStage.TOOL, modelIndex, chatPassed.size, modelId)
            val error = runCatching { toolProbe(modelId) }
                .getOrElse { it.message ?: it.javaClass.simpleName }
            if (error != null) errors += "第 ${attempt + 1}/$repetitions 轮：$error"
        }
        val passedCount = repetitions - errors.size
        if (passedCount >= requiredPasses) {
            available += modelId
            if (errors.isNotEmpty()) warnings += NovexModelWarning(
                modelId,
                NovexProbeStage.TOOL,
                "通过 $passedCount/$repetitions；${errors.joinToString("；")}",
            )
        } else {
            failures += NovexModelFailure(
                modelId,
                NovexProbeStage.TOOL,
                "仅通过 $passedCount/$repetitions；${errors.joinToString("；")}",
            )
        }
    }

    return NovexModelVerification(available, failures, warnings)
}

internal fun formatNovexVerificationReport(result: NovexModelVerification): String = buildString {
    if (result.availableModels.isNotEmpty()) {
        append("可用模型（${result.availableModels.size}）：")
        append(result.availableModels.joinToString("、"))
    } else {
        append("没有检测通过的模型。")
    }
    if (result.failures.isNotEmpty()) {
        append("\n不可用模型（${result.failures.size}）：")
        result.failures.forEach { failure ->
            val stage = if (failure.stage == NovexProbeStage.CHAT) "普通对话" else "工具调用"
            append("\n- ${failure.modelId}（$stage）：${failure.detail}")
        }
    }
    if (result.warnings.isNotEmpty()) {
        append("\n不稳定但可用（${result.warnings.map { it.modelId }.distinct().size}）：")
        result.warnings.forEach { warning ->
            val stage = if (warning.stage == NovexProbeStage.CHAT) "普通对话" else "工具调用"
            append("\n- ${warning.modelId}（$stage）：${warning.detail}")
        }
    }
}
