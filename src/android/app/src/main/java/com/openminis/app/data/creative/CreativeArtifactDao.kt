package com.openminis.app.data.creative

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface CreativeArtifactDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertArtifact(value: CreativeArtifactEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(value: CreativeArtifactRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun attach(value: CreativeArtifactAttachmentEntity): Long

    @Query(
        "DELETE FROM creative_artifact_attachments WHERE artifact_id = :artifactId AND " +
            "owner_kind = :ownerKind AND owner_id = :ownerId AND module_id = :moduleId AND slot = :slot",
    )
    suspend fun detach(
        artifactId: String,
        ownerKind: String,
        ownerId: String,
        moduleId: String,
        slot: String,
    )

    @Transaction
    @Query("SELECT * FROM creative_artifacts WHERE id = :id")
    suspend fun artifact(id: String): CreativeArtifactWithRelations?

    @Transaction
    @Query("SELECT * FROM creative_artifacts ORDER BY updated_at DESC, id ASC")
    suspend fun all(): List<CreativeArtifactWithRelations>

    @Query(
        "SELECT * FROM creative_artifacts WHERE origin_conversation_id = :conversationId AND " +
            "source_path = :sourcePath ORDER BY updated_at DESC LIMIT 1",
    )
    suspend fun artifactBySource(conversationId: String, sourcePath: String): CreativeArtifactEntity?

    @Query("SELECT COALESCE(MAX(revision_number), 0) FROM creative_artifact_revisions WHERE artifact_id = :artifactId")
    suspend fun latestRevisionNumber(artifactId: String): Int

    @Query(
        "UPDATE creative_artifacts SET title = :title, kind = :kind, current_revision_id = :revisionId, " +
            "current_storage_key = :storageKey, updated_at = :updatedAt WHERE id = :artifactId",
    )
    suspend fun updateCurrentRevision(
        artifactId: String,
        title: String,
        kind: String,
        revisionId: String,
        storageKey: String,
        updatedAt: Long,
    )

    @Query("UPDATE creative_artifacts SET favorite = :favorite WHERE id = :artifactId")
    suspend fun setFavorite(artifactId: String, favorite: Boolean)

    @Query("UPDATE creative_artifacts SET trashed_at = :trashedAt WHERE id = :artifactId")
    suspend fun setTrashedAt(artifactId: String, trashedAt: Long?)

    @Query("SELECT COUNT(*) FROM creative_artifact_attachments WHERE artifact_id = :artifactId")
    suspend fun attachmentCount(artifactId: String): Int

    @Query("SELECT storage_key FROM creative_artifact_revisions WHERE artifact_id = :artifactId")
    suspend fun storageKeys(artifactId: String): List<String>

    @Query("DELETE FROM creative_artifacts WHERE id = :artifactId")
    suspend fun deleteArtifactRow(artifactId: String)

    @Query("SELECT COUNT(*) FROM creative_artifact_revisions WHERE storage_key = :storageKey")
    suspend fun storageReferenceCount(storageKey: String): Int

    @Transaction
    suspend fun permanentlyDelete(artifactId: String): List<String> {
        require(attachmentCount(artifactId) == 0) { "创作成果仍被内容引用" }
        val keys = storageKeys(artifactId).distinct()
        deleteArtifactRow(artifactId)
        return keys.filter { storageReferenceCount(it) == 0 }
    }
}
