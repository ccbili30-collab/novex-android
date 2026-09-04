package com.openminis.app.data.creative

import androidx.room.withTransaction
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.domain.CreativeArtifact
import com.openminis.app.novex.domain.CreativeArtifactAttachment
import com.openminis.app.novex.domain.CreativeArtifactKind
import com.openminis.app.novex.domain.CreativeArtifactOrigin
import com.openminis.app.novex.domain.CreativeArtifactRevision
import com.openminis.app.novex.domain.NovexContentAddress
import com.openminis.app.novex.domain.NovexContentKind
import com.openminis.app.novex.domain.NovexCreativeArtifactReader
import com.openminis.app.novex.domain.NovexCreativeArtifactSummary
import com.openminis.app.novex.domain.NovexManagementArtifactPort
import com.openminis.app.novex.domain.NovexManagedArtifactDescription
import java.io.File
import java.util.UUID

data class CreativeArtifactQuery(
    val conversationId: String? = null,
    val owner: NovexContentAddress? = null,
    val ownerKinds: Set<NovexContentKind> = emptySet(),
    val unattachedOnly: Boolean = false,
    val kinds: Set<CreativeArtifactKind> = emptySet(),
    val favoritesOnly: Boolean = false,
    val includeTrashed: Boolean = false,
    val trashOnly: Boolean = false,
)

data class CreativeArtifactRecord(
    val artifact: CreativeArtifact,
    val revisions: List<CreativeArtifactRevision>,
    val attachments: List<CreativeArtifactAttachment>,
    val sourcePath: String?,
)

/** Product-facing creative library. It owns metadata transactions and content-addressed files. */
class CreativeArtifactRepository(
    private val database: AppDatabase,
    private val files: CreativeArtifactFileStore,
) : NovexManagementArtifactPort, NovexCreativeArtifactReader {
    private val dao get() = database.creativeArtifactDao()

    suspend fun capture(
        title: String,
        kind: CreativeArtifactKind,
        bytes: ByteArray,
        mimeType: String,
        origin: CreativeArtifactOrigin,
        sourcePath: String? = null,
        now: Long = System.currentTimeMillis(),
    ): CreativeArtifactRecord {
        val blob = files.put(bytes, mimeType)
        val artifactId = sourcePath?.let { dao.artifactBySource(origin.conversationId, it)?.id }
            ?: UUID.randomUUID().toString()
        database.withTransaction {
            val existing = dao.artifact(artifactId)
            val revisionNumber = (existing?.revisions?.maxOfOrNull { it.revisionNumber } ?: 0) + 1
            val revisionId = UUID.randomUUID().toString()
            if (existing == null) {
                dao.insertArtifact(
                    CreativeArtifactEntity(
                        id = artifactId,
                        kind = kind.name,
                        title = title.ifBlank { "未命名成果" },
                        originConversationId = origin.conversationId,
                        originBranchId = origin.branchId,
                        originMessageId = origin.messageId,
                        originToolCallId = origin.toolCallId,
                        sourcePath = sourcePath,
                        currentRevisionId = revisionId,
                        currentStorageKey = blob.storageKey,
                        favorite = false,
                        trashedAt = null,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
            dao.insertRevision(
                CreativeArtifactRevisionEntity(
                    id = revisionId,
                    artifactId = artifactId,
                    revisionNumber = revisionNumber,
                    storageKey = blob.storageKey,
                    contentHash = blob.contentHash,
                    mimeType = blob.mimeType,
                    sizeBytes = blob.sizeBytes,
                    createdAt = now,
                ),
            )
            if (existing != null) {
                dao.updateCurrentRevision(
                    artifactId = artifactId,
                    title = existing.artifact.title,
                    kind = kind.name,
                    revisionId = revisionId,
                    storageKey = blob.storageKey,
                    updatedAt = now,
                )
            }
        }
        return requireNotNull(artifact(artifactId))
    }

    suspend fun artifact(id: String): CreativeArtifactRecord? = dao.artifact(id)?.toDomain()

    suspend fun list(query: CreativeArtifactQuery = CreativeArtifactQuery()): List<CreativeArtifactRecord> =
        filterCreativeArtifactRecords(dao.all().map { value -> value.toDomain() }, query)

    /** Resolves the current image attached to each module of one world, role version or game. */
    override suspend fun availableArtifacts(): List<NovexCreativeArtifactSummary> = list().map { record ->
        NovexCreativeArtifactSummary(
            address = NovexContentAddress.creativeArtifact(record.artifact.id),
            title = record.artifact.title,
            kind = record.artifact.kind,
        )
    }

    override suspend fun attachedModuleImageFiles(owner: NovexContentAddress): Map<String, File> {
        val records = list(
            CreativeArtifactQuery(
                owner = owner,
                kinds = setOf(CreativeArtifactKind.IMAGE, CreativeArtifactKind.MAP),
            ),
        )
        return selectAttachedModuleImageIds(records, owner).mapNotNull { (moduleId, artifactId) ->
            runCatching { moduleId to file(artifactId) }.getOrNull()
        }.toMap()
    }

    override suspend fun exists(artifactId: String): Boolean = dao.artifact(artifactId) != null

    override suspend fun describe(artifactId: String): NovexManagedArtifactDescription? {
        val record = artifact(artifactId) ?: return null
        val revision = record.revisions.maxByOrNull { it.number }
        return NovexManagedArtifactDescription(
            id = record.artifact.id,
            title = record.artifact.title,
            kind = record.artifact.kind,
            mimeType = revision?.mimeType ?: "application/octet-stream",
            sizeBytes = revision?.sizeBytes ?: 0L,
            sourcePath = record.sourcePath,
        )
    }

    override suspend fun attach(attachment: CreativeArtifactAttachment) {
        requireNotNull(dao.artifact(attachment.artifactId)) { "创作成果不存在" }
        dao.attach(attachment.toEntity())
    }

    override suspend fun detach(attachment: CreativeArtifactAttachment) {
        val value = attachment.toEntity()
        dao.detach(value.artifactId, value.ownerKind, value.ownerId, value.moduleId, value.slot)
    }

    suspend fun setFavorite(artifactId: String, favorite: Boolean) {
        requireNotNull(dao.artifact(artifactId)) { "创作成果不存在" }
        dao.setFavorite(artifactId, favorite)
    }

    suspend fun moveToTrash(artifactId: String, now: Long = System.currentTimeMillis()) {
        requireNotNull(dao.artifact(artifactId)) { "创作成果不存在" }
        dao.setTrashedAt(artifactId, now)
    }

    suspend fun restore(artifactId: String) {
        requireNotNull(dao.artifact(artifactId)) { "创作成果不存在" }
        dao.setTrashedAt(artifactId, null)
    }

    suspend fun permanentlyDelete(artifactId: String) {
        val orphaned = database.withTransaction { dao.permanentlyDelete(artifactId) }
        orphaned.forEach { storageKey -> runCatching { files.delete(storageKey) } }
    }

    suspend fun bytes(artifactId: String): ByteArray {
        val value = requireNotNull(dao.artifact(artifactId)) { "创作成果不存在" }
        return files.read(value.artifact.currentStorageKey)
    }

    suspend fun file(artifactId: String): File {
        val value = requireNotNull(dao.artifact(artifactId)) { "创作成果不存在" }
        return files.file(value.artifact.currentStorageKey)
    }

    private fun CreativeArtifactWithRelations.toDomain(): CreativeArtifactRecord {
        val origin = CreativeArtifactOrigin(
            conversationId = artifact.originConversationId,
            branchId = artifact.originBranchId,
            messageId = artifact.originMessageId,
            toolCallId = artifact.originToolCallId,
        )
        return CreativeArtifactRecord(
            artifact = CreativeArtifact(
                id = artifact.id,
                kind = CreativeArtifactKind.valueOf(artifact.kind),
                title = artifact.title,
                storageKey = artifact.currentStorageKey,
                origin = origin,
                createdAt = artifact.createdAt,
                updatedAt = artifact.updatedAt,
                favorite = artifact.favorite,
                trashedAt = artifact.trashedAt,
            ),
            revisions = revisions.sortedBy { it.revisionNumber }.map { revision ->
                CreativeArtifactRevision(
                    id = revision.id,
                    artifactId = revision.artifactId,
                    number = revision.revisionNumber,
                    storageKey = revision.storageKey,
                    contentHash = revision.contentHash,
                    mimeType = revision.mimeType,
                    sizeBytes = revision.sizeBytes,
                    createdAt = revision.createdAt,
                )
            },
            attachments = attachments.map { it.toDomain() },
            sourcePath = artifact.sourcePath,
        )
    }

    private fun CreativeArtifactAttachment.toEntity() = CreativeArtifactAttachmentEntity(
        artifactId = artifactId,
        ownerKind = owner.kind.name,
        ownerId = owner.id,
        moduleId = moduleId.orEmpty(),
        slot = slot.orEmpty(),
    )

    private fun CreativeArtifactAttachmentEntity.toDomain() = CreativeArtifactAttachment(
        artifactId = artifactId,
        owner = NovexContentAddress(NovexContentKind.valueOf(ownerKind), ownerId),
        moduleId = moduleId.ifBlank { null },
        slot = slot.ifBlank { null },
    )
}

internal fun filterCreativeArtifactRecords(
    records: List<CreativeArtifactRecord>,
    query: CreativeArtifactQuery,
): List<CreativeArtifactRecord> = records.filter { record ->
    val value = record.artifact
    (query.conversationId == null || value.origin.conversationId == query.conversationId) &&
        (query.kinds.isEmpty() || value.kind in query.kinds) &&
        (!query.favoritesOnly || value.favorite) &&
        when {
            query.trashOnly -> value.isTrashed
            query.includeTrashed -> true
            else -> !value.isTrashed
        } &&
        (query.owner == null || record.attachments.any { it.owner == query.owner }) &&
        (query.ownerKinds.isEmpty() || record.attachments.any { it.owner.kind in query.ownerKinds }) &&
        (!query.unattachedOnly || record.attachments.isEmpty())
}

/**
 * Shared deterministic projection used by every Novex content page. When a module has several
 * generated images, the newest live image wins instead of leaving each page to invent a rule.
 */
internal fun selectAttachedModuleImageIds(
    records: List<CreativeArtifactRecord>,
    owner: NovexContentAddress,
): Map<String, String> = records.asSequence()
    .filter { record ->
        !record.artifact.isTrashed &&
            record.artifact.kind in setOf(CreativeArtifactKind.IMAGE, CreativeArtifactKind.MAP)
    }
    .sortedWith(compareBy<CreativeArtifactRecord>({ it.artifact.updatedAt }, { it.artifact.id }))
    .flatMap { record ->
        record.attachments.asSequence()
            .filter { attachment -> attachment.owner == owner && !attachment.moduleId.isNullOrBlank() }
            .map { attachment -> requireNotNull(attachment.moduleId) to record.artifact.id }
    }
    .toMap()
