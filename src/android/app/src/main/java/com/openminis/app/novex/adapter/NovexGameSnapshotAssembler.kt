package com.openminis.app.novex.adapter

import com.openminis.app.novex.domain.*
import org.json.JSONArray
import org.json.JSONObject

/** Captures the settings adopted by one game without activating linked games or granting edits. */
class NovexGameSnapshotAssembler(private val workspace: NovexWorkspace) {
    suspend fun create(projectId: String, backgroundSettings: List<BackgroundSetting> = emptyList()): ActiveInteractiveFictionSnapshot {
        val base = InteractiveFictionRuntimeSnapshotFactory.create(requireNotNull(workspace.interactiveFiction(projectId)) { "文游不存在" })
        val root = NovexReferenceTarget(NovexContentAddress.interactiveFiction(projectId))
        val identityReference = workspace.referencesFrom(root.subject).singleOrNull {
            it.sourceModuleId == null && it.purpose == NovexReferencePurpose.ANSWER_IDENTITY
        }
        val frozen = linkedMapOf<NovexReferenceTarget, NovexFrozenContext>()
        val reader = NovexReferenceContextReader(workspace)
        val adoptedBackgrounds = backgroundSettings.distinct().map { background ->
            NovexCardReference("conversation-background:${background.subject.kind}:${background.subject.id}",
                root.subject, NovexReferenceTarget(background.subject), NovexReferencePurpose.BACKGROUND)
        }
        val traversal = NovexReferenceTraversal.collect(root, setOf(NovexReferencePurpose.BACKGROUND, NovexReferencePurpose.RULES)) { target ->
            if (target == root) reader.references(target) + adoptedBackgrounds
            else reader.read(target)?.let { context ->
                frozen[target] = NovexFrozenContext(target, context)
                if (context.isEmpty()) emptyList() else reader.references(target)
            }
        }
        require(!traversal.truncated) { "引用展开超过上限，请缩小本局引用范围" }
        require(traversal.missingTargets.isEmpty()) { "文游引用的卡片、模块或条目缺失，请修复引用后启动" }
        val actor = identityReference?.let { reference ->
            require(workspace.referenceStatus(reference.target) == NovexReferenceTargetStatus.AVAILABLE) { "文游回答身份引用缺失，请修复后启动" }
            val identity = AnswerIdentity.CharacterVersion(reference.target.subject.id)
            val context = WorkspaceNovexContextLoader(workspace, expandReferences = false).load(NovexConversationConfigurationSnapshot(
                "snapshot:$projectId", answerIdentity = identity)).filter { it.kind == ContextSourceKind.ANSWER_IDENTITY }
            NovexFrozenContext(reference.target, context, identity.versionId)
        }
        val content = JSONObject(base.contentJson).apply {
            put("linkedContext", JSONArray().apply { (frozen.values + listOfNotNull(actor)).forEach { put(NovexFrozenContextCodec.encode(it)) } })
            put("references", JSONArray().apply { (traversal.references + listOfNotNull(identityReference)).forEach { put(JSONObject(NovexCardReferenceCodec.encode(it))) } })
            put("cycleReferenceIds", JSONArray(traversal.cycleReferenceIds.toList()))
        }.toString()
        return base.copy(contentJson = content, snapshotId = NovexFrozenContextCodec.digest(content),
            answerIdentity = actor?.actorVersionId?.let { AnswerIdentity.CharacterVersion(it) })
    }
}
