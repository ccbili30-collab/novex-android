package com.openminis.app.novex.domain

/** Same address and same text can merge; different adopted text must stay independently readable. */
object NovexContextSourceVersions {
    fun merge(candidates: List<NovexContextCandidate>): List<NovexContextCandidate> {
        val bySource = candidates.groupBy { it.sourceId }
        bySource.values.forEach { versions ->
            if (versions.any { it.worldbookConditions.isNotEmpty() }) require(versions.map { it.content to it.worldbookConditions }.distinct().size <= 1) {
                "世界书存在不同采用修订，请在对话设置中明确刷新来源后再继续"
            }
        }
        val ids = bySource.mapValues { (id, values) ->
            val bodies = values.map { it.content }.distinct()
            bodies.map { body ->
                if (bodies.size == 1) id else versionedId(id, body)
            }.toSet()
        }
        return bySource.flatMap { (id, values) ->
            values.groupBy { it.content }.map { (body, same) ->
                val first = same.first()
                val differs = ids.getValue(id).size > 1
                first.copy(sourceId = if (differs) versionedId(id, body) else id,
                    label = first.label + if (differs) " · 并列修订 ${NovexFrozenContextCodec.digest(body).take(12)}" else "",
                    kind = if (same.any { it.kind == ContextSourceKind.ANSWER_IDENTITY }) ContextSourceKind.ANSWER_IDENTITY else first.kind,
                    aliases = same.flatMap { it.aliases }.toSet(),
                    relatedSourceIds = same.flatMap { it.relatedSourceIds }.flatMap { ids[it] ?: setOf(it) }.toSet(),
                    alwaysInclude = same.any { it.alwaysInclude }, position = same.minOf { it.position })
            }
        }
    }
    fun versionedId(sourceId: String, body: String) = "$sourceId@revision:${NovexFrozenContextCodec.digest(body)}"
}

data class NovexAdoptedSourceUsage(val source: NovexFrozenContext, val revision: String, val origins: Set<String>)

/** Read-only explanation uses saved snapshots, never the latest originals. */
object NovexAdoptedSourceUsageProjection {
    fun read(configuration: NovexConversationConfigurationSnapshot): List<NovexAdoptedSourceUsage> {
        val rows = mutableListOf<Pair<NovexFrozenContext, String>>()
        configuration.adoptedContexts.filter { it.isActive(configuration) }.forEach { adopted ->
            rows += adopted.sources.map { it to if (adopted.acting) "回答身份" else "直接加入 · ${adopted.root.id}" }
        }
        val game = configuration.activeInteractiveFiction
        NovexEffectiveFrozenContext.gameSources(configuration).forEach { source ->
            if (source.actorVersionId != null) {
                if ((configuration.answerIdentity as? AnswerIdentity.CharacterVersion)?.versionId == source.actorVersionId) rows += source to "文游指定的回答身份"
            } else {
                if (source.adoptedByGame) rows += source to "活动文游 · ${game?.title.orEmpty()}"
                source.conversationRoots.filter { root -> configuration.backgroundSettings.any { it.subject == root } }
                    .forEach { rows += source to "直接加入 · ${it.id}" }
            }
        }
        return rows.groupBy { (source, _) -> source.target to revision(source) }
            .map { (key, same) -> NovexAdoptedSourceUsage(same.first().first, key.second, same.map { it.second }.toSet()) }
    }

    private fun revision(source: NovexFrozenContext): String {
        val encoded = NovexFrozenContextCodec.encode(source)
        // Adoption paths and local image paths are not content revisions; retained image bytes are.
        val media = source.media.map {
            listOf(it.owner?.kind?.name, it.owner?.id, it.moduleId, it.entryId, it.slot.name, it.asset.sha256)
        }.map { org.json.JSONArray(it).toString() }.sorted()
        return NovexFrozenContextCodec.digest(encoded.getJSONArray("sources").toString() +
            org.json.JSONArray(media).toString() + source.mediaCaptured + source.tavernWorldbookJson.orEmpty())
    }
}
