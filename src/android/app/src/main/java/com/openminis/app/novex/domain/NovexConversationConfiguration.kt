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

    /** Conversation-owned snapshot, not a live reference to a mutable preset or character card. */
    data class PersonaPreset(
        val presetId: String,
        val label: String,
        val instructions: String = "",
    ) : AnswerIdentity {
        init {
            require(presetId.isNotBlank()) { "人格预设编号不能为空" }
            require(label.isNotBlank()) { "人格名称不能为空" }
        }
    }

    data class CharacterVersion(val versionId: String) : AnswerIdentity {
        init {
            require(versionId.isNotBlank()) { "回答身份的角色版本编号不能为空" }
        }
    }
}

object NovexPersonaPresets {
    val gameHost = AnswerIdentity.PersonaPreset(
        "novex:game-host", "游戏主持人",
        "你是本局游戏主持人。依照活动文游的规则组织开局与推进，维护可核对的状态，尊重玩家自主行动；缺少世界时可以按文游规则与用户共创。管理作品时执行真实工具任务，不把操作结果伪装成剧情。",
    )
}

data class ConversationPlayerIdentity(
    val id: String,
    val label: String,
    val description: String = "",
) {
    init {
        require(id.isNotBlank()) { "玩家身份编号不能为空" }
    }
}

data class ActiveInteractiveFictionSnapshot(
    val projectId: String,
    val snapshotId: String,
    val title: String,
    /** Complete immutable project payload used by the conversation runtime. */
    val contentJson: String = "{}",
    val presetControls: List<ConversationControlDefinition> = emptyList(),
    val playerIdentity: ConversationPlayerIdentity? = null,
    val answerIdentity: AnswerIdentity? = null,
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
    /** Null means conversation-wide; AI-created controls use the reply branch that created them. */
    val branchId: String? = null,
) {
    init {
        require(id.isNotBlank()) { "快捷操作编号不能为空" }
        require(label.isNotBlank()) { "快捷操作名称不能为空" }
        require(actionKey.isNotBlank()) { "快捷操作行为编号不能为空" }
        require(branchId == null || branchId.isNotBlank()) { "快捷操作分支编号不能为空" }
    }
}

data class CompletedPlaythrough(
    val game: ActiveInteractiveFictionSnapshot,
    val states: Map<String, PlaythroughState>,
    val controls: List<ConversationControlDefinition>,
    val playthroughId: String = "",
)

data class NovexConversationConfigurationSnapshot(
    val conversationId: String,
    val answerIdentity: AnswerIdentity = AnswerIdentity.Nova,
    val backgroundSettings: List<BackgroundSetting> = emptyList(),
    val managedSubjects: List<ManagedSubject> = emptyList(),
    val activeInteractiveFiction: ActiveInteractiveFictionSnapshot? = null,
    val playthroughStates: Map<String, PlaythroughState> = emptyMap(),
    val controls: List<ConversationControlDefinition> = emptyList(),
    val preGameAnswerIdentity: AnswerIdentity? = null,
    val playerIdentity: ConversationPlayerIdentity? = null,
    val preGamePlayerIdentity: ConversationPlayerIdentity? = null,
    val completedPlaythroughs: List<CompletedPlaythrough> = emptyList(),
    val activePlaythroughId: String? = null,
    val adoptedContexts: List<NovexAdoptedContext> = emptyList(),
    val preGameAdoptedIdentity: NovexAdoptedContext? = null,
    val disabledSettings: Set<NovexReferenceTarget> = emptySet(),
) {
    val effectivePlaythroughId: String?
        get() = activeInteractiveFiction?.let { activePlaythroughId ?: "legacy:${it.snapshotId}" }

    val hasPersistentConfiguration: Boolean
        get() = answerIdentity != AnswerIdentity.Nova || playerIdentity != null ||
            backgroundSettings.isNotEmpty() || managedSubjects.isNotEmpty() ||
            activeInteractiveFiction != null || completedPlaythroughs.isNotEmpty() ||
            playthroughStates.isNotEmpty() || controls.isNotEmpty() || disabledSettings.isNotEmpty()
}

sealed interface NovexConversationCommand {
    data class SetPlayerIdentity(val identity: ConversationPlayerIdentity?) : NovexConversationCommand
    data class SetAnswerIdentity(val identity: AnswerIdentity) : NovexConversationCommand
    data class ActivateInteractiveFiction(
        val snapshot: ActiveInteractiveFictionSnapshot,
        val replacePlayerIdentity: Boolean = false,
        val playthroughId: String = java.util.UUID.randomUUID().toString(),
    ) : NovexConversationCommand
    data class EndInteractiveFiction(val playthroughId: String) : NovexConversationCommand
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
    data class SetSettingEnabled(val target: NovexReferenceTarget, val enabled: Boolean) : NovexConversationCommand
    data class MountSubject(
        val subject: NovexContentAddress,
        val access: ManagedAccess,
    ) : NovexConversationCommand
    data class UnmountSubject(val subject: NovexContentAddress) : NovexConversationCommand
}

class NovexConversationConfiguration private constructor(
    val snapshot: NovexConversationConfigurationSnapshot,
) {
    private fun archiveCurrentPlaythrough(): List<CompletedPlaythrough> = snapshot.activeInteractiveFiction?.let { game ->
        snapshot.completedPlaythroughs + CompletedPlaythrough(game, snapshot.playthroughStates, snapshot.controls, snapshot.effectivePlaythroughId.orEmpty())
    } ?: snapshot.completedPlaythroughs

    fun apply(command: NovexConversationCommand): NovexConversationConfiguration = when (command) {
        is NovexConversationCommand.EndInteractiveFiction -> {
            require(command.playthroughId.isNotBlank()) { "结束文游需要本局编号" }
            if (snapshot.activeInteractiveFiction == null && snapshot.completedPlaythroughs.lastOrNull()?.playthroughId == command.playthroughId) {
                this
            } else {
                require(snapshot.effectivePlaythroughId == command.playthroughId) { "当前局次已经变化，没有结束任何文游；请重新读取当前局次" }
                apply(NovexConversationCommand.DeactivateInteractiveFiction)
            }
        }
        is NovexConversationCommand.SetPlayerIdentity -> withSnapshot(snapshot.copy(playerIdentity = command.identity))
        is NovexConversationCommand.SetAnswerIdentity -> withSnapshot(
            snapshot.copy(answerIdentity = command.identity),
        )

        is NovexConversationCommand.ActivateInteractiveFiction -> {
            require(command.playthroughId.isNotBlank()) { "本局编号不能为空" }
            require(!NovexGamePlayerChoices.needsSelection(command.snapshot)) { "存在多个玩家身份来源，请明确选择后启动，不能自动拼接或覆盖" }
            val keepsCurrentPlaythrough = snapshot.activeInteractiveFiction == command.snapshot
            val requestedPlayer = command.snapshot.playerIdentity
            require(keepsCurrentPlaythrough || requestedPlayer == null || snapshot.playerIdentity == null ||
                requestedPlayer == snapshot.playerIdentity || command.replacePlayerIdentity) {
                "文游玩家身份与当前选择不同，请确认替换后再启动"
            }
            val localControls = snapshot.controls.filterNot {
                it.source == ConversationControlSource.PROJECT_PRESET ||
                    (!keepsCurrentPlaythrough && it.source == ConversationControlSource.AI)
            }
            withSnapshot(
                snapshot.copy(
                    activeInteractiveFiction = command.snapshot,
                    activePlaythroughId = if (keepsCurrentPlaythrough) snapshot.activePlaythroughId else command.playthroughId,
                    completedPlaythroughs = if (keepsCurrentPlaythrough) snapshot.completedPlaythroughs else archiveCurrentPlaythrough(),
                    answerIdentity = if (keepsCurrentPlaythrough) snapshot.answerIdentity else command.snapshot.answerIdentity ?: NovexPersonaPresets.gameHost,
                    preGameAnswerIdentity = if (snapshot.activeInteractiveFiction == null) {
                        snapshot.answerIdentity
                    } else snapshot.preGameAnswerIdentity,
                    preGameAdoptedIdentity = if (snapshot.activeInteractiveFiction == null) {
                        snapshot.adoptedContexts.singleOrNull { it.acting && it.isActive(snapshot) }
                    } else snapshot.preGameAdoptedIdentity,
                    playerIdentity = if (keepsCurrentPlaythrough) snapshot.playerIdentity else requestedPlayer ?: snapshot.playerIdentity,
                    preGamePlayerIdentity = if (snapshot.activeInteractiveFiction == null) {
                        snapshot.playerIdentity
                    } else snapshot.preGamePlayerIdentity,
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
                activePlaythroughId = null,
                completedPlaythroughs = archiveCurrentPlaythrough(),
                answerIdentity = snapshot.preGameAnswerIdentity ?: snapshot.answerIdentity,
                preGameAnswerIdentity = null,
                adoptedContexts = snapshot.preGameAdoptedIdentity?.let { original ->
                    snapshot.adoptedContexts.filterNot { it.acting } + original
                } ?: snapshot.adoptedContexts,
                preGameAdoptedIdentity = null,
                playerIdentity = if (snapshot.preGameAnswerIdentity != null) snapshot.preGamePlayerIdentity else snapshot.playerIdentity,
                preGamePlayerIdentity = null,
                controls = snapshot.controls.filterNot {
                    it.source != ConversationControlSource.USER
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

        is NovexConversationCommand.SetSettingEnabled -> {
            NovexSettingUse.validateToggle(snapshot, command.target, command.enabled)
            withSnapshot(snapshot.copy(disabledSettings = if (command.enabled) snapshot.disabledSettings - command.target
                else snapshot.disabledSettings + command.target))
        }

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
            require(snapshot.adoptedContexts.map { it.root to it.acting }.distinct().size == snapshot.adoptedContexts.size) {
                "同一对象的同一用途只能采用一份快照"
            }
            require(snapshot.playthroughStates.all { (branchId, state) -> branchId == state.branchId }) {
                "本局状态必须属于对应的消息分支"
            }
            val detached = snapshot.copy(
                backgroundSettings = snapshot.backgroundSettings.toList(),
                disabledSettings = snapshot.disabledSettings.toSet(),
                managedSubjects = snapshot.managedSubjects.toList(),
                playthroughStates = snapshot.playthroughStates.mapValues { (_, state) ->
                    state.copy(values = state.values.toMap())
                },
                controls = snapshot.controls.toList(),
                adoptedContexts = snapshot.adoptedContexts.map { adopted -> adopted.copy(sources = adopted.sources.map { source ->
                    source.copy(candidates = source.candidates.toList())
                }) },
                completedPlaythroughs = snapshot.completedPlaythroughs.map { completed ->
                    completed.copy(states = completed.states.mapValues { (_, state) -> state.copy(values = state.values.toMap()) },
                        controls = completed.controls.toList(), game = completed.game.copy(presetControls = completed.game.presetControls.toList()))
                },
            )
            return NovexConversationConfiguration(detached)
        }
    }
}
