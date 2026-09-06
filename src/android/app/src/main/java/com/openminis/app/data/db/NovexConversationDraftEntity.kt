package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Origin is retained after publication and intentionally does not cascade with session deletion. */
@Entity(tableName = "novex_conversation_drafts")
data class NovexConversationDraftEntity(
    @PrimaryKey @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "content_json") val contentJson: String,
)

@Dao
interface NovexConversationDraftDao {
    @Query("SELECT * FROM novex_conversation_drafts WHERE conversation_id = :conversationId")
    suspend fun get(conversationId: String): NovexConversationDraftEntity?

    @Query("SELECT * FROM novex_conversation_drafts")
    suspend fun list(): List<NovexConversationDraftEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(value: NovexConversationDraftEntity)
}
