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
        userRequests: List<String>, operationId: String, requestId: String? = null): Result {
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
            // A stable output key distinguishes intended copies from retries; words and content
            // equality never decide how many cards the user is allowed to create.
            val scope = requestId?.takeIf(String::isNotBlank) ?: NovexFrozenContextCodec.digest(JSONArray(userRequests).toString())
            val outputKey = arguments.optString("creation_key").takeIf(String::isNotBlank)
            val planId = if (tool == "novex_write_card" && outputKey != null)
                NovexFrozenContextCodec.digest("${configuration.conversationId}|$scope|$outputKey") else operationId
            val plan = management.propose(configuration, changes, userRequests.lastOrNull().orEmpty(), planId, userRequests.dropLast(1), scope)
            run {
                val applied = management.apply(configuration, plan, userRequests.lastOrNull().orEmpty())
                val updated = (if (applied.replayed) emptyList() else applied.createdSubjects).fold(configuration) { current, target ->
                    NovexConversationConfiguration.open(current).apply(NovexConversationCommand.MountSubject(target, ManagedAccess.EDIT)).snapshot
                }
                val checks = verify(plan, applied)
                if (!applied.replayed) require(checks.optBoolean("verified")) { "写入后的回读核验未通过，事务未提交" }
                val cards = applied.createdSubjects.map { savedCardReceipt(it) }
                val modules = applied.changedModuleIds.mapNotNull { workspace.module(it)?.module }.map { module ->
                    JSONObject().put("module_id", module.id).put("name", module.name).put("position", module.position)
                        .put("card_id", module.ownerId)
                }
                val references = plan.changes.filterIsInstance<NovexManagedChange.PutCardReference>().map { change ->
                    val ref = change.reference
                    JSONObject().put("reference_id", ref.id).put("source_id", ref.source.id)
                        .put("target_id", ref.target.subject.id).put("purpose", ref.purpose.wireName)
                }
                val userMessage = if (modules.isNotEmpty()) modules.joinToString("\n") {
                    "模块《${it.getString("name")}》已保存，当前排在第 ${it.getInt("position") + 1} 位。"
                } else if (cards.isEmpty()) "卡片修改已保存，可以打开查看。"
                    else cards.joinToString("\n") { "《${it.getString("name")}》已保存到卡片仓库，可以打开查看。" }
                result = Result(plan, applied, updated, JSONObject().put("status", if (checks.optBoolean("verified")) "saved_verified" else "saved_needs_review")
                    .put("saved", true).put("proposal_id", plan.id).put("replayed", applied.replayed)
                    .put("verification", checks).put("created_cards", JSONArray(cards))
                    .put("saved_modules", JSONArray(modules)).put("saved_references", JSONArray(references)).put("message", userMessage))
            }
        }
        return requireNotNull(result)
    }

    /** Read the committed object's current name; the initial empty-card directory is stale after saving. */
    private suspend fun savedCardReceipt(target: NovexContentAddress): JSONObject {
        val name = when (target.kind) {
            NovexContentKind.WORLD -> workspace.world(target.id)?.world?.name
            NovexContentKind.CHARACTER_VERSION -> workspace.characterForVersion(target.id)?.character?.character?.name
            NovexContentKind.INTERACTIVE_FICTION -> workspace.interactiveFiction(target.id)?.project?.name
            else -> null
        }
        return JSONObject().put("kind", target.kind.managementWireName()).put("id", target.id)
            .put("name", requireNotNull(name) { "已保存卡片暂不可读" })
            .put("location", "卡片仓库").put("open_in_app", true)
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
                cards.put(JSONObject().put("id", target?.id).put("modules", modules.size).put("verified", matches))
            }
            is NovexManagedChange.UpdateModule -> {
                val actual = workspace.module(change.moduleId)?.module
                verified = verified && actual != null && (change.name == null || actual.name == change.name) &&
                    (change.contentJson == null || same(requireNotNull(actual).contentJson, change.contentJson))
            }
            is NovexManagedChange.MoveModule -> verified = verified && workspace.module(change.moduleId)?.module?.position == change.toIndex
            is NovexManagedChange.AddModule -> {
                val id = applied.changedModuleIds.firstOrNull()
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
