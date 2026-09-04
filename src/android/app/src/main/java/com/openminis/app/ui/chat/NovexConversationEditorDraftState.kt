package com.openminis.app.ui.chat

import com.openminis.app.data.ConversationSettingsSnapshot
import com.openminis.app.novex.domain.ActiveInteractiveFictionSnapshot
import com.openminis.app.novex.domain.AnswerIdentity
import com.openminis.app.novex.domain.ConversationControlDefinition
import com.openminis.app.novex.domain.ManagedAccess
import com.openminis.app.novex.domain.NovexContentAddress
import com.openminis.app.novex.domain.NovexConversationCommand
import com.openminis.app.novex.domain.NovexConversationConfiguration
import com.openminis.app.novex.domain.NovexConversationConfigurationCodec
import com.openminis.app.novex.domain.NovexConversationConfigurationSnapshot

/** Labelled catalog entry used by the editor without exposing repositories to presentation code. */
internal data class ConversationContentOption(
    val address: NovexContentAddress,
    val label: String,
    val kindLabel: String,
)

/** Immutable editor state. Every structural change goes through the shared domain command surface. */
internal data class NovexConversationEditorDraftState(
    val settings: ConversationSettingsSnapshot,
    val configuration: NovexConversationConfigurationSnapshot,
) {
    fun updateSettings(
        edit: (ConversationSettingsSnapshot) -> ConversationSettingsSnapshot,
    ) = copy(settings = edit(settings))

    fun setAnswerIdentity(identity: AnswerIdentity) = apply(
        NovexConversationCommand.SetAnswerIdentity(identity),
    )

    fun addBackground(subject: NovexContentAddress) = apply(
        NovexConversationCommand.AddBackground(subject),
    )

    fun removeBackground(subject: NovexContentAddress) = apply(
        NovexConversationCommand.RemoveBackground(subject),
    )

    fun activateGame(snapshot: ActiveInteractiveFictionSnapshot) = apply(
        NovexConversationCommand.ActivateInteractiveFiction(snapshot),
    )

    fun deactivateGame() = apply(NovexConversationCommand.DeactivateInteractiveFiction)

    fun mount(subject: NovexContentAddress, access: ManagedAccess) = apply(
        NovexConversationCommand.MountSubject(subject, access),
    )

    fun unmount(subject: NovexContentAddress) = apply(
        NovexConversationCommand.UnmountSubject(subject),
    )

    fun upsertControl(control: ConversationControlDefinition) = apply(
        NovexConversationCommand.UpsertControl(control),
    )

    fun moveControl(controlId: String, toIndex: Int) = apply(
        NovexConversationCommand.MoveControl(controlId, toIndex),
    )

    fun removeControl(controlId: String) = apply(
        NovexConversationCommand.RemoveControl(controlId),
    )

    fun toSettings(): ConversationSettingsSnapshot = settings.copy(
        novexConfigurationJson = NovexConversationConfigurationCodec.encode(configuration),
    )

    private fun apply(command: NovexConversationCommand): NovexConversationEditorDraftState = copy(
        configuration = NovexConversationConfiguration.open(configuration).apply(command).snapshot,
    )

    companion object {
        fun from(
            conversationId: String,
            settings: ConversationSettingsSnapshot,
        ): NovexConversationEditorDraftState = NovexConversationEditorDraftState(
            settings = settings,
            configuration = NovexConversationConfigurationCodec.decode(
                settings.novexConfigurationJson,
                conversationId,
            ),
        )
    }
}
