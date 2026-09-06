package com.openminis.app.novex.adapter

import com.openminis.app.novex.domain.*
import org.json.JSONArray
import org.json.JSONObject

/** Captures explicitly adopted conversation settings independently from managed originals. */
class NovexConversationContextAdoption(
    private val workspace: NovexWorkspace,
    private val legacy: NovexLegacyContext = NovexLegacyContext(),
    private val mediaStore: NovexSnapshotMediaStore? = null,
) {
    suspend fun refresh(configuration: NovexConversationConfigurationSnapshot, root: NovexContentAddress,
        acting: Boolean): NovexConversationConfigurationSnapshot {
        require(if (acting) (configuration.answerIdentity as? AnswerIdentity.CharacterVersion)?.versionId == root.id
            else configuration.backgroundSettings.any { it.subject == root }) { "该对象没有以指定用途被当前对话采用" }
        val captured = capture(root, acting)
        val active = configuration.activeInteractiveFiction
        val gameSources = active?.let { NovexFrozenContextCodec.read(it.contentJson) }.orEmpty()
        if (acting && gameSources.any { it.actorVersionId == root.id }) {
            val replaced = gameSources.filterNot { it.actorVersionId == root.id } +
                NovexFrozenContext(NovexReferenceTarget(root), captured.sources.flatMap { it.candidates }, root.id,
                    media = captured.sources.flatMap { it.media }, mediaCaptured = true)
            val content = JSONObject(requireNotNull(active).contentJson).put("linkedContext", JSONArray(replaced.map(NovexFrozenContextCodec::encode))).toString()
            return configuration.copy(activeInteractiveFiction = active.copy(contentJson = content, snapshotId = NovexFrozenContextCodec.digest(content)))
        }
        return configuration.copy(adoptedContexts = configuration.adoptedContexts.filterNot { it.root == root && it.acting == acting } + captured)
    }

    suspend fun refreshGame(configuration: NovexConversationConfigurationSnapshot): NovexConversationConfigurationSnapshot {
        val active = requireNotNull(configuration.activeInteractiveFiction) { "没有活动文游可刷新" }
        val latest = NovexGameSnapshotAssembler(workspace, mediaStore).create(active.projectId, configuration.backgroundSettings, configuration.adoptedContexts)
        val old = JSONObject(active.contentJson)
        val content = JSONObject(latest.contentJson).apply {
            listOf("playerIdentity", "playerIdentityChoices", "selectedPlayerIdentityId").forEach { key ->
                if (old.has(key)) put(key, old.get(key)) else remove(key)
            }
            val sources = NovexFrozenContextCodec.read(latest.contentJson).filter { it.actorVersionId == null } +
                NovexFrozenContextCodec.read(active.contentJson).filter { it.actorVersionId != null }
            put("linkedContext", JSONArray(sources.map(NovexFrozenContextCodec::encode)))
            fun identityReferences(value: JSONObject, identity: Boolean): List<JSONObject> {
                val array = value.optJSONArray("references") ?: return emptyList()
                return (0 until array.length()).map { array.getJSONObject(it) }.filter {
                    (it.optString("purpose") in setOf(NovexReferencePurpose.ANSWER_IDENTITY.name, NovexReferencePurpose.PLAYER_IDENTITY.name)) == identity
                }
            }
            put("references", JSONArray(identityReferences(this, false) + identityReferences(old, true)))
        }.toString()
        // Refresh is a content adoption, not an activation command: preserve the current run and its identities.
        return configuration.copy(activeInteractiveFiction = active.copy(title = latest.title, contentJson = content,
            snapshotId = NovexFrozenContextCodec.digest(content)))
    }

    suspend fun adopt(configuration: NovexConversationConfigurationSnapshot): NovexConversationConfigurationSnapshot {
        val actor = (configuration.answerIdentity as? AnswerIdentity.CharacterVersion)?.versionId
        val preGameActor = (configuration.preGameAnswerIdentity as? AnswerIdentity.CharacterVersion)?.versionId
        val gameSources = configuration.activeInteractiveFiction?.let { NovexFrozenContextCodec.read(it.contentJson) }.orEmpty()
        val retained = configuration.adoptedContexts.filter {
            it.isActive(configuration) || (it.acting && it.root.id == preGameActor)
        }.toMutableList()
        val wanted = configuration.backgroundSettings.map { it.subject to false } + listOfNotNull(
            actor?.takeUnless { id -> gameSources.any { it.actorVersionId == id } }
                ?.let { NovexContentAddress.characterVersion(it) to true },
        )
        wanted.forEach { (root, acting) ->
            if (retained.none { it.root == root && it.acting == acting }) {
                val alreadyCaptured = gameSources.filter { !acting && it.actorVersionId == null && root in it.conversationRoots }
                retained += if (alreadyCaptured.isNotEmpty()) NovexAdoptedContext(root, false, alreadyCaptured) else capture(root, acting)
            }
        }
        return configuration.copy(adoptedContexts = retained)
    }

    private suspend fun capture(address: NovexContentAddress, acting: Boolean): NovexAdoptedContext {
        val root = NovexReferenceTarget(address)
        val rootConfiguration = NovexConversationConfigurationSnapshot("adopt:${address.id}",
            answerIdentity = if (acting) AnswerIdentity.CharacterVersion(address.id) else AnswerIdentity.Nova,
            backgroundSettings = if (acting) emptyList() else listOf(BackgroundSetting(address)))
        val kind = if (acting) ContextSourceKind.ANSWER_IDENTITY else ContextSourceKind.BACKGROUND_MODULE
        val core = WorkspaceNovexContextLoader(workspace, legacy, expandReferences = false).load(rootConfiguration).filter { it.kind == kind }
        require(core.isNotEmpty()) { "采用的${if (acting) "角色" else "背景"}已不存在，请重新选择" }
        val sources = linkedMapOf(root to NovexFrozenContext(root, core, if (acting) address.id else null))
        val reader = NovexReferenceContextReader(workspace)
        val traversal = NovexReferenceTraversal.collect(root, setOf(NovexReferencePurpose.BACKGROUND, NovexReferencePurpose.RULES)) { target ->
            if (target == root) reader.references(target)
            else reader.read(target)?.let { candidates ->
                sources[target] = NovexFrozenContext(target, candidates)
                if (candidates.isEmpty()) emptyList() else reader.references(target)
            }
        }
        require(!traversal.truncated) { "设定引用展开超过上限，请缩小引用范围" }
        require(traversal.missingTargets.isEmpty()) { "设定包含缺失引用，请修复后采用" }
        val media = NovexSnapshotMediaCapture(workspace, mediaStore)
        return NovexAdoptedContext(address, acting, sources.values.map { media.capture(it) })
    }
}
