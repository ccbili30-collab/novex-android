package com.openminis.app.novex.adapter

import androidx.room.withTransaction
import com.openminis.app.data.character.CharacterCatalogRepository
import com.openminis.app.data.character.ContentModuleRepository
import com.openminis.app.data.character.ManagedMediaAssetStore
import com.openminis.app.data.character.MediaAssetRepository
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.WorldEntity
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.interactivefiction.InteractiveFictionProjectEntity
import com.openminis.app.data.interactivefiction.InteractiveFictionRepository
import com.openminis.app.novex.domain.DefaultNovexWorkspace
import com.openminis.app.novex.domain.NovexCatalogPort
import com.openminis.app.novex.domain.NovexContentPort
import com.openminis.app.novex.domain.NovexMediaPort
import com.openminis.app.novex.domain.NovexInteractiveFictionPort
import com.openminis.app.novex.domain.NovexWorkspace
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object NovexWorkspaceFactory {
    fun create(database: AppDatabase, mediaRoot: File): NovexWorkspace {
        val catalog = CharacterCatalogRepository(database.characterCatalogDao())
        val content = ContentModuleRepository(database.contentModuleDao())
        val interactiveFiction = InteractiveFictionRepository(database.interactiveFictionDao())
        val canonicalMediaRoot = mediaRoot.canonicalFile
        val fileTransaction = ManagedMediaFileTransaction(canonicalMediaRoot)
        val commandMutex = Mutex()
        val mediaRepository = MediaAssetRepository(database.mediaAssetDao()) { path ->
            fileTransaction.deleteAfterCommit(path)
        }
        return DefaultNovexWorkspace(
            catalog = RoomCatalogAdapter(catalog),
            cardReferences = RoomCardReferenceAdapter(database.novexCardReferenceDao()),
            versionRelations = RoomCharacterVersionRelationAdapter(database.novexCharacterVersionRelationDao()),
            characterRevisions = RoomCharacterRevisionAdapter(database.novexCharacterRevisionDao()),
            cardRevisions = RoomCardRevisionAdapter(database.novexCardRevisionDao()),
            drafts = RoomDraftOwnershipAdapter(database.novexConversationDraftDao(), database.chatDao()),
            interactiveFiction = RoomInteractiveFictionAdapter(interactiveFiction),
            content = RoomContentAdapter(content),
            media = ManagedMediaAdapter(
                mediaRepository,
                ManagedMediaAssetStore(mediaRoot, mediaRepository, fileTransaction::deleteAfterRollback),
            ),
            transaction = { block ->
                // Database first, including calls already inside a conversation transaction.
                // Taking the command lock first can deadlock against an outer Room transaction
                // that is waiting to apply its next workspace command.
                var locked = false
                try {
                    val result = database.withTransaction {
                        commandMutex.lock()
                        locked = true
                        fileTransaction.begin()
                        block()
                    }
                    // An enclosing conversation transaction can still roll back the restored
                    // image references. Leave physical orphans for later collection in that case.
                    fileTransaction.commit(deleteFiles = !database.inTransaction())
                    result
                } catch (error: Throwable) {
                    if (locked) fileTransaction.rollback()
                    throw error
                } finally {
                    if (locked) commandMutex.unlock()
                }
            },
        )
    }

    /**
     * Application-startup variant: Room DAO acquisition is deferred to the
     * first suspend call and performed on an I/O dispatcher. Instrumented
     * repository tests keep using [create] when they need an eager workspace.
     */
    fun createDeferred(database: AppDatabase, mediaRoot: File): NovexWorkspace =
        DeferredNovexWorkspace { create(database, mediaRoot) }
}

internal class DeferredNovexWorkspace(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val factory: () -> NovexWorkspace,
) : NovexWorkspace {
    @Volatile
    private var initialized: NovexWorkspace? = null
    private val initializationMutex = Mutex()

    override suspend fun emptyConversationDrafts(conversationId: String) = workspace().emptyConversationDrafts(conversationId)
    override suspend fun conversationDrafts(conversationId: String) = workspace().conversationDrafts(conversationId)

    private suspend fun workspace(): NovexWorkspace = initialized ?: initializationMutex.withLock {
        initialized ?: withContext(dispatcher) { factory() }.also { initialized = it }
    }

    override suspend fun referenceStatus(target: com.openminis.app.novex.domain.NovexReferenceTarget) = workspace().referenceStatus(target)
    override suspend fun referencesFrom(source: com.openminis.app.novex.domain.NovexContentAddress) = workspace().referencesFrom(source)
    override suspend fun versionRelations(versionId: String) = workspace().versionRelations(versionId)
    override suspend fun characterRevisions(versionId: String) = workspace().characterRevisions(versionId)
    override suspend fun prepareCardCopy(root: com.openminis.app.novex.domain.NovexCardCopyKey, policy: com.openminis.app.novex.domain.NovexCardCopyPolicy) = workspace().prepareCardCopy(root, policy)
    override suspend fun cardRevisions(subject: com.openminis.app.novex.domain.NovexContentAddress) = workspace().cardRevisions(subject)
    override suspend fun referencesTo(target: com.openminis.app.novex.domain.NovexContentAddress) = workspace().referencesTo(target)
    override suspend fun worlds() = workspace().worlds()
    override suspend fun characters() = workspace().characters()
    override suspend fun interactiveFictions() = workspace().interactiveFictions()
    override suspend fun world(id: String) = workspace().world(id)
    override suspend fun characterForVersion(versionId: String) = workspace().characterForVersion(versionId)
    override suspend fun character(id: String) = workspace().character(id)
    override suspend fun interactiveFiction(id: String) = workspace().interactiveFiction(id)
    override suspend fun modules(owner: ModuleOwner) = workspace().modules(owner)
    override suspend fun module(id: String) = workspace().module(id)
    override suspend fun apply(command: com.openminis.app.novex.domain.NovexCommand) =
        workspace().apply(command)
}

private class RoomInteractiveFictionAdapter(
    private val repository: InteractiveFictionRepository,
) : NovexInteractiveFictionPort {
    override suspend fun create(
        name: String,
        summary: String,
        launchMode: com.openminis.app.data.interactivefiction.InteractiveFictionLaunchMode,
        playerIdentity: String,
        now: Long,
        sourceId: String?,
        sourceDocumentJson: String?,
    ) = repository.create(
        name = name,
        summary = summary,
        launchMode = launchMode,
        playerIdentity = playerIdentity,
        now = now,
        sourceId = sourceId,
        sourceDocumentJson = sourceDocumentJson,
    )

    override suspend fun save(project: InteractiveFictionProjectEntity, now: Long) = repository.save(project, now)
    override suspend fun project(id: String) = repository.project(id)
    override suspend fun list() = repository.list()
    override suspend fun delete(id: String) = repository.delete(id)
}

/** Keeps managed-file side effects aligned with the surrounding Room transaction. */
private class ManagedMediaFileTransaction(
    private val root: File,
) {
    private var active = false
    private val rollbackDeletes = linkedSetOf<File>()
    private val commitDeletes = linkedSetOf<File>()

    fun begin() {
        check(!active) { "媒体事务不能嵌套" }
        active = true
        rollbackDeletes.clear()
        commitDeletes.clear()
    }

    fun deleteAfterRollback(path: String) {
        val file = managedFile(path) ?: return
        if (active) rollbackDeletes += file else file.delete()
    }

    fun deleteAfterCommit(path: String): Boolean {
        val file = managedFile(path) ?: return false
        return if (active) {
            commitDeletes += file
            true
        } else {
            file.delete()
        }
    }

    fun commit(deleteFiles: Boolean = true) {
        check(active)
        if (deleteFiles) commitDeletes.forEach(File::delete)
        finish()
    }

    fun rollback() {
        if (!active) return
        rollbackDeletes.forEach(File::delete)
        finish()
    }

    private fun finish() {
        rollbackDeletes.clear()
        commitDeletes.clear()
        active = false
    }

    private fun managedFile(path: String): File? = runCatching { File(path).canonicalFile }.getOrNull()
        ?.takeIf { it.parentFile == root }
}

private class RoomCatalogAdapter(
    private val repository: CharacterCatalogRepository,
) : NovexCatalogPort {
    override suspend fun createWorld(
        name: String,
        overview: String,
        tagsJson: String,
        legacySnapshotJson: String?,
        now: Long,
    ) = repository.createWorld(name, overview, tagsJson, legacySnapshotJson, now)
    override suspend fun saveWorld(world: WorldEntity, now: Long) = repository.saveWorld(world, now)
    override suspend fun deleteWorld(worldId: String) = repository.deleteWorld(worldId)
    override suspend fun world(id: String) = repository.world(id)
    override suspend fun listWorlds() = repository.listWorlds()
    override suspend fun createCharacter(name: String, originalLabel: String, profileJson: String, now: Long) =
        repository.createCharacter(
            name = name,
            originalLabel = originalLabel,
            originalProfileJson = profileJson,
            now = now,
        )
    override suspend fun duplicateCharacter(characterId: String, now: Long) =
        repository.duplicateCharacter(characterId, now)
    override suspend fun deleteCharacter(characterId: String) = repository.deleteCharacter(characterId)
    override suspend fun saveCharacter(
        character: com.openminis.app.data.character.CharacterEntity,
        now: Long,
    ) = repository.saveCharacter(character, now)
    override suspend fun saveVersion(
        version: com.openminis.app.data.character.CharacterVersionEntity,
        now: Long,
    ) = repository.saveVersion(version, now)
    override suspend fun deleteVariant(versionId: String) = repository.deleteVersion(versionId)
    override suspend fun character(id: String) = repository.character(id)
    override suspend fun listCharacters() = repository.listCharacters()
    override suspend fun listVersions() = repository.listVersions()
    override suspend fun versionsForWorld(worldId: String) = repository.versionsForWorld(worldId)
    override suspend fun worldsForVersion(versionId: String) = repository.worldsForVersion(versionId)
    override suspend fun version(id: String) = repository.version(id)
    override suspend fun createVariant(characterId: String, label: String, profileJson: String, now: Long) =
        repository.createVariant(characterId, label, profileJson, now)
    override suspend fun link(worldId: String, versionId: String, position: Int, now: Long) =
        repository.addVersionToWorld(worldId, versionId, position, now)
    override suspend fun unlink(worldId: String, versionId: String) =
        repository.removeVersionFromWorld(worldId, versionId)
}

private class RoomContentAdapter(
    private val repository: ContentModuleRepository,
) : NovexContentPort {
    override suspend fun list(owner: ModuleOwner) = repository.list(owner)
    override suspend fun all() = repository.all()
    override suspend fun add(
        owner: ModuleOwner,
        type: com.openminis.app.data.character.ContentModuleType,
        name: String,
        contentJson: String,
        collapsed: Boolean,
        now: Long,
        id: String,
    ) = repository.add(owner, type, name, contentJson, collapsed, now, id)
    override suspend fun module(id: String) = repository.module(id)
    override suspend fun save(id: String, name: String, contentJson: String, now: Long) =
        repository.run {
            rename(id, name, now)
            updateContent(id, contentJson, now)
            requireNotNull(module(id)) { "模块不存在" }
        }
    override suspend fun move(id: String, toIndex: Int, now: Long) = repository.move(id, toIndex, now)
    override suspend fun delete(id: String) = repository.delete(id)
    override suspend fun copyAll(source: ModuleOwner, target: ModuleOwner, now: Long) =
        repository.copyAll(source, target, now)
    override suspend fun references(moduleId: String) = repository.references(moduleId)
    override suspend fun addReference(
        moduleId: String,
        target: com.openminis.app.data.character.ModuleReferenceTarget,
        position: Int,
    ) = repository.addReference(moduleId, target, position)
    override suspend fun removeReference(
        moduleId: String,
        target: com.openminis.app.data.character.ModuleReferenceTarget,
    ) = repository.removeReference(moduleId, target)
}

private class ManagedMediaAdapter(
    private val repository: MediaAssetRepository,
    private val store: ManagedMediaAssetStore,
) : NovexMediaPort {
    override suspend fun import(bytes: ByteArray, mimeType: String, now: Long) = store.import(bytes, mimeType, now)
    override suspend fun attach(owner: ModuleOwner, slot: MediaAssetSlot, assetId: String) =
        repository.attach(owner, slot, assetId)
    override suspend fun detach(owner: ModuleOwner, slot: MediaAssetSlot) = repository.detach(owner, slot)
    override suspend fun removeAll(owner: ModuleOwner) = repository.removeAll(owner)
    override suspend fun assetFor(owner: ModuleOwner, slot: MediaAssetSlot) = repository.assetFor(owner, slot)
    override suspend fun read(asset: com.openminis.app.data.character.MediaAssetEntity) =
        File(asset.managedPath).readBytes()
}

internal class RoomDraftOwnershipAdapter(
    private val dao: com.openminis.app.data.db.NovexConversationDraftDao,
    private val chatDao: com.openminis.app.data.db.ChatDao,
) : com.openminis.app.novex.domain.NovexDraftOwnershipPort {
    override suspend fun referencedSubjects(): Set<com.openminis.app.novex.domain.NovexContentAddress> {
        val candidates = list().flatMap { it.cards }.filter { it.isPrivate }
        val result = linkedSetOf<com.openminis.app.novex.domain.NovexContentAddress>()
        fun protectId(id: String?) {
            candidates.filter { it.subject.id == id || it.rootId == id }.forEach { result += it.subject }
        }
        // Conservative deletion guard only: exact stored identifiers, including unknown future fields.
        // This does not grant read/edit access or attach the referenced content to a prompt.
        fun scan(value: Any?) {
            when (value) {
                is org.json.JSONObject -> value.keys().forEach { key ->
                    val child = value.opt(key)
                    if (key == "id" || key.endsWith("Id") || key.endsWith("_id")) protectId(child as? String)
                    if (child is org.json.JSONObject || child is org.json.JSONArray) scan(child)
                }
                is org.json.JSONArray -> (0 until value.length()).forEach { scan(value.opt(it)) }
            }
        }
        chatDao.listSessions().forEach { session ->
            protectId(session.worldId)
            protectId(session.characterVersionId)
            protectId(session.characterId)
            session.novexConfigurationJson?.takeIf { it.isNotBlank() }?.let { raw ->
                try { scan(org.json.JSONObject(raw)) }
                catch (_: org.json.JSONException) { result += candidates.map { it.subject } }
            }
        }
        return result
    }
    override suspend fun load(conversationId: String) = dao.get(conversationId)?.let {
        com.openminis.app.novex.domain.NovexConversationDraftCodec.decode(it.contentJson)
    }
    override suspend fun list() = dao.list().map { com.openminis.app.novex.domain.NovexConversationDraftCodec.decode(it.contentJson) }
    override suspend fun save(snapshot: com.openminis.app.novex.domain.NovexConversationDraftSnapshot) {
        dao.save(com.openminis.app.data.db.NovexConversationDraftEntity(snapshot.conversationId,
            com.openminis.app.novex.domain.NovexConversationDraftCodec.encode(snapshot)))
    }
}
