package com.openminis.app.novex.adapter

import com.openminis.app.novex.domain.*
import org.json.JSONArray
import org.json.JSONObject

/** Captures the settings adopted by one game without activating linked games or granting edits. */
class NovexGameSnapshotAssembler(private val workspace: NovexWorkspace, private val mediaStore: NovexSnapshotMediaStore? = null) {
    suspend fun create(projectId: String, backgroundSettings: List<BackgroundSetting> = emptyList(),
        adoptedContexts: List<NovexAdoptedContext> = emptyList()): ActiveInteractiveFictionSnapshot {
        val project = requireNotNull(workspace.interactiveFiction(projectId)) { "文游不存在" }
        val base = InteractiveFictionRuntimeSnapshotFactory.create(project)
        val root = NovexReferenceTarget(NovexContentAddress.interactiveFiction(projectId))
        val identityReference = workspace.referencesFrom(root.subject).singleOrNull {
            it.sourceModuleId == null && it.purpose == NovexReferencePurpose.ANSWER_IDENTITY
        }
        require(identityReference == null || base.answerIdentity == null) { "文游同时指定了独立人格和回答角色，请保留其中一个回答身份后启动" }
        // Each adoption path retains its own revision and media. Text deduplication happens only after adoption.
        val frozen = linkedMapOf<Pair<NovexReferenceTarget, NovexContentAddress?>, NovexFrozenContext>()
        val reader = NovexReferenceContextReader(workspace)
        val references = linkedMapOf<String, NovexCardReference>()
        val cycles = linkedSetOf<String>()
        fun addSource(target: NovexReferenceTarget, candidates: List<NovexContextCandidate>, gameOwned: Boolean, conversationRoot: NovexContentAddress?,
            retainedMedia: List<NovexSnapshotMedia>? = null) {
            val key = target to conversationRoot
            val prior = frozen[key]
            frozen[key] = NovexFrozenContext(target, prior?.candidates ?: candidates,
                adoptedByGame = prior?.adoptedByGame == true || gameOwned,
                conversationRoots = prior?.conversationRoots.orEmpty() + listOfNotNull(conversationRoot),
                media = prior?.media ?: retainedMedia.orEmpty(),
                mediaCaptured = prior?.mediaCaptured ?: (retainedMedia != null))
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
        val gameReferences = references.values.toList()
        val backgroundAdoptions = mutableListOf<NovexAdoptedContext>()
        backgroundSettings.distinct().forEach { background ->
            val previous = adoptedContexts.singleOrNull { !it.acting && it.root == background.subject }
            if (previous == null) {
                // Keep this root's graph, including edges also used by the game, without mixing revisions.
                val priorReferences = references.toMap()
                references.clear()
                collect(NovexReferenceTarget(background.subject), false, background.subject)
                backgroundAdoptions += NovexAdoptedContext(background.subject, false,
                    frozen.filterKeys { it.second == background.subject }.values.toList(), references.values.toList())
                priorReferences.forEach { (id, reference) -> references.putIfAbsent(id, reference) }
            } else {
                previous.sources.forEach { addSource(it.target, it.candidates, false, background.subject, it.media) }
                backgroundAdoptions += previous
            }
        }
        val actor = identityReference?.let { reference ->
            require(workspace.referenceStatus(reference.target) == NovexReferenceTargetStatus.AVAILABLE) { "文游回答身份引用缺失，请修复后启动" }
            val identity = AnswerIdentity.CharacterVersion(reference.target.subject.id)
            val context = WorkspaceNovexContextLoader(workspace, expandReferences = false).load(NovexConversationConfigurationSnapshot(
                "snapshot:$projectId", answerIdentity = identity)).filter { it.kind == ContextSourceKind.ANSWER_IDENTITY }
            val version = workspace.characterForVersion(identity.versionId)?.character?.allVersions?.singleOrNull { it.id == identity.versionId }
            NovexFrozenContext(reference.target, context, identity.versionId, tavernWorldbookJson = version?.let { NovexTavernWorldbook.capture(it.profileJson) })
        }
        val players = NovexPlayerIdentityReader(workspace)
        val companions = actor?.let { players.read(it.target) }.orEmpty()
        val playerReference = workspace.referencesFrom(root.subject).singleOrNull {
            it.sourceModuleId == null && it.purpose == NovexReferencePurpose.PLAYER_IDENTITY
        }
        val referencedPlayers = playerReference?.let { players.read(it.target).also { identities ->
            require(identities.isNotEmpty()) { "玩家身份引用没有可采用的正文，请填写身份模块后启动" }
        } }.orEmpty()
        val media = NovexSnapshotMediaCapture(workspace, mediaStore)
        val linked = (frozen.values + listOfNotNull(actor)).map { if (it.mediaCaptured) it else media.capture(it) }
        val rootMedia = media.capture(root, project.modules.filter { NovexModuleVisibility.allowsContext(it.type, acting = false) }
            .mapTo(mutableSetOf()) { it.id })
        val content = JSONObject(base.contentJson).apply {
            put("linkedContext", JSONArray(linked.map(NovexFrozenContextCodec::encode)))
            put("adoptedMedia", JSONArray(rootMedia.map(NovexSnapshotMediaCodec::encode)))
            put("references", JSONArray().apply { (references.values + listOfNotNull(identityReference, playerReference)).forEach { put(JSONObject(NovexCardReferenceCodec.encode(it))) } })
            put("backgroundReferences", JSONArray(gameReferences.map { JSONObject(NovexCardReferenceCodec.encode(it)) }))
            put("conversationAdoptions", JSONArray(backgroundAdoptions.map { adopted ->
                // Use retained copies from the media capture above.
                NovexAdoptedContextCodec.encode(adopted.copy(sources = linked.filter { adopted.root in it.conversationRoots }))
            }))
            put("cycleReferenceIds", JSONArray(cycles.toList()))
        }.toString()
        return NovexGamePlayerChoices.prepare(base.copy(contentJson = content, snapshotId = NovexFrozenContextCodec.digest(content),
            answerIdentity = actor?.actorVersionId?.let { AnswerIdentity.CharacterVersion(it) } ?: base.answerIdentity),
            listOfNotNull(base.playerIdentity) + referencedPlayers + companions)
    }
}
