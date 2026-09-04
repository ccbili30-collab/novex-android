package com.openminis.app.data.interactivefiction

import java.util.UUID

class InteractiveFictionRepository(
    private val dao: InteractiveFictionDao,
) {
    suspend fun create(
        name: String,
        summary: String,
        launchMode: InteractiveFictionLaunchMode,
        playerIdentity: String,
        now: Long,
        id: String = UUID.randomUUID().toString(),
        sourceId: String? = null,
        sourceDocumentJson: String? = null,
    ): InteractiveFictionProjectEntity {
        val normalizedName = name.trim()
        require(normalizedName.isNotBlank()) { "文游名称不能为空" }
        val project = InteractiveFictionProjectEntity(
            id = id,
            name = normalizedName,
            summary = summary,
            launchMode = launchMode,
            playerIdentity = playerIdentity,
            createdAt = now,
            updatedAt = now,
            sourceId = sourceId,
            sourceDocumentJson = sourceDocumentJson,
        )
        dao.insert(project)
        return project
    }

    suspend fun save(project: InteractiveFictionProjectEntity, now: Long): InteractiveFictionProjectEntity {
        require(project.name.isNotBlank()) { "文游名称不能为空" }
        requireNotNull(dao.project(project.id)) { "文游不存在" }
        return project.copy(name = project.name.trim(), updatedAt = now).also { dao.update(it) }
    }

    suspend fun project(id: String): InteractiveFictionProjectEntity? = dao.project(id)

    suspend fun list(): List<InteractiveFictionProjectEntity> = dao.list()

    suspend fun delete(id: String) = dao.delete(id)
}
