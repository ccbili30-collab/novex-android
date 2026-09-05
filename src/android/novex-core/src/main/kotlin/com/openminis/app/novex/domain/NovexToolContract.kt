package com.openminis.app.novex.domain

import org.json.JSONArray
import org.json.JSONObject

/** Stable public reference. Device paths and storage identifiers never cross this seam. */
data class NovexResourceRef(val value: String) {
    init {
        require(value.startsWith("novex://")) { "资源引用必须使用 novex:// 协议" }
        require(!value.contains("..")) { "资源引用不能包含路径逃逸" }
        require(value.removePrefix("novex://").contains('/')) { "资源引用必须包含类型和编号" }
    }
}

enum class NovexToolSideEffect(val wireName: String) {
    NONE("none"),
    SESSION_REVERSIBLE("session_reversible"),
    SHARED_WRITE("shared_write"),
    EXTERNAL("external"),
}

enum class NovexToolRisk {
    READ_ONLY,
    SESSION_REVERSIBLE,
    SHARED_WRITE,
    EXTERNAL_SIDE_EFFECT,
}

enum class NovexExecutionGate {
    DIRECT,
    CONFIRMED_PLAN,
    EXPLICIT_AUTHORIZATION,
}

object NovexToolPermissionPolicy {
    fun gateFor(risk: NovexToolRisk): NovexExecutionGate = when (risk) {
        NovexToolRisk.READ_ONLY,
        NovexToolRisk.SESSION_REVERSIBLE,
        -> NovexExecutionGate.DIRECT

        NovexToolRisk.SHARED_WRITE -> NovexExecutionGate.CONFIRMED_PLAN
        NovexToolRisk.EXTERNAL_SIDE_EFFECT -> NovexExecutionGate.EXPLICIT_AUTHORIZATION
    }
}

data class NovexToolWarning(
    val code: String,
    val message: String,
)

data class NovexToolNextAction(
    val id: String,
    val label: String,
)

/**
 * Provider-independent result envelope used by every Novex model tool.
 *
 * The payload remains JSON-shaped so adapters can encode it for any model provider without
 * exposing their own exception names, paths or result formats.
 */
class NovexToolResult private constructor(
    val ok: Boolean,
    val code: String,
    val summary: String,
    val data: Map<String, Any?>,
    val warnings: List<NovexToolWarning>,
    val nextActions: List<NovexToolNextAction>,
    val affectedRefs: List<NovexResourceRef>,
    val sideEffect: NovexToolSideEffect,
    val allowedValues: List<String>,
) {
    init {
        require(code.matches(Regex("[a-z][a-z0-9_.-]*"))) { "工具结果码必须是稳定的小写名称" }
        require(summary.isNotBlank()) { "工具结果摘要不能为空" }
        require(warnings.all { it.code.isNotBlank() && it.message.isNotBlank() }) {
            "工具警告必须包含编号和说明"
        }
        require(nextActions.all { it.id.isNotBlank() && it.label.isNotBlank() }) {
            "后续动作必须包含编号和名称"
        }
        require(allowedValues.none(String::isBlank)) { "允许值不能为空" }
    }

    fun toJson(): String = JSONObject()
        .put("ok", ok)
        .put("code", code)
        .put("summary", summary)
        .put("data", JSONObject(data))
        .put(
            "warnings",
            JSONArray(warnings.map { warning ->
                JSONObject().put("code", warning.code).put("message", warning.message)
            }),
        )
        .put(
            "next_actions",
            JSONArray(nextActions.map { action ->
                JSONObject().put("id", action.id).put("label", action.label)
            }),
        )
        .put("affected_refs", JSONArray(affectedRefs.map(NovexResourceRef::value)))
        .put("side_effect", sideEffect.wireName)
        .apply {
            if (allowedValues.isNotEmpty()) put("allowed_values", JSONArray(allowedValues))
        }
        .toString()

    companion object {
        fun success(
            code: String,
            summary: String,
            data: Map<String, Any?> = emptyMap(),
            warnings: List<NovexToolWarning> = emptyList(),
            nextActions: List<NovexToolNextAction> = emptyList(),
            affectedRefs: List<NovexResourceRef> = emptyList(),
            sideEffect: NovexToolSideEffect = NovexToolSideEffect.NONE,
        ) = NovexToolResult(
            ok = true,
            code = code,
            summary = summary,
            data = data,
            warnings = warnings,
            nextActions = nextActions,
            affectedRefs = affectedRefs,
            sideEffect = sideEffect,
            allowedValues = emptyList(),
        )

        fun failure(
            code: String,
            summary: String,
            data: Map<String, Any?> = emptyMap(),
            warnings: List<NovexToolWarning> = emptyList(),
            nextActions: List<NovexToolNextAction> = emptyList(),
            affectedRefs: List<NovexResourceRef> = emptyList(),
            allowedValues: List<String> = emptyList(),
        ) = NovexToolResult(
            ok = false,
            code = code,
            summary = summary,
            data = data,
            warnings = warnings,
            nextActions = nextActions,
            affectedRefs = affectedRefs,
            sideEffect = NovexToolSideEffect.NONE,
            allowedValues = allowedValues,
        )
    }
}
