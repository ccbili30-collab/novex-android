package com.openminis.app.data.character

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface MediaAssetDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAsset(asset: MediaAssetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReference(reference: MediaAssetReferenceEntity)

    @Query("SELECT * FROM media_assets WHERE id = :id")
    suspend fun asset(id: String): MediaAssetEntity?

    @Query("SELECT * FROM media_assets WHERE content_hash = :hash")
    suspend fun assetByHash(hash: String): MediaAssetEntity?

    @Query("SELECT * FROM media_assets WHERE managed_path = :path")
    suspend fun assetByPath(path: String): MediaAssetEntity?

    @Query(
        "SELECT assets.* FROM media_assets AS assets " +
            "INNER JOIN media_asset_references AS refs ON refs.asset_id = assets.id " +
            "WHERE refs.owner_type = :ownerType AND refs.owner_id = :ownerId AND refs.slot = :slot",
    )
    suspend fun assetFor(
        ownerType: ModuleOwnerType,
        ownerId: String,
        slot: MediaAssetSlot,
    ): MediaAssetEntity?

    @Query(
        "SELECT * FROM media_asset_references " +
            "WHERE owner_type = :ownerType AND owner_id = :ownerId AND slot = :slot",
    )
    suspend fun reference(
        ownerType: ModuleOwnerType,
        ownerId: String,
        slot: MediaAssetSlot,
    ): MediaAssetReferenceEntity?

    @Query(
        "SELECT * FROM media_asset_references WHERE owner_type = :ownerType AND owner_id = :ownerId",
    )
    suspend fun referencesForOwner(
        ownerType: ModuleOwnerType,
        ownerId: String,
    ): List<MediaAssetReferenceEntity>

    @Query("SELECT COUNT(*) FROM media_asset_references WHERE asset_id = :assetId")
    suspend fun referenceCount(assetId: String): Int

    @Query(
        "DELETE FROM media_asset_references " +
            "WHERE owner_type = :ownerType AND owner_id = :ownerId AND slot = :slot",
    )
    suspend fun deleteReference(ownerType: ModuleOwnerType, ownerId: String, slot: MediaAssetSlot)

    @Query("DELETE FROM media_asset_references WHERE owner_type = :ownerType AND owner_id = :ownerId")
    suspend fun deleteReferencesForOwner(ownerType: ModuleOwnerType, ownerId: String)

    @Query("DELETE FROM media_assets WHERE id = :id")
    suspend fun deleteAsset(id: String)

    @Query("SELECT EXISTS(SELECT 1 FROM worlds WHERE id = :id)")
    suspend fun worldExists(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM character_versions WHERE id = :id)")
    suspend fun characterVersionExists(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM content_modules WHERE id = :id)")
    suspend fun contentModuleExists(id: String): Boolean

    @Transaction
    suspend fun attach(reference: MediaAssetReferenceEntity): List<String> {
        val previous = reference(reference.ownerType, reference.ownerId, reference.slot)
        upsertReference(reference)
        if (previous == null || previous.assetId == reference.assetId) return emptyList()
        return listOfNotNull(collectIfOrphaned(previous.assetId))
    }

    @Transaction
    suspend fun detach(
        ownerType: ModuleOwnerType,
        ownerId: String,
        slot: MediaAssetSlot,
    ): List<String> {
        val previous = reference(ownerType, ownerId, slot) ?: return emptyList()
        deleteReference(ownerType, ownerId, slot)
        return listOfNotNull(collectIfOrphaned(previous.assetId))
    }

    @Transaction
    suspend fun removeAll(ownerType: ModuleOwnerType, ownerId: String): List<String> {
        val assetIds = referencesForOwner(ownerType, ownerId).map { it.assetId }.distinct()
        deleteReferencesForOwner(ownerType, ownerId)
        return assetIds.mapNotNull { collectIfOrphaned(it) }
    }

    suspend fun collectIfOrphaned(assetId: String): String? {
        if (referenceCount(assetId) != 0) return null
        val orphan = asset(assetId) ?: return null
        // Delete the protected row while still inside the transaction. A new
        // reference cannot race in after this point because its FK would fail.
        deleteAsset(assetId)
        return orphan.managedPath
    }
}
