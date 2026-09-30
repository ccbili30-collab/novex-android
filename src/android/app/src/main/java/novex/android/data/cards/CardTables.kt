package novex.android.data.cards

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.openminis.app.data.character.CharacterVersionEntity
import com.openminis.app.data.character.WorldEntity
import com.openminis.app.data.interactivefiction.InteractiveFictionProjectEntity

/*
 * Row declarations for every card-library table of the main database.
 *
 * THIS FILE IS PERSISTED SCHEMA, NOT CODE TO REFACTOR FREELY. Room checks
 * the live database against these declarations on every open, so the table
 * names, column names, column order, nullability, defaults, indexes and
 * foreign keys below are a byte-level contract with every install that
 * already carries a "minis.db". Kotlin-side reshaping (property names,
 * class grouping) is fine; anything the schema observes is not. The
 * historical DDL that produced these tables sits in MainDatabaseMigrations.
 */

/** `novex_card_directories` — published card files, per owning key. */
@Entity(tableName = "novex_card_directories")
data class CardDirectoryRow(
    @PrimaryKey @ColumnInfo(name = "owner_key") val ownerKey: String,
    val directory: String,
    val digest: String,
    @ColumnInfo(name = "content_digest") val contentDigest: String,
)

/** `novex_card_references` — a directed edge between two library cards. */
@Entity(
    tableName = "novex_card_references",
    indices = [
        Index(value = ["source_kind", "source_id"], name = "index_novex_card_references_source"),
        Index(value = ["target_kind", "target_id"], name = "index_novex_card_references_target"),
    ],
)
data class CardLinkRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "source_kind") val sourceKind: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "source_module_id") val sourceModuleId: String?,
    @ColumnInfo(name = "target_kind") val targetKind: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    val position: Int,
    @ColumnInfo(name = "content_json") val contentJson: String,
)

/** `novex_character_version_relations` — a user-visible version-graph edge. */
@Entity(
    tableName = "novex_character_version_relations",
    foreignKeys = [
        ForeignKey(
            entity = CharacterVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_version_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["character_id"], name = "index_novex_version_relations_character"),
        Index(value = ["source_version_id"], name = "index_novex_version_relations_source"),
        Index(value = ["target_version_id"], name = "index_novex_version_relations_target"),
    ],
)
data class RoleVersionLinkRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "character_id") val characterId: String,
    @ColumnInfo(name = "source_version_id") val sourceVersionId: String,
    @ColumnInfo(name = "target_version_id") val targetVersionId: String,
    @ColumnInfo(name = "content_json") val contentJson: String,
)

/** `novex_world_revisions` — one numbered save of a world's editing session. */
@Entity(
    tableName = "novex_world_revisions",
    primaryKeys = ["world_id", "sequence"],
    foreignKeys = [
        ForeignKey(
            entity = WorldEntity::class,
            parentColumns = ["id"],
            childColumns = ["world_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class WorldRevisionRow(
    @ColumnInfo(name = "world_id") val worldId: String,
    val sequence: Int,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
    @ColumnInfo(name = "content_json") val contentJson: String,
)

/** `novex_game_revisions` — one numbered save of a fiction project. */
@Entity(
    tableName = "novex_game_revisions",
    primaryKeys = ["project_id", "sequence"],
    foreignKeys = [
        ForeignKey(
            entity = InteractiveFictionProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["project_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class GameRevisionRow(
    @ColumnInfo(name = "project_id") val projectId: String,
    val sequence: Int,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
    @ColumnInfo(name = "content_json") val contentJson: String,
)

/** `novex_character_revisions` — one numbered save of a role version. */
@Entity(
    tableName = "novex_character_revisions",
    primaryKeys = ["version_id", "sequence"],
    foreignKeys = [
        ForeignKey(
            entity = CharacterVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["version_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RoleRevisionRow(
    @ColumnInfo(name = "version_id") val versionId: String,
    val sequence: Int,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
    @ColumnInfo(name = "content_json") val contentJson: String,
)

/** `novex_work_groups` — a named bundle of library targets. */
@Entity(tableName = "novex_work_groups")
data class WorkGroupRow(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "organization_json", defaultValue = "'{}'") val organizationJson: String = "{}",
)

/** `novex_work_group_members` — membership rows of a work group. */
@Entity(
    tableName = "novex_work_group_members",
    primaryKeys = ["group_id", "kind", "target_id"],
    foreignKeys = [
        ForeignKey(
            entity = WorkGroupRow::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class WorkGroupMemberRow(
    @ColumnInfo(name = "group_id") val groupId: String,
    val kind: String,
    @ColumnInfo(name = "target_id") val targetId: String,
)

/** `novex_work_group_selection` — the single-row pointer to the active group. */
@Entity(tableName = "novex_work_group_selection")
data class WorkGroupPickRow(
    @PrimaryKey val id: Int = 0,
    val selection: String,
)
