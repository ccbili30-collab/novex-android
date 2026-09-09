package com.openminis.app.novex.domain

import com.openminis.app.data.character.CharacterVersionKind
import com.openminis.app.data.character.CharacterVersionProfile
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleReferenceEntity
import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.MediaAssetEntity
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.ModuleReferenceTargetType
import com.openminis.app.data.character.NovexCardKind
import com.openminis.app.data.character.NovexCardMedia
import com.openminis.app.data.character.NovexCardPackagePreview
import org.json.JSONArray
import org.json.JSONObject
import com.openminis.app.novex.domain.NovexCardTransferFields.sourceId
import com.openminis.app.novex.domain.NovexCardTransferFields.characterSourceId
import com.openminis.app.novex.domain.NovexCardTransferFields.tagsList
import com.openminis.app.novex.domain.NovexCardTransferFields.extension
import com.openminis.app.novex.domain.NovexCardTransferFields.putMedia
import com.openminis.app.novex.domain.NovexCardTransferFields.transferName
import com.openminis.app.novex.domain.NovexCardTransferFields.defaultPresentation
import com.openminis.app.novex.domain.NovexCardTransferFields.objects

/** Builds portable documents from read snapshots. Cannot mutate cards, pointers, or transactions. */
internal class NovexCardExporter(
    private val readMedia: suspend (MediaAssetEntity) -> ByteArray,
    private val moduleReferences: suspend (String) -> List<ContentModuleReferenceEntity>,
) {
    /** One media manifest per package, shared by roots, versions, and modules. */
    private inner class MediaAssembly(private val directoryMedia: MutableMap<String, MediaAssetEntity>?) {
        val files = mutableListOf<NovexCardMedia>()
        suspend fun add(basePath: String, asset: MediaAssetEntity?): String? {
            asset ?: return null
            val path = "$basePath.${asset.extension()}"
            directoryMedia?.set(path, asset)
            files += NovexCardMedia(path, asset.mimeType,
                if (directoryMedia == null) readMedia(asset) else ByteArray(0), asset.contentHash)
            return path
        }
    }

    suspend fun world(snapshot: NovexWorldSnapshot, directoryMedia: MutableMap<String, MediaAssetEntity>? = null): NovexCardPackagePreview {
        val original = runCatching { JSONObject(snapshot.world.legacySnapshotJson ?: "{}") }
            .getOrDefault(JSONObject())
        val sourceId = original.optString("sourceId").ifBlank { snapshot.world.id }
        val media = MediaAssembly(directoryMedia)
        val rootMedia = JSONObject()
        rootMedia.putMedia("cover", media.add("media/cover", snapshot.media[MediaAssetSlot.WORLD_COVER]))
        rootMedia.putMedia("logo", media.add("media/logo", snapshot.media[MediaAssetSlot.WORLD_LOGO]))
        rootMedia.putMedia("background", media.add("media/background", snapshot.media[MediaAssetSlot.WORLD_BACKGROUND]))
        val moduleJson = snapshot.modules.map { module ->
            exportModule(
                module = module,
                mainImage = snapshot.moduleImages[module.id],
                itemImages = snapshot.moduleItemImages[module.id].orEmpty(),
                media = media,
            )
        }
        val unresolvedLinks = original.optJSONArray("characterVersionLinks").objects().toMutableList()
        val existingVersionIds = unresolvedLinks.map { it.optString("sourceVersionId") }.toMutableSet()
        snapshot.versions.forEach { version ->
            val versionSourceId = version.sourceId() ?: version.id
            if (existingVersionIds.add(versionSourceId)) {
                val profile = CharacterVersionProfile.fromJson(version.profileJson, version.label)
                unresolvedLinks += JSONObject()
                    .put("sourceCharacterId", version.characterSourceId() ?: version.characterId)
                    .put("sourceVersionId", versionSourceId)
                    .put("fallbackCharacterName", profile.name)
                    .put("fallbackVersionName", version.label)
                    .put("roleInWorld", "")
            }
        }
        val document = original.apply {
            put("documentType", "novex.world")
            put("schemaVersion", 1)
            put("sourceId", sourceId)
            put("name", snapshot.world.name)
            put("tags", JSONArray(snapshot.world.tagsList()))
            put("overview", snapshot.world.overview)
            put("media", rootMedia)
            put("modules", JSONArray(moduleJson))
            put("moduleOrder", JSONArray(moduleJson.map { it.getString("id") }))
            put("characterVersionLinks", JSONArray(unresolvedLinks))
        }
        return NovexCardPackagePreview(
            kind = NovexCardKind.WORLD,
            packageId = sourceId,
            displayName = snapshot.world.name,
            documentJson = document.toString(2),
            media = media.files.distinctBy(NovexCardMedia::path),
        )
    }

    suspend fun character(snapshot: NovexCharacterSnapshot, relations: List<NovexCharacterVersionRelation>, selectedVersionIds: Set<String>? = null, directoryMedia: MutableMap<String, MediaAssetEntity>? = null): NovexCardPackagePreview {
        val characterId = snapshot.character.character.id
        val exportedVersions = snapshot.character.allVersions.filter { selectedVersionIds == null || it.id in selectedVersionIds }
        require(exportedVersions.isNotEmpty() && (selectedVersionIds == null || exportedVersions.size == selectedVersionIds.size)) {
            "所选角色版本已不存在，请重新选择导出范围"
        }
        val defaultVersion = exportedVersions.firstOrNull { it.kind == CharacterVersionKind.ORIGINAL } ?: exportedVersions.first()
        val originalProfileJson = JSONObject(defaultVersion.profileJson)
        val original = runCatching { JSONObject(originalProfileJson.optString("_novexCharacterDocument")) }
            .getOrDefault(JSONObject())
        val sourceId = originalProfileJson.optString("_novexCharacterSourceId")
            .ifBlank { snapshot.character.character.id }
        val media = MediaAssembly(directoryMedia)
        val claimedVersionIds = mutableSetOf<String>()
        val versionSourceIds = exportedVersions.associate { version ->
            val preferred = version.sourceId() ?: version.id
            var portableId = preferred
            var suffix = 0
            while (!claimedVersionIds.add(portableId)) {
                portableId = "${version.id}-${suffix++}"
            }
            version.id to portableId
        }
        val versionsJson = exportedVersions.map { version ->
            val profile = CharacterVersionProfile.fromJson(version.profileJson, snapshot.character.character.name)
            val sourceVersionId = versionSourceIds.getValue(version.id)
            val versionMedia = snapshot.mediaByVersion[version.id].orEmpty()
            val mediaJson = JSONObject()
            mediaJson.putMedia(
                "avatar",
                media.add("media/versions/$sourceVersionId/avatar", versionMedia[MediaAssetSlot.CHARACTER_AVATAR]),
            )
            mediaJson.putMedia(
                "pageBackground",
                media.add(
                    "media/versions/$sourceVersionId/background",
                    versionMedia[MediaAssetSlot.CHARACTER_PAGE_BACKGROUND],
                ),
            )
            val modules = snapshot.modulesByVersion[version.id].orEmpty().map { module ->
                exportModule(
                    module = module,
                    mainImage = snapshot.moduleImages[module.id],
                    itemImages = snapshot.moduleItemImages[module.id].orEmpty(),
                    media = media,
                    pathPrefix = "media/versions/$sourceVersionId/modules",
                    versionSourceIds = versionSourceIds,
                )
            }.toMutableList()
            if (profile.relationships.isNotEmpty()) {
                modules += JSONObject()
                    .put("id", "$sourceVersionId-relationships")
                    .put("type", "relationships")
                    .put("title", "关系")
                    .put("presentation", "compactList")
                    .put("content", JSONObject().put("items", JSONArray().apply {
                        profile.relationships.forEachIndexed { index, relation ->
                            put(
                                JSONObject()
                                    .put("id", "relation-$index")
                                    .put("fallbackName", relation.characterName)
                                    .put("relation", relation.relationship)
                                    .put("description", relation.description),
                            )
                        }
                    }))
            }
            val worlds = snapshot.worldsByVersion[version.id].orEmpty().map { world ->
                JSONObject()
                    .put("sourceWorldId", world.sourceId() ?: world.id)
                    .put("fallbackWorldName", world.name)
                    .put("roleInWorld", "")
            }
            JSONObject()
                .put("id", sourceVersionId)
                .put("kind", if (version.id == defaultVersion.id) "origin" else "variant")
                .put("name", version.label)
                .put("tags", JSONArray(profile.tags))
                .put(
                    "profile",
                    JSONObject(profile.toJson()).apply {
                        // Preserve role instructions and provider extensions. These fields
                        // are represented elsewhere in the portable version or local-only.
                        listOf("profileSchema", "name", "tags", "summary", "customAttributes", "relationships",
                            "_novexSourceId", "_novexCharacterSourceId", "_novexCharacterDocument")
                            .forEach(::remove)
                    }
                        .put("displayName", profile.name)
                        .put("gender", profile.gender)
                        .put("age", profile.age)
                        .put("race", profile.race)
                        .put("occupation", profile.occupation)
                        .put("introduction", profile.summary),
                )
                .put("media", mediaJson)
                .put("customAttributes", JSONArray().apply {
                    profile.customAttributes.forEach { attribute ->
                        put(JSONObject().put("key", attribute.name).put("value", attribute.value))
                    }
                })
                .put("modules", JSONArray(modules))
                .put("moduleOrder", JSONArray(modules.map { it.getString("id") }))
                .put("worldLinks", JSONArray(worlds))
        }
        val document = original.apply {
            put("documentType", "novex.character")
            put("schemaVersion", 1)
            put("sourceId", sourceId)
            put("name", snapshot.character.character.name)
            put("summary", CharacterVersionProfile.fromJson(defaultVersion.profileJson).summary)
            put("versions", JSONArray(versionsJson))
            put("versionOrder", JSONArray(versionsJson.map { it.getString("id") }))
            put("defaultVersionId", versionsJson.first { it.optString("kind") == "origin" }.getString("id"))
            if (selectedVersionIds != null) put("_novexExportSelection", JSONObject()
                .put("sourceCharacterId", characterId).put("sourceVersionIds", JSONArray(exportedVersions.map { it.id }))
                .put("defaultSourceVersionId", defaultVersion.id)
                .put("note", "导入后的默认版本仅用于包内展示，不改写来源人物阶段与分身关系"))
            put("versionRelations", JSONArray(relations.filter { it.sourceVersionId in versionSourceIds }.map { relation ->
                JSONObject(NovexCharacterVersionRelationCodec.encode(relation.copy(
                    sourceVersionId = versionSourceIds.getValue(relation.sourceVersionId),
                    targetVersionId = versionSourceIds[relation.targetVersionId] ?: relation.targetVersionId,
                    unresolvedTargetVersionId = if (versionSourceIds.containsKey(relation.targetVersionId)) null
                        else relation.unresolvedTargetVersionId ?: relation.targetVersionId,
                )))
            }))
        }
        return NovexCardPackagePreview(
            kind = NovexCardKind.CHARACTER,
            packageId = sourceId,
            displayName = snapshot.character.character.name,
            documentJson = document.toString(2),
            media = media.files.distinctBy(NovexCardMedia::path),
        )
    }

    suspend fun game(snapshot: NovexInteractiveFictionSnapshot, directoryMedia: MutableMap<String, MediaAssetEntity>? = null): NovexCardPackagePreview {
        val sourceId = snapshot.project.sourceId ?: snapshot.project.id
        val source = runCatching { JSONObject(snapshot.project.sourceDocumentJson ?: "{}") }
            .getOrDefault(JSONObject())
        val media = MediaAssembly(directoryMedia)
        val rootMedia = JSONObject()
        rootMedia.putMedia(
            "cover",
            media.add("media/cover", snapshot.media[MediaAssetSlot.INTERACTIVE_FICTION_COVER]),
        )
        rootMedia.putMedia(
            "background",
            media.add("media/background", snapshot.media[MediaAssetSlot.INTERACTIVE_FICTION_BACKGROUND]),
        )
        val modules = snapshot.modules.map { module ->
            exportModule(
                module = module,
                mainImage = snapshot.moduleImages[module.id],
                itemImages = snapshot.moduleItemImages[module.id].orEmpty(),
                media = media,
            )
        }
        val document = source.apply {
            put("documentType", "novex.game")
            put("schemaVersion", 1)
            put("sourceId", sourceId)
            put("name", snapshot.project.name)
            put("summary", snapshot.project.summary)
            put("launchMode", snapshot.project.launchMode.transferName())
            put("playerIdentity", snapshot.project.playerIdentity)
            put("media", rootMedia)
            put("modules", JSONArray(modules))
            put("moduleOrder", JSONArray(modules.map { it.getString("id") }))
        }
        return NovexCardPackagePreview(
            kind = NovexCardKind.GAME,
            packageId = sourceId,
            displayName = snapshot.project.name,
            documentJson = document.toString(2),
            media = media.files.distinctBy(NovexCardMedia::path),
        )
    }

    private suspend fun exportModule(
        module: ContentModuleEntity,
        mainImage: MediaAssetEntity?,
        itemImages: Map<String, MediaAssetEntity>,
        media: MediaAssembly,
        pathPrefix: String = "media/modules",
        versionSourceIds: Map<String, String> = emptyMap(),
    ): JSONObject {
        val document = ContentModuleDocumentCodec.decode(module.type, module.contentJson)
        var originalType = module.type.transferName()
        var presentation = module.type.defaultPresentation(document)
        val content = when (document) {
            is ContentModuleDocument.Article -> JSONObject().put("text", document.text)
            is ContentModuleDocument.SingleImage -> JSONObject()
                .put("image", media.add("$pathPrefix/${module.id}", mainImage)?.let { JSONObject().put("path", it) })
                .put("description", document.description)
            is ContentModuleDocument.Timeline -> JSONObject().put("nodes", JSONArray().apply {
                document.nodes.forEachIndexed { index, node ->
                    put(
                        JSONObject()
                            .put("id", "node-$index")
                            .put("time", node.time)
                            .put("title", node.title)
                            .put("description", node.description),
                    )
                }
            })
            is ContentModuleDocument.Collection -> JSONObject().put("items", JSONArray().apply {
                document.items.forEachIndexed { index, item ->
                    val itemId = item.id.ifBlank { "item-$index" }
                    val itemJson = runCatching { JSONObject(item.preservedJson) }.getOrDefault(JSONObject()).apply {
                        put("id", itemId)
                        put("name", item.name)
                        put("summary", item.summary)
                        put("description", item.description)
                        item.contextTriggerJson?.let { put("contextTrigger", org.json.JSONTokener(it).nextValue()) } ?: remove("contextTrigger")
                        media.add("$pathPrefix/${module.id}/$itemId", itemImages[item.id])?.let { path ->
                            put("image", JSONObject().put("path", path))
                        }
                    }
                    put(itemJson)
                }
            })
            is ContentModuleDocument.Unsupported -> {
                originalType = document.originalType
                presentation = document.presentation.orEmpty()
                runCatching { JSONObject(document.contentJson) }.getOrDefault(JSONObject().put("raw", document.contentJson))
            }
        }
        // Representative pictures belong to every module shape, including ordinary text.
        if (document !is ContentModuleDocument.SingleImage) {
            media.add("$pathPrefix/${module.id}", mainImage)?.let { content.put("image", JSONObject().put("path", it)) }
        }
        val retainedSource = runCatching { JSONObject(module.contentJson).optJSONObject("_novexTransferSource") }.getOrNull()
        val retainedContent = retainedSource?.optJSONObject("content")?.let { JSONObject(it.toString()) } ?: JSONObject()
        listOf("text", "description", "image", "nodes", "items").forEach(retainedContent::remove)
        val liveModule = runCatching { JSONObject(module.contentJson) }.getOrNull()
        // The editor accepts ordinary JSON extensions as well as rendered fields.
        // A typed display model is not a complete serialization of that document.
        // Keep author data; only our envelope and separately assembled media/links
        // are omitted here. Current values win over an older imported snapshot.
        if (document !is ContentModuleDocument.Unsupported) {
            val envelope = setOf("kind", "_novexTransferSource", "_novexPendingReferences", "image")
            liveModule?.keys()?.forEach { key ->
                if (key !in envelope) retainedContent.put(key, liveModule.get(key))
            }
        }
        content.keys().forEach { key -> retainedContent.put(key, content.get(key)) }
        if (liveModule?.has("contextTrigger") == true) retainedContent.put("contextTrigger", liveModule.get("contextTrigger"))
        else retainedContent.remove("contextTrigger")
        if (liveModule?.has(NovexStoryIllustrations.FIELD) == true) retainedContent.put(NovexStoryIllustrations.FIELD, liveModule.get(NovexStoryIllustrations.FIELD))
        else retainedContent.remove(NovexStoryIllustrations.FIELD)
        if (liveModule?.has(NovexModuleImageOrigins.FIELD) == true) retainedContent.put(NovexModuleImageOrigins.FIELD, liveModule.get(NovexModuleImageOrigins.FIELD))
        else retainedContent.remove(NovexModuleImageOrigins.FIELD)
        return (retainedSource?.let { JSONObject(it.toString()) } ?: JSONObject())
            .put("id", module.id)
            .put("type", originalType)
            .put("title", module.name)
            .put("presentation", presentation)
            .put("content", retainedContent)
            .put("references", JSONArray().apply {
                moduleReferences(module.id).forEach { reference ->
                    put(JSONObject()
                        .put("targetKind", when (reference.targetType) {
                            ModuleReferenceTargetType.MODULE -> "module"
                            ModuleReferenceTargetType.WORLD -> "world"
                            ModuleReferenceTargetType.CHARACTER_VERSION -> "characterVersion"
                        })
                        .put("targetId", if (reference.targetType == ModuleReferenceTargetType.CHARACTER_VERSION) {
                            versionSourceIds[reference.targetId] ?: reference.targetId
                        } else reference.targetId))
                }
                val pending = runCatching { JSONObject(module.contentJson)
                    .optJSONArray("_novexPendingReferences") }.getOrNull()
                if (pending != null) repeat(pending.length()) { put(pending.get(it)) }
            })
    }

}
