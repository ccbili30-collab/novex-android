package com.openminis.app.data.character

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

enum class ModuleOwnerType {
    WORLD,
    CHARACTER_VERSION,
    INTERACTIVE_FICTION,
    CONTENT_MODULE,
}

data class ModuleOwner(
    val type: ModuleOwnerType,
    val id: String,
) {
    companion object {
        private const val ITEM_SEPARATOR = "::item::"

        fun world(id: String) = ModuleOwner(ModuleOwnerType.WORLD, id)
        fun characterVersion(id: String) = ModuleOwner(ModuleOwnerType.CHARACTER_VERSION, id)
        fun interactiveFiction(id: String) = ModuleOwner(ModuleOwnerType.INTERACTIVE_FICTION, id)
        fun contentModule(id: String) = ModuleOwner(ModuleOwnerType.CONTENT_MODULE, id)
        fun contentModuleItem(moduleId: String, itemId: String) =
            ModuleOwner(ModuleOwnerType.CONTENT_MODULE, "$moduleId$ITEM_SEPARATOR$itemId")

        fun contentModuleId(ownerId: String): String = ownerId.substringBefore(ITEM_SEPARATOR)
    }
}

enum class ContentModuleType {
    TIMELINE,
    ERA_EVENT,
    MAP,
    REGION,
    FACTION,
    RACE,
    QUOTES,
    WORLD_EXPERIENCE,
    ATTRIBUTE_PANEL,
    EQUIPMENT,
    TALENT_SKILL,
    APPEARANCE_PERSONALITY,
    INTEREST,
    GAME_PLAYER_IDENTITY,
    GAME_OPENING,
    GAME_NARRATIVE_RULES,
    GAME_POWER_SYSTEM,
    GAME_ATTRIBUTES,
    GAME_SKILLS,
    GAME_EQUIPMENT,
    GAME_ITEMS,
    GAME_QUESTS,
    GAME_CHECKS,
    GAME_ENDINGS,
    GAME_CHARACTER_STATUS,
    GAME_QUICK_ACTIONS,
    CUSTOM,
}

@Entity(
    tableName = "content_modules",
    indices = [
        Index(
            value = ["owner_type", "owner_id", "position"],
            name = "index_content_modules_owner_order",
        ),
    ],
)
data class ContentModuleEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "owner_type") val ownerType: ModuleOwnerType,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    val type: ContentModuleType,
    val name: String,
    @ColumnInfo(name = "content_json") val contentJson: String = "{}",
    val position: Int,
    val collapsed: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    val owner: ModuleOwner
        get() = ModuleOwner(ownerType, ownerId)
}

enum class ModuleReferenceTargetType {
    MODULE,
    WORLD,
    CHARACTER_VERSION,
}

data class ModuleReferenceTarget(
    val type: ModuleReferenceTargetType,
    val id: String,
) {
    companion object {
        fun module(id: String) = ModuleReferenceTarget(ModuleReferenceTargetType.MODULE, id)
        fun world(id: String) = ModuleReferenceTarget(ModuleReferenceTargetType.WORLD, id)
        fun characterVersion(id: String) =
            ModuleReferenceTarget(ModuleReferenceTargetType.CHARACTER_VERSION, id)
    }
}

@Entity(
    tableName = "content_module_references",
    primaryKeys = ["source_module_id", "target_type", "target_id"],
    foreignKeys = [
        ForeignKey(
            entity = ContentModuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_module_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["target_type", "target_id"],
            name = "index_content_module_references_target",
        ),
    ],
)
data class ContentModuleReferenceEntity(
    @ColumnInfo(name = "source_module_id") val sourceModuleId: String,
    @ColumnInfo(name = "target_type") val targetType: ModuleReferenceTargetType,
    @ColumnInfo(name = "target_id") val targetId: String,
    val position: Int,
) {
    val target: ModuleReferenceTarget
        get() = ModuleReferenceTarget(targetType, targetId)
}

class ContentModuleConverters {
    @TypeConverter
    fun moduleOwnerTypeToString(value: ModuleOwnerType): String = value.name

    @TypeConverter
    fun stringToModuleOwnerType(value: String): ModuleOwnerType = ModuleOwnerType.valueOf(value)

    @TypeConverter
    fun contentModuleTypeToString(value: ContentModuleType): String = value.name

    @TypeConverter
    fun stringToContentModuleType(value: String): ContentModuleType = ContentModuleType.valueOf(value)

    @TypeConverter
    fun referenceTargetTypeToString(value: ModuleReferenceTargetType): String = value.name

    @TypeConverter
    fun stringToReferenceTargetType(value: String): ModuleReferenceTargetType =
        ModuleReferenceTargetType.valueOf(value)
}
