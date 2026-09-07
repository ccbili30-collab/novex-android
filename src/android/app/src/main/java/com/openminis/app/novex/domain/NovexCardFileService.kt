package com.openminis.app.novex.domain

import com.openminis.app.data.character.ModuleOwner
import org.json.JSONArray
import org.json.JSONObject

/** The model and real-case harness use this same create/write/verify seam. */
class NovexCardFileService(
    private val workspace: NovexWorkspace,
    private val management: NovexManagementService,
    private val operations: NovexCardFileOperations,
    private val transaction: NovexManagementTransaction,
) {
    data class Result(val plan: NovexManagementPlan, val applied: NovexManagementApplyResult?,
        val configuration: NovexConversationConfigurationSnapshot, val payload: JSONObject)

    suspend fun execute(configuration: NovexConversationConfigurationSnapshot, tool: String, arguments: JSONObject,
        userRequests: List<String>, operationId: String): Result {
        // Resolve a bounded, immutable source before opening the write transaction.
        val changes = when (tool) {
            "novex_write_card" -> operations.create(arguments)
            "novex_write_module" -> operations.writeModule(arguments)
            "novex_move_module" -> operations.moveModule(arguments)
            "novex_link_cards" -> operations.link(arguments)
            else -> error("未知卡片操作")
        }
        var result: Result? = null
        transaction.run {
            val plan = management.propose(configuration, changes, userRequests.lastOrNull().orEmpty(), operationId, userRequests.dropLast(1))
            if (plan.requiresConfirmation) {
                result = Result(plan, null, configuration, JSONObject().put("status", "waiting_confirmation")
                    .put("proposal_id", plan.id).put("summary", plan.summary).put("impact", JSONArray(plan.impact))
                    .put("confirmation", plan.confirmationPhrase).put("saved", false))
            } else {
                val applied = management.apply(configuration, plan, userRequests.lastOrNull().orEmpty())
                val updated = (if (applied.replayed) emptyList() else applied.createdSubjects).fold(configuration) { current, target ->
                    NovexConversationConfiguration.open(current).apply(NovexConversationCommand.MountSubject(target, ManagedAccess.EDIT)).snapshot
                }
                val checks = verify(plan, applied)
                if (!applied.replayed) require(checks.optBoolean("verified")) { "写入后的回读核验未通过，事务未提交" }
                result = Result(plan, applied, updated, JSONObject().put("status", if (checks.optBoolean("verified")) "saved_verified" else "saved_needs_review")
                    .put("saved", true).put("proposal_id", plan.id).put("replayed", applied.replayed)
                    .put("verification", checks).put("created_cards", JSONArray(applied.createdSubjects.map { target ->
                        JSONObject().put("kind", target.kind.managementWireName()).put("id", target.id)
                    })).put("message", "实际保存与结构回读结果；不证明内容语义正确。修改原卡不会刷新本局采用。"))
            }
        }
        return requireNotNull(result)
    }

    private suspend fun verify(plan: NovexManagementPlan, applied: NovexManagementApplyResult): JSONObject {
        var verified = true
        val cards = JSONArray()
        var creationIndex = 0
        fun same(a: String, b: String) = canonicalRevisionJson(JSONObject(a)) == canonicalRevisionJson(JSONObject(b))
        for (change in plan.changes) when (change) {
            is NovexManagedChange.CreateWorld, is NovexManagedChange.CreateCharacter, is NovexManagedChange.CreateInteractiveFiction -> {
                val drafts = when (change) {
                    is NovexManagedChange.CreateWorld -> change.modules
                    is NovexManagedChange.CreateCharacter -> change.modules
                    is NovexManagedChange.CreateInteractiveFiction -> change.modules
                    else -> emptyList()
                }
                val target = applied.createdSubjects.getOrNull(creationIndex++)
                val owner = target?.let { when (it.kind) {
                    NovexContentKind.WORLD -> ModuleOwner.world(it.id)
                    NovexContentKind.CHARACTER_VERSION -> ModuleOwner.characterVersion(it.id)
                    NovexContentKind.INTERACTIVE_FICTION -> ModuleOwner.interactiveFiction(it.id)
                    else -> null
                } }
                val modules = owner?.let { workspace.modules(it).modules }.orEmpty()
                val matches = drafts.isNotEmpty() && modules.size == drafts.size && drafts.zip(modules).all { (expected, actual) ->
                    expected.name == actual.name && expected.type == actual.type && same(expected.contentJson, actual.contentJson)
                }
                verified = verified && matches
                cards.put(JSONObject().put("id", target?.id).put("modules", modules.size).put("verified", matches)
                    .put("module_directory", JSONArray(modules.map { JSONObject().put("id", it.id).put("name", it.name).put("position", it.position) })))
            }
            is NovexManagedChange.UpdateModule -> {
                val actual = workspace.module(change.moduleId)?.module
                verified = verified && actual != null && (change.name == null || actual.name == change.name) &&
                    (change.contentJson == null || same(requireNotNull(actual).contentJson, change.contentJson))
            }
            is NovexManagedChange.MoveModule -> verified = verified && workspace.module(change.moduleId)?.module?.position == change.toIndex
            is NovexManagedChange.AddModule -> {
                val id = applied.changes.filterIsInstance<NovexChange.ModuleSaved>().firstOrNull()?.module?.id
                val actual = id?.let { workspace.module(it)?.module }
                verified = verified && actual != null && actual.name == change.name && same(actual.contentJson, change.contentJson)
            }
            is NovexManagedChange.PutCardReference -> verified = verified && workspace.referencesFrom(change.reference.source).any { it == change.reference }
            is NovexManagedChange.RemoveCardReference -> verified = verified && workspace.referencesFrom(change.source).none { it.id == change.referenceId }
            else -> verified = false
        }
        return JSONObject().put("verified", verified).put("cards", cards)
    }
}
