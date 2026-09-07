package com.openminis.app.novex.domain

import com.openminis.app.data.character.*
import com.openminis.app.data.interactivefiction.InteractiveFictionProjectEntity
import java.util.UUID
import org.json.JSONObject

/** A character key covers its versions; a world/game key denotes one independent card. */
data class NovexCardCopyKey(val kind: NovexCardKind, val id: String)
enum class NovexCardCopyPolicy(val label: String, val description: String) {
    DETACH("不带外部引用", "保留自身正文、模块和内部关系"),
    REUSE("沿用外部引用", "新卡继续引用原有的独立卡片"),
    DEPENDENCIES("连带复制", "复制可找到的依赖，内部引用统一指向副本"),
}
data class NovexCardCopyItem(val key: NovexCardCopyKey, val name: String, val versionCount: Int, val moduleCount: Int)
data class NovexCardCopyPlan(val root: NovexCardCopyKey, val policy: NovexCardCopyPolicy,
    val items: List<NovexCardCopyItem>, val sourceRevision: String, val externalReferenceCount: Int,
    val missingTargets: List<String>, val mediaCount: Int)
data class NovexCardCopyResult(val root: NovexCardCopyKey, val addresses: Map<NovexContentAddress, NovexContentAddress>, val sourceRevision: String)

internal class NovexCardCopy(
    private val workspace: NovexWorkspace, private val catalog: NovexCatalogPort,
    private val content: NovexContentPort, private val media: NovexMediaPort,
    private val fiction: NovexInteractiveFictionPort, private val references: NovexCardReferencePort,
    private val relations: NovexCharacterVersionRelationPort,
) {
    private data class Asset(val owner: ModuleOwner, val slot: MediaAssetSlot, val asset: MediaAssetEntity)
    private data class Row(val key: NovexCardCopyKey, val name: String,
        val world: WorldEntity? = null, val character: CharacterAggregate? = null, val game: InteractiveFictionProjectEntity? = null,
        val modules: Map<NovexContentAddress, List<ContentModuleEntity>>, val refs: List<NovexCardReference>,
        val moduleRefs: Map<String, List<ContentModuleReferenceEntity>>, val memberships: List<Pair<String, String>>,
        val relations: List<NovexCharacterVersionRelation>, val assets: List<Asset>)
    private data class Snapshot(val plan: NovexCardCopyPlan, val rows: List<Row>)
    suspend fun prepare(root: NovexCardCopyKey, policy: NovexCardCopyPolicy) = collect(root, policy).plan

    private suspend fun collect(root: NovexCardCopyKey, policy: NovexCardCopyPolicy): Snapshot {
        val queue = ArrayDeque<NovexCardCopyKey>().apply { add(root) }
        val rows = linkedMapOf<NovexCardCopyKey, Row>()
        val missing = linkedSetOf<String>()
        suspend fun resolve(subject: NovexContentAddress): NovexCardCopyKey? = when(subject.kind) {
            NovexContentKind.WORLD -> catalog.world(subject.id)?.let { NovexCardCopyKey(NovexCardKind.WORLD, it.id) }
            NovexContentKind.INTERACTIVE_FICTION -> fiction.project(subject.id)?.let { NovexCardCopyKey(NovexCardKind.GAME, it.id) }
            NovexContentKind.CHARACTER_VERSION -> catalog.version(subject.id)?.let { NovexCardCopyKey(NovexCardKind.CHARACTER, it.characterId) }
            else -> null
        }
        suspend fun target(value: ModuleReferenceTarget): NovexContentAddress? = when(value.type) {
            ModuleReferenceTargetType.WORLD -> NovexContentAddress.world(value.id)
            ModuleReferenceTargetType.CHARACTER_VERSION -> NovexContentAddress.characterVersion(value.id)
            ModuleReferenceTargetType.MODULE -> content.module(value.id)?.let { address(ModuleOwner(it.ownerType, it.ownerId)) }
        }
        while(queue.isNotEmpty()) {
            val key = queue.removeFirst()
            if(key in rows) continue
            require(rows.size < 100) { "依赖超过一百张卡片，请缩小复制范围" }
            val row = read(key); rows[key] = row
            val dependencies = row.refs.mapNotNull { ref ->
                if(ref.unresolvedTarget != null) { missing += "引用 ${ref.id}：来源已标记缺失"; null } else ref.target.subject
            }.toMutableList()
            row.refs.filter { it.unresolvedTarget == null }.forEach { ref ->
                if(workspace.referenceStatus(ref.target) != NovexReferenceTargetStatus.AVAILABLE) missing += "引用 ${ref.id}：目标卡片、模块或条目缺失"
            }
            row.moduleRefs.values.flatten().forEach { ref ->
                val subject = target(ModuleReferenceTarget(ref.targetType, ref.targetId))
                if(subject == null) missing += "模块引用 ${ref.targetType}:${ref.targetId} 缺失" else dependencies += subject
            }
            row.memberships.forEach { (world, version) -> dependencies += NovexContentAddress.world(world); dependencies += NovexContentAddress.characterVersion(version) }
            dependencies.distinct().forEach { subject ->
                val dependency = resolve(subject)
                if(dependency == null) missing += "${subject.kind}:${subject.id} 缺失"
                else if(policy == NovexCardCopyPolicy.DEPENDENCIES) queue.add(dependency)
            }
        }
        val subjects = rows.values.flatMap { it.modules.keys }.toSet()
        val moduleIds = rows.values.flatMap { it.modules.values.flatten().map { module -> module.id } }.toSet()
        val external = rows.values.sumOf { row -> row.refs.count { it.target.subject !in subjects || it.unresolvedTarget != null } +
            row.moduleRefs.values.flatten().count { ref -> when(ref.targetType) {
                ModuleReferenceTargetType.MODULE -> ref.targetId !in moduleIds
                ModuleReferenceTargetType.WORLD -> NovexContentAddress.world(ref.targetId) !in subjects
                ModuleReferenceTargetType.CHARACTER_VERSION -> NovexContentAddress.characterVersion(ref.targetId) !in subjects
            } } + row.memberships.count { (w, v) -> NovexContentAddress.world(w) !in subjects || NovexContentAddress.characterVersion(v) !in subjects } }
        // Complete owned payloads and relationships are included, not just card names/timestamps.
        val revision = NovexFrozenContextCodec.digest(rows.values.joinToString("\n") { it.toString() })
        return Snapshot(NovexCardCopyPlan(root, policy, rows.values.map { row ->
            NovexCardCopyItem(row.key, row.name, row.character?.allVersions?.size ?: 0, row.modules.values.sumOf { it.size })
        }, revision, external, missing.toList(), rows.values.flatMap { it.assets }.map { it.asset.contentHash }.distinct().size), rows.values.toList())
    }

    private suspend fun read(key: NovexCardCopyKey): Row {
        val world = if(key.kind == NovexCardKind.WORLD) requireNotNull(catalog.world(key.id)) { "世界不存在" } else null
        val character = if(key.kind == NovexCardKind.CHARACTER) requireNotNull(catalog.character(key.id)) { "角色不存在" } else null
        val game = if(key.kind == NovexCardKind.GAME) requireNotNull(fiction.project(key.id)) { "文游不存在" } else null
        val subjects = when(key.kind) {
            NovexCardKind.WORLD -> listOf(NovexContentAddress.world(key.id))
            NovexCardKind.GAME -> listOf(NovexContentAddress.interactiveFiction(key.id))
            NovexCardKind.CHARACTER -> character!!.allVersions.map { NovexContentAddress.characterVersion(it.id) }
        }
        val modules = subjects.associateWith { content.list(owner(it)).sortedBy { module -> module.position } }
        val assets = mutableListOf<Asset>()
        suspend fun image(owner: ModuleOwner, slot: MediaAssetSlot) { media.assetFor(owner, slot)?.let { assets += Asset(owner, slot, it) } }
        subjects.forEach { subject -> MediaAssetSlot.entries.forEach { image(owner(subject), it) } }
        modules.values.flatten().forEach { module ->
            image(ModuleOwner.contentModule(module.id), MediaAssetSlot.MODULE_IMAGE)
            (ContentModuleDocumentCodec.decode(module.type, module.contentJson) as? ContentModuleDocument.Collection)?.items.orEmpty()
                .forEach { item -> if(item.id.isNotBlank()) image(ModuleOwner.contentModuleItem(module.id, item.id), MediaAssetSlot.MODULE_IMAGE) }
        }
        val memberships = if(world != null) catalog.versionsForWorld(world.id).map { world.id to it.id }
            else character?.allVersions.orEmpty().flatMap { version -> catalog.worldsForVersion(version.id).map { it.id to version.id } }
        return Row(key, world?.name ?: game?.name ?: character!!.character.name, world, character, game, modules,
            subjects.flatMap { references.outgoing(it) }.sortedBy { it.id },
            modules.values.flatten().associate { it.id to content.references(it.id) }, memberships,
            character?.let { relations.forCharacter(it.character.id).sortedBy { relation -> relation.id } }.orEmpty(), assets)
    }

    suspend fun execute(plan: NovexCardCopyPlan, now: Long): NovexCardCopyResult {
        val snapshot = collect(plan.root, plan.policy)
        require(snapshot.plan == plan) { "来源或依赖已变化，请重新查看复制预览" }
        // Read every referenced image before mutation; missing media must not produce a false success.
        snapshot.rows.flatMap { it.assets }.distinctBy { it.asset.id }.forEach { media.read(it.asset) }
        val mapping = linkedMapOf<NovexContentAddress, NovexContentAddress>()
        val roots = linkedMapOf<NovexCardCopyKey, NovexCardCopyKey>()
        val moduleMap = linkedMapOf<String, String>()
        fun origin(raw: String?, key: NovexCardCopyKey, versionId: String? = null): String =
            (raw?.let { runCatching { JSONObject(it) }.getOrElse { JSONObject().put("_novexOriginalRaw", raw) } } ?: JSONObject())
                .apply {
                    if(key.kind == NovexCardKind.WORLD && has("_novexWorldSeries")) {
                        put("_novexSourceWorldSeries", get("_novexWorldSeries")); remove("_novexWorldSeries")
                    }
                }
                .put("_novexCopyOrigin", JSONObject().put("kind", key.kind.name).put("id", key.id).put("versionId", versionId)
                    .put("revision", plan.sourceRevision).put("policy", plan.policy.name).put("copiedAt", now)
                    .put("missingTargets", org.json.JSONArray(plan.missingTargets)).put("externalReferenceCount", plan.externalReferenceCount)).toString()
        snapshot.rows.forEach { row ->
            when(row.key.kind) {
                NovexCardKind.WORLD -> {
                    val old = row.world!!
                    val new = catalog.createWorld("${old.name} 副本", old.overview, old.tagsJson, origin(old.legacySnapshotJson, row.key), now)
                    mapping[NovexContentAddress.world(old.id)] = NovexContentAddress.world(new.id)
                    roots[row.key] = NovexCardCopyKey(row.key.kind, new.id)
                }
                NovexCardKind.GAME -> {
                    val old = row.game!!
                    val new = fiction.create("${old.name} 副本", old.summary, old.launchMode, old.playerIdentity, now, old.sourceId, origin(old.sourceDocumentJson, row.key))
                    mapping[NovexContentAddress.interactiveFiction(old.id)] = NovexContentAddress.interactiveFiction(new.id)
                    roots[row.key] = NovexCardCopyKey(row.key.kind, new.id)
                }
                NovexCardKind.CHARACTER -> {
                    val old = row.character!!; val new = catalog.duplicateCharacter(old.character.id, now)
                    old.allVersions.zip(new.allVersions).forEach { (a, b) ->
                        catalog.saveVersion(b.copy(profileJson = origin(b.profileJson, row.key, a.id)), now)
                        mapping[NovexContentAddress.characterVersion(a.id)] = NovexContentAddress.characterVersion(b.id)
                    }
                    roots[row.key] = NovexCardCopyKey(row.key.kind, new.character.id)
                }
            }
        }
        snapshot.rows.forEach { row -> row.modules.forEach { (subject, modules) -> modules.forEach { module ->
            val new = content.add(owner(mapping.getValue(subject)), module.type, module.name, module.contentJson, module.collapsed, now, UUID.randomUUID().toString())
            moduleMap[module.id] = new.id
        } } }
        fun mappedTarget(target: ModuleReferenceTarget): ModuleReferenceTarget? = when(target.type) {
            ModuleReferenceTargetType.MODULE -> moduleMap[target.id]?.let { ModuleReferenceTarget.module(it) }
            ModuleReferenceTargetType.WORLD -> mapping[NovexContentAddress.world(target.id)]?.let { ModuleReferenceTarget.world(it.id) }
            ModuleReferenceTargetType.CHARACTER_VERSION -> mapping[NovexContentAddress.characterVersion(target.id)]?.let { ModuleReferenceTarget.characterVersion(it.id) }
        } ?: target.takeIf { plan.policy == NovexCardCopyPolicy.REUSE }
        val memberships = linkedSetOf<Pair<String, String>>()
        val missing = mutableMapOf<NovexContentAddress, NovexContentAddress>()
        snapshot.rows.forEach { row ->
            row.moduleRefs.forEach { (old, refs) -> refs.forEach { ref ->
                mappedTarget(ModuleReferenceTarget(ref.targetType, ref.targetId))?.let { content.addReference(moduleMap.getValue(old), it, ref.position) }
            } }
            row.refs.forEach { ref ->
                val internal = ref.unresolvedTarget == null && ref.target.subject in mapping
                if(internal || plan.policy != NovexCardCopyPolicy.DETACH) {
                    val subject = if(internal) mapping.getValue(ref.target.subject) else if(plan.policy == NovexCardCopyPolicy.REUSE) ref.target.subject
                        else missing.getOrPut(ref.target.subject) { NovexContentAddress(ref.target.subject.kind, "missing:${UUID.randomUUID()}") }
                    val targetModule = if(internal) ref.target.moduleId?.let { moduleMap[it] ?: "missing:${UUID.randomUUID()}" } else ref.target.moduleId
                    references.save(ref.copy(id = UUID.randomUUID().toString(), source = mapping.getValue(ref.source),
                        sourceModuleId = ref.sourceModuleId?.let { moduleMap.getValue(it) },
                        target = NovexReferenceTarget(subject, targetModule, ref.target.entryId),
                        unresolvedTarget = ref.unresolvedTarget ?: ref.target.takeIf { !internal && plan.policy == NovexCardCopyPolicy.DEPENDENCIES } ))
                }
            }
            row.memberships.forEach { (world, version) ->
                val w = mapping[NovexContentAddress.world(world)]?.id ?: world.takeIf { plan.policy == NovexCardCopyPolicy.REUSE }
                val v = mapping[NovexContentAddress.characterVersion(version)]?.id ?: version.takeIf { plan.policy == NovexCardCopyPolicy.REUSE }
                if(w != null && v != null) memberships += w to v
            }
            row.character?.let { row.relations.forEach { relation ->
                val source = mapping.getValue(NovexContentAddress.characterVersion(relation.sourceVersionId)).id
                val target = if(relation.unresolvedTargetVersionId == null) mapping[NovexContentAddress.characterVersion(relation.targetVersionId)]?.id else null
                if(target != null || plan.policy != NovexCardCopyPolicy.DETACH) relations.save(roots.getValue(row.key).id,
                    relation.copy(id = UUID.randomUUID().toString(), sourceVersionId = source,
                        targetVersionId = target ?: "missing:${UUID.randomUUID()}",
                        unresolvedTargetVersionId = if(target != null) null else relation.unresolvedTargetVersionId ?: relation.targetVersionId))
            } }
            row.assets.forEach { asset ->
                val newOwner = if(asset.owner.type == ModuleOwnerType.CONTENT_MODULE) {
                    val oldModule = ModuleOwner.contentModuleId(asset.owner.id)
                    ModuleOwner(ModuleOwnerType.CONTENT_MODULE, moduleMap.getValue(oldModule) + asset.owner.id.removePrefix(oldModule))
                } else owner(mapping.getValue(requireNotNull(address(asset.owner))))
                media.attach(newOwner, asset.slot, asset.asset.id)
            }
        }
        memberships.groupBy({ it.first }, { it.second }).forEach { (world, versions) ->
            val start = catalog.versionsForWorld(world).size
            versions.forEachIndexed { index, version -> catalog.link(world, version, start + index, now) }
        }
        return NovexCardCopyResult(roots.getValue(plan.root), mapping, plan.sourceRevision)
    }
    private fun owner(subject: NovexContentAddress) = when(subject.kind) {
        NovexContentKind.WORLD -> ModuleOwner.world(subject.id)
        NovexContentKind.CHARACTER_VERSION -> ModuleOwner.characterVersion(subject.id)
        NovexContentKind.INTERACTIVE_FICTION -> ModuleOwner.interactiveFiction(subject.id)
        else -> error("文件使用已有成果复制入口")
    }
    private fun address(owner: ModuleOwner): NovexContentAddress? = when(owner.type) {
        ModuleOwnerType.WORLD -> NovexContentAddress.world(owner.id)
        ModuleOwnerType.CHARACTER_VERSION -> NovexContentAddress.characterVersion(owner.id)
        ModuleOwnerType.INTERACTIVE_FICTION -> NovexContentAddress.interactiveFiction(owner.id)
        else -> null
    }
}
