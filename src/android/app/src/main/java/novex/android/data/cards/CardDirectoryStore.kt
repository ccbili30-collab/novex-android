package novex.android.data.cards

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/*
 * Directory-pointer access for published cards. A row is written in the
 * same transaction as the card's database mutation, so a crash can never
 * leave files on disk the database forgot — or rows pointing at files a
 * crash already took. Row shape: CardTables.kt (CardDirectoryRow).
 */

@Dao
interface CardDirectoryDao {
    @Query("SELECT * FROM novex_card_directories AS d WHERE d.owner_key = :ownerKey")
    suspend fun byOwner(ownerKey: String): CardDirectoryRow?

    @Query("SELECT d.* FROM novex_card_directories AS d")
    suspend fun allOwners(): List<CardDirectoryRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: CardDirectoryRow)

    @Query("DELETE FROM novex_card_directories WHERE owner_key = :ownerKey")
    suspend fun drop(ownerKey: String)
}
