package com.openminis.app.novex.domain

enum class NovexContentKind {
    WORLD,
    CHARACTER_VERSION,
    INTERACTIVE_FICTION,
    CREATIVE_ARTIFACT,
}

data class NovexContentAddress(
    val kind: NovexContentKind,
    val id: String,
) {
    init {
        require(id.isNotBlank()) { "内容编号不能为空" }
    }

    companion object {
        fun world(id: String) = NovexContentAddress(NovexContentKind.WORLD, id)
        fun characterVersion(id: String) = NovexContentAddress(NovexContentKind.CHARACTER_VERSION, id)
        fun interactiveFiction(id: String) = NovexContentAddress(NovexContentKind.INTERACTIVE_FICTION, id)
        fun creativeArtifact(id: String) = NovexContentAddress(NovexContentKind.CREATIVE_ARTIFACT, id)
    }
}

data class BackgroundSetting(
    val subject: NovexContentAddress,
) {
    init {
        require(
            subject.kind == NovexContentKind.WORLD ||
                subject.kind == NovexContentKind.CHARACTER_VERSION,
        ) { "背景设定只能引用世界或角色版本" }
    }
}

enum class ManagedAccess {
    READ_ONLY,
    EDIT,
}

data class ManagedSubject(
    val subject: NovexContentAddress,
    val access: ManagedAccess,
)

sealed interface AnswerIdentity {
    data object Nova : AnswerIdentity

    data class CharacterVersion(val versionId: String) : AnswerIdentity {
        init {
            require(versionId.isNotBlank()) { "回答身份的角色版本编号不能为空" }
        }
    }
}

data class ActiveInteractiveFictionSnapshot(
    val projectId: String,
    val snapshotId: String,
    val title: String,
    /** Complete immutable project payload used by the conversation runtime. */
    val contentJson: String = "{}",
    val presetControls: List<ConversationControlDefinition> = emptyList(),
) {
    init {
        require(projectId.isNotBlank()) { "文游项目编号不能为空" }
        require(snapshotId.isNotBlank()) { "文游快照编号不能为空" }
        require(title.isNotBlank()) { "文游名称不能为空" }
        require(presetControls.all { it.source == ConversationControlSource.PROJECT_PRESET }) {
            "文游快照只能携带文游预设操作"
        }
    }
}

sealed interface PlaythroughValue {
    data class Text(val value: String) : PlaythroughValue
    data class Number(val value: Double) : PlaythroughValue
    data class Flag(val value: Boolean) : PlaythroughValue
}

data class PlaythroughState(
    val branchId: String,
    val values: Map<String, PlaythroughValue> = emptyMap(),
) {
    init {
        require(branchId.isNotBlank()) { "消息分支编号不能为空" }
        require(values.keys.none(String::isBlank)) { "本局状态字段名不能为空" }
    }
}

enum class ConversationControlBehavior {
    VIEW,
    ACTION,
}

enum class ConversationControlSource {
    PROJECT_PRESET,
    AI,
    USER,
}

data class ConversationControlDefinition(
    val id: String,
    val label: String,
    val behavior: ConversationControlBehavior,
    val source: ConversationControlSource,
    val actionKey: String,
    val payloadJson: String = "{}",
    val enabled: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "快捷操作编号不能为空" }
        require(label.isNotBlank()) { "快捷操作名称不能为空" }
        require(actionKey.isNotBlank()) { "快捷操作行为编号不能为空" }
    }
}

data class NovexConversationConfigurationSnapshot(
    val conversationId: String,
    val answerIdentity: AnswerIdentity = AnswerIdentity.Nova,
    val backgroundSettings: List<BackgroundSetting> = emptyList(),
    val managedSubjects: List<ManagedSubject> = emptyList(),
    val activeInteractiveFiction: ActiveInteractiveFictionSnapshot? = null,
    val playthroughStates: Map<String, PlaythroughState> = emptyMap(),
    val controls: List<ConversationControlDefinition> = emptyList(),
)

sealed interface NovexConversationCommand {
    data class SetAnswerIdentity(val identity: AnswerIdentity) : NovexConversationCommand
    data class ActivateInteractiveFiction(
        val snapshot: ActiveInteractiveFictionSnapshot,
    ) : NovexConversationCommand
    data object DeactivateInteractiveFiction : NovexConversationCommand
    data class SetPlaythroughValue(
        val branchId: String,
        val key: String,
        val value: PlaythroughValue,
    ) : NovexConversationCommand
    data class ForkPlaythroughState(
        val sourceBranchId: String,
        val newBranchId: String,
    ) : NovexConversationCommand
    data class UpsertControl(
        val control: ConversationControlDefinition,
    ) : NovexConversationCommand
    data class MoveControl(
        val controlId: String,
        val toIndex: Int,
    ) : NovexConversationCommand
    data class RemoveControl(val controlId: String) : NovexConversationCommand
    data class AddBackground(val subject: NovexContentAddress) : NovexConversationCommand
    data class RemoveBackground(val subject: NovexContentAddress) : NovexConversationCommand
    data class MountSubject(
        val subject: NovexContentAddress,
        val access: ManagedAccess,
    ) : NovexConversationCommand
    data class UnmountSubject(val subject: NovexContentAddress) : NovexConversationCommand
}

class NovexConversationConfiguration private constructor(
    val snapshot: NovexConversationConfigurationSnapshot,
) {
    fun apply(command: NovexConversationCommand): NovexConversationConfiguration = when (command) {
        is NovexConversationCommand.SetAnswerIdentity -> withSnapshot(
            snapshot.copy(answerIdentity = command.identity),
        )

        is NovexConversationCommand.ActivateInteractiveFiction -> {
            val keepsCurrentPlaythrough = snapshot.activeInteractiveFiction == command.snapshot
            val localControls = snapshot.controls.filterNot {
                it.source == ConversationControlSource.PROJECT_PRESET
            }
            withSnapshot(
                snapshot.copy(
                    activeInteractiveFiction = command.snapshot,
                    playthroughStates = if (keepsCurrentPlaythrough) {
                        snapshot.playthroughStates
                    } else {
                        emptyMap()
                    },
                    controls = localControls + command.snapshot.presetControls,
                ),
            )
        }

        NovexConversationCommand.DeactivateInteractiveFiction -> withSnapshot(
            snapshot.copy(
                activeInteractiveFiction = null,
                controls = snapshot.controls.filterNot {
                    it.source == ConversationControlSource.PROJECT_PRESET
                },
            ),
        )

        is NovexConversationCommand.SetPlaythroughValue -> {
            require(snapshot.activeInteractiveFiction != null) {
                "没有活动文游时不能修改本局状态"
            }
            require(command.branchId.isNotBlank()) { "消息分支编号不能为空" }
            require(command.key.isNotBlank()) { "本局状态字段名不能为空" }
            val prior = snapshot.playthroughStates[command.branchId]
                ?: PlaythroughState(command.branchId)
            val updated = prior.copy(values = prior.values + (command.key to command.value))
            withSnapshot(
                snapshot.copy(
                    playthroughStates = snapshot.playthroughStates + (command.branchId to updated),
                ),
            )
        }

        is NovexConversationCommand.ForkPlaythroughState -> {
            require(snapshot.activeInteractiveFiction != null) {
                "没有活动文游时不能创建本局分支状态"
            }
            require(command.sourceBranchId.isNotBlank()) { "来源消息分支编号不能为空" }
            require(command.newBranchId.isNotBlank()) { "新消息分支编号不能为空" }
            require(command.newBranchId !in snapshot.playthroughStates) {
                "新消息分支已经存在本局状态"
            }
            val source = snapshot.playthroughStates[command.sourceBranchId]
                ?: PlaythroughState(command.sourceBranchId)
            val forked = source.copy(branchId = command.newBranchId, values = source.values.toMap())
            withSnapshot(
                snapshot.copy(
                    playthroughStates = snapshot.playthroughStates +
                        (command.newBranchId to forked),
                ),
            )
        }

        is NovexConversationCommand.UpsertControl -> {
            val existingIndex = snapshot.controls.indexOfFirst { it.id == command.control.id }
            val controls = if (existingIndex < 0) {
                snapshot.controls + command.control
            } else {
                snapshot.controls.toMutableList().apply {
                    set(existingIndex, command.control)
                }
            }
            withSnapshot(snapshot.copy(controls = controls))
        }

        is NovexConversationCommand.MoveControl -> {
            val fromIndex = snapshot.controls.indexOfFirst { it.id == command.controlId }
            require(fromIndex >= 0) { "快捷操作不存在" }
            val controls = snapshot.controls.toMutableList()
            val control = controls.removeAt(fromIndex)
            controls.add(command.toIndex.coerceIn(0, controls.size), control)
            withSnapshot(snapshot.copy(controls = controls))
        }

        is NovexConversationCommand.RemoveControl -> withSnapshot(
            snapshot.copy(controls = snapshot.controls.filterNot { it.id == command.controlId }),
        )

        is NovexConversationCommand.AddBackground -> {
            val setting = BackgroundSetting(command.subject)
            if (snapshot.backgroundSettings.any { it.subject == command.subject }) {
                this
            } else {
                withSnapshot(snapshot.copy(backgroundSettings = snapshot.backgroundSettings + setting))
            }
        }

        is NovexConversationCommand.RemoveBackground -> withSnapshot(
            snapshot.copy(
                backgroundSettings = snapshot.backgroundSettings.filterNot {
                    it.subject == command.subject
                },
            ),
        )

        is NovexConversationCommand.MountSubject -> {
            val mounted = ManagedSubject(command.subject, command.access)
            val existingIndex = snapshot.managedSubjects.indexOfFirst {
                it.subject == command.subject
            }
            val subjects = if (existingIndex < 0) {
                snapshot.managedSubjects + mounted
            } else {
                snapshot.managedSubjects.toMutableList().apply { set(existingIndex, mounted) }
            }
            withSnapshot(snapshot.copy(managedSubjects = subjects))
        }

        is NovexConversationCommand.UnmountSubject -> withSnapshot(
            snapshot.copy(
                managedSubjects = snapshot.managedSubjects.filterNot {
                    it.subject == command.subject
                },
            ),
        )
    }

    private fun withSnapshot(value: NovexConversationConfigurationSnapshot) = open(value)

    companion object {
        fun empty(conversationId: String): NovexConversationConfiguration {
            return open(
                NovexConversationConfigurationSnapshot(conversationId = conversationId),
            )
        }

        fun open(snapshot: NovexConversationConfigurationSnapshot): NovexConversationConfiguration {
            require(snapshot.conversationId.isNotBlank()) { "对话编号不能为空" }
            require(
                snapshot.backgroundSettings.map(BackgroundSetting::subject).distinct().size ==
                    snapshot.backgroundSettings.size,
            ) { "背景设定不能重复" }
            require(
                snapshot.managedSubjects.map(ManagedSubject::subject).distinct().size ==
                    snapshot.managedSubjects.size,
            ) { "管理对象不能重复" }
            require(snapshot.controls.map(ConversationControlDefinition::id).distinct().size == snapshot.controls.size) {
                "快捷操作编号不能重复"
            }
            require(snapshot.playthroughStates.all { (branchId, state) -> branchId == state.branchId }) {
                "本局状态必须属于对应的消息分支"
            }
            val detached = snapshot.copy(
                backgroundSettings = snapshot.backgroundSettings.toList(),
                managedSubjects = snapshot.managedSubjects.toList(),
                playthroughStates = snapshot.playthroughStates.mapValues { (_, state) ->
                    state.copy(values = state.values.toMap())
                },
                controls = snapshot.controls.toList(),
            )
            return NovexConversationConfiguration(detached)
        }
    }
}
