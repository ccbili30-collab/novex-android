package com.openminis.app.data.interactivefiction

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface InteractiveFictionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(project: InteractiveFictionProjectEntity)

    @Update
    suspend fun update(project: InteractiveFictionProjectEntity)

    @Query("SELECT * FROM interactive_fiction_projects WHERE id = :id")
    suspend fun project(id: String): InteractiveFictionProjectEntity?

    @Query("SELECT * FROM interactive_fiction_projects ORDER BY updated_at DESC, id ASC")
    suspend fun list(): List<InteractiveFictionProjectEntity>

    @Query("DELETE FROM interactive_fiction_projects WHERE id = :id")
    suspend fun delete(id: String)
}
