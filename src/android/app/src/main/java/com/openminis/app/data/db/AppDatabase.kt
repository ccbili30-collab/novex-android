package com.openminis.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.openminis.app.data.character.CharacterCatalogConverters
import com.openminis.app.data.character.CharacterCatalogDao
import com.openminis.app.data.character.CharacterEntity
import com.openminis.app.data.character.CharacterVersionEntity
import com.openminis.app.data.character.CatalogMigrationStateEntity
import com.openminis.app.data.character.ContentModuleConverters
import com.openminis.app.data.character.ContentModuleDao
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleReferenceEntity
import com.openminis.app.data.character.MediaAssetConverters
import com.openminis.app.data.character.MediaAssetDao
import com.openminis.app.data.character.MediaAssetEntity
import com.openminis.app.data.character.MediaAssetReferenceEntity
import com.openminis.app.data.character.WorldCharacterVersionEntity
import com.openminis.app.data.character.WorldEntity
import com.openminis.app.data.interactivefiction.InteractiveFictionConverters
import com.openminis.app.data.interactivefiction.InteractiveFictionDao
import com.openminis.app.data.interactivefiction.InteractiveFictionProjectEntity

@Database(
    entities = [
        ChatSessionEntity::class,
        MessageEntity::class,
        CompactMarkerEntity::class,
        WebAppShortcutEntity::class,
        FolderEntity::class,
        WorldEntity::class,
        CharacterEntity::class,
        CharacterVersionEntity::class,
        WorldCharacterVersionEntity::class,
        CatalogMigrationStateEntity::class,
        ContentModuleEntity::class,
        ContentModuleReferenceEntity::class,
        MediaAssetEntity::class,
        MediaAssetReferenceEntity::class,
        InteractiveFictionProjectEntity::class,
    ],
    version = 23,
    exportSchema = false,
)
@TypeConverters(
    CharacterCatalogConverters::class,
    ContentModuleConverters::class,
    MediaAssetConverters::class,
    InteractiveFictionConverters::class,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun webAppShortcutDao(): WebAppShortcutDao
    abstract fun characterCatalogDao(): CharacterCatalogDao
    abstract fun contentModuleDao(): ContentModuleDao
    abstract fun mediaAssetDao(): MediaAssetDao
    abstract fun interactiveFictionDao(): InteractiveFictionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN last_message TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN model_binding TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoning_content TEXT")
            }
        }

        /**
         * compact_markers: add Phase-A id-first boundary columns. The legacy
         * sort_order columns stay for backfill; when both are present the
         * id-first fields win on lookup (see ChatDao.latestCompactMarker).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE compact_markers ADD COLUMN first_kept_message_id TEXT")
                db.execSQL("ALTER TABLE compact_markers ADD COLUMN last_compacted_message_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_compact_markers_first_kept_message_id ON compact_markers(first_kept_message_id)")
            }
        }

        /**
         * T239: per-session thinking-mode override. Nullable so existing
         * sessions transparently keep "unset" semantics; only sessions where
         * the user explicitly chooses a level start storing a non-null value.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN thinking_override TEXT")
            }
        }

        /**
         * T-pwa-1: pwa_shortcuts table backs the home-screen PWA pinning
         * flow. Pure additive migration — no existing entity is modified
         * and no data is rewritten.
         *
         * Superseded by MIGRATION_8_9 below (Pwa → WebApp rename); kept
         * here so users who already migrated from <=6 land on a
         * consistent state before the rename runs.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pwa_shortcuts (
                        id TEXT NOT NULL PRIMARY KEY,
                        html_path TEXT NOT NULL,
                        path_scope TEXT NOT NULL,
                        scope_context TEXT,
                        title TEXT NOT NULL,
                        icon_ref TEXT NOT NULL,
                        icon_cache_path TEXT,
                        created_at INTEGER NOT NULL,
                        source_session_id TEXT
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * compact_markers: add `version` column for marker schema versioning.
         * Mirrors iOS Phase v2 — version=1 = legacy multi-field model,
         * version=2 = simplified id-only anchor model. Existing rows default
         * to 1 so legacy resolution code keeps running for them.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE compact_markers ADD COLUMN version INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * Pwa → WebApp rename: copy every row from `pwa_shortcuts` into a
         * new `webapp_shortcuts` table with identical schema, then drop
         * the old table. Row contents (UUIDs, html paths, icon refs) are
         * preserved verbatim — only the table name changes — so existing
         * in-app shortcut lists keep showing the same entries.
         *
         * Note: pinned launcher icons created before this rename still
         * carry the old `ACTION_OPEN_PWA` intent action and will be dead
         * after the upgrade (manifest no longer registers it). The user
         * has to re-pin from inside the app. Per
         * `feedback_no_destructive_git` we do NOT silently delete data —
         * the DB row stays, only the launcher-side icon dies.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS webapp_shortcuts (
                        id TEXT NOT NULL PRIMARY KEY,
                        html_path TEXT NOT NULL,
                        path_scope TEXT NOT NULL,
                        scope_context TEXT,
                        title TEXT NOT NULL,
                        icon_ref TEXT NOT NULL,
                        icon_cache_path TEXT,
                        created_at INTEGER NOT NULL,
                        source_session_id TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO webapp_shortcuts (
                        id, html_path, path_scope, scope_context, title,
                        icon_ref, icon_cache_path, created_at, source_session_id
                    )
                    SELECT
                        id, html_path, path_scope, scope_context, title,
                        icon_ref, icon_cache_path, created_at, source_session_id
                    FROM pwa_shortcuts
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE IF EXISTS pwa_shortcuts")
            }
        }

        /**
         * [T-error-persist-android] messages.error_info — persist the terminal
         * error sticker on an assistant turn so the inline error survives a
         * session reload (mirrors iOS messages.error_info). Pure additive,
         * nullable column; existing rows read back NULL (= no error). No data
         * rewrite.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN error_info TEXT")
            }
        }

        /**
         * [T-android-session-grouping] Session groups. Adds the `folders` table
         * and `sessions.folder_id`.
         *
         * Purely additive: existing sessions read back `folder_id = NULL`
         * (= ungrouped), which is exactly the pre-migration behaviour, so no
         * data is rewritten and a downgrade loses only the grouping.
         *
         * `folder_id` carries NO foreign key on purpose — an id pointing at a
         * group that is not present locally must render as ungrouped rather
         * than fail a constraint (see ChatSessionEntity.folderId). The index is
         * plain and non-unique: many sessions share one group.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS folders (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        icon TEXT,
                        color TEXT,
                        origin TEXT NOT NULL DEFAULT 'manual',
                        sort_index INTEGER NOT NULL DEFAULT 0,
                        pinned_at INTEGER,
                        description TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("ALTER TABLE sessions ADD COLUMN folder_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_folder_id ON sessions(folder_id)")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN character_id TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN character_snapshot_json TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN persona_id TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN persona_snapshot_json TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN chat_background_path TEXT")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN world_snapshot_json TEXT")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN conversation_prompt TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN image_style_prompt TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN role_presentation_enabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN assistant_display_name TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN assistant_avatar_path TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN player_display_name TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN player_avatar_path TEXT")
                db.execSQL(
                    "UPDATE sessions SET role_presentation_enabled = 1 " +
                        "WHERE character_snapshot_json IS NOT NULL AND TRIM(character_snapshot_json) != ''",
                )
            }
        }

        /**
         * Retained conversation branches. Existing histories are backfilled as
         * one active parent/child chain without deleting or rewriting content.
         * The global sort order remains append-only and now orders siblings.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN parent_message_id TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN active_child_id TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN active_root_message_id TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN active_leaf_message_id TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_session_id_parent_message_id " +
                        "ON messages(session_id, parent_message_id)",
                )

                // Stable legacy order is sort_order, then created_at and id for
                // the rare imported history containing duplicate sort values.
                db.execSQL(
                    """
                    UPDATE messages
                    SET parent_message_id = (
                        SELECT previous.id
                        FROM messages AS previous
                        WHERE previous.session_id = messages.session_id
                          AND (
                            previous.sort_order < messages.sort_order OR
                            (previous.sort_order = messages.sort_order AND previous.created_at < messages.created_at) OR
                            (previous.sort_order = messages.sort_order AND previous.created_at = messages.created_at AND previous.id < messages.id)
                          )
                        ORDER BY previous.sort_order DESC, previous.created_at DESC, previous.id DESC
                        LIMIT 1
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE messages
                    SET active_child_id = (
                        SELECT next.id
                        FROM messages AS next
                        WHERE next.session_id = messages.session_id
                          AND (
                            next.sort_order > messages.sort_order OR
                            (next.sort_order = messages.sort_order AND next.created_at > messages.created_at) OR
                            (next.sort_order = messages.sort_order AND next.created_at = messages.created_at AND next.id > messages.id)
                          )
                        ORDER BY next.sort_order ASC, next.created_at ASC, next.id ASC
                        LIMIT 1
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE sessions
                    SET active_root_message_id = (
                        SELECT root.id FROM messages AS root
                        WHERE root.session_id = sessions.id
                        ORDER BY root.sort_order ASC, root.created_at ASC, root.id ASC
                        LIMIT 1
                    ),
                    active_leaf_message_id = (
                        SELECT leaf.id FROM messages AS leaf
                        WHERE leaf.session_id = sessions.id
                        ORDER BY leaf.sort_order DESC, leaf.created_at DESC, leaf.id DESC
                        LIMIT 1
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Reusable character versions and their many-to-many world membership.
         * This migration intentionally creates empty tables only; importing the
         * legacy SharedPreferences card library is the next checkpoint.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS worlds (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        overview TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS characters (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        original_version_id TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_characters_original_version_id " +
                        "ON characters(original_version_id)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS character_versions (
                        id TEXT NOT NULL PRIMARY KEY,
                        character_id TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        label TEXT NOT NULL,
                        profile_json TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        FOREIGN KEY (character_id) REFERENCES characters(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_character_versions_character_id " +
                        "ON character_versions(character_id)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_character_versions_character_id_kind " +
                        "ON character_versions(character_id, kind)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS world_character_versions (
                        world_id TEXT NOT NULL,
                        character_version_id TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        PRIMARY KEY (world_id, character_version_id),
                        FOREIGN KEY (world_id) REFERENCES worlds(id) ON DELETE CASCADE,
                        FOREIGN KEY (character_version_id) REFERENCES character_versions(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_world_character_versions_character_version_id " +
                        "ON world_character_versions(character_version_id)",
                )
            }
        }

        /** Import bookkeeping and non-destructive catalog references for legacy sessions. */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE worlds ADD COLUMN legacy_snapshot_json TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN world_id TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN character_version_id TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS catalog_migration_state (
                        id TEXT NOT NULL PRIMARY KEY,
                        completed_at INTEGER NOT NULL,
                        world_count INTEGER NOT NULL,
                        character_count INTEGER NOT NULL,
                        membership_count INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /** One owner-agnostic module schema shared by worlds and character versions. */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS content_modules (
                        id TEXT NOT NULL PRIMARY KEY,
                        owner_type TEXT NOT NULL,
                        owner_id TEXT NOT NULL,
                        type TEXT NOT NULL,
                        name TEXT NOT NULL,
                        content_json TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        collapsed INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_content_modules_owner_order " +
                        "ON content_modules(owner_type, owner_id, position)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS content_module_references (
                        source_module_id TEXT NOT NULL,
                        target_type TEXT NOT NULL,
                        target_id TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        PRIMARY KEY (source_module_id, target_type, target_id),
                        FOREIGN KEY (source_module_id) REFERENCES content_modules(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_content_module_references_target " +
                        "ON content_module_references(target_type, target_id)",
                )
            }
        }

        /** Shared, reference-counted image assets for worlds and character versions. */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS media_assets (
                        id TEXT NOT NULL PRIMARY KEY,
                        managed_path TEXT NOT NULL,
                        mime_type TEXT NOT NULL,
                        content_hash TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_media_assets_managed_path " +
                        "ON media_assets(managed_path)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_media_assets_content_hash " +
                        "ON media_assets(content_hash)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS media_asset_references (
                        owner_type TEXT NOT NULL,
                        owner_id TEXT NOT NULL,
                        slot TEXT NOT NULL,
                        asset_id TEXT NOT NULL,
                        PRIMARY KEY (owner_type, owner_id, slot),
                        FOREIGN KEY (asset_id) REFERENCES media_assets(id) ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_media_asset_references_asset_id " +
                        "ON media_asset_references(asset_id)",
                )
            }
        }

        /** World tags are a fixed base field; optional settings remain modules. */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE worlds ADD COLUMN tags_json TEXT NOT NULL DEFAULT '[]'")
            }
        }

        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE character_versions ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE character_versions
                    SET position = CASE
                        WHEN character_versions.kind = 'ORIGINAL' THEN 0
                        ELSE 1 + (
                            SELECT COUNT(*)
                            FROM character_versions AS earlier
                            WHERE earlier.character_id = character_versions.character_id
                              AND earlier.kind = 'VARIANT'
                              AND (
                                  earlier.created_at < character_versions.created_at
                                  OR (
                                      earlier.created_at = character_versions.created_at
                                      AND earlier.id < character_versions.id
                                  )
                              )
                        )
                    END
                    """.trimIndent(),
                )
            }
        }

        /** Adds reusable interactive-fiction projects without rewriting existing product data. */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS interactive_fiction_projects (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        launch_mode TEXT NOT NULL,
                        player_identity TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        source_id TEXT,
                        source_document_json TEXT
                    )
                    """.trimIndent(),
                )
            }
        }

        /** Stores the unified Novex conversation configuration without rewriting legacy columns. */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN novex_configuration_json TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // sessions: add iOS-parity columns
                db.execSQL("ALTER TABLE sessions ADD COLUMN source TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN memory_enabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE sessions ADD COLUMN pinned_at INTEGER")
                db.execSQL("ALTER TABLE sessions ADD COLUMN edit_count INTEGER NOT NULL DEFAULT 0")

                // messages: add iOS-parity columns
                db.execSQL("ALTER TABLE messages ADD COLUMN stream_interrupt_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN updated_at INTEGER")

                // compact_markers: new table mirroring iOS
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS compact_markers (
                        id TEXT NOT NULL PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        first_kept_sort_order INTEGER NOT NULL,
                        compacted_count INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        ui_boundary_sort_order INTEGER,
                        boundary_message_id TEXT,
                        FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_compact_markers_session_id ON compact_markers(session_id)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "minis.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
