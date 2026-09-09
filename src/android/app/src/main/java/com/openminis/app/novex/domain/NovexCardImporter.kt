package com.openminis.app.novex.domain

import com.openminis.app.data.character.CharacterVersionKind
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.CharacterVersionEntity
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.MediaAssetEntity
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.ModuleReferenceTarget
import com.openminis.app.data.character.NovexCharacterImportDocument
import com.openminis.app.data.character.NovexCharacterVersionImportDocument
import com.openminis.app.data.character.NovexModuleImportDocument
import com.openminis.app.data.character.NovexValidatedCardImport
import com.openminis.app.data.character.NovexWorldImportDocument
import com.openminis.app.data.character.NovexWorldImportLink
import com.openminis.app.data.character.NovexInteractiveFictionImportDocument
import com.openminis.app.novex.domain.NovexCardTransferFields.sourceId
import com.openminis.app.novex.domain.NovexCardTransferFields.objects
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Restores validated portable cards inside the workspace's existing commit transaction.
 * Owns source-id mapping and media assembly, never starts an independent commit.
 */
internal class NovexCardImporter(
    private val catalog: NovexCatalogPort,
    private val interactiveFiction: NovexInteractiveFictionPort,
    private val content: NovexContentPort,
    private val media: NovexMediaPort,
    private val versionRelations: NovexCharacterVersionRelationPort,
) {
    suspend fun import(card: NovexValidatedCardImport, now: Long, reconcileLegacyLinks: Boolean): String =
        when (val document = card.document) {
            is NovexWorldImportDocument -> importWorldCard(document, card, now, reconcileLegacyLinks)
            is NovexCharacterImportDocument -> importCharacterCard(document, card, now, reconcileLegacyLinks)
            is NovexInteractiveFictionImportDocument -> importInteractiveFictionCard(document, card, now)
        }

    private suspend fun importWorldCard(
        document: NovexWorldImportDocument,
        card: NovexValidatedCardImport,
        now: Long,
        reconcileLegacyLinks: Boolean = true,
    ): String {
        val world = catalog.createWorld(
            name = document.name,
            overview = document.overview,
            tagsJson = JSONArray(document.tags).toString(),
            legacySnapshotJson = NovexWorldSeriesTransport.imported(document.originalJson),
            now = now,
        )
        val assets = mutableMapOf<String, MediaAssetEntity>()
        suspend fun attach(path: String?, owner: ModuleOwner, slot: MediaAssetSlot) {
            if (path == null) return
            val asset = importCardMedia(path, card, assets, now)
            media.attach(owner, slot, asset.id)
        }
        val owner = ModuleOwner.world(world.id)
        attach(document.coverPath, owner, MediaAssetSlot.WORLD_COVER)
        attach(document.logoPath, owner, MediaAssetSlot.WORLD_LOGO)
        attach(document.backgroundPath, owner, MediaAssetSlot.WORLD_BACKGROUND)
        restoreImportedModuleReferences(importCardModules(owner, document.modules, card, assets, now), now)

        val versionsBySourceId = catalog.listVersions().mapNotNull { version ->
            version.sourceId()?.let { it to version }
        }.toMap()
        if (reconcileLegacyLinks) document.characterVersionLinks.forEachIndexed { index, link ->
            versionsBySourceId[link.sourceVersionId]?.let { version ->
                catalog.link(world.id, version.id, index, now)
            }
        }
        return world.id
    }

    private suspend fun importCharacterCard(
        document: NovexCharacterImportDocument,
        card: NovexValidatedCardImport,
        now: Long,
        reconcileLegacyLinks: Boolean = true,
    ): String {
        val originalDocument = document.versions.single { it.kind == CharacterVersionKind.ORIGINAL }
        val aggregate = catalog.createCharacter(
            name = document.name,
            originalLabel = originalDocument.label,
            profileJson = originalDocument.profileJson,
            now = now,
        )
        val importedVersions = mutableListOf(aggregate.original to originalDocument)
        document.versions.filter { it.kind == CharacterVersionKind.VARIANT }.forEach { versionDocument ->
            importedVersions += catalog.createVariant(
                characterId = aggregate.character.id,
                label = versionDocument.label,
                profileJson = versionDocument.profileJson,
                now = now,
            ) to versionDocument
        }
        val assets = mutableMapOf<String, MediaAssetEntity>()
        val importedModules = mutableListOf<Pair<NovexModuleImportDocument, ContentModuleEntity>>()
        importedVersions.forEach { (version, versionDocument) ->
            val owner = ModuleOwner.characterVersion(version.id)
            suspend fun attach(path: String?, slot: MediaAssetSlot) {
                if (path == null) return
                val asset = importCardMedia(path, card, assets, now)
                media.attach(owner, slot, asset.id)
            }
            attach(versionDocument.avatarPath, MediaAssetSlot.CHARACTER_AVATAR)
            attach(versionDocument.pageBackgroundPath, MediaAssetSlot.CHARACTER_PAGE_BACKGROUND)
            importedModules += importCardModules(owner, versionDocument.modules, card, assets, now)
        }
        restoreImportedModuleReferences(importedModules, now, importedVersions.associate { (version, document) ->
            ModuleReferenceTarget.characterVersion(document.sourceId) to ModuleReferenceTarget.characterVersion(version.id)
        })
        val relationJson = JSONObject(document.originalJson).optJSONArray("versionRelations") ?: JSONArray()
        restoreVersionRelations(
            List(relationJson.length()) { NovexCharacterVersionRelationCodec.decode(relationJson.getJSONObject(it).toString()) },
            importedVersions.associate { (version, source) -> source.sourceId to version.id },
            aggregate.character.id,
        )
        if (reconcileLegacyLinks) reconcileImportedCharacterLinks(importedVersions, now)
        return aggregate.character.id
    }

    private suspend fun restoreVersionRelations(
        relations: List<NovexCharacterVersionRelation>,
        versionIds: Map<String, String>,
        characterId: String,
    ) {
        require(relations.map { it.id }.distinct().size == relations.size) { "版本关系编号重复" }
        val missingIds = mutableMapOf<String, String>()
        val versions = requireNotNull(catalog.character(characterId)).allVersions
        relations.forEach { relation ->
            val source = requireNotNull(versionIds[relation.sourceVersionId]) { "版本关系来源不属于导入人物" }
            val target = if (relation.unresolvedTargetVersionId == null) versionIds[relation.targetVersionId] else null
            val unresolved = if (target == null) relation.unresolvedTargetVersionId ?: relation.targetVersionId else null
            val restored = relation.copy(
                id = UUID.randomUUID().toString(), sourceVersionId = source,
                targetVersionId = target ?: missingIds.getOrPut(requireNotNull(unresolved)) { "missing:${UUID.randomUUID()}" },
                unresolvedTargetVersionId = unresolved,
            )
            NovexCharacterVersionRelationRules.validate(restored, versions, versionRelations.forCharacter(characterId), allowMissingTarget = true)
            versionRelations.save(characterId, restored)
        }
    }

    private suspend fun importInteractiveFictionCard(
        document: NovexInteractiveFictionImportDocument,
        card: NovexValidatedCardImport,
        now: Long,
    ): String {
        val project = interactiveFiction.create(
            name = document.name,
            summary = document.summary,
            launchMode = document.launchMode,
            playerIdentity = document.playerIdentity,
            now = now,
            sourceId = document.sourceId,
            sourceDocumentJson = document.originalJson,
        )
        val owner = ModuleOwner.interactiveFiction(project.id)
        val assets = mutableMapOf<String, MediaAssetEntity>()
        suspend fun attach(path: String?, slot: MediaAssetSlot) {
            if (path == null) return
            val asset = importCardMedia(path, card, assets, now)
            media.attach(owner, slot, asset.id)
        }
        attach(document.coverPath, MediaAssetSlot.INTERACTIVE_FICTION_COVER)
        attach(document.backgroundPath, MediaAssetSlot.INTERACTIVE_FICTION_BACKGROUND)
        restoreImportedModuleReferences(importCardModules(owner, document.modules, card, assets, now), now)
        return project.id
    }

    private suspend fun importCardModules(
        owner: ModuleOwner,
        modules: List<NovexModuleImportDocument>,
        card: NovexValidatedCardImport,
        assets: MutableMap<String, MediaAssetEntity>,
        now: Long,
    ): List<Pair<NovexModuleImportDocument, ContentModuleEntity>> =
        modules.map { moduleDocument ->
            val module = content.add(
                owner = owner,
                type = moduleDocument.type,
                name = moduleDocument.title,
                contentJson = JSONObject(ContentModuleDocumentCodec.encode(moduleDocument.document))
                    .put("_novexTransferSource", JSONObject(moduleDocument.originalJson)).apply {
                        JSONObject(moduleDocument.originalJson).optJSONObject("content")?.let { original ->
                            if (original.has("contextTrigger")) put("contextTrigger", original.get("contextTrigger"))
                            if (original.has(NovexStoryIllustrations.FIELD)) put(NovexStoryIllustrations.FIELD, original.get(NovexStoryIllustrations.FIELD))
                            if (original.has(NovexModuleImageOrigins.FIELD)) put(NovexModuleImageOrigins.FIELD, original.get(NovexModuleImageOrigins.FIELD))
                        }
                    }.toString(),
                collapsed = true,
                now = now,
                id = UUID.randomUUID().toString(),
            )
            moduleDocument.imagePath?.let { path ->
                val asset = importCardMedia(path, card, assets, now)
                media.attach(ModuleOwner.contentModule(module.id), MediaAssetSlot.MODULE_IMAGE, asset.id)
            }
            moduleDocument.itemImagePaths.forEach { (itemId, path) ->
                val asset = importCardMedia(path, card, assets, now)
                media.attach(
                    ModuleOwner.contentModuleItem(module.id, itemId),
                    MediaAssetSlot.MODULE_IMAGE,
                    asset.id,
                )
            }
            moduleDocument to module
        }

    private suspend fun restoreImportedModuleReferences(
        imported: List<Pair<NovexModuleImportDocument, ContentModuleEntity>>,
        now: Long,
        versionTargets: Map<ModuleReferenceTarget, ModuleReferenceTarget> = emptyMap(),
    ) {
        val localIds = imported.associate { (document, module) -> document.sourceId to module.id }
        require(localIds.size == imported.size) { "卡包模块编号重复，无法安全恢复引用" }
        val targets = localIds.map { (source, local) ->
            ModuleReferenceTarget.module(source) to ModuleReferenceTarget.module(local)
        }.toMap() + versionTargets
        imported.forEach { (document, module) ->
            val pending = JSONArray()
            val references = JSONArray(document.referencesJson)
            repeat(references.length()) { index ->
                val reference = references.optJSONObject(index)
                val sourceTarget = reference?.let {
                    when (it.optString("targetKind")) {
                        "module" -> ModuleReferenceTarget.module(it.optString("targetId"))
                        "characterVersion" -> ModuleReferenceTarget.characterVersion(it.optString("targetId"))
                        else -> null
                    }
                }
                val localTarget = sourceTarget?.let(targets::get)
                if (localTarget != null) {
                    content.addReference(module.id, localTarget, index)
                } else {
                    // External or future link kinds are data, not permission to bind local objects.
                    pending.put(references.get(index))
                }
            }
            if (pending.length() > 0) {
                val json = JSONObject(module.contentJson).put("_novexPendingReferences", pending)
                content.save(module.id, module.name, json.toString(), now)
            }
        }
    }

    private suspend fun importCardMedia(
        path: String,
        card: NovexValidatedCardImport,
        assets: MutableMap<String, MediaAssetEntity>,
        now: Long,
    ): MediaAssetEntity = assets[path] ?: run {
        val source = requireNotNull(card.media[path]) { "卡包媒体不存在：$path" }
        media.import(source.bytes, source.mimeType, now).also { assets[path] = it }
    }

    private suspend fun reconcileImportedCharacterLinks(
        versions: List<Pair<CharacterVersionEntity, NovexCharacterVersionImportDocument>>,
        now: Long,
    ) {
        val worlds = catalog.listWorlds()
        versions.forEach { (version, document) ->
            val worldSourceIds = document.worldLinks.map(NovexWorldImportLink::sourceWorldId).toSet()
            worlds.filter { it.sourceId() in worldSourceIds }.forEach { world ->
                val position = catalog.versionsForWorld(world.id).size
                catalog.link(world.id, version.id, position, now)
            }
            worlds.forEach { world ->
                val links = runCatching {
                    JSONObject(world.legacySnapshotJson ?: "{}").optJSONArray("characterVersionLinks")
                }.getOrNull()
                val matchedPosition = links.objects().indexOfFirst { item ->
                    item.optString("sourceVersionId") == document.sourceId
                }
                if (matchedPosition >= 0) catalog.link(world.id, version.id, matchedPosition, now)
            }
        }
    }

}
