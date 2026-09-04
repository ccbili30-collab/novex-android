package com.openminis.app.ui.chat

import com.openminis.app.data.ConversationSettingsSnapshot
import com.openminis.app.novex.domain.ActiveInteractiveFictionSnapshot
import com.openminis.app.novex.domain.AnswerIdentity
import com.openminis.app.novex.domain.ConversationControlBehavior
import com.openminis.app.novex.domain.ConversationControlDefinition
import com.openminis.app.novex.domain.ConversationControlSource
import com.openminis.app.novex.domain.ManagedAccess
import com.openminis.app.novex.domain.NovexContentAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexConversationEditorDraftStateTest {
    private val world = NovexContentAddress.world("world-1")
    private val role = NovexContentAddress.characterVersion("role-v2")
    private val game = ActiveInteractiveFictionSnapshot("game-1", "snapshot-1", "云海冒险")

    @Test
    fun backgroundAndManagedSubjectsStayIndependentEvenForTheSameCard() {
        val draft = emptyDraft()
            .addBackground(world)
            .mount(world, ManagedAccess.EDIT)
            .removeBackground(world)

        assertTrue(draft.configuration.backgroundSettings.isEmpty())
        assertEquals(world, draft.configuration.managedSubjects.single().subject)
        assertEquals(ManagedAccess.EDIT, draft.configuration.managedSubjects.single().access)
    }

    @Test
    fun answerIdentityAndActiveGameHaveExactlyOneCurrentValue() {
        val draft = emptyDraft()
            .setAnswerIdentity(AnswerIdentity.CharacterVersion(role.id))
            .activateGame(game)
            .activateGame(game.copy(projectId = "game-2", snapshotId = "snapshot-2", title = "新文游"))

        assertEquals(AnswerIdentity.CharacterVersion(role.id), draft.configuration.answerIdentity)
        assertEquals("game-2", draft.configuration.activeInteractiveFiction?.projectId)
    }

    @Test
    fun controlsCanBeAddedUpdatedMovedDisabledAndRemovedWithoutChangingPrompts() {
        val status = control("status", "角色状态")
        val inventory = control("inventory", "物品栏")
        val draft = emptyDraft()
            .upsertControl(status)
            .upsertControl(inventory)
            .moveControl("inventory", 0)
            .upsertControl(status.copy(label = "状态", enabled = false))

        assertEquals(listOf("inventory", "status"), draft.configuration.controls.map { it.id })
        assertFalse(draft.configuration.controls.last().enabled)
        assertEquals("原始提示词", draft.toSettings().conversationPrompt)
        assertEquals("图片风格", draft.toSettings().imageStylePrompt)
        assertTrue(draft.removeControl("inventory").configuration.controls.single().id == "status")
    }

    @Test
    fun encodedSettingsRoundTripEveryEditorSection() {
        val settings = emptyDraft()
            .setAnswerIdentity(AnswerIdentity.CharacterVersion(role.id))
            .addBackground(world)
            .addBackground(role)
            .activateGame(game)
            .mount(world, ManagedAccess.READ_ONLY)
            .upsertControl(control("status", "角色状态"))
            .toSettings()

        val reopened = NovexConversationEditorDraftState.from("chat-1", settings)

        assertEquals(AnswerIdentity.CharacterVersion(role.id), reopened.configuration.answerIdentity)
        assertEquals(listOf(world, role), reopened.configuration.backgroundSettings.map { it.subject })
        assertEquals(game, reopened.configuration.activeInteractiveFiction)
        assertEquals(world, reopened.configuration.managedSubjects.single().subject)
        assertEquals("status", reopened.configuration.controls.single().id)
    }

    private fun emptyDraft() = NovexConversationEditorDraftState.from(
        conversationId = "chat-1",
        settings = ConversationSettingsSnapshot(
            conversationPrompt = "原始提示词",
            imageStylePrompt = "图片风格",
        ),
    )

    private fun control(id: String, label: String) = ConversationControlDefinition(
        id = id,
        label = label,
        behavior = ConversationControlBehavior.VIEW,
        source = ConversationControlSource.USER,
        actionKey = "view.$id",
    )
}
