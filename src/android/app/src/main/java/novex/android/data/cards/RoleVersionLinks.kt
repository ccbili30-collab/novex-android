package novex.android.data.cards

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/*
 * Access to the role-version relation edges. Deletion cascades from the
 * source version only; character and target ends are deliberately loose so
 * removing one version never silently edits another version's history —
 * a relation whose target is gone renders as a broken edge. Rows are
 * declared in CardTables.kt.
 */

@Dao
interface RoleVersionLinkDao {
    @Query("SELECT * FROM novex_character_version_relations AS rel WHERE rel.id = :linkId")
    suspend fun linkById(linkId: String): RoleVersionLinkRow?

    @Query(
        """
        SELECT rel.* FROM novex_character_version_relations AS rel
        WHERE rel.character_id = :characterId
        ORDER BY rel.id ASC
        """,
    )
    suspend fun linksOfCharacter(characterId: String): List<RoleVersionLinkRow>

    /** A version participates as source or as target; one read covers both. */
    @Query(
        """
        SELECT rel.* FROM novex_character_version_relations AS rel
        WHERE rel.target_version_id = :versionId OR rel.source_version_id = :versionId
        ORDER BY rel.id ASC
        """,
    )
    suspend fun linksTouchingVersion(versionId: String): List<RoleVersionLinkRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLink(row: RoleVersionLinkRow)

    @Query("DELETE FROM novex_character_version_relations WHERE id = :linkId")
    suspend fun dropLink(linkId: String)
}
