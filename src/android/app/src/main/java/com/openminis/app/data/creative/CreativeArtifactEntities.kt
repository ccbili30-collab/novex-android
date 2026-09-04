package com.openminis.app.data.creative

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "creative_artifacts",
    indices = [
        Index(value = ["origin_conversation_id", "updated_at"], name = "index_creative_artifacts_origin"),
        Index(value = ["origin_conversation_id", "source_path"], name = "index_creative_artifacts_source"),
        Index(value = ["trashed_at", "updated_at"], name = "index_creative_artifacts_trash"),
    ],
)
data class CreativeArtifactEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    @ColumnInfo(name = "origin_conversation_id") val originConversationId: String,
    @ColumnInfo(name = "origin_branch_id") val originBranchId: String,
    @ColumnInfo(name = "origin_message_id") val originMessageId: String?,
    @ColumnInfo(name = "origin_tool_call_id") val originToolCallId: String?,
    @ColumnInfo(name = "source_path") val sourcePath: String?,
    @ColumnInfo(name = "current_revision_id") val currentRevisionId: String,
    @ColumnInfo(name = "current_storage_key") val currentStorageKey: String,
    val favorite: Boolean,
    @ColumnInfo(name = "trashed_at") val trashedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "creative_artifact_revisions",
    foreignKeys = [
        ForeignKey(
            entity = CreativeArtifactEntity::class,
            parentColumns = ["id"],
            childColumns = ["artifact_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["artifact_id", "revision_number"], unique = true, name = "index_artifact_revision_number"),
        Index(value = ["storage_key"], name = "index_artifact_revision_storage"),
        Index(value = ["content_hash"], name = "index_artifact_revision_hash"),
    ],
)
data class CreativeArtifactRevisionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "artifact_id") val artifactId: String,
    @ColumnInfo(name = "revision_number") val revisionNumber: Int,
    @ColumnInfo(name = "storage_key") val storageKey: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "creative_artifact_attachments",
    primaryKeys = ["artifact_id", "owner_kind", "owner_id", "module_id", "slot"],
    foreignKeys = [
        ForeignKey(
            entity = CreativeArtifactEntity::class,
            parentColumns = ["id"],
            childColumns = ["artifact_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["owner_kind", "owner_id"], name = "index_artifact_attachment_owner"),
        Index(value = ["artifact_id"], name = "index_artifact_attachment_artifact"),
    ],
)
data class CreativeArtifactAttachmentEntity(
    @ColumnInfo(name = "artifact_id") val artifactId: String,
    @ColumnInfo(name = "owner_kind") val ownerKind: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    @ColumnInfo(name = "module_id") val moduleId: String = "",
    val slot: String = "",
)

data class CreativeArtifactWithRelations(
    @androidx.room.Embedded val artifact: CreativeArtifactEntity,
    @androidx.room.Relation(
        parentColumn = "id",
        entityColumn = "artifact_id",
    )
    val revisions: List<CreativeArtifactRevisionEntity>,
    @androidx.room.Relation(
        parentColumn = "id",
        entityColumn = "artifact_id",
    )
    val attachments: List<CreativeArtifactAttachmentEntity>,
)
