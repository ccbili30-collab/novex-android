package com.openminis.app.data.character

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaAssetRepository(
    private val dao: MediaAssetDao,
    private val deleteManagedFile: (String) -> Boolean,
) {
    suspend fun register(
        managedPath: String,
        mimeType: String,
        contentHash: String,
        now: Long = System.currentTimeMillis(),
        id: String = UUID.randomUUID().toString(),
    ): MediaAssetEntity {
        require(managedPath.isNotBlank()) { "受管资源路径不能为空" }
        require(contentHash.isNotBlank()) { "资源摘要不能为空" }
        dao.assetByHash(contentHash)?.let { return it }
        val candidate = MediaAssetEntity(
            id = id,
            managedPath = managedPath,
            mimeType = mimeType.ifBlank { "application/octet-stream" },
            contentHash = contentHash,
            createdAt = now,
        )
        val inserted = dao.insertAsset(candidate) != -1L
        return if (inserted) candidate else {
            dao.assetByHash(contentHash)
                ?: dao.assetByPath(managedPath)
                ?: error("资源登记冲突")
        }
    }

    suspend fun attach(owner: ModuleOwner, slot: MediaAssetSlot, assetId: String) {
        requireOwner(owner)
        requireSlot(owner.type, slot)
        requireNotNull(dao.asset(assetId)) { "资源不存在" }
        deleteFiles(
            dao.attach(
                MediaAssetReferenceEntity(
                    ownerType = owner.type,
                    ownerId = owner.id,
                    slot = slot,
                    assetId = assetId,
                ),
            ),
        )
    }

    suspend fun detach(owner: ModuleOwner, slot: MediaAssetSlot) {
        deleteFiles(dao.detach(owner.type, owner.id, slot))
    }

    suspend fun removeAll(owner: ModuleOwner) {
        deleteFiles(dao.removeAll(owner.type, owner.id))
    }

    suspend fun asset(id: String): MediaAssetEntity? = dao.asset(id)

    suspend fun assetByHash(hash: String): MediaAssetEntity? = dao.assetByHash(hash)

    suspend fun assetFor(owner: ModuleOwner, slot: MediaAssetSlot): MediaAssetEntity? =
        dao.assetFor(owner.type, owner.id, slot)

    suspend fun referenceCount(assetId: String): Int = dao.referenceCount(assetId)

    private suspend fun requireOwner(owner: ModuleOwner) {
        val exists = when (owner.type) {
            ModuleOwnerType.WORLD -> dao.worldExists(owner.id)
            ModuleOwnerType.CHARACTER_VERSION -> dao.characterVersionExists(owner.id)
            ModuleOwnerType.INTERACTIVE_FICTION -> dao.interactiveFictionExists(owner.id)
            ModuleOwnerType.CONTENT_MODULE -> dao.contentModuleExists(ModuleOwner.contentModuleId(owner.id))
        }
        require(exists) { "资源所有者不存在" }
    }

    private fun requireSlot(ownerType: ModuleOwnerType, slot: MediaAssetSlot) {
        val valid = when (ownerType) {
            ModuleOwnerType.WORLD -> slot in setOf(
                MediaAssetSlot.WORLD_COVER,
                MediaAssetSlot.WORLD_LOGO,
                MediaAssetSlot.WORLD_BACKGROUND,
            )
            ModuleOwnerType.CHARACTER_VERSION -> slot in setOf(
                MediaAssetSlot.CHARACTER_AVATAR,
                MediaAssetSlot.CHARACTER_PAGE_BACKGROUND,
            )
            ModuleOwnerType.INTERACTIVE_FICTION -> slot in setOf(
                MediaAssetSlot.INTERACTIVE_FICTION_COVER,
                MediaAssetSlot.INTERACTIVE_FICTION_BACKGROUND,
            )
            ModuleOwnerType.CONTENT_MODULE -> slot == MediaAssetSlot.MODULE_IMAGE
        }
        require(valid) { "资源槽位与所有者类型不匹配" }
    }

    private fun deleteFiles(paths: List<String>) {
        paths.forEach { path -> runCatching { deleteManagedFile(path) } }
    }
}

/** Copies bytes into a single content-addressed managed image directory. */
class ManagedMediaAssetStore(
    private val root: File,
    private val repository: MediaAssetRepository,
    private val onCreatedManagedFile: (String) -> Unit = {},
) {
    suspend fun import(
        bytes: ByteArray,
        mimeType: String,
        now: Long = System.currentTimeMillis(),
    ): MediaAssetEntity = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) { "图片内容不能为空" }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
        repository.assetByHash(hash)?.let { return@withContext it }

        root.mkdirs()
        val id = UUID.randomUUID().toString()
        val extension = when (mimeType.lowercase()) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "bin"
        }
        val target = File(root, "$id.$extension")
        val temporary = File(root, ".$id.tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(target)) { "无法保存图片资源" }
        val registered = runCatching {
            repository.register(
                managedPath = target.absolutePath,
                mimeType = mimeType,
                contentHash = hash,
                now = now,
                id = id,
            )
        }.getOrElse { error ->
            target.delete()
            throw error
        }
        if (registered.id != id) {
            target.delete()
        } else {
            onCreatedManagedFile(target.absolutePath)
        }
        registered
    }
}
