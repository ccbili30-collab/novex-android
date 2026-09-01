package com.openminis.app.data.character

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

@Entity(
    tableName = "media_assets",
    indices = [
        Index(value = ["managed_path"], unique = true, name = "index_media_assets_managed_path"),
        Index(value = ["content_hash"], unique = true, name = "index_media_assets_content_hash"),
    ],
)
data class MediaAssetEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "managed_path") val managedPath: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

enum class MediaAssetSlot {
    WORLD_COVER,
    WORLD_LOGO,
    WORLD_BACKGROUND,
    CHARACTER_AVATAR,
    CHARACTER_PAGE_BACKGROUND,
}

@Entity(
    tableName = "media_asset_references",
    primaryKeys = ["owner_type", "owner_id", "slot"],
    foreignKeys = [
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["asset_id"], name = "index_media_asset_references_asset_id"),
    ],
)
data class MediaAssetReferenceEntity(
    @ColumnInfo(name = "owner_type") val ownerType: ModuleOwnerType,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    val slot: MediaAssetSlot,
    @ColumnInfo(name = "asset_id") val assetId: String,
)

class MediaAssetConverters {
    @TypeConverter
    fun slotToString(value: MediaAssetSlot): String = value.name

    @TypeConverter
    fun stringToSlot(value: String): MediaAssetSlot = MediaAssetSlot.valueOf(value)
}
