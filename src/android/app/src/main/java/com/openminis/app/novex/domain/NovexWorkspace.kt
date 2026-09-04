package com.openminis.app.novex.domain

import com.openminis.app.data.character.CharacterAggregate
import com.openminis.app.data.character.CharacterVersionKind
import com.openminis.app.data.character.CharacterVersionProfile
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleReferenceEntity
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.CharacterVersionEntity
import com.openminis.app.data.character.CharacterLibraryDocument
import com.openminis.app.data.character.CharacterModuleDocument
import com.openminis.app.data.character.CharacterVersionDocument
import com.openminis.app.data.character.ContentModuleCatalog
import com.openminis.app.data.character.ContentModuleCollectionItem
import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.MediaAssetEntity
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.ModuleOwnerType
import com.openminis.app.data.character.ModuleReferenceTarget
import com.openminis.app.data.character.NovexCardImportDocument
import com.openminis.app.data.character.NovexCardKind
import com.openminis.app.data.character.NovexCardMedia
import com.openminis.app.data.character.NovexCardPackagePreview
import com.openminis.app.data.character.NovexCharacterImportDocument
import com.openminis.app.data.character.NovexCharacterVersionImportDocument
import com.openminis.app.data.character.NovexModuleImportDocument
import com.openminis.app.data.character.NovexValidatedCardImport
import com.openminis.app.data.character.NovexWorldImportDocument
import com.openminis.app.data.character.NovexWorldImportLink
import com.openminis.app.data.character.WorldEntity
import com.openminis.app.data.character.NovexInteractiveFictionImportDocument
import com.openminis.app.data.interactivefiction.InteractiveFictionDocumentComposer
import com.openminis.app.data.interactivefiction.InteractiveFictionLaunchMode
import com.openminis.app.data.interactivefiction.InteractiveFictionProjectEntity
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * The single Novex seam used by pages and future automation.
 *
 * Callers read complete page snapshots and submit domain commands. Storage,
 * managed files, ordering rules and reference cleanup stay behind this seam.
 */
interface NovexWorkspace {
    suspend fun worlds(): List<NovexWorldCard>
    suspend fun characters(): List<NovexCharacterCard>
    suspend fun interactiveFictions(): List<NovexInteractiveFictionCard>
    suspend fun world(id: String): NovexWorldSnapshot?
    suspend fun character(id: String): NovexCharacterSnapshot?
    suspend fun interactiveFiction(id: String): NovexInteractiveFictionSnapshot?
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

data class NovexInteractiveFictionCard(
    val project: InteractiveFictionProjectEntity,
    val image: MediaAssetEntity?,
    val moduleCount: Int,
)

data class NovexWorldSnapshot(
    val world: WorldEntity,
    val versions: List<com.openminis.app.data.character.CharacterVersionEntity>,
    val availableVersions: List<com.openminis.app.data.character.CharacterVersionEntity>,
    val worldsByVersion: Map<String, List<WorldEntity>>,
    val media: Map<MediaAssetSlot, MediaAssetEntity>,
    val modules: List<ContentModuleEntity>,
    val moduleImages: Map<String, MediaAssetEntity>,
    val moduleItemImages: Map<String, Map<String, MediaAssetEntity>>,
    val versionAvatars: Map<String, MediaAssetEntity> = emptyMap(),
)

data class NovexCharacterSnapshot(
    val character: CharacterAggregate,
    val worldsByVersion: Map<String, List<WorldEntity>>,
    val mediaByVersion: Map<String, Map<MediaAssetSlot, MediaAssetEntity>>,
    val modulesByVersion: Map<String, List<ContentModuleEntity>>,
    val moduleImages: Map<String, MediaAssetEntity>,
    val moduleItemImages: Map<String, Map<String, MediaAssetEntity>>,
)

data class NovexInteractiveFictionSnapshot(
    val project: InteractiveFictionProjectEntity,
    val media: Map<MediaAssetSlot, MediaAssetEntity>,
    val modules: List<ContentModuleEntity>,
    val moduleImages: Map<String, MediaAssetEntity>,
    val moduleItemImages: Map<String, Map<String, MediaAssetEntity>>,
)

data class NovexModuleSnapshot(
    val modules: List<ContentModuleEntity>,
    val images: Map<String, MediaAssetEntity>,
    val itemImages: Map<String, Map<String, MediaAssetEntity>>,
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

data class NovexModuleDraft(
    val id: String,
    val type: ContentModuleType,
    val name: String,
    val contentJson: String = "{}",
    val collapsed: Boolean = true,
) {
    companion object {
        fun from(module: ContentModuleEntity) = NovexModuleDraft(
            id = module.id,
            type = module.type,
            name = module.name,
            contentJson = module.contentJson,
            collapsed = module.collapsed,
        )
    }
}

sealed interface NovexImageChange {
    val slot: MediaAssetSlot

    data class Replace(
        override val slot: MediaAssetSlot,
        val bytes: ByteArray,
        val mimeType: String,
    ) : NovexImageChange

    data class Remove(
        override val slot: MediaAssetSlot,
    ) : NovexImageChange
}

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

    /** Saves one world's complete editor draft at a single transaction boundary. */
    data class SaveWorldPage(
        val worldId: String?,
        val name: String,
        val overview: String = "",
        val tagsJson: String = "[]",
        val modules: List<NovexModuleDraft> = emptyList(),
        val imageChanges: List<NovexImageChange> = emptyList(),
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

    data class ImportNativeCard(
        val card: NovexValidatedCardImport,
        val now: Long = System.currentTimeMillis(),
    ) : NovexCommand

    data class ExportNativeWorld(val worldId: String) : NovexCommand

    data class ExportNativeCharacter(val characterId: String) : NovexCommand

    data class DeleteCharacter(val characterId: String) : NovexCommand

    /** Saves one interactive-fiction editor draft at a single transaction boundary. */
    data class SaveInteractiveFictionPage(
        val projectId: String?,
        val name: String,
        val summary: String = "",
        val launchMode: InteractiveFictionLaunchMode = InteractiveFictionLaunchMode.FREE_SANDBOX,
        val playerIdentity: String = "",
        val modules: List<NovexModuleDraft> = emptyList(),
        val imageChanges: List<NovexImageChange> = emptyList(),
        val now: Long = System.currentTimeMillis(),
    ) : NovexCommand

    data class DeleteInteractiveFiction(val projectId: String) : NovexCommand

    data class ExportNativeInteractiveFiction(val projectId: String) : NovexCommand

    data class ExportInteractiveFictionText(val projectId: String) : NovexCommand

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

    /** Saves one character-version editor draft at a single transaction boundary. */
    data class SaveCharacterPage(
        val characterId: String?,
        val versionId: String?,
        val sourceVersionId: String?,
        val createVariant: Boolean,
        val rootName: String,
        val label: String,
        val profileJson: String,
        val modules: List<NovexModuleDraft> = emptyList(),
        val imageChanges: List<NovexImageChange> = emptyList(),
        val linkWorldId: String? = null,
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
        val id: String = UUID.randomUUID().toString(),
    ) : NovexCommand

    /** Reconciles one editor's complete in-memory module draft at the save boundary. */
    data class SaveModules(
        val owner: ModuleOwner,
        val modules: List<NovexModuleDraft>,
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
    data class InteractiveFictionSaved(val project: InteractiveFictionProjectEntity) : NovexChange
    data class VersionSaved(val version: CharacterVersionEntity) : NovexChange
    data class ModuleSaved(val module: ContentModuleEntity) : NovexChange
    data class ModulesSaved(val modules: List<ContentModuleEntity>) : NovexChange
    data class MediaAttached(val asset: MediaAssetEntity) : NovexChange
    data class CharacterExported(val document: CharacterLibraryDocument) : NovexChange
    data class NativeCardImported(val kind: NovexCardKind, val localId: String) : NovexChange
    data class NativeCardExported(val card: NovexCardPackagePreview) : NovexChange
    data class TextExported(val text: String) : NovexChange
    data object Completed : NovexChange
}

fun NovexChange.requireWorld(): WorldEntity = (this as NovexChange.WorldSaved).world

fun NovexChange.requireCharacter(): CharacterAggregate =
    (this as NovexChange.CharacterSaved).character

fun NovexChange.requireInteractiveFiction(): InteractiveFictionProjectEntity =
    (this as NovexChange.InteractiveFictionSaved).project

fun NovexChange.requireText(): String = (this as NovexChange.TextExported).text

fun NovexChange.requireVersion(): CharacterVersionEntity = (this as NovexChange.VersionSaved).version

fun NovexChange.requireModule(): ContentModuleEntity = (this as NovexChange.ModuleSaved).module

fun NovexChange.requireModules(): List<ContentModuleEntity> =
    (this as NovexChange.ModulesSaved).modules

fun NovexChange.requireMedia(): MediaAssetEntity = (this as NovexChange.MediaAttached).asset

fun NovexChange.requireDocument(): CharacterLibraryDocument =
    (this as NovexChange.CharacterExported).document

fun NovexChange.requireNativeImport(): NovexChange.NativeCardImported =
    this as NovexChange.NativeCardImported

fun NovexChange.requireNativeCard(): NovexCardPackagePreview =
    (this as NovexChange.NativeCardExported).card

internal interface NovexCatalogPort {
    suspend fun createWorld(
        name: String,
        overview: String,
        tagsJson: String,
        legacySnapshotJson: String?,
        now: Long,
    ): WorldEntity
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
        id: String,
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

internal interface NovexInteractiveFictionPort {
    suspend fun create(
        name: String,
        summary: String,
        launchMode: InteractiveFictionLaunchMode,
        playerIdentity: String,
        now: Long,
        sourceId: String? = null,
        sourceDocumentJson: String? = null,
    ): InteractiveFictionProjectEntity
    suspend fun save(project: InteractiveFictionProjectEntity, now: Long): InteractiveFictionProjectEntity
    suspend fun project(id: String): InteractiveFictionProjectEntity?
    suspend fun list(): List<InteractiveFictionProjectEntity>
    suspend fun delete(id: String)
}

internal interface NovexMediaPort {
    suspend fun import(bytes: ByteArray, mimeType: String, now: Long): MediaAssetEntity
    suspend fun attach(owner: ModuleOwner, slot: MediaAssetSlot, assetId: String)
    suspend fun detach(owner: ModuleOwner, slot: MediaAssetSlot)
    suspend fun removeAll(owner: ModuleOwner)
    suspend fun assetFor(owner: ModuleOwner, slot: MediaAssetSlot): MediaAssetEntity?
    suspend fun read(asset: MediaAssetEntity): ByteArray
}

internal class DefaultNovexWorkspace(
    private val catalog: NovexCatalogPort,
    private val interactiveFiction: NovexInteractiveFictionPort,
    private val content: NovexContentPort,
    private val media: NovexMediaPort,
    private val transaction: suspend (suspend () -> NovexChange) -> NovexChange,
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

    override suspend fun interactiveFictions(): List<NovexInteractiveFictionCard> =
        interactiveFiction.list().map { project ->
            val owner = ModuleOwner.interactiveFiction(project.id)
            NovexInteractiveFictionCard(
                project = project,
                image = media.assetFor(owner, MediaAssetSlot.INTERACTIVE_FICTION_COVER)
                    ?: media.assetFor(owner, MediaAssetSlot.INTERACTIVE_FICTION_BACKGROUND),
                moduleCount = content.list(owner).size,
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
            moduleItemImages = moduleItemImages(modules),
            versionAvatars = versions.mapNotNull { version ->
                media.assetFor(
                    ModuleOwner.characterVersion(version.id),
                    MediaAssetSlot.CHARACTER_AVATAR,
                )?.let { version.id to it }
            }.toMap(),
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
            moduleItemImages = moduleItemImages(modulesByVersion.values.flatten()),
        )
    }

    override suspend fun interactiveFiction(id: String): NovexInteractiveFictionSnapshot? {
        val project = interactiveFiction.project(id) ?: return null
        val owner = ModuleOwner.interactiveFiction(id)
        val modules = content.list(owner)
        return NovexInteractiveFictionSnapshot(
            project = project,
            media = mediaFor(
                owner,
                listOf(
                    MediaAssetSlot.INTERACTIVE_FICTION_COVER,
                    MediaAssetSlot.INTERACTIVE_FICTION_BACKGROUND,
                ),
            ),
            modules = modules,
            moduleImages = moduleImages(modules),
            moduleItemImages = moduleItemImages(modules),
        )
    }

    override suspend fun modules(owner: ModuleOwner): NovexModuleSnapshot {
        val modules = content.list(owner)
        return NovexModuleSnapshot(modules, moduleImages(modules), moduleItemImages(modules))
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

    override suspend fun apply(command: NovexCommand): NovexChange = transaction {
        applyInsideTransaction(command)
    }

    private suspend fun applyInsideTransaction(command: NovexCommand): NovexChange = when (command) {
        is NovexCommand.CreateWorld -> NovexChange.WorldSaved(
            catalog.createWorld(command.name, command.overview, command.tagsJson, null, command.now),
        )
        is NovexCommand.SaveWorld -> NovexChange.WorldSaved(catalog.saveWorld(command.world, command.now))
        is NovexCommand.SaveWorldPage -> {
            require(command.name.isNotBlank()) { "世界名称不能为空" }
            val duplicateSlots = command.imageChanges.groupingBy(NovexImageChange::slot)
                .eachCount()
                .filterValues { it > 1 }
            require(duplicateSlots.isEmpty()) { "同一图片位置不能重复修改" }
            val world = command.worldId?.let { worldId ->
                val existing = requireNotNull(catalog.world(worldId)) { "世界不存在" }
                catalog.saveWorld(
                    existing.copy(
                        name = command.name.trim(),
                        overview = command.overview,
                        tagsJson = command.tagsJson,
                    ),
                    command.now,
                )
            } ?: catalog.createWorld(
                name = command.name.trim(),
                overview = command.overview,
                tagsJson = command.tagsJson,
                legacySnapshotJson = null,
                now = command.now,
            )
            val owner = ModuleOwner.world(world.id)
            saveModules(owner, command.modules, command.now)
            command.imageChanges.forEach { change ->
                require(
                    change.slot == MediaAssetSlot.WORLD_COVER ||
                        change.slot == MediaAssetSlot.WORLD_LOGO ||
                        change.slot == MediaAssetSlot.WORLD_BACKGROUND,
                ) { "世界页面不支持该图片位置" }
                when (change) {
                    is NovexImageChange.Replace -> {
                        require(change.bytes.isNotEmpty()) { "图片内容不能为空" }
                        val asset = media.import(change.bytes, change.mimeType, command.now)
                        media.attach(owner, change.slot, asset.id)
                    }

                    is NovexImageChange.Remove -> media.detach(owner, change.slot)
                }
            }
            NovexChange.WorldSaved(world)
        }
        is NovexCommand.DeleteWorld -> {
            content.list(ModuleOwner.world(command.worldId)).forEach { module ->
                removeModuleMedia(module)
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
        is NovexCommand.ImportNativeCard -> {
            val localId = when (val document = command.card.document) {
                is NovexWorldImportDocument -> importWorldCard(document, command.card, command.now)
                is NovexCharacterImportDocument -> importCharacterCard(document, command.card, command.now)
                is NovexInteractiveFictionImportDocument ->
                    importInteractiveFictionCard(document, command.card, command.now)
            }
            NovexChange.NativeCardImported(command.card.document.kind(), localId)
        }
        is NovexCommand.ExportNativeWorld -> NovexChange.NativeCardExported(
            exportWorldCard(command.worldId),
        )
        is NovexCommand.ExportNativeCharacter -> NovexChange.NativeCardExported(
            exportCharacterCard(command.characterId),
        )
        is NovexCommand.ExportNativeInteractiveFiction -> NovexChange.NativeCardExported(
            exportInteractiveFictionCard(command.projectId),
        )
        is NovexCommand.ExportInteractiveFictionText -> {
            val snapshot = requireNotNull(interactiveFiction(command.projectId)) { "文游不存在" }
            NovexChange.TextExported(
                InteractiveFictionDocumentComposer.fullText(snapshot.project, snapshot.modules),
            )
        }
        is NovexCommand.DeleteCharacter -> {
            val aggregate = requireNotNull(catalog.character(command.characterId)) { "角色不存在" }
            aggregate.allVersions.forEach { version -> deleteVersionContents(version.id) }
            catalog.deleteCharacter(command.characterId)
            NovexChange.Completed
        }
        is NovexCommand.SaveInteractiveFictionPage -> {
            require(command.name.isNotBlank()) { "文游名称不能为空" }
            val duplicateSlots = command.imageChanges.groupingBy(NovexImageChange::slot)
                .eachCount()
                .filterValues { it > 1 }
            require(duplicateSlots.isEmpty()) { "同一图片位置不能重复修改" }
            val project = command.projectId?.let { projectId ->
                val existing = requireNotNull(interactiveFiction.project(projectId)) { "文游不存在" }
                interactiveFiction.save(
                    existing.copy(
                        name = command.name.trim(),
                        summary = command.summary,
                        launchMode = command.launchMode,
                        playerIdentity = command.playerIdentity,
                    ),
                    command.now,
                )
            } ?: interactiveFiction.create(
                name = command.name.trim(),
                summary = command.summary,
                launchMode = command.launchMode,
                playerIdentity = command.playerIdentity,
                now = command.now,
            )
            val owner = ModuleOwner.interactiveFiction(project.id)
            saveModules(owner, command.modules, command.now)
            command.imageChanges.forEach { change ->
                require(
                    change.slot == MediaAssetSlot.INTERACTIVE_FICTION_COVER ||
                        change.slot == MediaAssetSlot.INTERACTIVE_FICTION_BACKGROUND,
                ) { "文游页面不支持该图片位置" }
                when (change) {
                    is NovexImageChange.Replace -> {
                        require(change.bytes.isNotEmpty()) { "图片内容不能为空" }
                        val asset = media.import(change.bytes, change.mimeType, command.now)
                        media.attach(owner, change.slot, asset.id)
                    }
                    is NovexImageChange.Remove -> media.detach(owner, change.slot)
                }
            }
            NovexChange.InteractiveFictionSaved(project)
        }
        is NovexCommand.DeleteInteractiveFiction -> {
            val owner = ModuleOwner.interactiveFiction(command.projectId)
            content.list(owner).forEach { module ->
                removeModuleMedia(module)
                content.delete(module.id)
            }
            media.removeAll(owner)
            interactiveFiction.delete(command.projectId)
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
        is NovexCommand.SaveCharacterPage -> {
            require(command.rootName.isNotBlank()) { "角色名称不能为空" }
            val profileName = runCatching {
                com.openminis.app.data.character.CharacterVersionProfile.fromJson(command.profileJson).name
            }.getOrDefault("")
            require(profileName.isNotBlank()) { "角色姓名不能为空" }
            val duplicateSlots = command.imageChanges.groupingBy(NovexImageChange::slot)
                .eachCount()
                .filterValues { it > 1 }
            require(duplicateSlots.isEmpty()) { "同一图片位置不能重复修改" }
            val editingExisting = command.characterId != null && !command.createVariant
            require(!editingExisting || command.versionId != null) { "缺少要编辑的角色版本" }
            require(!command.createVariant || command.characterId != null) { "创建分身需要所属角色" }
            require(!command.createVariant || command.sourceVersionId != null) { "创建分身需要来源版本" }

            val aggregateBefore = command.characterId?.let { characterId ->
                requireNotNull(catalog.character(characterId)) { "角色不存在" }
            }
            val targetVersion = when {
                aggregateBefore == null -> catalog.createCharacter(
                    command.rootName.trim(),
                    command.label.ifBlank { "本体" },
                    command.profileJson,
                    command.now,
                ).original
                command.createVariant -> {
                    val source = requireNotNull(catalog.version(command.sourceVersionId!!)) {
                        "来源角色版本不存在"
                    }
                    require(source.characterId == aggregateBefore.character.id) { "来源版本不属于当前角色" }
                    catalog.createVariant(
                        aggregateBefore.character.id,
                        command.label.ifBlank { "新分身" },
                        command.profileJson,
                        command.now,
                    )
                }
                else -> {
                    val existing = aggregateBefore.allVersions.firstOrNull { it.id == command.versionId }
                        ?: error("角色版本不存在")
                    catalog.saveVersion(
                        existing.copy(label = command.label, profileJson = command.profileJson),
                        command.now,
                    )
                }
            }
            val rootId = targetVersion.characterId
            if (targetVersion.kind == com.openminis.app.data.character.CharacterVersionKind.ORIGINAL) {
                val root = requireNotNull(catalog.character(rootId)) { "角色不存在" }.character
                catalog.saveCharacter(root.copy(name = command.rootName.trim()), command.now)
            }

            val owner = ModuleOwner.characterVersion(targetVersion.id)
            saveModules(owner, command.modules, command.now)
            val supportedSlots = setOf(
                MediaAssetSlot.CHARACTER_AVATAR,
                MediaAssetSlot.CHARACTER_PAGE_BACKGROUND,
            )
            command.imageChanges.forEach { change ->
                require(change.slot in supportedSlots) { "角色页面不支持该图片位置" }
            }
            if (command.createVariant) {
                val sourceOwner = ModuleOwner.characterVersion(command.sourceVersionId!!)
                supportedSlots.filterNot(command.imageChanges.map(NovexImageChange::slot).toSet()::contains)
                    .forEach { slot ->
                        media.assetFor(sourceOwner, slot)?.let { asset -> media.attach(owner, slot, asset.id) }
                    }
            }
            command.imageChanges.forEach { change ->
                when (change) {
                    is NovexImageChange.Replace -> {
                        require(change.bytes.isNotEmpty()) { "图片内容不能为空" }
                        val asset = media.import(change.bytes, change.mimeType, command.now)
                        media.attach(owner, change.slot, asset.id)
                    }
                    is NovexImageChange.Remove -> media.detach(owner, change.slot)
                }
            }
            command.linkWorldId?.let { worldId ->
                requireNotNull(catalog.world(worldId)) { "世界不存在" }
                val linked = catalog.versionsForWorld(worldId)
                if (linked.none { it.id == targetVersion.id }) {
                    catalog.link(worldId, targetVersion.id, linked.size, command.now)
                }
            }
            NovexChange.CharacterSaved(
                requireNotNull(catalog.character(rootId)) { "角色不存在" },
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
                command.id,
            ),
        )
        is NovexCommand.SaveModules -> NovexChange.ModulesSaved(
            saveModules(command.owner, command.modules, command.now),
        )
        is NovexCommand.SaveModule -> {
            content.module(command.moduleId)?.let { removeMissingItemMedia(it, command.contentJson) }
            NovexChange.ModuleSaved(
                content.save(command.moduleId, command.name, command.contentJson, command.now),
            )
        }
        is NovexCommand.MoveModule -> NovexChange.ModuleSaved(
            content.move(command.moduleId, command.toIndex, command.now),
        )
        is NovexCommand.DeleteModule -> {
            content.module(command.moduleId)?.let { removeModuleMedia(it) }
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

    private suspend fun saveModules(
        owner: ModuleOwner,
        drafts: List<NovexModuleDraft>,
        now: Long,
    ): List<ContentModuleEntity> {
        val scope = requireNotNull(ContentModuleCatalog.scopeFor(owner.type)) {
            "该对象不能拥有内容模块"
        }
        require(drafts.map(NovexModuleDraft::id).distinct().size == drafts.size) {
            "模块编号不能重复"
        }
        val occupiedBuiltIns = mutableSetOf<ContentModuleType>()
        drafts.forEach { draft ->
            require(draft.name.isNotBlank()) { "模块名称不能为空" }
            val definition = ContentModuleCatalog.definition(draft.type)
            require(definition in ContentModuleCatalog.definitions(scope)) {
                "该对象不支持${definition.displayName}"
            }
            require(definition.repeatable || occupiedBuiltIns.add(draft.type)) {
                "${definition.displayName}已经存在，每个对象只能添加一个"
            }
        }
        val existing = content.list(owner).associateBy(ContentModuleEntity::id)
        drafts.forEach { draft ->
            existing[draft.id]?.let { saved ->
                require(saved.type == draft.type) { "不能改变已有模块类型" }
            }
        }
        val desiredIds = drafts.map(NovexModuleDraft::id).toSet()
        existing.values.filter { it.id !in desiredIds }.forEach { removed ->
            removeModuleMedia(removed)
            content.delete(removed.id)
        }
        drafts.forEach { draft ->
            if (draft.id in existing) {
                removeMissingItemMedia(existing.getValue(draft.id), draft.contentJson)
                content.save(draft.id, draft.name, draft.contentJson, now)
            } else {
                content.add(
                    owner = owner,
                    type = draft.type,
                    name = draft.name,
                    contentJson = draft.contentJson,
                    collapsed = draft.collapsed,
                    now = now,
                    id = draft.id,
                )
            }
        }
        drafts.forEachIndexed { index, draft -> content.move(draft.id, index, now) }
        return content.list(owner)
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

    private suspend fun moduleItemImages(
        modules: List<ContentModuleEntity>,
    ): Map<String, Map<String, MediaAssetEntity>> = modules.mapNotNull { module ->
        val collection = ContentModuleDocumentCodec.decode(module.type, module.contentJson)
            as? ContentModuleDocument.Collection
        val images = collection?.items.orEmpty().mapNotNull { item ->
            val itemId = item.id.takeIf(String::isNotBlank) ?: return@mapNotNull null
            media.assetFor(ModuleOwner.contentModuleItem(module.id, itemId), MediaAssetSlot.MODULE_IMAGE)
                ?.let { itemId to it }
        }.toMap()
        images.takeIf(Map<String, MediaAssetEntity>::isNotEmpty)?.let { module.id to it }
    }.toMap()

    private suspend fun moduleReferenceOptions(module: ContentModuleEntity): List<NovexModuleReferenceOption> {
        val ownerTarget = when (module.ownerType) {
            ModuleOwnerType.WORLD -> ModuleReferenceTarget.world(module.ownerId)
            ModuleOwnerType.CHARACTER_VERSION -> ModuleReferenceTarget.characterVersion(module.ownerId)
            ModuleOwnerType.INTERACTIVE_FICTION -> null
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
            val sourceCollection = ContentModuleDocumentCodec.decode(sourceModule.type, sourceModule.contentJson)
                as? ContentModuleDocument.Collection
            sourceCollection?.items.orEmpty().forEach { item ->
                val itemId = item.id.takeIf(String::isNotBlank) ?: return@forEach
                media.assetFor(
                    ModuleOwner.contentModuleItem(sourceModule.id, itemId),
                    MediaAssetSlot.MODULE_IMAGE,
                )?.let { asset ->
                    media.attach(
                        ModuleOwner.contentModuleItem(copiedModule.id, itemId),
                        MediaAssetSlot.MODULE_IMAGE,
                        asset.id,
                    )
                }
            }
        }
        listOf(MediaAssetSlot.CHARACTER_AVATAR, MediaAssetSlot.CHARACTER_PAGE_BACKGROUND).forEach { slot ->
            media.assetFor(sourceOwner, slot)?.let { asset -> media.attach(targetOwner, slot, asset.id) }
        }
    }

    private suspend fun deleteVersionContents(versionId: String) {
        val owner = ModuleOwner.characterVersion(versionId)
        content.list(owner).forEach { module ->
            removeModuleMedia(module)
            content.delete(module.id)
        }
        media.removeAll(owner)
    }

    private suspend fun removeModuleMedia(module: ContentModuleEntity) {
        val collection = ContentModuleDocumentCodec.decode(module.type, module.contentJson)
            as? ContentModuleDocument.Collection
        collection?.items.orEmpty().map(ContentModuleCollectionItem::id)
            .filter(String::isNotBlank)
            .distinct()
            .forEach { itemId -> media.removeAll(ModuleOwner.contentModuleItem(module.id, itemId)) }
        media.removeAll(ModuleOwner.contentModule(module.id))
    }

    private suspend fun removeMissingItemMedia(module: ContentModuleEntity, nextContentJson: String) {
        val previousIds = (ContentModuleDocumentCodec.decode(module.type, module.contentJson)
            as? ContentModuleDocument.Collection)?.items.orEmpty().map(ContentModuleCollectionItem::id).toSet()
        val nextIds = (ContentModuleDocumentCodec.decode(module.type, nextContentJson)
            as? ContentModuleDocument.Collection)?.items.orEmpty().map(ContentModuleCollectionItem::id).toSet()
        (previousIds - nextIds).filter(String::isNotBlank).forEach { itemId ->
            media.removeAll(ModuleOwner.contentModuleItem(module.id, itemId))
        }
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
                UUID.randomUUID().toString(),
            )
        }
    }

    private suspend fun importWorldCard(
        document: NovexWorldImportDocument,
        card: NovexValidatedCardImport,
        now: Long,
    ): String {
        val world = catalog.createWorld(
            name = document.name,
            overview = document.overview,
            tagsJson = JSONArray(document.tags).toString(),
            legacySnapshotJson = document.originalJson,
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
        importCardModules(owner, document.modules, card, assets, now)

        val versionsBySourceId = catalog.listVersions().mapNotNull { version ->
            version.sourceId()?.let { it to version }
        }.toMap()
        document.characterVersionLinks.forEachIndexed { index, link ->
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
        importedVersions.forEach { (version, versionDocument) ->
            val owner = ModuleOwner.characterVersion(version.id)
            suspend fun attach(path: String?, slot: MediaAssetSlot) {
                if (path == null) return
                val asset = importCardMedia(path, card, assets, now)
                media.attach(owner, slot, asset.id)
            }
            attach(versionDocument.avatarPath, MediaAssetSlot.CHARACTER_AVATAR)
            attach(versionDocument.pageBackgroundPath, MediaAssetSlot.CHARACTER_PAGE_BACKGROUND)
            importCardModules(owner, versionDocument.modules, card, assets, now)
        }
        reconcileImportedCharacterLinks(importedVersions, now)
        return aggregate.character.id
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
        importCardModules(owner, document.modules, card, assets, now)
        return project.id
    }

    private suspend fun importCardModules(
        owner: ModuleOwner,
        modules: List<NovexModuleImportDocument>,
        card: NovexValidatedCardImport,
        assets: MutableMap<String, MediaAssetEntity>,
        now: Long,
    ) {
        modules.forEach { moduleDocument ->
            val module = content.add(
                owner = owner,
                type = moduleDocument.type,
                name = moduleDocument.title,
                contentJson = ContentModuleDocumentCodec.encode(moduleDocument.document),
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

    private suspend fun exportWorldCard(worldId: String): NovexCardPackagePreview {
        val snapshot = requireNotNull(world(worldId)) { "世界不存在" }
        val original = runCatching { JSONObject(snapshot.world.legacySnapshotJson ?: "{}") }
            .getOrDefault(JSONObject())
        val sourceId = original.optString("sourceId").ifBlank { snapshot.world.id }
        val mediaFiles = mutableListOf<NovexCardMedia>()
        suspend fun exportAsset(basePath: String, asset: MediaAssetEntity?): String? {
            asset ?: return null
            val path = "$basePath.${asset.extension()}"
            mediaFiles += NovexCardMedia(path, asset.mimeType, media.read(asset))
            return path
        }
        val rootMedia = JSONObject()
        rootMedia.putMedia("cover", exportAsset("media/cover", snapshot.media[MediaAssetSlot.WORLD_COVER]))
        rootMedia.putMedia("logo", exportAsset("media/logo", snapshot.media[MediaAssetSlot.WORLD_LOGO]))
        rootMedia.putMedia("background", exportAsset("media/background", snapshot.media[MediaAssetSlot.WORLD_BACKGROUND]))
        val moduleJson = snapshot.modules.map { module ->
            exportModule(
                module = module,
                mainImage = snapshot.moduleImages[module.id],
                itemImages = snapshot.moduleItemImages[module.id].orEmpty(),
                mediaFiles = mediaFiles,
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
            media = mediaFiles.distinctBy(NovexCardMedia::path),
        )
    }

    private suspend fun exportCharacterCard(characterId: String): NovexCardPackagePreview {
        val snapshot = requireNotNull(character(characterId)) { "角色不存在" }
        val originalProfileJson = JSONObject(snapshot.character.original.profileJson)
        val original = runCatching { JSONObject(originalProfileJson.optString("_novexCharacterDocument")) }
            .getOrDefault(JSONObject())
        val sourceId = originalProfileJson.optString("_novexCharacterSourceId")
            .ifBlank { snapshot.character.character.id }
        val mediaFiles = mutableListOf<NovexCardMedia>()
        suspend fun exportAsset(basePath: String, asset: MediaAssetEntity?): String? {
            asset ?: return null
            val path = "$basePath.${asset.extension()}"
            mediaFiles += NovexCardMedia(path, asset.mimeType, media.read(asset))
            return path
        }
        val versionsJson = snapshot.character.allVersions.map { version ->
            val profile = CharacterVersionProfile.fromJson(version.profileJson, snapshot.character.character.name)
            val sourceVersionId = version.sourceId() ?: version.id
            val versionMedia = snapshot.mediaByVersion[version.id].orEmpty()
            val mediaJson = JSONObject()
            mediaJson.putMedia(
                "avatar",
                exportAsset("media/versions/$sourceVersionId/avatar", versionMedia[MediaAssetSlot.CHARACTER_AVATAR]),
            )
            mediaJson.putMedia(
                "pageBackground",
                exportAsset(
                    "media/versions/$sourceVersionId/background",
                    versionMedia[MediaAssetSlot.CHARACTER_PAGE_BACKGROUND],
                ),
            )
            val modules = snapshot.modulesByVersion[version.id].orEmpty().map { module ->
                exportModule(
                    module = module,
                    mainImage = snapshot.moduleImages[module.id],
                    itemImages = snapshot.moduleItemImages[module.id].orEmpty(),
                    mediaFiles = mediaFiles,
                    pathPrefix = "media/versions/$sourceVersionId/modules",
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
                .put("kind", if (version.kind == CharacterVersionKind.ORIGINAL) "origin" else "variant")
                .put("name", version.label)
                .put("tags", JSONArray(profile.tags))
                .put(
                    "profile",
                    JSONObject()
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
            put("summary", CharacterVersionProfile.fromJson(snapshot.character.original.profileJson).summary)
            put("versions", JSONArray(versionsJson))
            put("versionOrder", JSONArray(versionsJson.map { it.getString("id") }))
            put("defaultVersionId", versionsJson.first { it.optString("kind") == "origin" }.getString("id"))
        }
        return NovexCardPackagePreview(
            kind = NovexCardKind.CHARACTER,
            packageId = sourceId,
            displayName = snapshot.character.character.name,
            documentJson = document.toString(2),
            media = mediaFiles.distinctBy(NovexCardMedia::path),
        )
    }

    private suspend fun exportInteractiveFictionCard(projectId: String): NovexCardPackagePreview {
        val snapshot = requireNotNull(interactiveFiction(projectId)) { "文游不存在" }
        val sourceId = snapshot.project.sourceId ?: snapshot.project.id
        val source = runCatching { JSONObject(snapshot.project.sourceDocumentJson ?: "{}") }
            .getOrDefault(JSONObject())
        val mediaFiles = mutableListOf<NovexCardMedia>()
        suspend fun exportAsset(basePath: String, asset: MediaAssetEntity?): String? {
            asset ?: return null
            val path = "$basePath.${asset.extension()}"
            mediaFiles += NovexCardMedia(path, asset.mimeType, media.read(asset))
            return path
        }
        val rootMedia = JSONObject()
        rootMedia.putMedia(
            "cover",
            exportAsset("media/cover", snapshot.media[MediaAssetSlot.INTERACTIVE_FICTION_COVER]),
        )
        rootMedia.putMedia(
            "background",
            exportAsset("media/background", snapshot.media[MediaAssetSlot.INTERACTIVE_FICTION_BACKGROUND]),
        )
        val modules = snapshot.modules.map { module ->
            exportModule(
                module = module,
                mainImage = snapshot.moduleImages[module.id],
                itemImages = snapshot.moduleItemImages[module.id].orEmpty(),
                mediaFiles = mediaFiles,
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
            media = mediaFiles.distinctBy(NovexCardMedia::path),
        )
    }

    private suspend fun exportModule(
        module: ContentModuleEntity,
        mainImage: MediaAssetEntity?,
        itemImages: Map<String, MediaAssetEntity>,
        mediaFiles: MutableList<NovexCardMedia>,
        pathPrefix: String = "media/modules",
    ): JSONObject {
        suspend fun exportAsset(basePath: String, asset: MediaAssetEntity?): String? {
            asset ?: return null
            val path = "$basePath.${asset.extension()}"
            mediaFiles += NovexCardMedia(path, asset.mimeType, media.read(asset))
            return path
        }
        val document = ContentModuleDocumentCodec.decode(module.type, module.contentJson)
        var originalType = module.type.transferName()
        var presentation = module.type.defaultPresentation(document)
        val content = when (document) {
            is ContentModuleDocument.Article -> JSONObject().put("text", document.text)
            is ContentModuleDocument.SingleImage -> JSONObject()
                .put("image", exportAsset("$pathPrefix/${module.id}", mainImage)?.let { JSONObject().put("path", it) })
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
                        exportAsset("$pathPrefix/${module.id}/$itemId", itemImages[item.id])?.let { path ->
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
        return JSONObject()
            .put("id", module.id)
            .put("type", originalType)
            .put("title", module.name)
            .put("presentation", presentation)
            .put("content", content)
    }

    private fun NovexCardImportDocument.kind(): NovexCardKind = when (this) {
        is NovexWorldImportDocument -> NovexCardKind.WORLD
        is NovexCharacterImportDocument -> NovexCardKind.CHARACTER
        is NovexInteractiveFictionImportDocument -> NovexCardKind.GAME
    }

    private fun CharacterVersionEntity.sourceId(): String? = runCatching {
        JSONObject(profileJson).optString("_novexSourceId").takeIf(String::isNotBlank)
    }.getOrNull()

    private fun CharacterVersionEntity.characterSourceId(): String? = runCatching {
        JSONObject(profileJson).optString("_novexCharacterSourceId").takeIf(String::isNotBlank)
    }.getOrNull()

    private fun WorldEntity.sourceId(): String? = runCatching {
        JSONObject(legacySnapshotJson ?: "{}").optString("sourceId").takeIf(String::isNotBlank)
    }.getOrNull()

    private fun WorldEntity.tagsList(): List<String> = runCatching {
        val array = JSONArray(tagsJson)
        buildList {
            repeat(array.length()) { index -> array.optString(index).takeIf(String::isNotBlank)?.let(::add) }
        }
    }.getOrDefault(emptyList())

    private fun MediaAssetEntity.extension(): String = when (mimeType.lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "bin"
    }

    private fun JSONObject.putMedia(key: String, path: String?) {
        put(key, path?.let { JSONObject().put("path", it) })
    }

    private fun ContentModuleType.transferName(): String = when (this) {
        ContentModuleType.TIMELINE -> "timeline"
        ContentModuleType.ERA_EVENT -> "eraEvents"
        ContentModuleType.MAP -> "map"
        ContentModuleType.REGION -> "regions"
        ContentModuleType.FACTION -> "factions"
        ContentModuleType.RACE -> "races"
        ContentModuleType.QUOTES -> "quotes"
        ContentModuleType.WORLD_EXPERIENCE -> "worldExperience"
        ContentModuleType.ATTRIBUTE_PANEL -> "attributePanel"
        ContentModuleType.EQUIPMENT -> "equipment"
        ContentModuleType.TALENT_SKILL -> "skills"
        ContentModuleType.APPEARANCE_PERSONALITY -> "appearancePersonality"
        ContentModuleType.INTEREST -> "interests"
        ContentModuleType.GAME_PLAYER_IDENTITY -> "gamePlayerIdentity"
        ContentModuleType.GAME_OPENING -> "gameOpening"
        ContentModuleType.GAME_NARRATIVE_RULES -> "gameNarrativeRules"
        ContentModuleType.GAME_POWER_SYSTEM -> "gamePowerSystem"
        ContentModuleType.GAME_ATTRIBUTES -> "gameAttributes"
        ContentModuleType.GAME_SKILLS -> "gameSkills"
        ContentModuleType.GAME_EQUIPMENT -> "gameEquipment"
        ContentModuleType.GAME_ITEMS -> "gameItems"
        ContentModuleType.GAME_QUESTS -> "gameQuests"
        ContentModuleType.GAME_CHECKS -> "gameChecks"
        ContentModuleType.GAME_ENDINGS -> "gameEndings"
        ContentModuleType.GAME_CHARACTER_STATUS -> "gameCharacterStatus"
        ContentModuleType.GAME_QUICK_ACTIONS -> "gameQuickActions"
        ContentModuleType.CUSTOM -> "custom"
    }

    private fun InteractiveFictionLaunchMode.transferName(): String = when (this) {
        InteractiveFictionLaunchMode.FIXED_IDENTITY -> "fixedIdentity"
        InteractiveFictionLaunchMode.USER_CREATED_IDENTITY -> "userCreatedIdentity"
        InteractiveFictionLaunchMode.CO_CREATE_WORLD -> "coCreateWorld"
        InteractiveFictionLaunchMode.FREE_SANDBOX -> "freeSandbox"
    }

    private fun ContentModuleType.defaultPresentation(document: ContentModuleDocument): String = when (document) {
        is ContentModuleDocument.Article -> "article"
        is ContentModuleDocument.SingleImage -> "singleImage"
        is ContentModuleDocument.Timeline -> "timeline"
        is ContentModuleDocument.Collection -> when (this) {
            ContentModuleType.FACTION -> "horizontalCards"
            ContentModuleType.QUOTES -> "quoteCards"
            else -> "compactList"
        }
        is ContentModuleDocument.Unsupported -> document.presentation.orEmpty()
    }

    private fun JSONArray?.objects(): List<JSONObject> = buildList {
        val array = this@objects ?: return@buildList
        repeat(array.length()) { index -> array.optJSONObject(index)?.let(::add) }
    }
}
