package com.openminis.app.data.character

import java.util.UUID

/** Shared editing surface for both world modules and character-version modules. */
class ContentModuleRepository(
    private val dao: ContentModuleDao,
) {
    suspend fun add(
        owner: ModuleOwner,
        type: ContentModuleType,
        name: String,
        contentJson: String = "{}",
        collapsed: Boolean = true,
        now: Long = System.currentTimeMillis(),
        id: String = UUID.randomUUID().toString(),
    ): ContentModuleEntity {
        requireOwner(owner)
        return dao.append(
            ContentModuleEntity(
                id = id,
                ownerType = owner.type,
                ownerId = owner.id,
                type = type,
                name = name.trim().ifBlank { defaultName(type) },
                contentJson = contentJson,
                position = -1,
                collapsed = collapsed,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun list(owner: ModuleOwner): List<ContentModuleEntity> =
        dao.list(owner.type, owner.id)

    suspend fun module(id: String): ContentModuleEntity? = dao.module(id)

    suspend fun rename(id: String, name: String, now: Long = System.currentTimeMillis()) {
        val module = requireNotNull(dao.module(id)) { "模块不存在" }
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "模块名称不能为空" }
        dao.update(module.copy(name = normalized, updatedAt = now))
    }

    suspend fun setCollapsed(
        id: String,
        collapsed: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        val module = requireNotNull(dao.module(id)) { "模块不存在" }
        dao.update(module.copy(collapsed = collapsed, updatedAt = now))
    }

    suspend fun updateContent(
        id: String,
        contentJson: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val module = requireNotNull(dao.module(id)) { "模块不存在" }
        dao.update(module.copy(contentJson = contentJson, updatedAt = now))
    }

    suspend fun move(
        id: String,
        toIndex: Int,
        now: Long = System.currentTimeMillis(),
    ): ContentModuleEntity = requireNotNull(dao.move(id, toIndex, now)) { "模块不存在" }

    suspend fun delete(id: String) {
        dao.deleteAndCompact(id)
    }

    /** Copies module values and references once; later edits do not stay synchronized. */
    suspend fun copyAll(
        source: ModuleOwner,
        target: ModuleOwner,
        now: Long = System.currentTimeMillis(),
    ): List<ContentModuleEntity> {
        requireOwner(source)
        requireOwner(target)
        val sourceModules = list(source)
        val copies = sourceModules.map { module ->
            add(
                owner = target,
                type = module.type,
                name = module.name,
                contentJson = module.contentJson,
                collapsed = module.collapsed,
                now = now,
            )
        }
        val copiedIds = sourceModules.map(ContentModuleEntity::id).zip(copies.map(ContentModuleEntity::id)).toMap()
        sourceModules.zip(copies).forEach { (sourceModule, copiedModule) ->
            references(sourceModule.id).forEach { reference ->
                val targetId = if (reference.targetType == ModuleReferenceTargetType.MODULE) {
                    copiedIds[reference.targetId] ?: reference.targetId
                } else reference.targetId
                addReference(
                    copiedModule.id,
                    ModuleReferenceTarget(reference.targetType, targetId),
                    reference.position,
                )
            }
        }
        return copies
    }

    suspend fun addReference(
        sourceModuleId: String,
        target: ModuleReferenceTarget,
        position: Int,
    ) {
        requireNotNull(dao.module(sourceModuleId)) { "来源模块不存在" }
        requireTarget(target)
        dao.upsertReference(
            ContentModuleReferenceEntity(
                sourceModuleId = sourceModuleId,
                targetType = target.type,
                targetId = target.id,
                position = position.coerceAtLeast(0),
            ),
        )
    }

    suspend fun removeReference(sourceModuleId: String, target: ModuleReferenceTarget) {
        dao.deleteReference(sourceModuleId, target.type, target.id)
    }

    suspend fun references(moduleId: String): List<ContentModuleReferenceEntity> =
        dao.references(moduleId)

    private suspend fun requireOwner(owner: ModuleOwner) {
        val exists = when (owner.type) {
            ModuleOwnerType.WORLD -> dao.worldExists(owner.id)
            ModuleOwnerType.CHARACTER_VERSION -> dao.characterVersionExists(owner.id)
            ModuleOwnerType.CONTENT_MODULE -> false
        }
        require(exists) { "模块所有者不存在" }
    }

    private suspend fun requireTarget(target: ModuleReferenceTarget) {
        val exists = when (target.type) {
            ModuleReferenceTargetType.MODULE -> dao.module(target.id) != null
            ModuleReferenceTargetType.WORLD -> dao.worldExists(target.id)
            ModuleReferenceTargetType.CHARACTER_VERSION -> dao.characterVersionExists(target.id)
        }
        require(exists) { "引用目标不存在" }
    }

    private fun defaultName(type: ContentModuleType): String = when (type) {
        ContentModuleType.CUSTOM -> "自定义模块"
        else -> type.name
    }
}
