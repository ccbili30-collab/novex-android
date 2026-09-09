package com.openminis.app.novex.domain

import com.openminis.app.data.character.ModuleOwnerType
import org.json.JSONArray
import org.json.JSONObject

/** Shared address-only completion fields for both content executors. Read inside their transaction. */
internal class NovexSavedContentReceipt(private val workspace: NovexWorkspace) {
    suspend fun fields(plan: NovexManagementPlan, applied: NovexManagementApplyResult): JSONObject {
        val created = applied.createdSubjects.map { card(it) }
        val updated = plan.changes.filterIsInstance<NovexManagedChange.UpdateCard>().map { card(it.target) }
        val modules = applied.changedModuleIds.mapNotNull { workspace.module(it)?.module }.map { module ->
            val kind = when (module.ownerType) {
                ModuleOwnerType.WORLD -> "world"
                ModuleOwnerType.CHARACTER_VERSION -> "character_version"
                ModuleOwnerType.INTERACTIVE_FICTION -> "game"
                ModuleOwnerType.CONTENT_MODULE -> "content_module"
            }
            JSONObject().put("module_id", module.id).put("name", module.name).put("position", module.position)
                .put("card_id", module.ownerId).put("card_kind", kind)
        }
        val references = plan.changes.filterIsInstance<NovexManagedChange.PutCardReference>().map { change ->
            val ref = change.reference
            JSONObject().put("reference_id", ref.id).put("source_id", ref.source.id)
                .put("source_kind", ref.source.kind.managementWireName())
                .put("target_id", ref.target.subject.id).put("target_kind", ref.target.subject.kind.managementWireName())
                .put("purpose", ref.purpose.wireName)
        }
        return JSONObject().put("created_cards", JSONArray(created)).put("updated_cards", JSONArray(updated))
            .put("saved_modules", JSONArray(modules)).put("saved_references", JSONArray(references))
    }

    private suspend fun card(target: NovexContentAddress): JSONObject {
        val name = when (target.kind) {
            NovexContentKind.WORLD -> workspace.world(target.id)?.world?.name
            NovexContentKind.CHARACTER_VERSION -> workspace.characterForVersion(target.id)?.character?.let { aggregate ->
                aggregate.allVersions.firstOrNull { it.id == target.id }?.let { version ->
                    JSONObject(version.profileJson).optString("name").ifBlank { aggregate.character.name }
                }
            }
            NovexContentKind.INTERACTIVE_FICTION -> workspace.interactiveFiction(target.id)?.project?.name
            else -> null
        }
        return JSONObject().put("kind", target.kind.managementWireName()).put("id", target.id)
            .put("name", name.orEmpty()).put("location", "卡片仓库").put("open_in_app", name != null)
    }
}
