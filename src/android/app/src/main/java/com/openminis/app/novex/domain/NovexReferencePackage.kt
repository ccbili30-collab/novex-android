package com.openminis.app.novex.domain

import com.openminis.app.data.character.*
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Portable dependency graph. Every import allocates its own map; names and ambient catalog matches are irrelevant. */
internal class NovexReferencePackage(
    private val workspace: NovexWorkspace,
    private val restoreReference: suspend (NovexCardReference) -> Unit,
    private val exportSingle: suspend (NovexCardKind, String) -> NovexCardPackagePreview,
    private val importSingle: suspend (NovexValidatedCardImport, Long, Boolean) -> String,
    private val restoreLegacyLink: suspend (String, String, Int, Long) -> Unit,
) {
    private data class Key(val kind: NovexCardKind, val id: String)
    private data class Exported(val key: String, val preview: NovexCardPackagePreview, val addresses: Map<NovexContentAddress, String>)

    suspend fun export(kind: NovexCardKind, id: String, selectedVersionIds: Set<String>? = null,
        includeDependencies: Boolean = true, explicitScope: Boolean = false): NovexCardPackagePreview {
        val root = Key(kind, id)
        val cards = linkedMapOf<Key, Exported>()
        val references = linkedMapOf<String, NovexCardReference>()
        val queue = ArrayDeque<Key>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            if (key in cards) continue
            require(cards.size < 100) { "关联卡片超过一百张，请缩小依赖范围再导出" }
            val preview = exportSingle(key.kind, key.id)
            val addresses = addresses(key, preview, selectedVersionIds.takeIf { key == root })
            cards[key] = Exported("card-${cards.size}", preview, addresses)
            addresses.keys.forEach { source -> workspace.referencesFrom(source).forEach { reference ->
                references[reference.id] = reference
                if (includeDependencies && reference.unresolvedTarget == null) keyFor(reference.target.subject)?.let(queue::add)
            } }
            if (includeDependencies && explicitScope) {
                val owners = addresses.keys.map { source -> when (source.kind) {
                    NovexContentKind.WORLD -> ModuleOwner.world(source.id)
                    NovexContentKind.CHARACTER_VERSION -> ModuleOwner.characterVersion(source.id)
                    NovexContentKind.INTERACTIVE_FICTION -> ModuleOwner.interactiveFiction(source.id)
                    NovexContentKind.CREATIVE_ARTIFACT -> error("卡片包不收录创作文件")
                } }
                owners.forEach { owner -> workspace.modules(owner).modules.forEach { module ->
                    workspace.module(module.id)?.references.orEmpty().forEach { ref ->
                        val target = when(ref.targetType) {
                            ModuleReferenceTargetType.WORLD -> NovexContentAddress.world(ref.targetId)
                            ModuleReferenceTargetType.CHARACTER_VERSION -> NovexContentAddress.characterVersion(ref.targetId)
                            ModuleReferenceTargetType.MODULE -> workspace.module(ref.targetId)?.module?.let { target -> when(target.ownerType) {
                                ModuleOwnerType.WORLD -> NovexContentAddress.world(target.ownerId)
                                ModuleOwnerType.CHARACTER_VERSION -> NovexContentAddress.characterVersion(target.ownerId)
                                ModuleOwnerType.INTERACTIVE_FICTION -> NovexContentAddress.interactiveFiction(target.ownerId)
                                else -> null
                            } }
                        }
                        target?.let { keyFor(it) }?.let(queue::add)
                    }
                } }
                when(key.kind) {
                    NovexCardKind.WORLD -> workspace.world(key.id)?.versions.orEmpty().forEach { version ->
                        keyFor(NovexContentAddress.characterVersion(version.id))?.let(queue::add)
                    }
                    NovexCardKind.CHARACTER -> addresses.keys.forEach { source ->
                        workspace.character(key.id)?.worldsByVersion?.get(source.id).orEmpty().forEach { world -> queue.add(Key(NovexCardKind.WORLD, world.id)) }
                    }
                    else -> Unit
                }
            }
        }
        val rootCard = cards.getValue(root)
        if (references.isEmpty() && !explicitScope) return rootCard.preview.copy(documentJson =
            JSONObject(rootCard.preview.documentJson).apply { remove("referenceBundle") }.toString(2))
        val media = mutableListOf<NovexCardMedia>()
        val records = JSONArray()
        var rootDocument = JSONObject()
        cards.values.forEach { exported ->
            val prefix = if (exported.key == rootCard.key) "" else "dependencies/${exported.key}/"
            val paths = exported.preview.media.associate { it.path to "$prefix${it.path}" }
            val document = JSONObject(exported.preview.documentJson).apply { remove("referenceBundle") }
            relocateMedia(document, paths)
            media += exported.preview.media.map { it.copy(path = paths.getValue(it.path)) }
            if (exported.key == rootCard.key) rootDocument = document
            records.put(JSONObject().apply {
                put("key", exported.key); put("kind", exported.preview.kind.name)
                put("packageId", exported.preview.packageId); put("name", exported.preview.displayName)
                put("sourceRevision", NovexFrozenContextCodec.digest(document.toString()))
                if (exported.key != rootCard.key) put("document", document)
                put("addresses", JSONArray().apply { exported.addresses.forEach { (address, documentId) ->
                    put(JSONObject().put("kind", address.kind.name).put("id", address.id).put("documentId", documentId))
                } })
            })
        }
        if (explicitScope) {
            val included = cards.values.flatMap { it.addresses.keys }.toSet()
            val includedModules = included.flatMap { source -> workspace.modules(source.owner()).modules.map { it.id } }.toSet()
            val diagnostics = linkedSetOf<String>()
            references.values.forEach { ref ->
                if (ref.target.subject !in included || ref.unresolvedTarget != null || workspace.referenceStatus(ref.target) != NovexReferenceTargetStatus.AVAILABLE)
                    diagnostics += "带用途引用 ${ref.id}：目标未收录或缺失（${ref.target.subject.id}）"
            }
            included.forEach { source ->
                workspace.modules(source.owner()).modules.forEach { module -> workspace.module(module.id)?.references.orEmpty().forEach { ref ->
                    val present = when(ref.targetType) {
                        ModuleReferenceTargetType.MODULE -> ref.targetId in includedModules
                        ModuleReferenceTargetType.WORLD -> NovexContentAddress.world(ref.targetId) in included
                        ModuleReferenceTargetType.CHARACTER_VERSION -> NovexContentAddress.characterVersion(ref.targetId) in included
                    }
                    if(!present) diagnostics += "模块 ${module.name} 的引用未收录或缺失：${ref.targetId}"
                } }
                when(source.kind) {
                    NovexContentKind.WORLD -> workspace.world(source.id)?.versions.orEmpty().forEach { version ->
                        if(NovexContentAddress.characterVersion(version.id) !in included) diagnostics += "世界配套人物未收录：${version.label}（${version.id}）"
                    }
                    NovexContentKind.CHARACTER_VERSION -> {
                        workspace.characterForVersion(source.id)?.worldsByVersion?.get(source.id).orEmpty().forEach { world ->
                            if(NovexContentAddress.world(world.id) !in included) diagnostics += "人物所属世界未收录：${world.name}（${world.id}）"
                        }
                        workspace.versionRelations(source.id).filter { it.sourceVersionId == source.id }.forEach { relation ->
                            if(NovexContentAddress.characterVersion(relation.targetVersionId) !in included || relation.unresolvedTargetVersionId != null)
                                diagnostics += "人物版本关系目标未收录或缺失：${relation.targetVersionId}"
                        }
                    }
                    else -> Unit
                }
            }
            rootDocument.put("_novexExportDiagnostics", JSONArray(diagnostics.toList()))
        }
        rootDocument.put("referenceBundle", JSONObject().put("version", 1).put("scopedExport", explicitScope).put("root", rootCard.key)
            .put("cards", records).put("references", JSONArray().apply {
                references.values.forEach { put(JSONObject(NovexCardReferenceCodec.encode(it))) }
            }))
        return rootCard.preview.copy(documentJson = rootDocument.toString(2), media = media)
    }

    suspend fun import(card: NovexValidatedCardImport, now: Long): String {
        val root = JSONObject(card.document.originalJson)
        val bundle = root.optJSONObject("referenceBundle") ?: return importSingle(card, now, true)
        require(bundle.getInt("version") == 1) { "不支持的卡片依赖版本" }
        val rows = bundle.getJSONArray("cards").objects()
        require(rows.size in 1..100) { "卡片依赖数量无效" }
        require(rows.map { it.getString("key") }.distinct().size == rows.size) { "依赖卡片编号重复" }
        val rootKey = bundle.getString("root")
        require(rows.count { it.getString("key") == rootKey } == 1) { "依赖包主卡编号无效" }
        val rootWithoutBundle = JSONObject(root.toString()).apply { remove("referenceBundle") }
        // Validate every dependency before creating any object; the caller also supplies one transaction.
        val validated = rows.map { row ->
            val isRoot = row.getString("key") == rootKey
            val document = if (isRoot) rootWithoutBundle else row.getJSONObject("document")
            require(!document.has("referenceBundle")) { "依赖包不能嵌套另一套依赖包" }
            val kind = NovexCardKind.valueOf(row.getString("kind"))
            if (isRoot) require(kind == card.document.cardKind()) { "依赖包主卡类型不一致" }
            val parsed = NovexCardTransferParser.parse(NovexCardPackagePreview(kind,
                row.getString("packageId"), row.getString("name"), document.toString(), card.media.values.toList()))
            // Keep the original root exchange document, including its source mapping and extensions.
            row to if (isRoot) card else parsed
        }
        val references = bundle.getJSONArray("references").objects().map { NovexCardReferenceCodec.decode(it.toString()) }
        require(references.map { it.id }.distinct().size == references.size) { "依赖包引用编号重复" }
        val addressMap = linkedMapOf<NovexContentAddress, NovexContentAddress>()
        val moduleMap = linkedMapOf<String, String>()
        val importedRecords = mutableListOf<Pair<NovexCardImportDocument, Map<String, NovexContentAddress>>>()
        var rootId: String? = null
        val seriesMap = mutableMapOf<String, String>()
        validated.forEach { (row, importedCard) ->
            val localId = importSingle(importedCard, now, false)
            if(importedCard.document is NovexWorldImportDocument) {
                val world = requireNotNull(workspace.world(localId)).world
                val remapped = NovexWorldSeriesTransport.imported(importedCard.document.originalJson, seriesMap)
                if(remapped != world.legacySnapshotJson) workspace.apply(NovexCommand.SaveWorld(world.copy(legacySnapshotJson = remapped), now))
            }
            if (row.getString("key") == rootKey) rootId = localId
            val document = importedCard.document
            val importedAddresses = importedAddresses(document, localId)
            importedRecords += document to importedAddresses
            val declared = row.getJSONArray("addresses").objects()
            require(declared.size == importedAddresses.size) { "依赖卡片的版本映射不完整" }
            declared.forEach { item ->
                val old = NovexContentAddress(NovexContentKind.valueOf(item.getString("kind")), item.getString("id"))
                val local = requireNotNull(importedAddresses[item.getString("documentId")]) { "依赖卡片版本编号不存在" }
                require(old.kind == local.kind && addressMap.put(old, local) == null) { "依赖卡片地址重复或类型不一致" }
            }
            when (document) {
                is NovexWorldImportDocument -> mapModules(document.modules, ModuleOwner.world(localId), moduleMap)
                is NovexInteractiveFictionImportDocument -> mapModules(document.modules, ModuleOwner.interactiveFiction(localId), moduleMap)
                is NovexCharacterImportDocument -> document.versions.forEach { version ->
                    mapModules(version.modules, ModuleOwner.characterVersion(importedAddresses.getValue(version.sourceId).id), moduleMap)
                }
            }
        }
        // Restore the older membership relation only among unambiguous objects created by this import.
        val scopedWorlds = importedRecords.filter { it.first is NovexWorldImportDocument }
            .flatMap { it.second.entries }.groupBy({ it.key }, { it.value.id })
        val scopedVersions = importedRecords.filter { it.first is NovexCharacterImportDocument }
            .flatMap { it.second.entries }.groupBy({ it.key }, { it.value.id })
        val memberships = linkedSetOf<Pair<String, String>>()
        importedRecords.forEach { (document, addresses) -> when (document) {
            is NovexWorldImportDocument -> document.characterVersionLinks.forEach { link ->
                scopedVersions[link.sourceVersionId]?.singleOrNull()?.let { version ->
                    memberships += addresses.getValue(document.sourceId).id to version
                }
            }
            is NovexCharacterImportDocument -> document.versions.forEach { version -> version.worldLinks.forEach { link ->
                scopedWorlds[link.sourceWorldId]?.singleOrNull()?.let { world ->
                    memberships += world to addresses.getValue(version.sourceId).id
                }
            } }
            is NovexInteractiveFictionImportDocument -> Unit
        } }
        memberships.groupBy({ it.first }, { it.second }).forEach { (world, versions) ->
            versions.forEachIndexed { position, version -> restoreLegacyLink(world, version, position, now) }
        }
        if(bundle.optBoolean("scopedExport", false)) {
            val documents = importedRecords.flatMap { (document, _) -> when(document) {
                is NovexWorldImportDocument -> document.modules
                is NovexInteractiveFictionImportDocument -> document.modules
                is NovexCharacterImportDocument -> document.versions.flatMap { it.modules }
            } }
            documents.forEach { document ->
                val sourceId = moduleMap.getValue(document.sourceId)
                val restored = mutableSetOf<Pair<String, String>>()
                JSONArray(document.referencesJson).objects().forEachIndexed { position, reference ->
                    val kind = reference.optString("targetKind"); val id = reference.optString("targetId")
                    val target = when(kind) {
                        "module" -> moduleMap[id]?.let(ModuleReferenceTarget::module)
                        "world" -> scopedWorlds[id]?.singleOrNull()?.let(ModuleReferenceTarget::world)
                        "characterVersion" -> scopedVersions[id]?.singleOrNull()?.let(ModuleReferenceTarget::characterVersion)
                        else -> null
                    }
                    if(target != null) {
                        if(workspace.module(sourceId)?.references.orEmpty().none { it.target == target })
                            workspace.apply(NovexCommand.AddModuleReference(sourceId, target, position))
                        restored += kind to id
                    }
                }
                val module = requireNotNull(workspace.module(sourceId)).module
                val raw = JSONObject(module.contentJson)
                raw.optJSONArray("_novexPendingReferences")?.let { pending ->
                    val remaining = (0 until pending.length()).map { pending.get(it) }.filterNot { item ->
                        item is JSONObject && (item.optString("targetKind") to item.optString("targetId")) in restored
                    }
                    if(remaining.size != pending.length()) {
                        if(remaining.isEmpty()) raw.remove("_novexPendingReferences") else raw.put("_novexPendingReferences", JSONArray(remaining))
                        workspace.apply(NovexCommand.SaveModule(sourceId, module.name, raw.toString(), now))
                    }
                }
            }
        }
        val missing = mutableMapOf<NovexContentAddress, NovexContentAddress>()
        references.forEach { reference ->
            val source = requireNotNull(addressMap[reference.source]) { "依赖包引用来源不在包内" }
            val target = addressMap[reference.target.subject] ?: missing.getOrPut(reference.target.subject) {
                NovexContentAddress(reference.target.subject.kind, "missing:${UUID.randomUUID()}")
            }
            val sourceModule = reference.sourceModuleId?.let { requireNotNull(moduleMap[it]) { "依赖包引用来源模块不存在" } }
            val targetModule = reference.target.moduleId?.let { moduleMap[it] ?: "missing:${UUID.randomUUID()}" }
            val restored = reference.copy(id = UUID.randomUUID().toString(), source = source, sourceModuleId = sourceModule,
                target = NovexReferenceTarget(target, targetModule, reference.target.entryId),
                unresolvedTarget = reference.unresolvedTarget ?: reference.target.takeIf {
                    reference.target.subject !in addressMap || (reference.target.moduleId != null && reference.target.moduleId !in moduleMap)
                })
            // Missing targets are deliberately retained. They must never bind to an ambient local ID.
            restoreReference(restored)
        }
        return requireNotNull(rootId)
    }

    private suspend fun mapModules(documents: List<NovexModuleImportDocument>, owner: ModuleOwner, map: MutableMap<String, String>) {
        val modules = workspace.modules(owner).modules.sortedBy { it.position }
        require(modules.size == documents.size) { "导入后的模块映射不完整" }
        documents.zip(modules).forEach { (document, module) ->
            require(map.put(document.sourceId, module.id) == null) { "依赖包模块编号重复" }
        }
    }

    private suspend fun importedAddresses(document: NovexCardImportDocument, localId: String): Map<String, NovexContentAddress> = when (document) {
        is NovexWorldImportDocument -> mapOf(document.sourceId to NovexContentAddress.world(localId))
        is NovexInteractiveFictionImportDocument -> mapOf(document.sourceId to NovexContentAddress.interactiveFiction(localId))
        is NovexCharacterImportDocument -> requireNotNull(workspace.character(localId)).character.allVersions.associate { version ->
            JSONObject(version.profileJson).getString("_novexSourceId") to NovexContentAddress.characterVersion(version.id)
        }.also { require(it.size == document.versions.size) { "角色版本来源编号重复" } }
    }

    private suspend fun addresses(key: Key, preview: NovexCardPackagePreview, selectedVersionIds: Set<String>? = null): Map<NovexContentAddress, String> = when (key.kind) {
        NovexCardKind.WORLD -> mapOf(NovexContentAddress.world(key.id) to JSONObject(preview.documentJson).getString("sourceId"))
        NovexCardKind.GAME -> mapOf(NovexContentAddress.interactiveFiction(key.id) to JSONObject(preview.documentJson).getString("sourceId"))
        NovexCardKind.CHARACTER -> {
            val versions = requireNotNull(workspace.character(key.id)).character.allVersions.filter { selectedVersionIds == null || it.id in selectedVersionIds }
            val exported = JSONObject(preview.documentJson).getJSONArray("versions").objects()
            require(versions.size == exported.size) { "导出的版本映射不完整" }
            versions.zip(exported).associate { (version, document) ->
                NovexContentAddress.characterVersion(version.id) to document.getString("id")
            }
        }
    }

    private suspend fun keyFor(address: NovexContentAddress): Key? = when (address.kind) {
        NovexContentKind.WORLD -> workspace.world(address.id)?.let { Key(NovexCardKind.WORLD, address.id) }
        NovexContentKind.CHARACTER_VERSION -> workspace.characterForVersion(address.id)?.let { Key(NovexCardKind.CHARACTER, it.character.character.id) }
        NovexContentKind.INTERACTIVE_FICTION -> workspace.interactiveFiction(address.id)?.let { Key(NovexCardKind.GAME, address.id) }
        NovexContentKind.CREATIVE_ARTIFACT -> null
    }

    private fun relocateMedia(value: Any, paths: Map<String, String>) {
        when (value) {
            is JSONObject -> value.keys().asSequence().toList().forEach { key ->
                val child = value.get(key)
                if (key == "path" && child is String && child in paths) value.put(key, paths.getValue(child))
                else relocateMedia(child, paths)
            }
            is JSONArray -> (0 until value.length()).forEach { relocateMedia(value.get(it), paths) }
        }
    }

    private fun NovexContentAddress.owner(): ModuleOwner = when(kind) {
        NovexContentKind.WORLD -> ModuleOwner.world(id)
        NovexContentKind.CHARACTER_VERSION -> ModuleOwner.characterVersion(id)
        NovexContentKind.INTERACTIVE_FICTION -> ModuleOwner.interactiveFiction(id)
        NovexContentKind.CREATIVE_ARTIFACT -> error("创作文件不属于卡片包")
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun NovexCardImportDocument.cardKind() = when (this) {
        is NovexWorldImportDocument -> NovexCardKind.WORLD
        is NovexCharacterImportDocument -> NovexCardKind.CHARACTER
        is NovexInteractiveFictionImportDocument -> NovexCardKind.GAME
    }
}
