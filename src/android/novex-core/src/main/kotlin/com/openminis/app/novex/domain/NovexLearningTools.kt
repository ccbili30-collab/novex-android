package com.openminis.app.novex.domain

import org.json.JSONObject

fun interface NovexLearningPreflightResolver {
    fun prepare(collectionRef: NovexResourceRef, modelId: String?): NovexLearningPreflightSnapshot?
}

/** Read-only model seam. Starting and confirming a task remain application-internal operations. */
class NovexLearningTools(
    private val preflights: NovexLearningPreflightResolver,
) {
    fun learningPrepare(
        collectionRef: NovexResourceRef,
        modelId: String?,
    ): NovexToolResult {
        val preflight = preflights.prepare(collectionRef, modelId) ?: return NovexToolResult.failure(
            code = "learning.collection_not_found",
            summary = "找不到当前对话可用的资料集",
            affectedRefs = listOf(collectionRef),
        )
        return NovexToolResult.success(
            code = "learning.preflight_ready",
            summary = if (preflight.requiresConfirmation) {
                "学习预检已准备好，需要等待用户在原生界面确认"
            } else {
                "资料规模较小，可以在当前对话中按需读取"
            },
            data = mapOf(
                "preflight_id" to preflight.id,
                "collection_ref" to preflight.collectionRef.value,
                "model_id" to preflight.modelId,
                "model_provider" to preflight.modelProviderName,
                "source_count" to preflight.sourceCount,
                "page_count" to preflight.pageCount,
                "image_count" to preflight.imageCount,
                "ocr_source_count" to preflight.ocrSourceCount,
                "network_source_count" to preflight.networkSourceCount,
                "estimated_source_tokens" to preflight.estimatedSourceTokens,
                "estimated_model_rounds" to preflight.estimatedModelRounds,
                "estimated_cost" to preflight.estimatedCost?.let { cost ->
                    mapOf(
                        "currency" to cost.currencyCode,
                        "minimum_minor_units" to cost.minimumMinorUnits,
                        "maximum_minor_units" to cost.maximumMinorUnits,
                    )
                },
                "planned_steps" to preflight.plannedSteps,
                "requires_confirmation" to preflight.requiresConfirmation,
                "prohibited_outcomes" to preflight.prohibitedOutcomes.toList().sorted(),
            ),
            warnings = preflight.risks.map { risk -> NovexToolWarning(risk.code, risk.message) },
            nextActions = if (preflight.requiresConfirmation) {
                listOf(NovexToolNextAction("wait_for_native_confirmation", "等待用户确认整理计划"))
            } else {
                listOf(NovexToolNextAction("read_documents", "按需读取资料"))
            },
            affectedRefs = listOf(preflight.collectionRef),
        )
    }
}

class NovexLearningToolRouter(
    private val tools: NovexLearningTools,
) {
    fun execute(name: String, argumentsJson: String): NovexToolResult {
        if (name != LEARNING_PREPARE) {
            return NovexToolResult.failure(
                code = "tool.unknown",
                summary = "当前学习工具不存在",
                allowedValues = listOf(LEARNING_PREPARE),
            )
        }
        return runCatching {
            val arguments = JSONObject(argumentsJson.ifBlank { "{}" })
            val collection = arguments.optString("collection_ref").trim()
            require(collection.startsWith("novex://source-collections/")) {
                "collection_ref 必须是 Novex 资料集引用"
            }
            tools.learningPrepare(
                collectionRef = NovexResourceRef(collection),
                modelId = arguments.optString("model_id").trim().ifBlank { null },
            )
        }.getOrElse { failure ->
            NovexToolResult.failure(
                code = "tool.invalid_arguments",
                summary = failure.message?.takeIf(String::isNotBlank) ?: "学习工具参数无效",
                allowedValues = listOf(LEARNING_PREPARE),
            )
        }
    }

    companion object {
        const val LEARNING_PREPARE = "learning_prepare"
    }
}
