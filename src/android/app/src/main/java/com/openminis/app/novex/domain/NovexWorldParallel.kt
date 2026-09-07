package com.openminis.app.novex.domain

import com.openminis.app.data.character.*
import java.util.UUID
import org.json.JSONObject

/** A series groups independent worlds; selected versions stay variants of their original person. */
data class NovexWorldParallelPerson(val versionId: String, val characterId: String, val name: String, val label: String)
data class NovexWorldParallelPlan(val worldId: String, val worldCopy: NovexCardCopyPlan,
    val people: List<NovexWorldParallelPerson>, val characterRevisions: Map<String, String>, val missingPeople: List<String>)
data class NovexWorldParallelResult(val worldId: String, val seriesId: String, val versions: Map<String, String>)

internal class NovexWorldParallel(private val workspace: NovexWorkspace) {
    suspend fun prepare(worldId: String): NovexWorldParallelPlan {
        val world = requireNotNull(workspace.world(worldId)) { "世界不存在" }
        val ids = world.versions.map { it.id }.toMutableSet()
        workspace.referencesFrom(NovexContentAddress.world(worldId)).filter { it.unresolvedTarget == null }.forEach { ref ->
            if(ref.target.subject.kind == NovexContentKind.CHARACTER_VERSION) ids += ref.target.subject.id
        }
        world.modules.forEach { module -> workspace.module(module.id)?.references.orEmpty().forEach { ref ->
            when(ref.targetType) {
                ModuleReferenceTargetType.CHARACTER_VERSION -> ids += ref.targetId
                ModuleReferenceTargetType.MODULE -> workspace.module(ref.targetId)?.module?.takeIf { it.ownerType == ModuleOwnerType.CHARACTER_VERSION }?.let { ids += it.ownerId }
                else -> Unit
            }
        } }
        val missing = mutableListOf<String>()
        val people = ids.sorted().mapNotNull { id ->
            val page = workspace.characterForVersion(id)
            val version = page?.character?.allVersions?.singleOrNull { it.id == id }
            if(version == null) { missing += id; null }
            else NovexWorldParallelPerson(id, version.characterId, requireNotNull(page).character.character.name, version.label)
        }
        val revisions = people.map { it.characterId }.distinct().associateWith { id ->
            workspace.prepareCardCopy(NovexCardCopyKey(NovexCardKind.CHARACTER, id), NovexCardCopyPolicy.REUSE).sourceRevision
        }
        return NovexWorldParallelPlan(worldId,
            workspace.prepareCardCopy(NovexCardCopyKey(NovexCardKind.WORLD, worldId), NovexCardCopyPolicy.REUSE), people, revisions, missing)
    }

    /** The caller provides the same database/media transaction as other native card commands. */
    suspend fun create(plan: NovexWorldParallelPlan, name: String, selected: Set<String>, now: Long): NovexWorldParallelResult {
        require(name.isNotBlank()) { "请填写平行世界名称" }
        require(selected.all { id -> plan.people.any { it.versionId == id } }) { "所选人物不属于此预览" }
        require(prepare(plan.worldId) == plan) { "世界、配套人物或关系已变化，请重新查看预览" }
        selected.forEach { id ->
            val person = plan.people.single { it.versionId == id }
            // Reuse native media reads before creating anything; a missing image must fail atomically.
            workspace.apply(NovexCommand.ExportNativeSelection(NovexCardCopyKey(NovexCardKind.CHARACTER, person.characterId), setOf(id)))
        }
        val original = requireNotNull(workspace.world(plan.worldId))
        val copied = (workspace.apply(NovexCommand.CopyCard(plan.worldCopy, now)) as NovexChange.CardsCopied).result
        val newWorldId = copied.root.id
        val sourceRaw = objectPreservingRaw(original.world.legacySnapshotJson)
        val oldSeries = sourceRaw.optJSONObject("_novexWorldSeries")
        val series = if(oldSeries != null && oldSeries.optString("id").isNotBlank()) JSONObject(oldSeries.toString())
            else JSONObject().put("id", UUID.randomUUID().toString()).put("name", "${original.world.name}系列")
        if(oldSeries == null || oldSeries.optString("id").isBlank()) workspace.apply(NovexCommand.SaveWorld(
            original.world.copy(legacySnapshotJson = sourceRaw.put("_novexWorldSeries", series).toString()), now))
        val newWorld = requireNotNull(workspace.world(newWorldId)).world
        workspace.apply(NovexCommand.SaveWorld(newWorld.copy(name = name.trim(), legacySnapshotJson = objectPreservingRaw(newWorld.legacySnapshotJson)
            .put("_novexWorldSeries", series).put("_novexParallelOrigin", JSONObject().put("sourceWorldId", plan.worldId)
                .put("sourceRevision", plan.worldCopy.sourceRevision).put("createdAt", now)).toString()), now))
        val mapping = linkedMapOf<NovexContentAddress, NovexContentAddress>(NovexContentAddress.world(plan.worldId) to NovexContentAddress.world(newWorldId))
        val moduleIds = linkedMapOf<String, String>()
        fun mapModules(old: List<ContentModuleEntity>, new: List<ContentModuleEntity>) {
            require(old.size == new.size) { "复制模块数量不一致" }
            old.sortedBy { it.position }.zip(new.sortedBy { it.position }).forEach { (a, b) -> moduleIds[a.id] = b.id }
        }
        mapModules(original.modules, workspace.world(newWorldId)!!.modules)
        val versions = linkedMapOf<String, String>()
        selected.sorted().forEach { id ->
            if(workspace.world(newWorldId)!!.versions.none { it.id == id })
                workspace.apply(NovexCommand.LinkCharacterVersion(newWorldId, id, workspace.world(newWorldId)!!.versions.size, now))
            val before = workspace.modules(ModuleOwner.characterVersion(id)).modules
            val version = workspace.apply(NovexCommand.SaveAsWorldVariant(id, newWorldId, now)).requireVersion()
            versions[id] = version.id
            mapping[NovexContentAddress.characterVersion(id)] = NovexContentAddress.characterVersion(version.id)
            mapModules(before, workspace.modules(ModuleOwner.characterVersion(version.id)).modules)
        }
        // Finish remapping only after all selected variants/modules exist; unselected people stay shared.
        mapping.values.forEach { source -> workspace.referencesFrom(source).forEach { ref ->
            val target = mapping[ref.target.subject]
            if(ref.unresolvedTarget == null && target != null) workspace.apply(NovexCommand.PutCardReference(ref.copy(
                target = ref.target.copy(subject = target, moduleId = ref.target.moduleId?.let { moduleIds[it] ?: it }))))
        } }
        moduleIds.values.forEach { id -> workspace.module(id)?.references.orEmpty().forEach { ref ->
            val targetId = when(ref.targetType) {
                ModuleReferenceTargetType.MODULE -> moduleIds[ref.targetId]
                ModuleReferenceTargetType.WORLD -> mapping[NovexContentAddress.world(ref.targetId)]?.id
                ModuleReferenceTargetType.CHARACTER_VERSION -> versions[ref.targetId]
            }
            if(targetId != null && targetId != ref.targetId) {
                workspace.apply(NovexCommand.RemoveModuleReference(id, ref.target))
                workspace.apply(NovexCommand.AddModuleReference(id, ModuleReferenceTarget(ref.targetType, targetId), ref.position))
            }
        } }
        return NovexWorldParallelResult(newWorldId, series.getString("id"), versions)
    }

    private fun objectPreservingRaw(raw: String?): JSONObject = raw?.let {
        runCatching { JSONObject(it) }.getOrElse { JSONObject().put("_novexOriginalRaw", raw) }
    } ?: JSONObject()
}


internal object NovexWorldSeriesTransport {
    fun imported(raw: String, mapping: MutableMap<String, String> = mutableMapOf()): String {
        val value = JSONObject(raw)
        val series = value.optJSONObject("_novexWorldSeries") ?: return raw
        val sourceId = series.optString("id").takeIf { it.isNotBlank() } ?: return raw
        value.put("_novexImportedWorldSeries", JSONObject(series.toString()))
        value.put("_novexWorldSeries", JSONObject(series.toString()).put("id", mapping.getOrPut(sourceId) { UUID.randomUUID().toString() }))
        return value.toString()
    }
}
