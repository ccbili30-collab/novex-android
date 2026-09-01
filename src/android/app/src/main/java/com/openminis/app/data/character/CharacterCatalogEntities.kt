package com.openminis.app.data.character

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/** A root character has exactly one original version and any number of variants. */
@Entity(
    tableName = "characters",
    indices = [
        Index(
            value = ["original_version_id"],
            unique = true,
            name = "index_characters_original_version_id",
        ),
    ],
)
data class CharacterEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "original_version_id") val originalVersionId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

enum class CharacterVersionKind {
    ORIGINAL,
    VARIANT,
}

class CharacterCatalogConverters {
    @TypeConverter
    fun characterVersionKindToString(value: CharacterVersionKind): String = value.name

    @TypeConverter
    fun stringToCharacterVersionKind(value: String): CharacterVersionKind =
        CharacterVersionKind.valueOf(value)
}

@Entity(
    tableName = "character_versions",
    foreignKeys = [
        ForeignKey(
            entity = CharacterEntity::class,
            parentColumns = ["id"],
            childColumns = ["character_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["character_id"],
            name = "index_character_versions_character_id",
        ),
        Index(
            value = ["character_id", "kind"],
            name = "index_character_versions_character_id_kind",
        ),
    ],
)
data class CharacterVersionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "character_id") val characterId: String,
    val kind: CharacterVersionKind,
    /** Library-facing version label, such as “本体” or “赛博分身”. */
    val label: String,
    /** Transitional structured profile; the shared module layer will replace its optional sections. */
    @ColumnInfo(name = "profile_json") val profileJson: String = "{}",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "worlds")
data class WorldEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** The only world content section present before users add modules. */
    val overview: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "world_character_versions",
    primaryKeys = ["world_id", "character_version_id"],
    foreignKeys = [
        ForeignKey(
            entity = WorldEntity::class,
            parentColumns = ["id"],
            childColumns = ["world_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CharacterVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["character_version_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["character_version_id"],
            name = "index_world_character_versions_character_version_id",
        ),
    ],
)
data class WorldCharacterVersionEntity(
    @ColumnInfo(name = "world_id") val worldId: String,
    @ColumnInfo(name = "character_version_id") val characterVersionId: String,
    val position: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

data class CharacterAggregate(
    val character: CharacterEntity,
    val original: CharacterVersionEntity,
    val variants: List<CharacterVersionEntity>,
) {
    val allVersions: List<CharacterVersionEntity>
        get() = listOf(original) + variants
}
