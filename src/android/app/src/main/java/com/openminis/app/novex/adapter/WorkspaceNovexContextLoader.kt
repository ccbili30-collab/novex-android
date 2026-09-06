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

/** Older conversations can retain card snapshots whose originals no longer exist in the catalog. */
data class NovexLegacyContext(
    val characterVersionId: String? = null,
    val character: com.openminis.app.data.character.CharacterCard? = null,
    val world: com.openminis.app.data.character.StoryWorld? = null,
)

/**
 * Resolves only content that the conversation explicitly uses as background, answer identity or
 * an active game. Managed subjects are intentionally absent: edit authorization is not context.
 */
class WorkspaceNovexContextLoader(
    private val workspace: NovexWorkspace,
    private val legacy: NovexLegacyContext = NovexLegacyContext(),
    private val expandReferences: Boolean = true,
) {
    suspend fun load(configuration: NovexConversationConfigurationSnapshot): List<NovexContextCandidate> {
        val candidates = mutableListOf<NovexContextCandidate>()
        val adopted = configuration.adoptedContexts.filter { it.isActive(configuration) }
        val frozenContext = configuration.activeInteractiveFiction?.let {
            com.openminis.app.novex.domain.NovexFrozenContextCodec.read(it.contentJson)
        }.orEmpty().filter { source -> source.actorVersionId != null || source.adoptedByGame ||
            configuration.backgroundSettings.any { it.subject in source.conversationRoots } }
        val frozenBackgrounds = frozenContext.filter { it.actorVersionId == null && it.target.moduleId == null }
            .mapTo(mutableSetOf()) { it.target.subject }
        frozenBackgrounds += adopted.filterNot { it.acting }.map { it.root }
        when (val identity = configuration.answerIdentity) {
            AnswerIdentity.Nova -> candidates.add(NovexContextCandidate(
                sourceId = "answer-identity:nova",
                label = "回答身份 · Nova（诺瓦）",
                content = "你是 Nova（诺瓦），适合交流、资料整理与共同创作的助手。加入世界背景不自动开始扮演或游戏。",
                kind = ContextSourceKind.ANSWER_IDENTITY,
                alwaysInclude = true, position = Int.MIN_VALUE,
            ))
            is AnswerIdentity.PersonaPreset -> candidates.add(NovexContextCandidate(
                sourceId = "answer-identity:persona:${identity.presetId}",
                label = "回答人格 · ${identity.label}",
                content = "当前回答人格：${identity.label}\n${identity.instructions}",
                kind = ContextSourceKind.ANSWER_IDENTITY,
                alwaysInclude = true, position = Int.MIN_VALUE,
            ))
            is AnswerIdentity.CharacterVersion -> Unit
        }
        workspace.conversationDrafts(configuration.conversationId)?.let { drafts ->
            if (drafts.cards.isNotEmpty() || drafts.pendingWrites.isNotEmpty()) candidates += NovexContextCandidate(
                sourceId = "conversation-drafts:${configuration.conversationId}",
                label = "本对话 · 创作目标目录",
                content = buildString {
                    appendLine("本对话工作空间编号：${configuration.conversationId}。文件通过工作空间查看、读取和写入工具定位。")
                    appendLine("以下是创作目标目录，不是背景正文、角色身份或活动文游；仅按用户创作意图使用，不自动编写空卡。")
                    drafts.cards.forEach { card -> appendLine("${when (card.subject.kind) {
                        NovexContentKind.WORLD -> "世界"
                        NovexContentKind.CHARACTER_VERSION -> "角色版本"
                        NovexContentKind.INTERACTIVE_FICTION -> "文游"
                        NovexContentKind.CREATIVE_ARTIFACT -> "创作文件"
                    }}：${card.subject.id}（${if (card.isPrivate) "本对话私有" else "已归库，来源为本对话"}）") }
                    drafts.pendingWrites.forEach { appendLine("待执行计划编号：${it.id}，未执行不等于已保存正文。") }
                },
                kind = ContextSourceKind.TOOL_DEFINITION, alwaysInclude = true, position = -2,
            )
        }
        configuration.playerIdentity?.let { player ->
            candidates += NovexContextCandidate(
                sourceId = "player-identity:${player.id}",
                label = "当前玩家身份 · ${player.label}",
                content = "玩家身份：${player.label}\n${player.description}\n这描述用户的故事身份，不代表其已经做出行动。",
                alwaysInclude = true, position = Int.MIN_VALUE + 1,
            )
        }
        val backgroundWorldIds = configuration.backgroundSettings
            .filterNot { it.subject in frozenBackgrounds }
            .filter { it.subject.kind == NovexContentKind.WORLD }
            .map { it.subject.id }
        val backgroundVersionIds = configuration.backgroundSettings
            .filterNot { it.subject in frozenBackgrounds }
            .filter { it.subject.kind == NovexContentKind.CHARACTER_VERSION }
            .map { it.subject.id }
        val identityVersionId = (configuration.answerIdentity as? AnswerIdentity.CharacterVersion)?.versionId

        backgroundWorldIds.distinct().forEach { worldId ->
            val snapshot = workspace.world(worldId)
            if (snapshot == null) {
                legacy.world?.takeIf { it.id == worldId }?.let { world ->
                    candidates += NovexContextCandidate(worldCoreId(worldId), "世界 · ${world.name}",
                        world.description, alwaysInclude = true, position = -1)
                }
                return@forEach
            }
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

        val frozenActor = frozenContext.any { it.actorVersionId != null && it.actorVersionId == identityVersionId } ||
            adopted.any { it.acting && it.root.id == identityVersionId }
        val requestedVersions = (backgroundVersionIds + listOfNotNull(identityVersionId))
            .filterNot { frozenActor && it == identityVersionId }.distinct()
        if (requestedVersions.isNotEmpty()) {
            requestedVersions.forEach { versionId ->
                val identity = versionId == identityVersionId
                val entry = workspace.characterForVersion(versionId)?.let { card ->
                    card.character.allVersions.firstOrNull { it.id == versionId }?.let { version ->
                        Triple(card.character.character.name, version, card)
                    }
                }
                if (entry == null) {
                    legacy.character?.takeIf { versionId == (legacy.characterVersionId ?: it.id) }?.let { role ->
                        val text = if (identity) {
                            com.openminis.app.data.character.CharacterPromptComposer.compose(role.toJson().toString(), null).orEmpty()
                        } else listOf(role.name, role.summary, role.background, role.knowledge).filter(String::isNotBlank).joinToString("\n")
                        candidates += NovexContextCandidate(characterCoreId(versionId), "角色 · ${role.name}", text,
                            kind = if (identity) ContextSourceKind.ANSWER_IDENTITY else ContextSourceKind.BACKGROUND_MODULE,
                            alwaysInclude = true, position = if (identity) Int.MIN_VALUE else -1)
                    }
                    return@forEach
                }
                val (rootName, version) = entry.first to entry.second
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
                if (identity) {
                    val raw = runCatching { JSONObject(version.profileJson) }.getOrDefault(JSONObject())
                    val hasDedicatedInstructions = modules.any {
                        it.type == com.openminis.app.data.character.ContentModuleType.ROLE_INSTRUCTIONS &&
                            ContentModuleDocumentCodec.decode(it.type, it.contentJson).toPlainText().isNotBlank()
                    }
                    val instructions = if (hasDedicatedInstructions) "" else listOf("personality", "scenario", "exampleDialogue", "systemPrompt", "postHistoryInstructions", "contentBoundary")
                        .mapNotNull { key -> raw.optString(key).takeIf(String::isNotBlank) }
                        .joinToString("\n")
                    candidates += NovexContextCandidate(
                        sourceId = "character-version:$versionId:instructions",
                        label = "角色 · $rootName · 扮演要求",
                        content = "你扮演${profile.name}，不得替用户决定行动或虚构其内心。\n$instructions",
                        kind = ContextSourceKind.ANSWER_IDENTITY,
                        alwaysInclude = true, position = Int.MIN_VALUE,
                    )
                }
                candidates += moduleCandidates(
                    ownerLabel = "角色 · $rootName · ${version.label}",
                    modules = modules,
                    kind = if (identity) ContextSourceKind.ANSWER_IDENTITY else ContextSourceKind.BACKGROUND_MODULE,
                )
            }
        }

        if (expandReferences) {
            val reader = NovexReferenceContextReader(workspace)
            val roots = backgroundWorldIds.map { com.openminis.app.novex.domain.NovexContentAddress.world(it) } +
                requestedVersions.map { com.openminis.app.novex.domain.NovexContentAddress.characterVersion(it) }
            val alreadyRead = roots.mapTo(mutableSetOf()) { com.openminis.app.novex.domain.NovexReferenceTarget(it) }
            roots.forEach { address ->
                val root = com.openminis.app.novex.domain.NovexReferenceTarget(address)
                com.openminis.app.novex.domain.NovexReferenceTraversal.collect(root, setOf(
                    com.openminis.app.novex.domain.NovexReferencePurpose.BACKGROUND,
                    com.openminis.app.novex.domain.NovexReferencePurpose.RULES,
                )) { target ->
                    if (target == root) reader.references(target)
                    else reader.read(target)?.let { context ->
                        if (alreadyRead.add(target)) candidates += context
                        if (context.isEmpty()) emptyList() else reader.references(target)
                    }
                }
            }
        }
        adopted.filter { context -> context.acting && frozenContext.none { it.actorVersionId == context.root.id } }
            .forEach { context -> context.sources.forEach { candidates += it.candidates } }
        configuration.activeInteractiveFiction?.let { active ->
            frozenContext.filter { it.actorVersionId == identityVersionId && it.actorVersionId != null ||
                it.actorVersionId == null && it.adoptedByGame }.forEach { frozen ->
                candidates += frozen.candidates
            }
            candidates += gameCandidates(active.snapshotId, active.title, active.contentJson,
                includeLegacyPlayer = active.playerIdentity == null && configuration.playerIdentity == null,
                playthroughId = configuration.effectivePlaythroughId.orEmpty())
        }
        adopted.filterNot { it.acting }.forEach { context -> context.sources.forEach { candidates += it.candidates } }
        frozenContext.filter { it.actorVersionId == null && !it.adoptedByGame }.forEach { candidates += it.candidates }
        return candidates.mergeDuplicates()
    }

    private suspend fun moduleCandidates(
        ownerLabel: String,
        modules: List<ContentModuleEntity>,
        kind: ContextSourceKind = ContextSourceKind.BACKGROUND_MODULE,
    ): List<NovexContextCandidate> = modules
        .filter { com.openminis.app.novex.domain.NovexModuleVisibility.allowsContext(it.type, kind == ContextSourceKind.ANSWER_IDENTITY) }
        .sortedBy(ContentModuleEntity::position).map { module ->
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
            alwaysInclude = module.type == com.openminis.app.data.character.ContentModuleType.ROLE_INSTRUCTIONS,
            position = if (module.type == com.openminis.app.data.character.ContentModuleType.ROLE_INSTRUCTIONS) Int.MIN_VALUE + 2 else module.position,
        )
    }

    private fun gameCandidates(snapshotId: String, title: String, raw: String, includeLegacyPlayer: Boolean, playthroughId: String): List<NovexContextCandidate> {
        val root = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val prefix = "game:$snapshotId"
        val core = listOf(
            "本局编号：$playthroughId",
            root.optString("summary").takeIf(String::isNotBlank),
            root.optString("playerIdentity").takeIf { includeLegacyPlayer && it.isNotBlank() }?.let { "玩家身份：$it" },
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
                if (typeName == "GAME_ANSWER_IDENTITY") return@repeat
                if (typeName == "GAME_PLAYER_IDENTITY" && !includeLegacyPlayer) return@repeat
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
