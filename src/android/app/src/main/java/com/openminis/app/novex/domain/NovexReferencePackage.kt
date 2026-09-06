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

    suspend fun export(kind: NovexCardKind, id: String): NovexCardPackagePreview {
        val root = Key(kind, id)
        val cards = linkedMapOf<Key, Exported>()
        val references = linkedMapOf<String, NovexCardReference>()
        val queue = ArrayDeque<Key>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            if (key in cards) continue
            require(cards.size < 100) { "关联卡片超过一百张，请缩小依赖范围再导出" }
            val preview = exportSingle(key.kind, key.id)
            val addresses = addresses(key, preview)
            cards[key] = Exported("card-${cards.size}", preview, addresses)
            addresses.keys.forEach { source -> workspace.referencesFrom(source).forEach { reference ->
                references[reference.id] = reference
                keyFor(reference.target.subject)?.let(queue::add)
            } }
        }
        val rootCard = cards.getValue(root)
        if (references.isEmpty()) return rootCard.preview.copy(documentJson =
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
        rootDocument.put("referenceBundle", JSONObject().put("version", 1).put("root", rootCard.key)
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
        validated.forEach { (row, importedCard) ->
            val localId = importSingle(importedCard, now, false)
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

    private suspend fun addresses(key: Key, preview: NovexCardPackagePreview): Map<NovexContentAddress, String> = when (key.kind) {
        NovexCardKind.WORLD -> mapOf(NovexContentAddress.world(key.id) to JSONObject(preview.documentJson).getString("sourceId"))
        NovexCardKind.GAME -> mapOf(NovexContentAddress.interactiveFiction(key.id) to JSONObject(preview.documentJson).getString("sourceId"))
        NovexCardKind.CHARACTER -> requireNotNull(workspace.character(key.id)).character.allVersions.associate { version ->
            NovexContentAddress.characterVersion(version.id) to JSONObject(version.profileJson).optString("_novexSourceId").ifBlank { version.id }
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

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun NovexCardImportDocument.cardKind() = when (this) {
        is NovexWorldImportDocument -> NovexCardKind.WORLD
        is NovexCharacterImportDocument -> NovexCardKind.CHARACTER
        is NovexInteractiveFictionImportDocument -> NovexCardKind.GAME
    }
}
