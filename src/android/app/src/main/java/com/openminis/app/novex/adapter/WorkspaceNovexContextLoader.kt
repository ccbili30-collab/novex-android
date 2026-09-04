package com.openminis.app.novex.adapter

import com.openminis.app.data.character.CharacterVersionProfile
import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.ModuleReferenceTargetType
import com.openminis.app.data.character.toPlainText
import com.openminis.app.novex.domain.AnswerIdentity
import com.openminis.app.novex.domain.ContextSourceKind
import com.openminis.app.novex.domain.NovexContentKind
import com.openminis.app.novex.domain.NovexContextCandidate
import com.openminis.app.novex.domain.NovexConversationConfigurationSnapshot
import com.openminis.app.novex.domain.NovexWorkspace
import org.json.JSONArray
import org.json.JSONObject

/**
 * Resolves only content that the conversation explicitly uses as background, answer identity or
 * an active game. Managed subjects are intentionally absent: edit authorization is not context.
 */
class WorkspaceNovexContextLoader(
    private val workspace: NovexWorkspace,
) {
    suspend fun load(configuration: NovexConversationConfigurationSnapshot): List<NovexContextCandidate> {
        val candidates = mutableListOf<NovexContextCandidate>()
        val backgroundWorldIds = configuration.backgroundSettings
            .filter { it.subject.kind == NovexContentKind.WORLD }
            .map { it.subject.id }
        val backgroundVersionIds = configuration.backgroundSettings
            .filter { it.subject.kind == NovexContentKind.CHARACTER_VERSION }
            .map { it.subject.id }
        val identityVersionId = (configuration.answerIdentity as? AnswerIdentity.CharacterVersion)?.versionId

        backgroundWorldIds.distinct().forEach { worldId ->
            val snapshot = workspace.world(worldId) ?: return@forEach
            val coreId = worldCoreId(worldId)
            candidates += NovexContextCandidate(
                sourceId = coreId,
                label = "世界 · ${snapshot.world.name} · 世界观概述",
                content = snapshot.world.overview,
                aliases = setOf(snapshot.world.name) + stringArray(snapshot.world.tagsJson),
                alwaysInclude = true,
                position = -1,
            )
            candidates += moduleCandidates(
                ownerLabel = "世界 · ${snapshot.world.name}",
                modules = snapshot.modules,
            )
        }

        val requestedVersions = (backgroundVersionIds + listOfNotNull(identityVersionId)).distinct()
        if (requestedVersions.isNotEmpty()) {
            val versions = workspace.characters().flatMap { card ->
                card.character.allVersions.map { version -> Triple(card.character.character.name, version, card) }
            }.associateBy { it.second.id }
            requestedVersions.forEach { versionId ->
                val (rootName, version) = versions[versionId]?.let { it.first to it.second } ?: return@forEach
                val identity = versionId == identityVersionId
                val profile = CharacterVersionProfile.fromJson(version.profileJson, rootName)
                candidates += NovexContextCandidate(
                    sourceId = characterCoreId(versionId),
                    label = "角色 · $rootName · ${version.label}",
                    content = profile.toContextText(version.label),
                    kind = if (identity) ContextSourceKind.ANSWER_IDENTITY else ContextSourceKind.BACKGROUND_MODULE,
                    aliases = buildSet {
                        add(rootName)
                        add(version.label)
                        add(profile.name)
                        addAll(profile.tags)
                        profile.relationships.forEach { add(it.characterName) }
                    }.filterTo(linkedSetOf()) { it.isNotBlank() },
                    alwaysInclude = true,
                    position = -1,
                )
                val modules = workspace.modules(ModuleOwner.characterVersion(versionId)).modules
                candidates += moduleCandidates(
                    ownerLabel = "角色 · $rootName · ${version.label}",
                    modules = modules,
                    kind = if (identity) ContextSourceKind.ANSWER_IDENTITY else ContextSourceKind.BACKGROUND_MODULE,
                )
            }
        }

        configuration.activeInteractiveFiction?.let { active ->
            candidates += gameCandidates(active.snapshotId, active.title, active.contentJson)
        }
        return candidates.mergeDuplicates()
    }

    private suspend fun moduleCandidates(
        ownerLabel: String,
        modules: List<ContentModuleEntity>,
        kind: ContextSourceKind = ContextSourceKind.BACKGROUND_MODULE,
    ): List<NovexContextCandidate> = modules.sortedBy(ContentModuleEntity::position).map { module ->
        val document = ContentModuleDocumentCodec.decode(module.type, module.contentJson)
        val references = workspace.module(module.id)?.references.orEmpty()
        NovexContextCandidate(
            sourceId = module.id,
            label = "$ownerLabel · ${module.name}",
            content = document.toPlainText(),
            kind = kind,
            aliases = buildSet {
                add(module.name)
                addAll(document.aliases())
            },
            relatedSourceIds = references.mapTo(linkedSetOf()) { reference ->
                when (reference.targetType) {
                    ModuleReferenceTargetType.MODULE -> reference.targetId
                    ModuleReferenceTargetType.WORLD -> worldCoreId(reference.targetId)
                    ModuleReferenceTargetType.CHARACTER_VERSION -> characterCoreId(reference.targetId)
                }
            },
            position = module.position,
        )
    }

    private fun gameCandidates(snapshotId: String, title: String, raw: String): List<NovexContextCandidate> {
        val root = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val prefix = "game:$snapshotId"
        val core = listOf(
            root.optString("summary").takeIf(String::isNotBlank),
            root.optString("playerIdentity").takeIf(String::isNotBlank)?.let { "玩家身份：$it" },
            root.optString("launchMode").takeIf(String::isNotBlank)?.let { "启动方式：$it" },
        ).filterNotNull().joinToString("\n")
        return buildList {
            add(
                NovexContextCandidate(
                    sourceId = "$prefix:core",
                    label = "文游 · $title · 核心设定",
                    content = core,
                    aliases = setOf(title),
                    alwaysInclude = true,
                    position = -1,
                ),
            )
            val modules = root.optJSONArray("modules") ?: JSONArray()
            repeat(modules.length()) { index ->
                val value = modules.optJSONObject(index) ?: return@repeat
                val moduleId = value.optString("id").ifBlank { index.toString() }
                val typeName = value.optString("type")
                val type = runCatching {
                    com.openminis.app.data.character.ContentModuleType.valueOf(typeName)
                }.getOrNull() ?: return@repeat
                val document = ContentModuleDocumentCodec.decode(type, value.optString("contentJson", "{}"))
                add(
                    NovexContextCandidate(
                        sourceId = "$prefix:module:$moduleId",
                        label = "文游 · $title · ${value.optString("name").ifBlank { typeName }}",
                        content = document.toPlainText(),
                        aliases = buildSet {
                            add(value.optString("name"))
                            addAll(document.aliases())
                        }.filterTo(linkedSetOf()) { it.isNotBlank() },
                        alwaysInclude = typeName == "GAME_NARRATIVE_RULES" || typeName == "GAME_PLAYER_IDENTITY",
                        position = value.optInt("position", index),
                    ),
                )
            }
        }
    }

    private fun ContentModuleDocument.aliases(): Set<String> = when (this) {
        is ContentModuleDocument.Timeline -> nodes.flatMapTo(linkedSetOf()) { listOf(it.time, it.title) }
        is ContentModuleDocument.Collection -> items.mapTo(linkedSetOf()) { it.name }
        else -> emptySet()
    }.filterTo(linkedSetOf()) { it.isNotBlank() }

    private fun CharacterVersionProfile.toContextText(versionLabel: String): String = buildList {
        add("姓名：$name")
        add("版本：$versionLabel")
        if (tags.isNotEmpty()) add("标签：${tags.joinToString("、")}")
        gender.takeIf(String::isNotBlank)?.let { add("性别：$it") }
        age.takeIf(String::isNotBlank)?.let { add("年龄：$it") }
        race.takeIf(String::isNotBlank)?.let { add("种族：$it") }
        occupation.takeIf(String::isNotBlank)?.let { add("职业：$it") }
        summary.takeIf(String::isNotBlank)?.let { add("简介：$it") }
        customAttributes.forEach { add("${it.name}：${it.value}") }
        relationships.forEach { relation ->
            add("关系 · ${relation.characterName}：${relation.relationship} ${relation.description}".trim())
        }
    }.joinToString("\n")

    private fun List<NovexContextCandidate>.mergeDuplicates(): List<NovexContextCandidate> {
        val merged = linkedMapOf<String, NovexContextCandidate>()
        forEach { candidate ->
            val previous = merged[candidate.sourceId]
            merged[candidate.sourceId] = if (previous == null) candidate else previous.copy(
                kind = if (
                    previous.kind == ContextSourceKind.ANSWER_IDENTITY ||
                    candidate.kind == ContextSourceKind.ANSWER_IDENTITY
                ) ContextSourceKind.ANSWER_IDENTITY else previous.kind,
                aliases = previous.aliases + candidate.aliases,
                relatedSourceIds = previous.relatedSourceIds + candidate.relatedSourceIds,
                alwaysInclude = previous.alwaysInclude || candidate.alwaysInclude,
            )
        }
        return merged.values.toList()
    }

    private fun stringArray(raw: String): Set<String> = runCatching {
        val array = JSONArray(raw)
        buildSet { repeat(array.length()) { index -> array.optString(index).takeIf(String::isNotBlank)?.let(::add) } }
    }.getOrDefault(emptySet())

    private fun worldCoreId(id: String) = "world:$id:overview"
    private fun characterCoreId(id: String) = "character-version:$id:profile"
}
