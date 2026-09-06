package com.openminis.app.novex.adapter

import com.openminis.app.novex.domain.*
import org.json.JSONArray
import org.json.JSONObject

/** Captures the settings adopted by one game without activating linked games or granting edits. */
class NovexGameSnapshotAssembler(private val workspace: NovexWorkspace) {
    suspend fun create(projectId: String, backgroundSettings: List<BackgroundSetting> = emptyList(),
        adoptedContexts: List<NovexAdoptedContext> = emptyList()): ActiveInteractiveFictionSnapshot {
        val base = InteractiveFictionRuntimeSnapshotFactory.create(requireNotNull(workspace.interactiveFiction(projectId)) { "文游不存在" })
        val root = NovexReferenceTarget(NovexContentAddress.interactiveFiction(projectId))
        val identityReference = workspace.referencesFrom(root.subject).singleOrNull {
            it.sourceModuleId == null && it.purpose == NovexReferencePurpose.ANSWER_IDENTITY
        }
        require(identityReference == null || base.answerIdentity == null) { "文游同时指定了独立人格和回答角色，请保留其中一个回答身份后启动" }
        val frozen = linkedMapOf<NovexReferenceTarget, NovexFrozenContext>()
        val reader = NovexReferenceContextReader(workspace)
        val references = linkedMapOf<String, NovexCardReference>()
        val cycles = linkedSetOf<String>()
        fun addSource(target: NovexReferenceTarget, candidates: List<NovexContextCandidate>, gameOwned: Boolean, conversationRoot: NovexContentAddress?) {
            val prior = frozen[target]
            frozen[target] = NovexFrozenContext(target, prior?.candidates ?: candidates,
                adoptedByGame = prior?.adoptedByGame == true || gameOwned,
                conversationRoots = prior?.conversationRoots.orEmpty() + listOfNotNull(conversationRoot))
            require(frozen.size <= 1000) { "本局采用的资料范围超过上限，请缩小引用范围" }
        }
        suspend fun collect(start: NovexReferenceTarget, gameOwned: Boolean, conversationRoot: NovexContentAddress?) {
            val traversal = NovexReferenceTraversal.collect(start, setOf(NovexReferencePurpose.BACKGROUND, NovexReferencePurpose.RULES)) { target ->
                if (target == root && gameOwned) reader.references(target)
                else reader.read(target)?.let { context ->
                    addSource(target, context, gameOwned, conversationRoot)
                    if (context.isEmpty()) emptyList() else reader.references(target)
                }
            }
            require(!traversal.truncated) { "引用展开超过上限，请缩小本局引用范围" }
            require(traversal.missingTargets.isEmpty()) { "文游引用的卡片、模块或条目缺失，请修复引用后启动" }
            references += traversal.references.associateBy { it.id }
            cycles += traversal.cycleReferenceIds
        }
        collect(root, true, null)
        backgroundSettings.distinct().forEach { background ->
            val previous = adoptedContexts.singleOrNull { !it.acting && it.root == background.subject }
            if (previous == null) collect(NovexReferenceTarget(background.subject), false, background.subject)
            else previous.sources.forEach { addSource(it.target, it.candidates, false, background.subject) }
        }
        val actor = identityReference?.let { reference ->
            require(workspace.referenceStatus(reference.target) == NovexReferenceTargetStatus.AVAILABLE) { "文游回答身份引用缺失，请修复后启动" }
            val identity = AnswerIdentity.CharacterVersion(reference.target.subject.id)
            val context = WorkspaceNovexContextLoader(workspace, expandReferences = false).load(NovexConversationConfigurationSnapshot(
                "snapshot:$projectId", answerIdentity = identity)).filter { it.kind == ContextSourceKind.ANSWER_IDENTITY }
            NovexFrozenContext(reference.target, context, identity.versionId)
        }
        val players = NovexPlayerIdentityReader(workspace)
        val companions = actor?.let { players.read(it.target) }.orEmpty()
        val playerReference = workspace.referencesFrom(root.subject).singleOrNull {
            it.sourceModuleId == null && it.purpose == NovexReferencePurpose.PLAYER_IDENTITY
        }
        val referencedPlayers = playerReference?.let { players.read(it.target).also { identities ->
            require(identities.isNotEmpty()) { "玩家身份引用没有可采用的正文，请填写身份模块后启动" }
        } }.orEmpty()
        val content = JSONObject(base.contentJson).apply {
            put("linkedContext", JSONArray().apply { (frozen.values + listOfNotNull(actor)).forEach { put(NovexFrozenContextCodec.encode(it)) } })
            put("references", JSONArray().apply { (references.values + listOfNotNull(identityReference, playerReference)).forEach { put(JSONObject(NovexCardReferenceCodec.encode(it))) } })
            put("cycleReferenceIds", JSONArray(cycles.toList()))
        }.toString()
        return NovexGamePlayerChoices.prepare(base.copy(contentJson = content, snapshotId = NovexFrozenContextCodec.digest(content),
            answerIdentity = actor?.actorVersionId?.let { AnswerIdentity.CharacterVersion(it) } ?: base.answerIdentity),
            listOfNotNull(base.playerIdentity) + referencedPlayers + companions)
    }
}
