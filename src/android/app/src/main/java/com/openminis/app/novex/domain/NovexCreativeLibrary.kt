package com.openminis.app.novex.domain

import java.io.File

enum class CreativeArtifactKind {
    DOCUMENT,
    IMAGE,
    MAP,
    CARD_ARCHIVE,
    OTHER,
}

data class NovexCreativeArtifactSummary(
    val address: NovexContentAddress,
    val title: String,
    val kind: CreativeArtifactKind,
)

/** Read-only seam shared by conversation configuration and all content-page renderers. */
interface NovexCreativeArtifactReader {
    suspend fun availableArtifacts(): List<NovexCreativeArtifactSummary>
    suspend fun attachedModuleImageFiles(owner: NovexContentAddress): Map<String, File>
}

data class CreativeArtifactOrigin(
    val conversationId: String,
    val branchId: String,
    val messageId: String? = null,
    val toolCallId: String? = null,
) {
    init {
        require(conversationId.isNotBlank()) { "来源对话编号不能为空" }
        require(branchId.isNotBlank()) { "来源消息分支编号不能为空" }
        require(messageId == null || messageId.isNotBlank()) { "来源消息编号不能为空" }
        require(toolCallId == null || toolCallId.isNotBlank()) { "来源工具调用编号不能为空" }
    }
}

data class CreativeArtifact(
    val id: String,
    val kind: CreativeArtifactKind,
    val title: String,
    val storageKey: String,
    val origin: CreativeArtifactOrigin,
    val createdAt: Long = 0L,
    val updatedAt: Long = createdAt,
    val favorite: Boolean = false,
    val trashedAt: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "创作成果编号不能为空" }
        require(title.isNotBlank()) { "创作成果名称不能为空" }
        require(storageKey.isNotBlank()) { "创作成果存储编号不能为空" }
        require(trashedAt == null || trashedAt >= 0L) { "回收时间不能为负数" }
    }

    val isTrashed: Boolean get() = trashedAt != null
}

data class CreativeArtifactRevision(
    val id: String,
    val artifactId: String,
    val number: Int,
    val storageKey: String,
    val contentHash: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long = 0L,
) {
    init {
        require(id.isNotBlank()) { "成果版本编号不能为空" }
        require(artifactId.isNotBlank()) { "创作成果编号不能为空" }
        require(number > 0) { "成果版本序号必须大于零" }
        require(storageKey.isNotBlank()) { "成果版本存储编号不能为空" }
        require(contentHash.isNotBlank()) { "成果版本摘要不能为空" }
        require(mimeType.isNotBlank()) { "成果版本媒体类型不能为空" }
        require(sizeBytes >= 0L) { "成果版本大小不能为负数" }
    }
}

data class CreativeArtifactAttachment(
    val artifactId: String,
    val owner: NovexContentAddress,
    val moduleId: String? = null,
    val slot: String? = null,
) {
    init {
        require(artifactId.isNotBlank()) { "创作成果编号不能为空" }
        require(moduleId == null || moduleId.isNotBlank()) { "内容模块编号不能为空" }
        require(slot == null || slot.isNotBlank()) { "内容位置不能为空" }
    }
}

data class NovexCreativeLibrarySnapshot(
    val artifacts: Map<String, CreativeArtifact> = emptyMap(),
    val revisions: Map<String, List<CreativeArtifactRevision>> = emptyMap(),
    val attachments: List<CreativeArtifactAttachment> = emptyList(),
)

sealed interface NovexCreativeLibraryCommand {
    data class RegisterArtifact(
        val artifact: CreativeArtifact,
        val initialRevision: CreativeArtifactRevision? = null,
    ) : NovexCreativeLibraryCommand
    data class AddRevision(
        val artifactId: String,
        val revision: CreativeArtifactRevision,
    ) : NovexCreativeLibraryCommand
    data class SetFavorite(val artifactId: String, val favorite: Boolean) : NovexCreativeLibraryCommand
    data class MoveToTrash(val artifactId: String, val trashedAt: Long) : NovexCreativeLibraryCommand
    data class RestoreArtifact(val artifactId: String) : NovexCreativeLibraryCommand
    data class DeleteArtifact(val artifactId: String) : NovexCreativeLibraryCommand
    data class AttachArtifact(
        val attachment: CreativeArtifactAttachment,
    ) : NovexCreativeLibraryCommand
    data class DetachArtifact(
        val attachment: CreativeArtifactAttachment,
    ) : NovexCreativeLibraryCommand
}

class NovexCreativeLibrary private constructor(
    val snapshot: NovexCreativeLibrarySnapshot,
) {
    fun apply(command: NovexCreativeLibraryCommand): NovexCreativeLibrary = when (command) {
        is NovexCreativeLibraryCommand.RegisterArtifact -> {
            require(command.artifact.id !in snapshot.artifacts) { "创作成果已经存在" }
            command.initialRevision?.let { revision ->
                require(revision.artifactId == command.artifact.id) { "成果版本不属于该成果" }
                require(revision.number == 1) { "初始成果版本序号必须为一" }
            }
            withSnapshot(
                snapshot.copy(
                    artifacts = snapshot.artifacts + (command.artifact.id to command.artifact),
                    revisions = command.initialRevision?.let { revision ->
                        snapshot.revisions + (command.artifact.id to listOf(revision))
                    } ?: snapshot.revisions,
                ),
            )
        }

        is NovexCreativeLibraryCommand.AddRevision -> {
            val artifact = requireNotNull(snapshot.artifacts[command.artifactId]) { "创作成果不存在" }
            val existing = snapshot.revisions[command.artifactId].orEmpty()
            require(command.revision.artifactId == command.artifactId) { "成果版本不属于该成果" }
            require(command.revision.id !in snapshot.revisions.values.flatten().map { it.id }) {
                "成果版本已经存在"
            }
            require(command.revision.number == (existing.maxOfOrNull { it.number } ?: 0) + 1) {
                "成果版本序号必须连续"
            }
            withSnapshot(
                snapshot.copy(
                    artifacts = snapshot.artifacts + (
                        artifact.id to artifact.copy(
                            storageKey = command.revision.storageKey,
                            updatedAt = command.revision.createdAt,
                        )
                    ),
                    revisions = snapshot.revisions + (
                        command.artifactId to (existing + command.revision)
                    ),
                ),
            )
        }

        is NovexCreativeLibraryCommand.SetFavorite -> updateArtifact(command.artifactId) {
            it.copy(favorite = command.favorite)
        }

        is NovexCreativeLibraryCommand.MoveToTrash -> updateArtifact(command.artifactId) {
            it.copy(trashedAt = command.trashedAt)
        }

        is NovexCreativeLibraryCommand.RestoreArtifact -> updateArtifact(command.artifactId) {
            it.copy(trashedAt = null)
        }

        is NovexCreativeLibraryCommand.DeleteArtifact -> {
            require(command.artifactId in snapshot.artifacts) { "创作成果不存在" }
            require(snapshot.attachments.none { it.artifactId == command.artifactId }) {
                "创作成果仍被内容引用"
            }
            withSnapshot(
                snapshot.copy(
                    artifacts = snapshot.artifacts - command.artifactId,
                    revisions = snapshot.revisions - command.artifactId,
                ),
            )
        }

        is NovexCreativeLibraryCommand.AttachArtifact -> {
            require(command.attachment.artifactId in snapshot.artifacts) { "创作成果不存在" }
            if (command.attachment in snapshot.attachments) {
                this
            } else {
                withSnapshot(
                    snapshot.copy(attachments = snapshot.attachments + command.attachment),
                )
            }
        }

        is NovexCreativeLibraryCommand.DetachArtifact -> withSnapshot(
            snapshot.copy(attachments = snapshot.attachments - command.attachment),
        )
    }

    private fun updateArtifact(
        artifactId: String,
        transform: (CreativeArtifact) -> CreativeArtifact,
    ): NovexCreativeLibrary {
        val artifact = requireNotNull(snapshot.artifacts[artifactId]) { "创作成果不存在" }
        return withSnapshot(
            snapshot.copy(artifacts = snapshot.artifacts + (artifactId to transform(artifact))),
        )
    }

    private fun withSnapshot(value: NovexCreativeLibrarySnapshot) = open(value)

    companion object {
        fun empty() = open(NovexCreativeLibrarySnapshot())

        fun open(snapshot: NovexCreativeLibrarySnapshot): NovexCreativeLibrary {
            require(snapshot.artifacts.all { (id, artifact) -> id == artifact.id }) {
                "创作成果必须存放在对应编号下"
            }
            require(snapshot.attachments.distinct().size == snapshot.attachments.size) {
                "创作成果引用不能重复"
            }
            require(snapshot.attachments.all { it.artifactId in snapshot.artifacts }) {
                "创作成果引用不能指向不存在的成果"
            }
            require(snapshot.revisions.keys.all { it in snapshot.artifacts }) {
                "成果版本不能指向不存在的成果"
            }
            val revisions = snapshot.revisions.values.flatten()
            require(revisions.map(CreativeArtifactRevision::id).distinct().size == revisions.size) {
                "成果版本编号不能重复"
            }
            snapshot.revisions.forEach { (artifactId, values) ->
                require(values.all { it.artifactId == artifactId }) { "成果版本归属不一致" }
                require(values.map { it.number }.sorted() == (1..values.size).toList()) {
                    "成果版本序号必须从一开始连续"
                }
            }
            return NovexCreativeLibrary(
                snapshot.copy(
                    artifacts = snapshot.artifacts.toMap(),
                    revisions = snapshot.revisions.mapValues { it.value.toList() },
                    attachments = snapshot.attachments.toList(),
                ),
            )
        }
    }
}
