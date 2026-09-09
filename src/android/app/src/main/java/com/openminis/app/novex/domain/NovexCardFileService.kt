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
        userRequests: List<String>, operationId: String, requestId: String? = null,
        preparedChanges: String? = null): Result {
        // Resolve a bounded, immutable source before opening the write transaction.
        val changes = preparedChanges ?: operations.prepare(tool, arguments)
        var result: Result? = null
        transaction.run {
            operations.requireSourceAccess(arguments)
            // A stable output key distinguishes intended copies from retries; words and content
            // equality never decide how many cards the user is allowed to create.
            val scope = requestId?.takeIf(String::isNotBlank) ?: NovexFrozenContextCodec.digest(JSONArray(userRequests).toString())
            val outputKey = arguments.optString("creation_key").takeIf(String::isNotBlank)
            val planId = if (tool == "novex_write_card" && outputKey != null)
                NovexFrozenContextCodec.digest("${configuration.conversationId}|$scope|$outputKey") else operationId
            val plan = management.propose(configuration, changes, userRequests.lastOrNull().orEmpty(), planId, userRequests.dropLast(1), scope)
            run {
                val completed = workspace.conversationDrafts(configuration.conversationId)?.completedWrites?.any { it.id == plan.id } == true
                if (!completed && !arguments.optBoolean("allow_duplicate_name")) {
                    plan.changes.filterIsInstance<NovexManagedChange.AddModule>().forEach { added ->
                        val sameNames = workspace.modules(added.owner).modules.filter { it.name == added.name }
                        require(sameNames.isEmpty()) {
                            "已有同名模块“${added.name}”（${sameNames.joinToString { it.id }}）。补充或整理请读取后用原 module_id（模块编号）更新；确实需要另一份同名模块时设置 allow_duplicate_name（允许另建同名）为 true。"
                        }
                    }
                }
                val applied = management.apply(configuration, plan, userRequests.lastOrNull().orEmpty())
                val updated = management.configurationAfterWrite(configuration, plan, applied)
                val checks = verify(plan, applied)
                if (!applied.replayed) require(checks.optBoolean("verified")) { "写入后的回读核验未通过，事务未提交" }
                val receipt = NovexSavedContentReceipt(workspace).fields(plan, applied)
                val cards = receipt.getJSONArray("created_cards").objects()
                val updatedCards = receipt.getJSONArray("updated_cards").objects()
                val modules = receipt.getJSONArray("saved_modules").objects()
                val references = receipt.getJSONArray("saved_references").objects()
                val userMessage = if (applied.replayed && !checks.optBoolean("verified")) "这次操作此前已保存，原内容后来已有变更；本次没有再次写入，请读取当前内容。"
                else if (modules.isNotEmpty()) modules.joinToString("\n") {
                    "模块《${it.getString("name")}》已保存，当前排在第 ${it.getInt("position") + 1} 位。"
                } else if (updatedCards.isNotEmpty()) updatedCards.joinToString("\n") { "《${it.getString("name")}》已更新。" }
                else if (cards.isEmpty()) "卡片修改已保存，可以打开查看。"
                    else cards.joinToString("\n") { "《${it.getString("name")}》已保存到卡片仓库，可以打开查看。" }
                result = Result(plan, applied, updated, JSONObject().put("status", if (checks.optBoolean("verified")) "saved_verified" else "saved_needs_review")
                    .put("saved", true).put("proposal_id", plan.id).put("replayed", applied.replayed)
                    .put("verification", checks).put("created_cards", JSONArray(cards))
                    .put("updated_cards", JSONArray(updatedCards)).put("saved_modules", JSONArray(modules)).put("saved_references", JSONArray(references)).put("message", userMessage))
            }
        }
        return requireNotNull(result)
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

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
            is NovexManagedChange.UpdateCard -> verified = verified && NovexCardHeaderEdit.matches(workspace, change)
            is NovexManagedChange.UpdateModule -> {
                val actual = workspace.module(change.moduleId)?.module
                val expectedContent = change.contentJson?.let { incoming ->
                    plan.originalModuleDocuments[change.moduleId]?.let { original ->
                        com.openminis.app.data.character.ContentModuleDocumentCodec.preserveTransferSource(original,
                            NovexModuleImageOrigins.preserve(original, incoming, requireNotNull(actual).type))
                    } ?: incoming
                }
                verified = verified && actual != null && (change.name == null || actual.name == change.name) &&
                    (expectedContent == null || same(requireNotNull(actual).contentJson, expectedContent)) &&
                    (change.appendText == null || JSONObject(requireNotNull(actual).contentJson).optString("text").endsWith("\n" + change.appendText))
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
