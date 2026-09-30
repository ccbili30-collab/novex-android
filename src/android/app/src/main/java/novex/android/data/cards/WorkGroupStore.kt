package novex.android.data.cards

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/*
 * Work-group access: the named bundles that turn library targets (worlds,
 * role versions, games, creative artifacts) into one selectable workspace,
 * plus the single-row pointer (id 0) holding which bundle is active — or
 * the literal 'all'. Row shapes live in CardTables.kt.
 */

@Dao
interface WorkGroupDao {
    @Query(
        """
        SELECT grp.* FROM novex_work_groups AS grp
        ORDER BY grp.name ASC, grp.id ASC
        """,
    )
    fun observeGroups(): Flow<List<WorkGroupRow>>

    @Query("SELECT m.* FROM novex_work_group_members AS m")
    fun observeMembers(): Flow<List<WorkGroupMemberRow>>

    @Query("SELECT pick.* FROM novex_work_group_selection AS pick WHERE pick.id = 0")
    fun observeSelection(): Flow<WorkGroupPickRow?>

    @Query("SELECT grp.* FROM novex_work_groups AS grp WHERE grp.id = :groupId")
    suspend fun groupById(groupId: String): WorkGroupRow?

    @Query("SELECT m.* FROM novex_work_group_members AS m WHERE m.group_id = :groupId")
    suspend fun membersOf(groupId: String): List<WorkGroupMemberRow>

    @Insert
    suspend fun insertGroup(group: WorkGroupRow)

    @Query("UPDATE novex_work_groups SET name = :name WHERE id = :groupId")
    suspend fun renameGroup(groupId: String, name: String)

    @Query("UPDATE novex_work_groups SET organization_json = :organizationJson WHERE id = :groupId")
    suspend fun writeOrganization(groupId: String, organizationJson: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun select(pick: WorkGroupPickRow)

    @Insert
    suspend fun insertMembers(members: List<WorkGroupMemberRow>)

    @Query("DELETE FROM novex_work_group_members WHERE group_id = :groupId")
    suspend fun clearMembers(groupId: String)

    @Query("DELETE FROM novex_work_groups WHERE id = :groupId")
    suspend fun dropGroup(groupId: String)

    /** A vanished pick falls back to the whole-library view. */
    @Query("UPDATE novex_work_group_selection SET selection = 'all' WHERE selection = :groupId")
    suspend fun resetSelection(groupId: String)

    /**
     * Existence probe for one member candidate. Creative artifacts count as
     * present only while they are out of the trash; anything else is a miss.
     */
    @Query(
        """
        SELECT CASE :kind
            WHEN 'CREATIVE_ARTIFACT' THEN EXISTS(SELECT 1 FROM creative_artifacts WHERE trashed_at IS NULL AND id = :id)
            WHEN 'INTERACTIVE_FICTION' THEN EXISTS(SELECT 1 FROM interactive_fiction_projects WHERE id = :id)
            WHEN 'CHARACTER_VERSION' THEN EXISTS(SELECT 1 FROM character_versions WHERE id = :id)
            WHEN 'WORLD' THEN EXISTS(SELECT 1 FROM worlds WHERE id = :id)
            ELSE 0
        END
        """,
    )
    suspend fun targetExists(kind: String, id: String): Boolean
}
