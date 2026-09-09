package com.openminis.app.novex.domain

import org.json.JSONArray
import org.json.JSONObject

/** Changes only adopted use preferences. Source cards and their content revisions stay untouched. */
object NovexWorldbookUse {
    /** Disabled missing sources stay listed but do not prevent unrelated enabled settings from starting. */
    fun requiredMissing(root: NovexReferenceTarget, traversal: NovexReferenceTraversalResult): Set<NovexReferenceTarget> {
        val seen = linkedSetOf<NovexReferenceTarget>()
        val queue = java.util.ArrayDeque<NovexReferenceTarget>()
        queue.add(root)
        val outgoing = traversal.references.groupBy { it.source }
        while (queue.isNotEmpty()) {
            val target = queue.removeFirst()
            if (!seen.add(target)) continue
            outgoing[target.subject].orEmpty().filter { it.enabled &&
                (target.moduleId == null || target.moduleId == it.sourceModuleId) }.forEach { queue.add(it.target) }
        }
        return traversal.missingTargets.intersect(seen)
    }

    fun references(configuration: NovexConversationConfigurationSnapshot): List<NovexCardReference> =
        (configuration.activeInteractiveFiction?.let { NovexSettingUse.references(it.contentJson, "backgroundReferences") }.orEmpty() +
            configuration.adoptedContexts.filter { it.isActive(configuration) }.flatMap { it.references.orEmpty() })
            .filter { it.purpose in setOf(NovexReferencePurpose.BACKGROUND, NovexReferencePurpose.RULES) }
            .also { references -> require(references.groupBy { it.id }.values.none { it.distinct().size > 1 }) {
                "世界书引用存在不同采用修订，请先明确刷新相关来源"
            } }.distinctBy { it.id }

    fun setReference(configuration: NovexConversationConfigurationSnapshot, referenceId: String, enabled: Boolean): NovexConversationConfigurationSnapshot {
        val reference = requireNotNull(references(configuration).singleOrNull { it.id == referenceId }) { "这条设定不属于当前采用范围" }
        if (enabled) {
            val available = NovexEffectiveFrozenContext.gameSources(configuration) +
                configuration.adoptedContexts.filter { it.isActive(configuration) }.flatMap { it.sources }
            require(available.any { it.target == reference.target }) { "这份设定缺少已保存内容，请刷新采用资料后再启用" }
        }
        return rewrite(configuration) { if (it.id == referenceId) it.copy(enabled = enabled) else it }
    }

    private fun rewrite(configuration: NovexConversationConfigurationSnapshot,
        change: (NovexCardReference) -> NovexCardReference): NovexConversationConfigurationSnapshot {
        val game = configuration.activeInteractiveFiction?.let { current ->
            val root = JSONObject(current.contentJson)
            var changed = false
            listOf("references", "backgroundReferences").forEach { key ->
                NovexSettingUse.references(current.contentJson, key)?.let { original ->
                    val updated = original.map { if (it.purpose in setOf(NovexReferencePurpose.BACKGROUND, NovexReferencePurpose.RULES)) change(it) else it }
                    if (updated != original) {
                        root.put(key, JSONArray(updated.map { JSONObject(NovexCardReferenceCodec.encode(it)) }))
                        changed = true
                    }
                }
            }
            if (changed) root.toString().let { current.copy(contentJson = it, snapshotId = NovexFrozenContextCodec.digest(it)) } else current
        }
        return configuration.copy(activeInteractiveFiction = game,
            adoptedContexts = configuration.adoptedContexts.map { adoption ->
                if (adoption.isActive(configuration)) adoption.copy(references = adoption.references?.map {
                    if (it.purpose in setOf(NovexReferencePurpose.BACKGROUND, NovexReferencePurpose.RULES)) change(it) else it
                }) else adoption
            })
    }
}
