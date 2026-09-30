package novex.android.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/*
 * Schema history of the main database, version 1 → 39.
 *
 * The SQL inside each step is the persisted-format contract and is kept
 * verbatim; what is organized here is only the scaffolding around it: one
 * shared column-listing helper serves every "add column if the build that
 * ran in between already added it" guard, and each step is registered
 * through [step] so the wiring stays uniform.
 */

private fun columnsOf(db: SupportSQLiteDatabase, table: String): Set<String> =
    db.query("PRAGMA table_info($table)").use { cursor ->
        val nameColumn = cursor.getColumnIndexOrThrow("name")
        buildSet {
            while (cursor.moveToNext()) add(cursor.getString(nameColumn))
        }
    }

/** ALTERs only when the column is genuinely absent (idempotent across re-entry). */
private fun SupportSQLiteDatabase.addColumnUnlessPresent(table: String, column: String, ddl: String) {
    if (column !in columnsOf(this, table)) execSQL(ddl)
}

internal fun step(from: Int, to: Int, body: SupportSQLiteDatabase.() -> Unit): Migration =
    object : Migration(from, to) {
        override fun migrate(db: SupportSQLiteDatabase) = db.body()
    }

/** sessions: first parity wave with the iOS store. */
val MIGRATION_1_2 = step(1, 2) {
    execSQL("ALTER TABLE sessions ADD COLUMN last_message TEXT")
    execSQL("ALTER TABLE sessions ADD COLUMN model_binding TEXT")
}

val MIGRATION_2_3 = step(2, 3) {
    execSQL("ALTER TABLE messages ADD COLUMN reasoning_content TEXT")
}

/** sessions + messages parity columns, and the compact-marker table itself. */
val MIGRATION_3_4 = step(3, 4) {
    execSQL("ALTER TABLE sessions ADD COLUMN source TEXT")
    execSQL("ALTER TABLE sessions ADD COLUMN memory_enabled INTEGER NOT NULL DEFAULT 1")
    execSQL("ALTER TABLE sessions ADD COLUMN pinned_at INTEGER")
    execSQL("ALTER TABLE sessions ADD COLUMN edit_count INTEGER NOT NULL DEFAULT 0")
    execSQL("ALTER TABLE messages ADD COLUMN stream_interrupt_count INTEGER NOT NULL DEFAULT 0")
    execSQL("ALTER TABLE messages ADD COLUMN updated_at INTEGER")
    execSQL(
        """
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
        """.trimIndent(),
    )
    execSQL("CREATE INDEX IF NOT EXISTS index_compact_markers_session_id ON compact_markers(session_id)")
}

/** compact_markers: Phase-A id-first boundary columns; legacy sort fields stay for backfill. */
val MIGRATION_4_5 = step(4, 5) {
    execSQL("ALTER TABLE compact_markers ADD COLUMN first_kept_message_id TEXT")
    execSQL("ALTER TABLE compact_markers ADD COLUMN last_compacted_message_id TEXT")
    execSQL("CREATE INDEX IF NOT EXISTS index_compact_markers_first_kept_message_id ON compact_markers(first_kept_message_id)")
}

/** T239 per-session thinking override; null keeps the unset semantics. */
val MIGRATION_5_6 = step(5, 6) {
    execSQL("ALTER TABLE sessions ADD COLUMN thinking_override TEXT")
}

/**
 * pwa_shortcuts for the home-screen pinning flow. Superseded by the rename
 * in step 8→9 but still needed so installs that paused on ≤6 converge.
 */
val MIGRATION_6_7 = step(6, 7) {
    execSQL(
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
        """.trimIndent(),
    )
}

/** Marker schema versioning: 1 = legacy multi-field, 2 = id-only anchor model. */
val MIGRATION_7_8 = step(7, 8) {
    execSQL("ALTER TABLE compact_markers ADD COLUMN version INTEGER NOT NULL DEFAULT 1")
}

/**
 * Pwa → WebApp rename: rows are copied into an identically shaped
 * webapp_shortcuts table and the old table dropped. Launcher icons pinned
 * before the rename keep their DB rows (re-pin is a user action), per the
 * no-silent-data-loss rule.
 */
val MIGRATION_8_9 = step(8, 9) {
    execSQL(
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
        """.trimIndent(),
    )
    execSQL(
        """
        INSERT INTO webapp_shortcuts (
            id, html_path, path_scope, scope_context, title,
            icon_ref, icon_cache_path, created_at, source_session_id
        )
        SELECT
            id, html_path, path_scope, scope_context, title,
            icon_ref, icon_cache_path, created_at, source_session_id
        FROM pwa_shortcuts
        """.trimIndent(),
    )
    execSQL("DROP TABLE IF EXISTS pwa_shortcuts")
}

/** messages.error_info so the terminal error sticker survives a reload. */
val MIGRATION_9_10 = step(9, 10) {
    execSQL("ALTER TABLE messages ADD COLUMN error_info TEXT")
}

/** Session groups: the folders table plus a deliberately un-indexed-FK folder_id. */
val MIGRATION_10_11 = step(10, 11) {
    execSQL(
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
    execSQL("ALTER TABLE sessions ADD COLUMN folder_id TEXT")
    execSQL("CREATE INDEX IF NOT EXISTS index_sessions_folder_id ON sessions(folder_id)")
}

val MIGRATION_11_12 = step(11, 12) {
    execSQL("ALTER TABLE sessions ADD COLUMN character_id TEXT")
    execSQL("ALTER TABLE sessions ADD COLUMN character_snapshot_json TEXT")
    execSQL("ALTER TABLE sessions ADD COLUMN persona_id TEXT")
    execSQL("ALTER TABLE sessions ADD COLUMN persona_snapshot_json TEXT")
    execSQL("ALTER TABLE sessions ADD COLUMN chat_background_path TEXT")
}

val MIGRATION_12_13 = step(12, 13) {
    execSQL("ALTER TABLE sessions ADD COLUMN world_snapshot_json TEXT")
}

val MIGRATION_13_14 = step(13, 14) {
    execSQL("ALTER TABLE sessions ADD COLUMN conversation_prompt TEXT")
    execSQL("ALTER TABLE sessions ADD COLUMN image_style_prompt TEXT")
    execSQL("ALTER TABLE sessions ADD COLUMN role_presentation_enabled INTEGER NOT NULL DEFAULT 0")
    execSQL("ALTER TABLE sessions ADD COLUMN assistant_display_name TEXT")
    execSQL("ALTER TABLE sessions ADD COLUMN assistant_avatar_path TEXT")
    execSQL("ALTER TABLE sessions ADD COLUMN player_display_name TEXT")
    execSQL("ALTER TABLE sessions ADD COLUMN player_avatar_path TEXT")
    execSQL(
        "UPDATE sessions SET role_presentation_enabled = 1 " +
            "WHERE character_snapshot_json IS NOT NULL AND TRIM(character_snapshot_json) != ''",
    )
}

/**
 * Retained conversation branches. Existing histories are backfilled as one
 * active parent/child chain without deleting or rewriting content; the
 * global sort order stays append-only and now orders siblings.
 */
val MIGRATION_14_15 = step(14, 15) {
    execSQL("ALTER TABLE messages ADD COLUMN parent_message_id TEXT")
    execSQL("ALTER TABLE messages ADD COLUMN active_child_id TEXT")
    execSQL("ALTER TABLE sessions ADD COLUMN active_root_message_id TEXT")
    execSQL("ALTER TABLE sessions ADD COLUMN active_leaf_message_id TEXT")
    execSQL(
        "CREATE INDEX IF NOT EXISTS index_messages_session_id_parent_message_id " +
            "ON messages(session_id, parent_message_id)",
    )
    // Stable legacy order is sort_order, then created_at and id for the rare
    // imported history that contains duplicate sort values.
    execSQL(
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
    execSQL(
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
    execSQL(
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

/**
 * Reusable character versions and their many-to-many world membership.
 * Tables are created empty on purpose; importing the legacy preference
 * store is the next checkpoint.
 */
val MIGRATION_15_16 = step(15, 16) {
    execSQL(
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
    execSQL(
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
    execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS index_characters_original_version_id " +
            "ON characters(original_version_id)",
    )
    execSQL(
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
    execSQL(
        "CREATE INDEX IF NOT EXISTS index_character_versions_character_id " +
            "ON character_versions(character_id)",
    )
    execSQL(
        "CREATE INDEX IF NOT EXISTS index_character_versions_character_id_kind " +
            "ON character_versions(character_id, kind)",
    )
    execSQL(
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
    execSQL(
        "CREATE INDEX IF NOT EXISTS index_world_character_versions_character_version_id " +
            "ON world_character_versions(character_version_id)",
    )
}

/** Import bookkeeping and non-destructive catalog references for legacy sessions. */
val MIGRATION_16_17 = step(16, 17) {
    execSQL("ALTER TABLE worlds ADD COLUMN legacy_snapshot_json TEXT")
    execSQL("ALTER TABLE sessions ADD COLUMN world_id TEXT")
    execSQL("ALTER TABLE sessions ADD COLUMN character_version_id TEXT")
    execSQL(
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

/** One owner-agnostic module schema shared by worlds and character versions. */
val MIGRATION_17_18 = step(17, 18) {
    execSQL(
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
    execSQL(
        "CREATE INDEX IF NOT EXISTS index_content_modules_owner_order " +
            "ON content_modules(owner_type, owner_id, position)",
    )
    execSQL(
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
    execSQL(
        "CREATE INDEX IF NOT EXISTS index_content_module_references_target " +
            "ON content_module_references(target_type, target_id)",
    )
}

/** Shared, reference-counted image assets for worlds and character versions. */
val MIGRATION_18_19 = step(18, 19) {
    execSQL(
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
    execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS index_media_assets_managed_path " +
            "ON media_assets(managed_path)",
    )
    execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS index_media_assets_content_hash " +
            "ON media_assets(content_hash)",
    )
    execSQL(
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
    execSQL(
        "CREATE INDEX IF NOT EXISTS index_media_asset_references_asset_id " +
            "ON media_asset_references(asset_id)",
    )
}

/** World tags become a fixed base field; optional settings stay in modules. */
val MIGRATION_19_20 = step(19, 20) {
    execSQL("ALTER TABLE worlds ADD COLUMN tags_json TEXT NOT NULL DEFAULT '[]'")
}

val MIGRATION_20_21 = step(20, 21) {
    execSQL("ALTER TABLE character_versions ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
    execSQL(
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

/** Adds reusable interactive-fiction projects without touching existing product data. */
val MIGRATION_21_22 = step(21, 22) {
    execSQL(
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

/** Stores the unified Novex conversation configuration alongside legacy columns. */
val MIGRATION_22_23 = step(22, 23) {
    execSQL("ALTER TABLE sessions ADD COLUMN novex_configuration_json TEXT")
}

/** Branch-local context provenance; messages and existing cards are untouched. */
val MIGRATION_23_24 = step(23, 24) {
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS novex_context_usage_records (
            id TEXT NOT NULL PRIMARY KEY,
            session_id TEXT NOT NULL,
            request_message_id TEXT NOT NULL,
            response_message_id TEXT,
            branch_id TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    execSQL(
        "CREATE INDEX IF NOT EXISTS index_novex_context_usage_session " +
            "ON novex_context_usage_records(session_id, created_at)",
    )
    execSQL(
        "CREATE INDEX IF NOT EXISTS index_novex_context_usage_request " +
            "ON novex_context_usage_records(session_id, request_message_id)",
    )
}

/** Adds the durable creative library; source conversations are intentionally not foreign keys. */
val MIGRATION_24_25 = step(24, 25) {
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS creative_artifacts (
            id TEXT NOT NULL PRIMARY KEY,
            kind TEXT NOT NULL,
            title TEXT NOT NULL,
            origin_conversation_id TEXT NOT NULL,
            origin_branch_id TEXT NOT NULL,
            origin_message_id TEXT,
            origin_tool_call_id TEXT,
            source_path TEXT,
            current_revision_id TEXT NOT NULL,
            current_storage_key TEXT NOT NULL,
            favorite INTEGER NOT NULL,
            trashed_at INTEGER,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )
        """.trimIndent(),
    )
    execSQL(
        "CREATE INDEX IF NOT EXISTS index_creative_artifacts_origin " +
            "ON creative_artifacts(origin_conversation_id, updated_at)",
    )
    execSQL(
        "CREATE INDEX IF NOT EXISTS index_creative_artifacts_source " +
            "ON creative_artifacts(origin_conversation_id, source_path)",
    )
    execSQL(
        "CREATE INDEX IF NOT EXISTS index_creative_artifacts_trash " +
            "ON creative_artifacts(trashed_at, updated_at)",
    )
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS creative_artifact_revisions (
            id TEXT NOT NULL PRIMARY KEY,
            artifact_id TEXT NOT NULL,
            revision_number INTEGER NOT NULL,
            storage_key TEXT NOT NULL,
            content_hash TEXT NOT NULL,
            mime_type TEXT NOT NULL,
            size_bytes INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            FOREIGN KEY (artifact_id) REFERENCES creative_artifacts(id) ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS index_artifact_revision_number " +
            "ON creative_artifact_revisions(artifact_id, revision_number)",
    )
    execSQL(
        "CREATE INDEX IF NOT EXISTS index_artifact_revision_storage " +
            "ON creative_artifact_revisions(storage_key)",
    )
    execSQL(
        "CREATE INDEX IF NOT EXISTS index_artifact_revision_hash " +
            "ON creative_artifact_revisions(content_hash)",
    )
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS creative_artifact_attachments (
            artifact_id TEXT NOT NULL,
            owner_kind TEXT NOT NULL,
            owner_id TEXT NOT NULL,
            module_id TEXT NOT NULL,
            slot TEXT NOT NULL,
            PRIMARY KEY (artifact_id, owner_kind, owner_id, module_id, slot),
            FOREIGN KEY (artifact_id) REFERENCES creative_artifacts(id) ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    execSQL(
        "CREATE INDEX IF NOT EXISTS index_artifact_attachment_owner " +
            "ON creative_artifact_attachments(owner_kind, owner_id)",
    )
    execSQL(
        "CREATE INDEX IF NOT EXISTS index_artifact_attachment_artifact " +
            "ON creative_artifact_attachments(artifact_id)",
    )
}

val MIGRATION_25_26 = step(25, 26) {
    execSQL("CREATE TABLE IF NOT EXISTS novex_conversation_drafts (conversation_id TEXT NOT NULL PRIMARY KEY, content_json TEXT NOT NULL)")
}

val MIGRATION_26_27 = step(26, 27) {
    execSQL(
        "CREATE TABLE IF NOT EXISTS novex_card_references (id TEXT NOT NULL PRIMARY KEY, source_kind TEXT NOT NULL, source_id TEXT NOT NULL, " +
            "source_module_id TEXT, target_kind TEXT NOT NULL, target_id TEXT NOT NULL, position INTEGER NOT NULL, content_json TEXT NOT NULL)",
    )
    execSQL("CREATE INDEX IF NOT EXISTS index_novex_card_references_source ON novex_card_references (source_kind, source_id)")
    execSQL("CREATE INDEX IF NOT EXISTS index_novex_card_references_target ON novex_card_references (target_kind, target_id)")
}

val MIGRATION_27_28 = step(27, 28) {
    execSQL(
        "CREATE TABLE IF NOT EXISTS novex_character_version_relations (id TEXT NOT NULL PRIMARY KEY, character_id TEXT NOT NULL, source_version_id TEXT NOT NULL, " +
            "target_version_id TEXT NOT NULL, content_json TEXT NOT NULL, FOREIGN KEY(source_version_id) REFERENCES character_versions(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
    )
    execSQL("CREATE INDEX IF NOT EXISTS index_novex_version_relations_character ON novex_character_version_relations (character_id)")
    execSQL("CREATE INDEX IF NOT EXISTS index_novex_version_relations_source ON novex_character_version_relations (source_version_id)")
    execSQL("CREATE INDEX IF NOT EXISTS index_novex_version_relations_target ON novex_character_version_relations (target_version_id)")
}

val MIGRATION_28_29 = step(28, 29) {
    execSQL(
        "CREATE TABLE IF NOT EXISTS novex_character_revisions (version_id TEXT NOT NULL, sequence INTEGER NOT NULL, saved_at INTEGER NOT NULL, " +
            "content_json TEXT NOT NULL, PRIMARY KEY(version_id, sequence), FOREIGN KEY(version_id) REFERENCES character_versions(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
    )
}

val MIGRATION_29_30 = step(29, 30) {
    execSQL("CREATE TABLE IF NOT EXISTS novex_work_groups (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
    execSQL(
        "CREATE TABLE IF NOT EXISTS novex_work_group_members (group_id TEXT NOT NULL, kind TEXT NOT NULL, target_id TEXT NOT NULL, " +
            "PRIMARY KEY(group_id, kind, target_id), FOREIGN KEY(group_id) REFERENCES novex_work_groups(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
    )
    execSQL("CREATE TABLE IF NOT EXISTS novex_work_group_selection (id INTEGER NOT NULL PRIMARY KEY, selection TEXT NOT NULL)")
}

val MIGRATION_30_31 = step(30, 31) {
    execSQL(
        "CREATE TABLE IF NOT EXISTS novex_world_revisions (world_id TEXT NOT NULL, sequence INTEGER NOT NULL, saved_at INTEGER NOT NULL, content_json TEXT NOT NULL, " +
            "PRIMARY KEY(world_id, sequence), FOREIGN KEY(world_id) REFERENCES worlds(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
    )
    execSQL(
        "CREATE TABLE IF NOT EXISTS novex_game_revisions (project_id TEXT NOT NULL, sequence INTEGER NOT NULL, saved_at INTEGER NOT NULL, content_json TEXT NOT NULL, " +
            "PRIMARY KEY(project_id, sequence), FOREIGN KEY(project_id) REFERENCES interactive_fiction_projects(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
    )
}

val MIGRATION_31_32 = step(31, 32) {
    execSQL("ALTER TABLE novex_work_groups ADD COLUMN organization_json TEXT NOT NULL DEFAULT '{}'")
}

val MIGRATION_32_33 = step(32, 33) {
    execSQL("CREATE TABLE IF NOT EXISTS novex_card_directories (owner_key TEXT NOT NULL PRIMARY KEY, directory TEXT NOT NULL, digest TEXT NOT NULL, content_digest TEXT NOT NULL)")
}

val MIGRATION_33_34 = step(33, 34) {
    addColumnUnlessPresent("compact_markers", "history_scope_key", "ALTER TABLE compact_markers ADD COLUMN history_scope_key TEXT")
}

val MIGRATION_34_35 = step(34, 35) {
    addColumnUnlessPresent("sessions", "composer_draft", "ALTER TABLE sessions ADD COLUMN composer_draft TEXT")
}

val MIGRATION_35_36 = step(35, 36) {
    addColumnUnlessPresent("sessions", "per_turn_prompt", "ALTER TABLE sessions ADD COLUMN per_turn_prompt TEXT")
}

val MIGRATION_36_37 = step(36, 37) {
    addColumnUnlessPresent("sessions", "side_of_session", "ALTER TABLE sessions ADD COLUMN side_of_session TEXT")
}

val MIGRATION_37_38 = step(37, 38) {
    addColumnUnlessPresent("sessions", "runtime_dice_enabled", "ALTER TABLE sessions ADD COLUMN runtime_dice_enabled INTEGER NOT NULL DEFAULT 0")
    addColumnUnlessPresent("sessions", "runtime_ledger_enabled", "ALTER TABLE sessions ADD COLUMN runtime_ledger_enabled INTEGER NOT NULL DEFAULT 0")
}

val MIGRATION_38_39 = step(38, 39) {
    addColumnUnlessPresent("sessions", "text_style_prompt", "ALTER TABLE sessions ADD COLUMN text_style_prompt TEXT")
}

/** Every step, wired into the database builder in ascending order. */
internal val MAIN_SCHEMA_STEPS: Array<Migration> = arrayOf(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
    MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
    MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21,
    MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26,
    MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31,
    MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36,
    MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39,
)
