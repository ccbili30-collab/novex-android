package com.openminis.app.novex.domain

enum class CreativeArtifactKind {
    DOCUMENT,
    IMAGE,
    MAP,
    CARD_ARCHIVE,
    OTHER,
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
) {
    init {
        require(id.isNotBlank()) { "创作成果编号不能为空" }
        require(title.isNotBlank()) { "创作成果名称不能为空" }
        require(storageKey.isNotBlank()) { "创作成果存储编号不能为空" }
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
    val attachments: List<CreativeArtifactAttachment> = emptyList(),
)

sealed interface NovexCreativeLibraryCommand {
    data class RegisterArtifact(val artifact: CreativeArtifact) : NovexCreativeLibraryCommand
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
            withSnapshot(
                snapshot.copy(
                    artifacts = snapshot.artifacts + (command.artifact.id to command.artifact),
                ),
            )
        }

        is NovexCreativeLibraryCommand.DeleteArtifact -> {
            require(command.artifactId in snapshot.artifacts) { "创作成果不存在" }
            require(snapshot.attachments.none { it.artifactId == command.artifactId }) {
                "创作成果仍被内容引用"
            }
            withSnapshot(
                snapshot.copy(artifacts = snapshot.artifacts - command.artifactId),
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
            return NovexCreativeLibrary(
                snapshot.copy(
                    artifacts = snapshot.artifacts.toMap(),
                    attachments = snapshot.attachments.toList(),
                ),
            )
        }
    }
}
