package com.openminis.app.data.db

import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/** Shared by card, module and revision DAOs. Callers hydrate within their read transaction.
 * SQLite can store a large value that Android cannot put in one CursorWindow row. Read bounded
 * byte slices, then decode once: slicing SQL TEXT would truncate embedded NULs, and decoding
 * each byte slice separately would corrupt Unicode characters crossing a slice boundary.
 * No schema change, marker strings, truncation or device-specific window enlargement is needed.
 */
interface NovexCardTextReader {
    @RawQuery
    suspend fun cardTextChunk(query: SupportSQLiteQuery): ByteArray?

    @RawQuery
    suspend fun cardTextLength(query: SupportSQLiteQuery): Long?
}

internal enum class NovexCardTextField(val table: String, val column: String, val key: String = "id") {
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

internal suspend fun NovexCardTextReader.readCardText(
    field: NovexCardTextField,
    id: String,
    sequence: Int? = null,
): String? {
    val chunkSize = 128 * 1024
    val predicate = "WHERE ${field.key} = ?" + if (sequence == null) "" else " AND sequence = ?"
    val keys = if (sequence == null) arrayOf<Any>(id) else arrayOf<Any>(id, sequence)
    val size = cardTextLength(SimpleSQLiteQuery(
        "SELECT length(CAST(${field.column} AS BLOB)) FROM ${field.table} $predicate", keys,
    )) ?: return null
    require(size in 0..Int.MAX_VALUE.toLong()) { "卡片正文超过当前文本读取器可寻址的大小" }
    // One exact allocation avoids ByteArrayOutputStream's doubling and final full-sized copy.
    val bytes = ByteArray(size.toInt())
    var offset = 0
    while (offset < bytes.size) {
        val query = SimpleSQLiteQuery(
            "SELECT substr(CAST(${field.column} AS BLOB), ?, ?) FROM ${field.table} $predicate",
            if (sequence == null) arrayOf<Any>(offset + 1, chunkSize, id)
            else arrayOf<Any>(offset + 1, chunkSize, id, sequence),
        )
        val chunk = requireNotNull(cardTextChunk(query)) { "读取中的卡片正文已不存在" }
        check(chunk.isNotEmpty() && chunk.size <= bytes.size - offset) { "读取中的卡片正文发生变化" }
        chunk.copyInto(bytes, offset)
        offset += chunk.size
    }
    return bytes.toString(Charsets.UTF_8)
}
