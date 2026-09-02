package com.openminis.app.novex.domain

import com.openminis.app.data.character.CharacterAggregate
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleReferenceEntity
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.CharacterVersionEntity
import com.openminis.app.data.character.CharacterLibraryDocument
import com.openminis.app.data.character.CharacterModuleDocument
import com.openminis.app.data.character.CharacterVersionDocument
import com.openminis.app.data.character.MediaAssetEntity
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.ModuleOwnerType
import com.openminis.app.data.character.ModuleReferenceTarget
import com.openminis.app.data.character.WorldEntity

/**
 * The single Novex seam used by pages and future automation.
 *
 * Callers read complete page snapshots and submit domain commands. Storage,
 * managed files, ordering rules and reference cleanup stay behind this seam.
 */
interface NovexWorkspace {
    suspend fun worlds(): List<NovexWorldCard>
    suspend fun characters(): List<NovexCharacterCard>
    suspend fun world(id: String): NovexWorldSnapshot?
    suspend fun character(id: String): NovexCharacterSnapshot?
    suspend fun modules(owner: ModuleOwner): NovexModuleSnapshot
    suspend fun module(id: String): NovexModuleDetail?
    suspend fun apply(command: NovexCommand): NovexChange
}

data class NovexWorldCard(
    val world: WorldEntity,
    val image: MediaAssetEntity?,
    val characterCount: Int,
    val moduleCount: Int,
)

data class NovexCharacterCard(
    val character: CharacterAggregate,
    val avatar: MediaAssetEntity?,
)

data class NovexWorldSnapshot(
    val world: WorldEntity,
    val versions: List<com.openminis.app.data.character.CharacterVersionEntity>,
    val availableVersions: List<com.openminis.app.data.character.CharacterVersionEntity>,
    val worldsByVersion: Map<String, List<WorldEntity>>,
    val media: Map<MediaAssetSlot, MediaAssetEntity>,
    val modules: List<ContentModuleEntity>,
    val moduleImages: Map<String, MediaAssetEntity>,
)

data class NovexCharacterSnapshot(
    val character: CharacterAggregate,
    val worldsByVersion: Map<String, List<WorldEntity>>,
    val mediaByVersion: Map<String, Map<MediaAssetSlot, MediaAssetEntity>>,
    val modulesByVersion: Map<String, List<ContentModuleEntity>>,
    val moduleImages: Map<String, MediaAssetEntity>,
)

data class NovexModuleSnapshot(
    val modules: List<ContentModuleEntity>,
    val images: Map<String, MediaAssetEntity>,
)

data class NovexModuleDetail(
    val module: ContentModuleEntity,
    val image: MediaAssetEntity?,
    val references: List<ContentModuleReferenceEntity>,
    val referenceOptions: List<NovexModuleReferenceOption>,
)

data class NovexModuleReferenceOption(
    val target: ModuleReferenceTarget,
    val label: String,
    val kindLabel: String,
)

sealed interface NovexCommand {
    data class CreateWorld(
        val name: String,
        val overview: String = "",
        val tagsJson: String = "[]",
        val now: Long = System.currentTimeMillis(),
    ) : NovexCommand

    data class SaveWorld(
        val world: WorldEntity,
        val now: Long = System.currentTimeMillis(),
    ) : NovexCommand

    data class DeleteWorld(val worldId: String) : NovexCommand

    data class CreateCharacter(
        val name: String,
        val profileJson: String = "{}",
        val now: Long = System.currentTimeMillis(),
    ) : NovexCommand

    data class DuplicateCharacter(
        val characterId: String,
        val now: Long = System.currentTimeMillis(),
    ) : NovexCommand

    data class ImportCharacter(
        val document: CharacterLibraryDocument,
        val now: Long = System.currentTimeMillis(),
    ) : NovexCommand

    data class ExportCharacter(val characterId: String) : NovexCommand

    data class DeleteCharacter(val characterId: String) : NovexCommand

    data class CreateVariant(
        val characterId: String,
        val label: String,
        val profileJson: String = "{}",
        val now: Long = System.currentTimeMillis(),
    ) : NovexCommand

    data class SaveCharacterVersion(
        val characterId: String,
        val versionId: String,
        val rootName: String,
        val label: String,
        val profileJson: String,
        val now: Long = System.currentTimeMillis(),
    ) : NovexCommand

    data class DeleteVariant(val versionId: String) : NovexCommand

    data class LinkCharacterVersion(
        val worldId: String,
        val versionId: String,
        val position: Int,
        val now: Long = System.currentTimeMillis(),
    ) : NovexCommand

    data class UnlinkCharacterVersion(
        val worldId: String,
        val versionId: String,
    ) : NovexCommand

    data class SaveAsWorldVariant(
        val sourceVersionId: String,
        val worldId: String,
        val now: Long = System.currentTimeMillis(),
    ) : NovexCommand

    data class AddModule(
        val owner: ModuleOwner,
        val type: ContentModuleType,
        val name: String,
        val contentJson: String = "{}",
        val collapsed: Boolean = true,
        val now: Long = System.currentTimeMillis(),
    ) : NovexCommand

    data class SaveModule(
        val moduleId: String,
        val name: String,
        val contentJson: String,
        val now: Long = System.currentTimeMillis(),
    ) : NovexCommand

    data class MoveModule(
        val moduleId: String,
        val toIndex: Int,
        val now: Long = System.currentTimeMillis(),
    ) : NovexCommand

    data class DeleteModule(val moduleId: String) : NovexCommand

    data class AddModuleReference(
        val moduleId: String,
        val target: ModuleReferenceTarget,
        val position: Int,
    ) : NovexCommand

    data class RemoveModuleReference(
        val moduleId: String,
        val target: ModuleReferenceTarget,
    ) : NovexCommand

    data class AttachImage(
        val owner: ModuleOwner,
        val slot: MediaAssetSlot,
        val bytes: ByteArray,
        val mimeType: String,
        val now: Long = System.currentTimeMillis(),
    ) : NovexCommand

    data class DetachImage(
        val owner: ModuleOwner,
        val slot: MediaAssetSlot,
    ) : NovexCommand
}

sealed interface NovexChange {
    data class WorldSaved(val world: WorldEntity) : NovexChange
    data class CharacterSaved(val character: CharacterAggregate) : NovexChange
    data class VersionSaved(val version: CharacterVersionEntity) : NovexChange
    data class ModuleSaved(val module: ContentModuleEntity) : NovexChange
    data class MediaAttached(val asset: MediaAssetEntity) : NovexChange
    data class CharacterExported(val document: CharacterLibraryDocument) : NovexChange
    data object Completed : NovexChange
}

fun NovexChange.requireWorld(): WorldEntity = (this as NovexChange.WorldSaved).world

fun NovexChange.requireCharacter(): CharacterAggregate =
    (this as NovexChange.CharacterSaved).character

fun NovexChange.requireVersion(): CharacterVersionEntity = (this as NovexChange.VersionSaved).version

fun NovexChange.requireModule(): ContentModuleEntity = (this as NovexChange.ModuleSaved).module

fun NovexChange.requireMedia(): MediaAssetEntity = (this as NovexChange.MediaAttached).asset

fun NovexChange.requireDocument(): CharacterLibraryDocument =
    (this as NovexChange.CharacterExported).document

internal interface NovexCatalogPort {
    suspend fun createWorld(name: String, overview: String, tagsJson: String, now: Long): WorldEntity
    suspend fun saveWorld(world: WorldEntity, now: Long): WorldEntity
    suspend fun deleteWorld(worldId: String)
    suspend fun world(id: String): WorldEntity?
    suspend fun listWorlds(): List<WorldEntity>
    suspend fun createCharacter(
        name: String,
        originalLabel: String,
        profileJson: String,
        now: Long,
    ): CharacterAggregate
    suspend fun duplicateCharacter(characterId: String, now: Long): CharacterAggregate
    suspend fun deleteCharacter(characterId: String)
    suspend fun saveCharacter(
        character: com.openminis.app.data.character.CharacterEntity,
        now: Long,
    ): com.openminis.app.data.character.CharacterEntity
    suspend fun saveVersion(version: CharacterVersionEntity, now: Long): CharacterVersionEntity
    suspend fun deleteVariant(versionId: String)
    suspend fun character(id: String): CharacterAggregate?
    suspend fun listCharacters(): List<com.openminis.app.data.character.CharacterEntity>
    suspend fun listVersions(): List<com.openminis.app.data.character.CharacterVersionEntity>
    suspend fun versionsForWorld(worldId: String): List<com.openminis.app.data.character.CharacterVersionEntity>
    suspend fun worldsForVersion(versionId: String): List<WorldEntity>
    suspend fun version(id: String): CharacterVersionEntity?
    suspend fun createVariant(
        characterId: String,
        label: String,
        profileJson: String,
        now: Long,
    ): CharacterVersionEntity
    suspend fun link(worldId: String, versionId: String, position: Int, now: Long)
    suspend fun unlink(worldId: String, versionId: String)
}

internal interface NovexContentPort {
    suspend fun list(owner: ModuleOwner): List<ContentModuleEntity>
    suspend fun all(): List<ContentModuleEntity>
    suspend fun add(
        owner: ModuleOwner,
        type: ContentModuleType,
        name: String,
        contentJson: String,
        collapsed: Boolean,
        now: Long,
    ): ContentModuleEntity
    suspend fun module(id: String): ContentModuleEntity?
    suspend fun save(id: String, name: String, contentJson: String, now: Long): ContentModuleEntity
    suspend fun move(id: String, toIndex: Int, now: Long): ContentModuleEntity
    suspend fun delete(id: String)
    suspend fun copyAll(source: ModuleOwner, target: ModuleOwner, now: Long): List<ContentModuleEntity>
    suspend fun references(moduleId: String): List<ContentModuleReferenceEntity>
    suspend fun addReference(moduleId: String, target: ModuleReferenceTarget, position: Int)
    suspend fun removeReference(moduleId: String, target: ModuleReferenceTarget)
}

internal interface NovexMediaPort {
    suspend fun import(bytes: ByteArray, mimeType: String, now: Long): MediaAssetEntity
    suspend fun attach(owner: ModuleOwner, slot: MediaAssetSlot, assetId: String)
    suspend fun detach(owner: ModuleOwner, slot: MediaAssetSlot)
    suspend fun removeAll(owner: ModuleOwner)
    suspend fun assetFor(owner: ModuleOwner, slot: MediaAssetSlot): MediaAssetEntity?
}

internal class DefaultNovexWorkspace(
    private val catalog: NovexCatalogPort,
    private val content: NovexContentPort,
    private val media: NovexMediaPort,
) : NovexWorkspace {
    override suspend fun worlds(): List<NovexWorldCard> = catalog.listWorlds().map { world ->
        val owner = ModuleOwner.world(world.id)
        NovexWorldCard(
            world = world,
            image = media.assetFor(owner, MediaAssetSlot.WORLD_COVER)
                ?: media.assetFor(owner, MediaAssetSlot.WORLD_BACKGROUND),
            characterCount = catalog.versionsForWorld(world.id).size,
            moduleCount = content.list(owner).size,
        )
    }

    override suspend fun characters(): List<NovexCharacterCard> = catalog.listCharacters().mapNotNull { root ->
        val aggregate = catalog.character(root.id) ?: return@mapNotNull null
        NovexCharacterCard(
            character = aggregate,
            avatar = media.assetFor(
                ModuleOwner.characterVersion(aggregate.original.id),
                MediaAssetSlot.CHARACTER_AVATAR,
            ),
        )
    }

    override suspend fun world(id: String): NovexWorldSnapshot? {
        val world = catalog.world(id) ?: return null
        val versions = catalog.versionsForWorld(id)
        val modules = content.list(ModuleOwner.world(id))
        return NovexWorldSnapshot(
            world = world,
            versions = versions,
            availableVersions = catalog.listVersions(),
            worldsByVersion = versions.associate { it.id to catalog.worldsForVersion(it.id) },
            media = mediaFor(
                ModuleOwner.world(id),
                listOf(MediaAssetSlot.WORLD_COVER, MediaAssetSlot.WORLD_LOGO, MediaAssetSlot.WORLD_BACKGROUND),
            ),
            modules = modules,
            moduleImages = moduleImages(modules),
        )
    }

    override suspend fun character(id: String): NovexCharacterSnapshot? {
        val aggregate = catalog.character(id) ?: return null
        val modulesByVersion = aggregate.allVersions.associate { version ->
            version.id to content.list(ModuleOwner.characterVersion(version.id))
        }
        return NovexCharacterSnapshot(
            character = aggregate,
            worldsByVersion = aggregate.allVersions.associate { it.id to catalog.worldsForVersion(it.id) },
            mediaByVersion = aggregate.allVersions.associate { version ->
                version.id to mediaFor(
                    ModuleOwner.characterVersion(version.id),
                    listOf(MediaAssetSlot.CHARACTER_AVATAR, MediaAssetSlot.CHARACTER_PAGE_BACKGROUND),
                )
            },
            modulesByVersion = modulesByVersion,
            moduleImages = moduleImages(modulesByVersion.values.flatten()),
        )
    }

    override suspend fun modules(owner: ModuleOwner): NovexModuleSnapshot {
        val modules = content.list(owner)
        return NovexModuleSnapshot(modules, moduleImages(modules))
    }

    override suspend fun module(id: String): NovexModuleDetail? {
        val module = content.module(id) ?: return null
        return NovexModuleDetail(
            module = module,
            image = media.assetFor(ModuleOwner.contentModule(id), MediaAssetSlot.MODULE_IMAGE),
            references = content.references(id),
            referenceOptions = moduleReferenceOptions(module),
        )
    }

    override suspend fun apply(command: NovexCommand): NovexChange = when (command) {
        is NovexCommand.CreateWorld -> NovexChange.WorldSaved(
            catalog.createWorld(command.name, command.overview, command.tagsJson, command.now),
        )
        is NovexCommand.SaveWorld -> NovexChange.WorldSaved(catalog.saveWorld(command.world, command.now))
        is NovexCommand.DeleteWorld -> {
            content.list(ModuleOwner.world(command.worldId)).forEach { module ->
                media.removeAll(ModuleOwner.contentModule(module.id))
                content.delete(module.id)
            }
            media.removeAll(ModuleOwner.world(command.worldId))
            catalog.deleteWorld(command.worldId)
            NovexChange.Completed
        }
        is NovexCommand.CreateCharacter -> NovexChange.CharacterSaved(
            catalog.createCharacter(command.name, "本体", command.profileJson, command.now),
        )
        is NovexCommand.ImportCharacter -> {
            val original = command.document.versions.single { it.kind == com.openminis.app.data.character.CharacterVersionKind.ORIGINAL }
            val created = catalog.createCharacter(
                command.document.name,
                original.label,
                original.profileJson,
                command.now,
            )
            addDocumentModules(created.original.id, original.modules, command.now)
            val variants = command.document.versions
                .filter { it.kind == com.openminis.app.data.character.CharacterVersionKind.VARIANT }
                .map { version ->
                    catalog.createVariant(
                        created.character.id,
                        version.label,
                        version.profileJson,
                        command.now,
                    ).also { addDocumentModules(it.id, version.modules, command.now) }
                }
            NovexChange.CharacterSaved(CharacterAggregate(created.character, created.original, variants))
        }
        is NovexCommand.DuplicateCharacter -> {
            val source = requireNotNull(catalog.character(command.characterId)) { "角色不存在" }
            val copy = catalog.duplicateCharacter(command.characterId, command.now)
            source.allVersions.zip(copy.allVersions).forEach { (sourceVersion, copiedVersion) ->
                copyVersionContents(sourceVersion.id, copiedVersion.id, command.now)
            }
            NovexChange.CharacterSaved(copy)
        }
        is NovexCommand.ExportCharacter -> {
            val aggregate = requireNotNull(catalog.character(command.characterId)) { "角色不存在" }
            NovexChange.CharacterExported(
                CharacterLibraryDocument(
                    name = aggregate.character.name,
                    versions = aggregate.allVersions.map { version ->
                        CharacterVersionDocument(
                            kind = version.kind,
                            label = version.label,
                            profileJson = version.profileJson,
                            modules = content.list(ModuleOwner.characterVersion(version.id)).map { module ->
                                CharacterModuleDocument(
                                    type = module.type,
                                    name = module.name,
                                    contentJson = module.contentJson,
                                    collapsed = module.collapsed,
                                )
                            },
                        )
                    },
                ),
            )
        }
        is NovexCommand.DeleteCharacter -> {
            val aggregate = requireNotNull(catalog.character(command.characterId)) { "角色不存在" }
            aggregate.allVersions.forEach { version -> deleteVersionContents(version.id) }
            catalog.deleteCharacter(command.characterId)
            NovexChange.Completed
        }
        is NovexCommand.CreateVariant -> NovexChange.VersionSaved(
            catalog.createVariant(command.characterId, command.label, command.profileJson, command.now),
        )
        is NovexCommand.SaveCharacterVersion -> {
            val aggregate = requireNotNull(catalog.character(command.characterId)) { "角色不存在" }
            val existing = aggregate.allVersions.firstOrNull { it.id == command.versionId }
                ?: error("角色版本不存在")
            val savedVersion = catalog.saveVersion(
                existing.copy(label = command.label, profileJson = command.profileJson),
                command.now,
            )
            val savedRoot = if (savedVersion.kind == com.openminis.app.data.character.CharacterVersionKind.ORIGINAL) {
                catalog.saveCharacter(aggregate.character.copy(name = command.rootName), command.now)
            } else {
                aggregate.character
            }
            NovexChange.CharacterSaved(
                requireNotNull(catalog.character(savedRoot.id)) { "角色不存在" },
            )
        }
        is NovexCommand.DeleteVariant -> {
            val version = requireNotNull(catalog.version(command.versionId)) { "角色版本不存在" }
            require(version.kind == com.openminis.app.data.character.CharacterVersionKind.VARIANT) { "不能删除角色本体" }
            deleteVersionContents(version.id)
            catalog.deleteVariant(version.id)
            NovexChange.Completed
        }
        is NovexCommand.LinkCharacterVersion -> {
            catalog.link(command.worldId, command.versionId, command.position, command.now)
            NovexChange.Completed
        }
        is NovexCommand.UnlinkCharacterVersion -> {
            catalog.unlink(command.worldId, command.versionId)
            NovexChange.Completed
        }
        is NovexCommand.SaveAsWorldVariant -> {
            val source = requireNotNull(catalog.version(command.sourceVersionId)) { "角色版本不存在" }
            val world = requireNotNull(catalog.world(command.worldId)) { "世界不存在" }
            val linkedVersions = catalog.versionsForWorld(world.id)
            val position = linkedVersions.indexOfFirst { it.id == source.id }
            require(position >= 0) { "当前世界未关联这个角色版本" }
            val variant = catalog.createVariant(
                source.characterId,
                "${world.name}分身",
                source.profileJson,
                command.now,
            )
            copyVersionContents(source.id, variant.id, command.now)
            catalog.unlink(world.id, source.id)
            catalog.link(world.id, variant.id, position, command.now)
            NovexChange.VersionSaved(variant)
        }
        is NovexCommand.AddModule -> NovexChange.ModuleSaved(
            content.add(
                command.owner,
                command.type,
                command.name,
                command.contentJson,
                command.collapsed,
                command.now,
            ),
        )
        is NovexCommand.SaveModule -> NovexChange.ModuleSaved(
            content.save(command.moduleId, command.name, command.contentJson, command.now),
        )
        is NovexCommand.MoveModule -> NovexChange.ModuleSaved(
            content.move(command.moduleId, command.toIndex, command.now),
        )
        is NovexCommand.DeleteModule -> {
            media.removeAll(ModuleOwner.contentModule(command.moduleId))
            content.delete(command.moduleId)
            NovexChange.Completed
        }
        is NovexCommand.AddModuleReference -> {
            content.addReference(command.moduleId, command.target, command.position)
            NovexChange.Completed
        }
        is NovexCommand.RemoveModuleReference -> {
            content.removeReference(command.moduleId, command.target)
            NovexChange.Completed
        }
        is NovexCommand.AttachImage -> {
            val asset = media.import(command.bytes, command.mimeType, command.now)
            media.attach(command.owner, command.slot, asset.id)
            NovexChange.MediaAttached(asset)
        }
        is NovexCommand.DetachImage -> {
            media.detach(command.owner, command.slot)
            NovexChange.Completed
        }
    }

    private suspend fun mediaFor(
        owner: ModuleOwner,
        slots: List<MediaAssetSlot>,
    ): Map<MediaAssetSlot, MediaAssetEntity> = slots.mapNotNull { slot ->
        media.assetFor(owner, slot)?.let { slot to it }
    }.toMap()

    private suspend fun moduleImages(
        modules: List<ContentModuleEntity>,
    ): Map<String, MediaAssetEntity> = modules.mapNotNull { module ->
        media.assetFor(ModuleOwner.contentModule(module.id), MediaAssetSlot.MODULE_IMAGE)
            ?.let { module.id to it }
    }.toMap()

    private suspend fun moduleReferenceOptions(module: ContentModuleEntity): List<NovexModuleReferenceOption> {
        val ownerTarget = when (module.ownerType) {
            ModuleOwnerType.WORLD -> ModuleReferenceTarget.world(module.ownerId)
            ModuleOwnerType.CHARACTER_VERSION -> ModuleReferenceTarget.characterVersion(module.ownerId)
            ModuleOwnerType.CONTENT_MODULE -> null
        }
        val worlds = catalog.listWorlds().map { world ->
            NovexModuleReferenceOption(ModuleReferenceTarget.world(world.id), world.name, "世界")
        }
        val versions = catalog.listVersions().map { version ->
            val name = runCatching {
                org.json.JSONObject(version.profileJson).optString("name").trim().ifBlank { version.label }
            }.getOrDefault(version.label)
            NovexModuleReferenceOption(
                ModuleReferenceTarget.characterVersion(version.id),
                "$name · ${version.label}",
                "角色版本",
            )
        }
        val modules = content.all().filterNot { it.id == module.id }.map { candidate ->
            NovexModuleReferenceOption(ModuleReferenceTarget.module(candidate.id), candidate.name, "内容模块")
        }
        return (worlds + versions + modules).filterNot { it.target == ownerTarget }
    }

    private suspend fun copyVersionContents(sourceVersionId: String, targetVersionId: String, now: Long) {
        val sourceOwner = ModuleOwner.characterVersion(sourceVersionId)
        val targetOwner = ModuleOwner.characterVersion(targetVersionId)
        val sourceModules = content.list(sourceOwner)
        val copiedModules = content.copyAll(sourceOwner, targetOwner, now)
        sourceModules.zip(copiedModules).forEach { (sourceModule, copiedModule) ->
            media.assetFor(ModuleOwner.contentModule(sourceModule.id), MediaAssetSlot.MODULE_IMAGE)?.let { asset ->
                media.attach(ModuleOwner.contentModule(copiedModule.id), MediaAssetSlot.MODULE_IMAGE, asset.id)
            }
        }
        listOf(MediaAssetSlot.CHARACTER_AVATAR, MediaAssetSlot.CHARACTER_PAGE_BACKGROUND).forEach { slot ->
            media.assetFor(sourceOwner, slot)?.let { asset -> media.attach(targetOwner, slot, asset.id) }
        }
    }

    private suspend fun deleteVersionContents(versionId: String) {
        val owner = ModuleOwner.characterVersion(versionId)
        content.list(owner).forEach { module ->
            media.removeAll(ModuleOwner.contentModule(module.id))
            content.delete(module.id)
        }
        media.removeAll(owner)
    }

    private suspend fun addDocumentModules(
        versionId: String,
        modules: List<CharacterModuleDocument>,
        now: Long,
    ) {
        val owner = ModuleOwner.characterVersion(versionId)
        modules.forEach { module ->
            content.add(
                owner,
                module.type,
                module.name,
                module.contentJson,
                module.collapsed,
                now,
            )
        }
    }
}
