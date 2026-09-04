package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Exact, immutable record of the structured Novex sources selected for one request. */
@Entity(
    tableName = "novex_context_usage_records",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["session_id", "created_at"], name = "index_novex_context_usage_session"),
        Index(value = ["session_id", "request_message_id"], name = "index_novex_context_usage_request"),
    ],
)
data class NovexContextUsageRecordEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "request_message_id") val requestMessageId: String,
    @ColumnInfo(name = "response_message_id") val responseMessageId: String?,
    @ColumnInfo(name = "branch_id") val branchId: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
