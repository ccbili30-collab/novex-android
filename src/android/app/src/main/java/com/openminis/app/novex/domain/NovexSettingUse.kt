package com.openminis.app.novex.domain

import org.json.JSONObject

/** Conversation-only use preferences. They never grant management access or change an identity. */
object NovexSettingUse {
    fun enabled(configuration: NovexConversationConfigurationSnapshot, target: NovexReferenceTarget): Boolean =
        configuration.disabledSettings.none { disabled -> disabled.subject == target.subject &&
            (disabled.moduleId == null || disabled.moduleId == target.moduleId) }

    fun references(raw: String, key: String): List<NovexCardReference>? = JSONObject(raw).optJSONArray(key)?.let { array ->
        (0 until array.length()).map { NovexCardReferenceCodec.decode(array.getJSONObject(it).toString()) }
    }

    fun moduleId(source: NovexFrozenContext, candidate: NovexContextCandidate): String? = source.target.moduleId ?: candidate.sourceId.takeUnless {
        it == "world:${source.target.subject.id}:overview" || it == "character-version:${source.target.subject.id}:profile" ||
            it == "character-version:${source.target.subject.id}:instructions" || it == "game-reference:${source.target.subject.id}:summary"
    }?.substringBefore(":entry:")

    fun filterSource(configuration: NovexConversationConfigurationSnapshot, source: NovexFrozenContext): NovexFrozenContext? {
        if (source.actorVersionId != null) return source
        if (!enabled(configuration, source.target)) return null
        return source.copy(candidates = source.candidates.filter { candidate ->
            enabled(configuration, NovexReferenceTarget(source.target.subject, moduleId(source, candidate)))
        }, media = source.media.filter { image -> enabled(configuration,
            NovexReferenceTarget(image.owner ?: source.target.subject, image.moduleId)) })
    }

    /** Evaluate each saved adoption separately: another enabled root may still reach a shared child. */
    fun select(configuration: NovexConversationConfigurationSnapshot, root: NovexReferenceTarget,
        sources: List<NovexFrozenContext>, references: List<NovexCardReference>?, acting: Boolean = false): List<NovexFrozenContext> {
        if (!acting && !enabled(configuration, root)) return emptyList()
        if (references == null) return sources.mapNotNull { filterSource(configuration, it) }
        val visited = linkedSetOf<NovexReferenceTarget>()
        val queue = java.util.ArrayDeque<NovexReferenceTarget>()
        val outgoing = references.groupBy { it.source }
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            outgoing[current.subject].orEmpty().filter { reference ->
                reference.purpose in setOf(NovexReferencePurpose.BACKGROUND, NovexReferencePurpose.RULES) &&
                    (current.moduleId == null || current.moduleId == reference.sourceModuleId) &&
                    ((acting && current == root) || enabled(configuration, NovexReferenceTarget(reference.source, reference.sourceModuleId))) &&
                    enabled(configuration, reference.target)
            }.forEach { queue.add(it.target) }
        }
        return sources.filter { it.target in visited }.mapNotNull { filterSource(configuration, it) }
    }

    fun validateToggle(configuration: NovexConversationConfigurationSnapshot, target: NovexReferenceTarget, enable: Boolean) {
        require(target.entryId == null) { "使用开关精确到模块；条目继续沿用模块开关" }
        require(target.subject.kind != NovexContentKind.CREATIVE_ARTIFACT) { "文件使用范围沿用工作区权限" }
        require(configuration.activeInteractiveFiction?.projectId != target.subject.id ||
            target.subject.kind != NovexContentKind.INTERACTIVE_FICTION || target.moduleId != null) { "请通过结束文游停止活动局次" }
        if (enable) return
        // Legacy lists cannot establish which module supplies a transitive dependency. Never read current references to guess.
        configuration.adoptedContexts.filter { it.isActive(configuration) }.forEach { adopted ->
            if (adopted.references == null && adopted.sources.size > 1 && adopted.sources.any { it.target.subject == target.subject }) {
                require(!adopted.acting && target.subject == adopted.root && target.moduleId == null) {
                    "此项旧快照没有保存引用路径；请先明确从原卡刷新该项，再关闭配套卡或模块"
                }
            }
        }
        configuration.activeInteractiveFiction?.let { game ->
            if (references(game.contentJson, "backgroundReferences") == null &&
                NovexFrozenContextCodec.read(game.contentJson).any { it.adoptedByGame && it.actorVersionId == null }) {
                require(NovexFrozenContextCodec.read(game.contentJson).none { it.target.subject == target.subject } &&
                    target.subject != NovexContentAddress.interactiveFiction(game.projectId)) {
                    "此局旧快照没有单独保存文游引用路径；请先明确刷新文游资料，再关闭配套卡或模块"
                }
            }
        }
    }
}
