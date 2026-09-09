package com.openminis.app.data.db

import androidx.room.*

/** Published directory pointer, committed with the card's database mutation. */
@Entity(tableName = "novex_card_directories")
data class NovexCardDirectoryEntity(
    @PrimaryKey @ColumnInfo(name = "owner_key") val ownerKey: String,
    val directory: String,
    val digest: String,
    @ColumnInfo(name = "content_digest") val contentDigest: String,
)

@Dao
interface NovexCardDirectoryDao {
    @Query("SELECT * FROM novex_card_directories WHERE owner_key = :ownerKey")
    suspend fun get(ownerKey: String): NovexCardDirectoryEntity?
    @Query("SELECT * FROM novex_card_directories")
    suspend fun list(): List<NovexCardDirectoryEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(value: NovexCardDirectoryEntity)
    @Query("DELETE FROM novex_card_directories WHERE owner_key = :ownerKey")
    suspend fun delete(ownerKey: String)
}
