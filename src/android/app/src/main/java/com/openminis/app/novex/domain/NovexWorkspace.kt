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
import com.openminis.app.data.character.ModuleReferenceTargetType
import com.openminis.app.data.character.NovexCardImportDocument
import com.openminis.app.data.character.NovexCardKind
import com.openminis.app.data.character.NovexCardPackagePreview
import com.openminis.app.data.character.NovexCharacterImportDocument
import com.openminis.app.data.character.NovexValidatedCardImport
import com.openminis.app.data.character.NovexWorldImportDocument
import com.openminis.app.data.character.WorldEntity
import com.openminis.app.data.character.NovexInteractiveFictionImportDocument
import com.openminis.app.data.interactivefiction.InteractiveFictionDocumentComposer
import com.openminis.app.data.interactivefiction.InteractiveFictionLaunchMode
import com.openminis.app.data.interactivefiction.InteractiveFictionProjectEntity
import com.openminis.app.novex.domain.NovexCardTransferFields.sourceId
import java.util.UUID
import org.json.JSONObject

/**
 * The single Novex seam used by pages and future automation.
 *
 * Callers read complete page snapshots and submit domain commands. Storage,
 * managed files, ordering rules and reference cleanup stay behind this seam.
 */
interface NovexWorkspace {
    suspend fun cardDirectory(subject: NovexContentAddress): java.io.File? = null
    suspend fun migrateCardDirectories(): Int = 0
    suspend fun characterRevisions(versionId: String): List<NovexCharacterRevision> = emptyList()
    suspend fun prepareWorldParallel(worldId: String): NovexWorldParallelPlan = error("尚未配置平行世界创建")
    suspend fun cardRevisions(subject: NovexContentAddress): List<NovexCardRevision> = emptyList()
    suspend fun prepareCardCopy(root: NovexCardCopyKey, policy: NovexCardCopyPolicy = NovexCardCopyPolicy.DETACH): NovexCardCopyPlan = error("尚未配置卡片复制")
    suspend fun versionRelations(versionId: String): List<NovexCharacterVersionRelation> = emptyList()
    suspend fun referenceStatus(target: NovexReferenceTarget): NovexReferenceTargetStatus = NovexReferenceTargetStatus.MISSING_CARD
    suspend fun referencesFrom(source: NovexContentAddress): List<NovexCardReference> = emptyList()
    suspend fun referencesTo(target: NovexContentAddress): List<NovexCardReference> = emptyList()
    suspend fun conversationDrafts(conversationId: String): NovexConversationDraftSnapshot? = null
    suspend fun emptyConversationDrafts(conversationId: String): List<NovexConversationDraftCard> = emptyList()
    suspend fun worlds(): List<NovexWorldCard>
    suspend fun characters(): List<NovexCharacterCard>
    suspend fun interactiveFictions(): List<NovexInteractiveFictionCard>
    suspend fun world(id: String): NovexWorldSnapshot?
    suspend fun characterForVersion(versionId: String): NovexCharacterSnapshot? =
        characters().firstOrNull { card -> card.character.allVersions.any { it.id == versionId } }
            ?.let { character(it.character.character.id) }
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
    val itemImages: Map<String, MediaAssetEntity> = emptyMap(),
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
    data class PutVersionRelation(val relation: NovexCharacterVersionRelation) : NovexCommand
    data class RemoveVersionRelation(val id: String, val expectedSourceVersionId: String) : NovexCommand
    data class ReserveConversationDraftWrite(val conversationId: String, val reservation: NovexDraftWriteReservation) : NovexCommand
    data class CompleteConversationDraftWrite(val conversationId: String, val receipt: NovexManagementWriteReceipt) : NovexCommand
    data class ReleaseConversationDraftWrite(val conversationId: String, val planId: String) : NovexCommand
    data class AddConversationDraft(val conversationId: String, val kind: NovexContentKind, val now: Long = System.currentTimeMillis()) : NovexCommand
    data class PutCardReference(val reference: NovexCardReference) : NovexCommand
    data class RemoveCardReference(val id: String, val expectedSource: NovexContentAddress? = null) : NovexCommand
    data class FillConversationDraft(val conversationId: String, val subject: NovexContentAddress, val creation: NovexCommand) : NovexCommand
    data class EnsureConversationDrafts(val conversationId: String, val now: Long = System.currentTimeMillis()) : NovexCommand
    data class FinalizeConversationDrafts(val conversationId: String, val protectedSubjects: Set<NovexContentAddress> = emptySet()) : NovexCommand
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

    data class CopyCard(val plan: NovexCardCopyPlan, val now: Long = System.currentTimeMillis()) : NovexCommand

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

    data class CreateParallelWorld(val plan: NovexWorldParallelPlan, val name: String, val selectedVersionIds: Set<String>,
        val now: Long = System.currentTimeMillis()) : NovexCommand
    data class ExportNativeSelection(val root: NovexCardCopyKey, val versionIds: Set<String>? = null,
        val includeDependencies: Boolean = false) : NovexCommand
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
        val source: String? = null,
    ) : NovexCommand

    data class DetachImage(
        val owner: ModuleOwner,
        val slot: MediaAssetSlot,
    ) : NovexCommand
}

sealed interface NovexChange {
    data class WorldParallelCreated(val result: NovexWorldParallelResult) : NovexChange
    data class CardsCopied(val result: NovexCardCopyResult) : NovexChange
    data class CardReferenceSaved(val reference: NovexCardReference) : NovexChange
    data class ConversationDraftsPrepared(val snapshot: NovexConversationDraftSnapshot) : NovexChange
    data class ConversationDraftsFinalized(val result: NovexDraftFinalization) : NovexChange
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

fun NovexChange.requireConversationDrafts(): NovexConversationDraftSnapshot = (this as NovexChange.ConversationDraftsPrepared).snapshot
fun NovexChange.requireDraftFinalization(): NovexDraftFinalization = (this as NovexChange.ConversationDraftsFinalized).result

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
    private val drafts: NovexDraftOwnershipPort = UnavailableNovexDraftOwnership,
    private val cardReferences: NovexCardReferencePort = UnavailableNovexCardReferences,
    private val versionRelations: NovexCharacterVersionRelationPort = UnavailableNovexVersionRelations,
    private val characterRevisions: NovexCharacterRevisionPort = UnavailableNovexCharacterRevisions,
    private val cardRevisions: NovexCardRevisionPort = UnavailableNovexCardRevisions,
    private val cardDirectories: NovexCardDirectories? = null,
) : NovexWorkspace {
    private val importer = NovexCardImporter(catalog, interactiveFiction, content, media, versionRelations)
    private val exporter = NovexCardExporter(media::read, content::references)
    private val directoryRoots = linkedSetOf<NovexCardCopyKey>()
    private fun cardCopy() = NovexCardCopy(this, catalog, content, media, interactiveFiction, cardReferences, versionRelations)
    override suspend fun prepareWorldParallel(worldId: String): NovexWorldParallelPlan {
        lateinit var plan: NovexWorldParallelPlan
        transaction { plan = NovexWorldParallel(insideTransactionWorkspace()).prepare(worldId); NovexChange.Completed }
        return plan
    }
    override suspend fun prepareCardCopy(root: NovexCardCopyKey, policy: NovexCardCopyPolicy): NovexCardCopyPlan {
        lateinit var prepared: NovexCardCopyPlan
        transaction { prepared = cardCopy().prepare(root, policy); NovexChange.Completed }
        return prepared
    }
    private fun referencePackage(selectedCharacterId: String? = null, selectedVersionIds: Set<String>? = null, directoryMedia: MutableMap<String, MediaAssetEntity>? = null) = NovexReferencePackage(insideTransactionWorkspace(),
        restoreReference = { NovexCardReferences(cardReferences, catalog, interactiveFiction, content).put(it, allowMissingTarget = true) },
        exportSingle = { kind, id -> when (kind) {
            NovexCardKind.WORLD -> exporter.world(requireNotNull(world(id)) { "世界不存在" }, directoryMedia)
            NovexCardKind.CHARACTER -> exporter.character(requireNotNull(character(id)) { "角色不存在" }, versionRelations.forCharacter(id), selectedVersionIds.takeIf { id == selectedCharacterId }, directoryMedia)
            NovexCardKind.GAME -> exporter.game(requireNotNull(interactiveFiction(id)) { "文游不存在" }, directoryMedia)
        } },
        importSingle = { card, now, reconcile ->
            val id = importer.import(card, now, reconcile)
            if (cardDirectories != null) directoryRoots += NovexCardCopyKey(card.document.kind(), id)
            id
        },
        restoreLegacyLink = { world, version, position, now -> catalog.link(world, version, position, now) })
    override suspend fun conversationDrafts(conversationId: String) = drafts.load(conversationId)

    private suspend fun privateCards() = drafts.list().flatMap { it.cards }.filter { it.isPrivate }

    override suspend fun worlds(): List<NovexWorldCard> {
        val all = catalog.listWorlds()
        val privateIds = privateCards().filter { it.subject.kind == NovexContentKind.WORLD }.mapTo(hashSetOf()) { it.rootId }
        return all.filterNot { it.id in privateIds }.map { world ->
            val owner = ModuleOwner.world(world.id)
            NovexWorldCard(
                world = world,
                image = media.assetFor(owner, MediaAssetSlot.WORLD_COVER)
                    ?: media.assetFor(owner, MediaAssetSlot.WORLD_BACKGROUND),
                characterCount = catalog.versionsForWorld(world.id).size,
                moduleCount = content.list(owner).size,
            )
        }
    }

    override suspend fun characters(): List<NovexCharacterCard> {
        val all = catalog.listCharacters()
        val privateIds = privateCards().filter { it.subject.kind == NovexContentKind.CHARACTER_VERSION }.mapTo(hashSetOf()) { it.rootId }
        return all.filterNot { it.id in privateIds }.mapNotNull { root ->
            val aggregate = catalog.character(root.id) ?: return@mapNotNull null
            NovexCharacterCard(
                character = aggregate,
                avatar = media.assetFor(
                    ModuleOwner.characterVersion(aggregate.original.id),
                    MediaAssetSlot.CHARACTER_AVATAR,
                ),
            )
        }
    }

    override suspend fun interactiveFictions(): List<NovexInteractiveFictionCard> {
        val all = interactiveFiction.list()
        val privateIds = privateCards().filter { it.subject.kind == NovexContentKind.INTERACTIVE_FICTION }.mapTo(hashSetOf()) { it.rootId }
        return all.filterNot { it.id in privateIds }.map { project ->
            val owner = ModuleOwner.interactiveFiction(project.id)
            NovexInteractiveFictionCard(
                project = project,
                image = media.assetFor(owner, MediaAssetSlot.INTERACTIVE_FICTION_COVER)
                    ?: media.assetFor(owner, MediaAssetSlot.INTERACTIVE_FICTION_BACKGROUND),
                moduleCount = content.list(owner).size,
            )
        }
    }

    override suspend fun world(id: String): NovexWorldSnapshot? {
        val world = catalog.world(id) ?: return null
        val versions = catalog.versionsForWorld(id)
        val modules = content.list(ModuleOwner.world(id))
        val availableVersions = catalog.listVersions()
        val privateCharacterIds = privateCards().filter { it.subject.kind == NovexContentKind.CHARACTER_VERSION }
            .mapTo(hashSetOf()) { it.rootId }
        return NovexWorldSnapshot(
            world = world,
            versions = versions,
            availableVersions = availableVersions.filterNot { it.characterId in privateCharacterIds },
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
            itemImages = moduleItemImages(listOf(module))[module.id].orEmpty(),
        )
    }

    // Native compound commands share the outer transaction and command lock. Calling the
    // public entry recursively would attempt to lock its non-reentrant mutex again.
    private fun insideTransactionWorkspace(): NovexWorkspace = object : NovexWorkspace by this {
        override suspend fun apply(command: NovexCommand) = applyRecorded(command)
        override suspend fun prepareCardCopy(root: NovexCardCopyKey, policy: NovexCardCopyPolicy) = cardCopy().prepare(root, policy)
        override suspend fun prepareWorldParallel(worldId: String) = NovexWorldParallel(this).prepare(worldId)
    }

    override suspend fun apply(command: NovexCommand): NovexChange = transaction {
        directoryRoots.clear()
        try {
            applyRecorded(command).also { directoryRoots.toList().forEach { synchronizeDirectory(it) } }
        } finally { directoryRoots.clear() }
    }

    private suspend fun synchronizeDirectory(key: NovexCardCopyKey) {
        val directories = cardDirectories ?: return
        directories.synchronize(key, NovexCardDirectorySnapshot(this).read(key)) {
            val files = linkedMapOf<String, MediaAssetEntity>()
            val card = referencePackage(directoryMedia = files).export(key.kind, key.id, includeDependencies = false)
            NovexCardDirectoryPayload(card, files.mapValues { java.io.File(it.value.managedPath) })
        }
    }

    override suspend fun cardDirectory(subject: NovexContentAddress): java.io.File? {
        val directories = cardDirectories ?: return null
        var file: java.io.File? = null
        transaction {
            NovexCardDirectoryChanges(this).root(subject)?.let { key -> synchronizeDirectory(key); file = directories.resolve(key) }
            NovexChange.Completed
        }
        return file
    }

    /** One card per transaction: restartable, and a broken old image cannot block opening the app. */
    override suspend fun migrateCardDirectories(): Int {
        if (cardDirectories == null) return 0
        val keys = catalog.listWorlds().map { NovexCardCopyKey(NovexCardKind.WORLD, it.id) } +
            catalog.listCharacters().map { NovexCardCopyKey(NovexCardKind.CHARACTER, it.id) } +
            interactiveFiction.list().map { NovexCardCopyKey(NovexCardKind.GAME, it.id) }
        var failures = 0
        keys.forEach { key ->
            try { transaction { synchronizeDirectory(key); NovexChange.Completed } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { failures++ }
            kotlinx.coroutines.yield()
        }
        transaction {
            cardDirectories.reclaimUnreferenced(System.currentTimeMillis() - 24L * 60 * 60 * 1000)
            NovexChange.Completed
        }
        return failures
    }

    private suspend fun applyRecorded(command: NovexCommand): NovexChange {
        val historyCommand = if (command is NovexCommand.RemoveCardReference && command.expectedSource == null)
            command.copy(expectedSource = cardReferences.get(command.id)?.source) else command
        val directoryChanges = if (cardDirectories != null) NovexCardDirectoryChanges(this) else null
        directoryChanges?.before(historyCommand)?.let(directoryRoots::addAll)
        val history = NovexCharacterRevisionJournal(this, characterRevisions)
        val cards = NovexCardRevisionJournal(this, cardRevisions)
        val targets = if (characterRevisions === UnavailableNovexCharacterRevisions) emptyList() else history.existingTargets(historyCommand)
        val cardTargets = if (cardRevisions === UnavailableNovexCardRevisions) emptyList() else cards.existingTargets(historyCommand)
        val at = command.revisionTime()
        targets.forEach { history.record(it, at) }
        cardTargets.forEach { cards.record(it, at) }
        return applyInsideTransaction(command).also { result ->
            directoryChanges?.after(result)?.let(directoryRoots::addAll)
            draftLifecycle().publishSavedContent(NovexSavedCardTargets(this).resolve(command, result))
            if (characterRevisions !== UnavailableNovexCharacterRevisions)
                (targets + history.resultingTargets(result)).distinct().forEach { history.record(it, at) }
            if (cardRevisions !== UnavailableNovexCardRevisions)
                (cardTargets + cards.resultingTargets(result)).distinct().forEach { cards.record(it, at) }
        }
    }

    override suspend fun characterRevisions(versionId: String) = characterRevisions.list(versionId)
    override suspend fun cardRevisions(subject: NovexContentAddress) = cardRevisions.list(subject)

    override suspend fun referenceStatus(target: NovexReferenceTarget) = NovexCardReferences(cardReferences, catalog, interactiveFiction, content).status(target)

    override suspend fun referencesFrom(source: NovexContentAddress) = cardReferences.outgoing(source)
    override suspend fun versionRelations(versionId: String) = versionRelations.forVersion(versionId)
    override suspend fun referencesTo(target: NovexContentAddress) = cardReferences.incoming(target)

    override suspend fun characterForVersion(versionId: String): NovexCharacterSnapshot? =
        catalog.version(versionId)?.let { character(it.characterId) }

    override suspend fun emptyConversationDrafts(conversationId: String) = draftLifecycle().emptyCards(conversationId)

    /** Upgrade old nonempty drafts without deleting empty or reserved targets. */
    suspend fun recoverSavedCards() = transaction {
        draftLifecycle().publishSavedContent()
        NovexChange.Completed
    }

    private fun draftLifecycle() = NovexConversationDrafts(catalog, interactiveFiction, drafts, content, media, cardReferences) { card ->
        val command = when (card.subject.kind) {
            NovexContentKind.WORLD -> NovexCommand.DeleteWorld(card.rootId)
            NovexContentKind.CHARACTER_VERSION -> NovexCommand.DeleteCharacter(card.rootId)
            NovexContentKind.INTERACTIVE_FICTION -> NovexCommand.DeleteInteractiveFiction(card.rootId)
            NovexContentKind.CREATIVE_ARTIFACT -> error("不清理创作文件")
        }
        applyInsideTransaction(command)
        Unit
    }

    private suspend fun applyInsideTransaction(command: NovexCommand): NovexChange = when (command) {
        is NovexCommand.ReserveConversationDraftWrite -> NovexChange.ConversationDraftsPrepared(
            draftLifecycle().reserve(command.conversationId, command.reservation),
        )
        is NovexCommand.CompleteConversationDraftWrite -> NovexChange.ConversationDraftsPrepared(
            draftLifecycle().complete(command.conversationId, command.receipt),
        )
        is NovexCommand.ReleaseConversationDraftWrite -> NovexChange.ConversationDraftsPrepared(
            draftLifecycle().release(command.conversationId, command.planId),
        )
        is NovexCommand.PutVersionRelation -> {
            val source = requireNotNull(catalog.version(command.relation.sourceVersionId)) { "关系来源版本不存在" }
            val character = requireNotNull(catalog.character(source.characterId)) { "人物不存在" }
            val target = catalog.version(command.relation.targetVersionId)
            val versions = (character.allVersions + listOfNotNull(target)).distinctBy { it.id }
            versionRelations.get(command.relation.id)?.let { require(it.sourceVersionId == source.id) { "关系编号属于另一来源版本" } }
            NovexCharacterVersionRelationRules.validate(command.relation, versions, versionRelations.forCharacter(source.characterId))
            versionRelations.save(source.characterId, command.relation)
            NovexChange.Completed
        }
        is NovexCommand.RemoveVersionRelation -> {
            val existing = requireNotNull(versionRelations.get(command.id)) { "版本关系不存在" }
            require(existing.sourceVersionId == command.expectedSourceVersionId) { "关系不属于指定来源版本" }
            versionRelations.delete(command.id)
            NovexChange.Completed
        }
        is NovexCommand.PutCardReference -> NovexChange.CardReferenceSaved(
            NovexCardReferences(cardReferences, catalog, interactiveFiction, content).put(command.reference),
        )
        is NovexCommand.RemoveCardReference -> {
            command.expectedSource?.let { source ->
                require(cardReferences.get(command.id)?.source == source) { "引用不存在或不属于指定来源卡片" }
            }
            cardReferences.delete(command.id)
            NovexChange.Completed
        }
        is NovexCommand.AddConversationDraft -> NovexChange.ConversationDraftsPrepared(
            draftLifecycle().add(command.conversationId, command.kind, command.now),
        )
        is NovexCommand.FillConversationDraft -> applyInsideTransaction(
            draftLifecycle().fillCommand(command.conversationId, command.subject, command.creation),
        )
        is NovexCommand.EnsureConversationDrafts -> NovexChange.ConversationDraftsPrepared(
            draftLifecycle().ensure(command.conversationId, command.now),
        )
        is NovexCommand.FinalizeConversationDrafts -> NovexChange.ConversationDraftsFinalized(
            draftLifecycle().finalize(command.conversationId, command.protectedSubjects),
        )
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
                cardReferences.deleteSourceModule(module.id)
                content.delete(module.id)
            }
            media.removeAll(ModuleOwner.world(command.worldId))
            cardReferences.deleteSource(NovexContentAddress.world(command.worldId))
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
        is NovexCommand.CopyCard -> NovexChange.CardsCopied(cardCopy().execute(command.plan, command.now))
        is NovexCommand.DuplicateCharacter -> {
            val copy = cardCopy()
            val result = copy.execute(copy.prepare(NovexCardCopyKey(NovexCardKind.CHARACTER, command.characterId), NovexCardCopyPolicy.DETACH), command.now)
            NovexChange.CharacterSaved(requireNotNull(catalog.character(result.root.id)))
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
            val localId = referencePackage().import(command.card, command.now)
            NovexChange.NativeCardImported(command.card.document.kind(), localId)
        }
        is NovexCommand.CreateParallelWorld -> NovexChange.WorldParallelCreated(
            NovexWorldParallel(insideTransactionWorkspace()).create(command.plan, command.name, command.selectedVersionIds, command.now))
        is NovexCommand.ExportNativeSelection -> {
            require(command.versionIds == null || command.root.kind == NovexCardKind.CHARACTER) { "仅角色卡支持选择版本" }
            NovexChange.NativeCardExported(referencePackage(command.root.id, command.versionIds).export(
                command.root.kind, command.root.id, command.versionIds, command.includeDependencies, explicitScope = true))
        }
        is NovexCommand.ExportNativeWorld -> NovexChange.NativeCardExported(
            referencePackage().export(NovexCardKind.WORLD, command.worldId),
        )
        is NovexCommand.ExportNativeCharacter -> NovexChange.NativeCardExported(
            referencePackage().export(NovexCardKind.CHARACTER, command.characterId),
        )
        is NovexCommand.ExportNativeInteractiveFiction -> NovexChange.NativeCardExported(
            referencePackage().export(NovexCardKind.GAME, command.projectId),
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
                cardReferences.deleteSourceModule(module.id)
                content.delete(module.id)
            }
            media.removeAll(owner)
            cardReferences.deleteSource(NovexContentAddress.interactiveFiction(command.projectId))
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
            val moduleIds = copyVersionContents(source.id, variant.id, command.now)
            copyVersionReferences(mapOf(source.id to variant.id), moduleIds)
            versionRelations.save(source.characterId, NovexCharacterVersionRelation(
                UUID.randomUUID().toString(), variant.id, source.id, NovexCharacterVersionRelationKind.PARALLEL))
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
            val previous = content.module(command.moduleId)
            previous?.let { removeMissingItemMedia(it, command.contentJson) }
            NovexChange.ModuleSaved(
                content.save(command.moduleId, command.name, previous?.let { NovexModuleImageOrigins.preserve(it.contentJson, command.contentJson, it.type) } ?: command.contentJson, command.now),
            )
        }
        is NovexCommand.MoveModule -> NovexChange.ModuleSaved(
            content.move(command.moduleId, command.toIndex, command.now),
        )
        is NovexCommand.DeleteModule -> {
            content.module(command.moduleId)?.let { removeModuleMedia(it) }
            cardReferences.deleteSourceModule(command.moduleId)
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
            recordImageOrigin(command.owner, command.source ?: "本地图片", command.now)
            NovexChange.MediaAttached(asset)
        }
        is NovexCommand.DetachImage -> {
            media.detach(command.owner, command.slot)
            recordImageOrigin(command.owner, null, System.currentTimeMillis())
            NovexChange.Completed
        }
    }

    private suspend fun recordImageOrigin(owner: ModuleOwner, source: String?, now: Long) {
        if (owner.type != ModuleOwnerType.CONTENT_MODULE) return
        val module = content.module(ModuleOwner.contentModuleId(owner.id)) ?: return
        val key = ModuleOwner.contentModuleItemId(owner.id)?.let { "entry:$it" } ?: "main"
        content.save(module.id, module.name, NovexModuleImageOrigins.set(module.contentJson, module.type, key, source), now)
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
            cardReferences.deleteSourceModule(removed.id)
            content.delete(removed.id)
        }
        drafts.forEach { draft ->
            if (draft.id in existing) {
                removeMissingItemMedia(existing.getValue(draft.id), draft.contentJson)
                content.save(draft.id, draft.name, NovexModuleImageOrigins.preserve(existing.getValue(draft.id).contentJson, draft.contentJson, draft.type), now)
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
        val allWorlds = catalog.listWorlds()
        val allVersions = catalog.listVersions()
        val allModules = content.all()
        val privateCards = privateCards()
        val privateWorldIds = privateCards.filter { it.subject.kind == NovexContentKind.WORLD }.mapTo(hashSetOf()) { it.rootId }
        val privateCharacterIds = privateCards.filter { it.subject.kind == NovexContentKind.CHARACTER_VERSION }.mapTo(hashSetOf()) { it.rootId }
        val privateVersionIds = allVersions.filter { it.characterId in privateCharacterIds }.mapTo(hashSetOf()) { it.id }
        val privateGameIds = privateCards.filter { it.subject.kind == NovexContentKind.INTERACTIVE_FICTION }.mapTo(hashSetOf()) { it.rootId }
        val worlds = allWorlds.filterNot { it.id in privateWorldIds }.map { world ->
            NovexModuleReferenceOption(ModuleReferenceTarget.world(world.id), world.name, "世界")
        }
        val versions = allVersions.filterNot { it.id in privateVersionIds }.map { version ->
            val name = runCatching {
                org.json.JSONObject(version.profileJson).optString("name").trim().ifBlank { version.label }
            }.getOrDefault(version.label)
            NovexModuleReferenceOption(
                ModuleReferenceTarget.characterVersion(version.id),
                "$name · ${version.label}",
                "角色版本",
            )
        }
        val modules = allModules.filterNot {
            it.id == module.id || when (it.ownerType) {
                ModuleOwnerType.WORLD -> it.ownerId in privateWorldIds
                ModuleOwnerType.CHARACTER_VERSION -> it.ownerId in privateVersionIds
                ModuleOwnerType.INTERACTIVE_FICTION -> it.ownerId in privateGameIds
                ModuleOwnerType.CONTENT_MODULE -> false
            }
        }.map { candidate ->
            NovexModuleReferenceOption(ModuleReferenceTarget.module(candidate.id), candidate.name, "内容模块")
        }
        return (worlds + versions + modules).filterNot { it.target == ownerTarget }
    }

    private suspend fun copyVersionContents(sourceVersionId: String, targetVersionId: String, now: Long): Map<String, String> {
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
        return sourceModules.zip(copiedModules).associate { (old, new) -> old.id to new.id }
    }

    private suspend fun copyVersionReferences(versionIds: Map<String, String>, moduleIds: Map<String, String>) {
        // All copied modules must exist before cross-version links can be restored.
        moduleIds.forEach { (sourceId, copiedId) ->
            content.references(copiedId).forEach { content.removeReference(copiedId, ModuleReferenceTarget(it.targetType, it.targetId)) }
            content.references(sourceId).forEach { reference ->
                val targetId = when (reference.targetType) {
                    ModuleReferenceTargetType.MODULE -> moduleIds[reference.targetId]
                    ModuleReferenceTargetType.CHARACTER_VERSION -> versionIds[reference.targetId]
                    else -> null
                } ?: reference.targetId
                content.addReference(copiedId, ModuleReferenceTarget(reference.targetType, targetId), reference.position)
            }
        }
        versionIds.forEach { (sourceId, copiedId) ->
            cardReferences.outgoing(NovexContentAddress.characterVersion(sourceId)).forEach { reference ->
                val copiedTarget = if (reference.unresolvedTarget == null && reference.target.subject.kind == NovexContentKind.CHARACTER_VERSION)
                    versionIds[reference.target.subject.id] else null
                val target = if (copiedTarget == null) reference.target else reference.target.copy(
                    subject = NovexContentAddress.characterVersion(copiedTarget),
                    moduleId = reference.target.moduleId?.let { moduleIds[it] ?: it },
                )
                NovexCardReferences(cardReferences, catalog, interactiveFiction, content).put(reference.copy(
                    id = UUID.randomUUID().toString(), source = NovexContentAddress.characterVersion(copiedId), target = target,
                    sourceModuleId = reference.sourceModuleId?.let { moduleIds.getValue(it) },
                ), allowMissingTarget = true)
            }
        }
    }

    private suspend fun deleteVersionContents(versionId: String) {
        cardReferences.deleteSource(NovexContentAddress.characterVersion(versionId))
        val owner = ModuleOwner.characterVersion(versionId)
        content.list(owner).forEach { module ->
            removeModuleMedia(module)
            cardReferences.deleteSourceModule(module.id)
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

    private fun NovexCardImportDocument.kind(): NovexCardKind = when (this) {
        is NovexWorldImportDocument -> NovexCardKind.WORLD
        is NovexCharacterImportDocument -> NovexCardKind.CHARACTER
        is NovexInteractiveFictionImportDocument -> NovexCardKind.GAME
    }

}
