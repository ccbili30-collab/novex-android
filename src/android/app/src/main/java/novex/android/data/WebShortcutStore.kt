package novex.android.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/*
 * Pinned WebApp shortcuts: one row per home-screen icon, mapping the icon
 * back to a stored HTML file plus the scope information needed to resolve
 * the path at launch. scope_context discriminates within a path scope
 * (session id, mount key, or null for the shared area); icon_ref names the
 * icon source ("preset:<n>", "file:<path>" or "html").
 */

/** One row of `webapp_shortcuts`. */
@Entity(tableName = "webapp_shortcuts")
data class WebShortcutRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "html_path") val htmlPath: String,
    @ColumnInfo(name = "path_scope") val pathScope: String,
    @ColumnInfo(name = "scope_context") val scopeContext: String?,
    val title: String,
    @ColumnInfo(name = "icon_ref") val iconRef: String,
    @ColumnInfo(name = "icon_cache_path") val iconCachePath: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "source_session_id") val sourceSessionId: String?,
)

@Dao
interface WebShortcutDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: WebShortcutRow)

    @Update
    suspend fun update(row: WebShortcutRow)

    @Delete
    suspend fun remove(row: WebShortcutRow)

    @Query("DELETE FROM webapp_shortcuts WHERE webapp_shortcuts.id = :shortcutId")
    suspend fun removeById(shortcutId: String)

    @Query("SELECT webapp_shortcuts.* FROM webapp_shortcuts WHERE webapp_shortcuts.id = :shortcutId LIMIT 1")
    suspend fun byId(shortcutId: String): WebShortcutRow?

    @Query("SELECT webapp_shortcuts.* FROM webapp_shortcuts ORDER BY webapp_shortcuts.created_at DESC")
    suspend fun all(): List<WebShortcutRow>
}
