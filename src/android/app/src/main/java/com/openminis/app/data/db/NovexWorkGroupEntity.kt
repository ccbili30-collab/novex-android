package com.openminis.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "novex_work_groups")
data class NovexWorkGroupEntity(@PrimaryKey val id: String, val name: String)

@Entity(tableName = "novex_work_group_members", primaryKeys = ["group_id", "kind", "target_id"], foreignKeys = [
    ForeignKey(entity = NovexWorkGroupEntity::class, parentColumns = ["id"], childColumns = ["group_id"], onDelete = ForeignKey.CASCADE),
])
data class NovexWorkGroupMemberEntity(@ColumnInfo(name = "group_id") val groupId: String,
    val kind: String, @ColumnInfo(name = "target_id") val targetId: String)

@Entity(tableName = "novex_work_group_selection")
data class NovexWorkGroupSelectionEntity(@PrimaryKey val id: Int = 0, val selection: String)

@Dao
interface NovexWorkGroupDao {
    @Query("SELECT * FROM novex_work_groups ORDER BY name, id")
    fun observeGroups(): Flow<List<NovexWorkGroupEntity>>
    @Query("SELECT * FROM novex_work_group_members")
    fun observeMembers(): Flow<List<NovexWorkGroupMemberEntity>>
    @Query("SELECT * FROM novex_work_group_selection WHERE id = 0")
    fun observeSelection(): Flow<NovexWorkGroupSelectionEntity?>
    @Query("SELECT * FROM novex_work_groups WHERE id = :id")
    suspend fun find(id: String): NovexWorkGroupEntity?
    @Query("SELECT * FROM novex_work_group_members WHERE group_id = :id")
    suspend fun members(id: String): List<NovexWorkGroupMemberEntity>
    @Insert suspend fun insert(group: NovexWorkGroupEntity)
    @Query("UPDATE novex_work_groups SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun select(selection: NovexWorkGroupSelectionEntity)
    @Insert suspend fun insertMembers(members: List<NovexWorkGroupMemberEntity>)
    @Query("DELETE FROM novex_work_group_members WHERE group_id = :id")
    suspend fun clearMembers(id: String)
    @Query("DELETE FROM novex_work_groups WHERE id = :id")
    suspend fun deleteGroup(id: String)
    @Query("UPDATE novex_work_group_selection SET selection = 'all' WHERE selection = :id")
    suspend fun clearSelection(id: String)
    @Query("SELECT CASE WHEN :kind = 'WORLD' THEN EXISTS(SELECT 1 FROM worlds WHERE id = :id) WHEN :kind = 'CHARACTER_VERSION' THEN EXISTS(SELECT 1 FROM character_versions WHERE id = :id) WHEN :kind = 'INTERACTIVE_FICTION' THEN EXISTS(SELECT 1 FROM interactive_fiction_projects WHERE id = :id) ELSE 0 END")
    suspend fun targetExists(kind: String, id: String): Boolean
}
