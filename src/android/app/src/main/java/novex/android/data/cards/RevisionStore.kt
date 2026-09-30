package novex.android.data.cards

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/*
 * Revision-chain access: every saved editing session of a world, a role
 * version, or an interactive-fiction project appends one numbered row.
 * Bodies live in the row but are never loaded through the list queries —
 * those substitute an empty string and hydrate on demand through the
 * bounded reader below, so a multi-megabyte body cannot blow a
 * CursorWindow. Row shapes are declared in CardTables.kt.
 */

/**
 * Two @RawQuery primitives shared by every DAO that must read a column of
 * unbounded size. Callers stay inside their read transaction and hydrate
 * row-by-row; the slicing SQL is part of the storage contract (byte slices
 * of the UTF-8 BLOB view — slicing TEXT would truncate embedded NULs and
 * per-slice decoding would corrupt boundary-crossing characters).
 */
interface LargeColumnReader {
    @RawQuery
    suspend fun blobSlice(query: SupportSQLiteQuery): ByteArray?

    @RawQuery
    suspend fun blobLength(query: SupportSQLiteQuery): Long?
}

/** Where a large text column lives: table, column, and its row-key column. */
internal enum class LargeTextField(val table: String, val column: String, val key: String = "id") {
    WORLD_OVERVIEW("worlds", "overview"),
    WORLD_TAGS("worlds", "tags_json"),
    WORLD_SOURCE("worlds", "legacy_snapshot_json"),
    ROLE_PROFILE("character_versions", "profile_json"),
    MODULE_CONTENT("content_modules", "content_json"),
    GAME_SUMMARY("interactive_fiction_projects", "summary"),
    GAME_PLAYER("interactive_fiction_projects", "player_identity"),
    GAME_SOURCE("interactive_fiction_projects", "source_document_json"),
    WORLD_REVISION("novex_world_revisions", "content_json", "world_id"),
    ROLE_REVISION("novex_character_revisions", "content_json", "version_id"),
    GAME_REVISION("novex_game_revisions", "content_json", "project_id"),
}

private const val SLICE_BYTES = 128 * 1024

/**
 * Reads one field value as text through bounded BLOB slices. Returns null
 * when the row is absent; fails loudly when the row mutates mid-read,
 * because a short reassembly would silently corrupt the card body.
 */
internal suspend fun LargeColumnReader.readLargeText(
    field: LargeTextField,
    id: String,
    sequence: Int? = null,
): String? {
    val rowFilter = "WHERE ${field.key} = ?" + (if (sequence == null) "" else " AND sequence = ?")
    val keyArgs: Array<Any> = if (sequence == null) arrayOf(id) else arrayOf(id, sequence!!)

    val total = blobLength(
        SimpleSQLiteQuery("SELECT length(CAST(${field.column} AS BLOB)) FROM ${field.table} $rowFilter", keyArgs),
    ) ?: return null
    require(total in 0..Int.MAX_VALUE.toLong()) { "卡片正文长度超出了分片读取器的能力范围" }

    val slices = ArrayList<ByteArray>(total.toInt() / SLICE_BYTES + 1)
    var consumed = 0L
    while (consumed < total) {
        val slice = blobSlice(
            SimpleSQLiteQuery(
                "SELECT substr(CAST(${field.column} AS BLOB), ?, ?) FROM ${field.table} $rowFilter",
                arrayOf<Any>(consumed + 1, SLICE_BYTES.toLong(), *keyArgs),
            ),
        ) ?: throw IllegalStateException("分片读取期间卡片正文行已被删除")
        check(slice.isNotEmpty() && consumed + slice.size <= total) { "分片读取期间卡片正文行发生了变化" }
        slices.add(slice)
        consumed += slice.size
    }

    val out = ByteArray(total.toInt())
    var cursor = 0
    for (slice in slices) {
        slice.copyInto(out, cursor)
        cursor += slice.size
    }
    return out.toString(Charsets.UTF_8)
}

@Dao
interface CardRevisionDao : LargeColumnReader {
    @Query(
        "SELECT w.world_id, w.sequence, w.saved_at, '' AS content_json FROM novex_world_revisions AS w " +
            "WHERE w.world_id = :worldId ORDER BY w.sequence ASC",
    )
    suspend fun worldRevisionStubs(worldId: String): List<WorldRevisionRow>

    @Transaction
    suspend fun worldRevisions(worldId: String): List<WorldRevisionRow> =
        worldRevisionStubs(worldId).map { stub ->
            stub.copy(contentJson = requireNotNull(readLargeText(LargeTextField.WORLD_REVISION, stub.worldId, stub.sequence)))
        }

    @Query(
        "SELECT w.world_id, w.sequence, w.saved_at, '' AS content_json FROM novex_world_revisions AS w " +
            "WHERE w.world_id = :worldId ORDER BY w.sequence DESC LIMIT 1",
    )
    suspend fun newestWorldRevisionStub(worldId: String): WorldRevisionRow?

    @Transaction
    suspend fun newestWorldRevision(worldId: String): WorldRevisionRow? =
        newestWorldRevisionStub(worldId)?.let { stub ->
            stub.copy(contentJson = requireNotNull(readLargeText(LargeTextField.WORLD_REVISION, stub.worldId, stub.sequence)))
        }

    @Query(
        "SELECT g.project_id, g.sequence, g.saved_at, '' AS content_json FROM novex_game_revisions AS g " +
            "WHERE g.project_id = :projectId ORDER BY g.sequence ASC",
    )
    suspend fun gameRevisionStubs(projectId: String): List<GameRevisionRow>

    @Transaction
    suspend fun gameRevisions(projectId: String): List<GameRevisionRow> =
        gameRevisionStubs(projectId).map { stub ->
            stub.copy(contentJson = requireNotNull(readLargeText(LargeTextField.GAME_REVISION, stub.projectId, stub.sequence)))
        }

    @Query(
        "SELECT g.project_id, g.sequence, g.saved_at, '' AS content_json FROM novex_game_revisions AS g " +
            "WHERE g.project_id = :projectId ORDER BY g.sequence DESC LIMIT 1",
    )
    suspend fun newestGameRevisionStub(projectId: String): GameRevisionRow?

    @Transaction
    suspend fun newestGameRevision(projectId: String): GameRevisionRow? =
        newestGameRevisionStub(projectId)?.let { stub ->
            stub.copy(contentJson = requireNotNull(readLargeText(LargeTextField.GAME_REVISION, stub.projectId, stub.sequence)))
        }

    @Insert
    suspend fun appendWorldRevision(row: WorldRevisionRow)

    @Insert
    suspend fun appendGameRevision(row: GameRevisionRow)
}

@Dao
interface RoleRevisionDao : LargeColumnReader {
    @Query(
        "SELECT r.version_id, r.sequence, r.saved_at, '' AS content_json FROM novex_character_revisions AS r " +
            "WHERE r.version_id = :versionId ORDER BY r.sequence ASC",
    )
    suspend fun revisionStubs(versionId: String): List<RoleRevisionRow>

    @Transaction
    suspend fun revisions(versionId: String): List<RoleRevisionRow> =
        revisionStubs(versionId).map { stub ->
            stub.copy(contentJson = requireNotNull(readLargeText(LargeTextField.ROLE_REVISION, stub.versionId, stub.sequence)))
        }

    @Query(
        "SELECT r.version_id, r.sequence, r.saved_at, '' AS content_json FROM novex_character_revisions AS r " +
            "WHERE r.version_id = :versionId ORDER BY r.sequence DESC LIMIT 1",
    )
    suspend fun newestRevisionStub(versionId: String): RoleRevisionRow?

    @Transaction
    suspend fun newestRevision(versionId: String): RoleRevisionRow? =
        newestRevisionStub(versionId)?.let { stub ->
            stub.copy(contentJson = requireNotNull(readLargeText(LargeTextField.ROLE_REVISION, stub.versionId, stub.sequence)))
        }

    @Insert
    suspend fun appendRevision(row: RoleRevisionRow)
}
