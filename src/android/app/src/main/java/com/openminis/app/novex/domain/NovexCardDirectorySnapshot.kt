package com.openminis.app.novex.domain

import com.openminis.app.data.character.*
import org.json.JSONArray
import org.json.JSONObject

/** Complete local card payload; references are identifiers, never recursively copied foreign cards. */
internal class NovexCardDirectorySnapshot(private val workspace: NovexWorkspace) {
    private fun fields(vararg values: Pair<String, Any?>) = JSONObject().apply { values.forEach { (name, value) -> put(name, json(value)) } }
    private fun json(value: Any?): Any = when(value) {
        null -> JSONObject.NULL
        is String, is Number, is Boolean -> value
        is Enum<*> -> value.name
        is List<*> -> JSONArray(value.map(::json))
        is Map<*, *> -> JSONObject().apply { value.forEach { (key, item) -> put(key.toString(), json(item)) } }
        is WorldEntity -> fields("id" to value.id, "name" to value.name, "overview" to value.overview, "tagsJson" to value.tagsJson,
            "legacySnapshotJson" to value.legacySnapshotJson, "createdAt" to value.createdAt, "updatedAt" to value.updatedAt)
        is CharacterEntity -> fields("id" to value.id, "name" to value.name, "originalVersionId" to value.originalVersionId,
            "createdAt" to value.createdAt, "updatedAt" to value.updatedAt)
        is CharacterVersionEntity -> fields("id" to value.id, "characterId" to value.characterId, "kind" to value.kind, "label" to value.label,
            "profileJson" to value.profileJson, "position" to value.position, "createdAt" to value.createdAt, "updatedAt" to value.updatedAt)
        is ContentModuleEntity -> fields("id" to value.id, "ownerType" to value.ownerType, "ownerId" to value.ownerId, "type" to value.type,
            "name" to value.name, "contentJson" to value.contentJson, "position" to value.position, "collapsed" to value.collapsed,
            "createdAt" to value.createdAt, "updatedAt" to value.updatedAt)
        is ContentModuleReferenceEntity -> fields("sourceModuleId" to value.sourceModuleId, "targetType" to value.targetType,
            "targetId" to value.targetId, "position" to value.position)
        is MediaAssetEntity -> fields("id" to value.id, "managedPath" to value.managedPath, "mimeType" to value.mimeType,
            "contentHash" to value.contentHash, "createdAt" to value.createdAt)
        is com.openminis.app.data.interactivefiction.InteractiveFictionProjectEntity -> fields("id" to value.id, "name" to value.name,
            "summary" to value.summary, "launchMode" to value.launchMode, "playerIdentity" to value.playerIdentity,
            "createdAt" to value.createdAt, "updatedAt" to value.updatedAt, "sourceId" to value.sourceId, "sourceDocumentJson" to value.sourceDocumentJson)
        is NovexCharacterVersionRelation -> JSONObject(NovexCharacterVersionRelationCodec.encode(value))
        else -> error("卡片目录含尚未登记的记录类型")
    }

    suspend fun read(key: NovexCardCopyKey): String? {
        val root = JSONObject().put("version", 1).put("kind", key.kind.name).put("id", key.id)
        val owners: List<NovexContentAddress>
        val modules: List<ContentModuleEntity>
        when (key.kind) {
            NovexCardKind.WORLD -> {
                val page = workspace.world(key.id) ?: return null
                root.put("record", json(page.world)).put("characterVersions", JSONArray(page.versions.map { it.id }))
                    .put("media", json(page.media)).put("moduleImages", json(page.moduleImages)).put("entryImages", json(page.moduleItemImages))
                modules = page.modules; owners = listOf(NovexContentAddress.world(key.id))
            }
            NovexCardKind.CHARACTER -> {
                val page = workspace.character(key.id) ?: return null
                root.put("record", json(page.character.character)).put("versions", json(page.character.allVersions))
                    .put("worldLinks", JSONObject().apply { page.worldsByVersion.forEach { (version, worlds) -> put(version, JSONArray(worlds.map { it.id })) } })
                    .put("media", json(page.mediaByVersion)).put("moduleImages", json(page.moduleImages)).put("entryImages", json(page.moduleItemImages))
                modules = page.modulesByVersion.values.flatten()
                owners = page.character.allVersions.map { NovexContentAddress.characterVersion(it.id) }
                root.put("versionRelations", JSONArray(owners.flatMap { workspace.versionRelations(it.id) }.distinctBy { it.id }.map { json(it) }))
            }
            NovexCardKind.GAME -> {
                val page = workspace.interactiveFiction(key.id) ?: return null
                root.put("record", json(page.project)).put("media", json(page.media)).put("moduleImages", json(page.moduleImages)).put("entryImages", json(page.moduleItemImages))
                modules = page.modules; owners = listOf(NovexContentAddress.interactiveFiction(key.id))
            }
        }
        root.put("modules", json(modules.sortedWith(compareBy<ContentModuleEntity> { it.ownerId }.thenBy { it.position })))
        root.put("moduleReferences", JSONObject().apply { modules.forEach { put(it.id, json(workspace.module(it.id)?.references.orEmpty())) } })
        root.put("references", JSONArray(owners.flatMap { workspace.referencesFrom(it) }.sortedBy { it.id }.map { JSONObject(NovexCardReferenceCodec.encode(it)) }))
        return canonicalRevisionJson(root)
    }
}
