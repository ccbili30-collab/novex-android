package com.openminis.app.novex.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexCreativeArtifactAccessPolicyTest {
    private val world = NovexContentAddress.world("world-1")
    private val character = NovexContentAddress.characterVersion("character-version-1")
    private val game = NovexContentAddress.interactiveFiction("game-1")

    @Test
    fun `current conversation can read its own artifact`() {
        assertTrue(
            isCreativeArtifactAccessibleToConversation(
                originConversationId = "conversation-1",
                attachments = emptyList(),
                configuration = configuration(),
                conversationId = "conversation-1",
            ),
        )
    }

    @Test
    fun `foreign artifact requires an explicitly mounted owner`() {
        assertFalse(accessibleTo(configuration(), world))
        assertTrue(
            accessibleTo(
                configuration = configuration(background = world),
                attachedOwner = world,
            ),
        )
        assertTrue(
            accessibleTo(
                configuration = configuration(managed = character),
                attachedOwner = character,
            ),
        )
        assertTrue(
            accessibleTo(
                configuration = configuration(gameId = game.id),
                attachedOwner = game,
            ),
        )
    }

    private fun accessibleTo(
        configuration: NovexConversationConfigurationSnapshot,
        attachedOwner: NovexContentAddress,
    ): Boolean = isCreativeArtifactAccessibleToConversation(
        originConversationId = "other-conversation",
        attachments = listOf(CreativeArtifactAttachment("artifact-1", attachedOwner)),
        configuration = configuration,
        conversationId = "conversation-1",
    )

    private fun configuration(
        background: NovexContentAddress? = null,
        managed: NovexContentAddress? = null,
        gameId: String? = null,
    ) = NovexConversationConfigurationSnapshot(
        conversationId = "conversation-1",
        backgroundSettings = listOfNotNull(background?.let(::BackgroundSetting)),
        managedSubjects = listOfNotNull(managed?.let { ManagedSubject(it, ManagedAccess.READ_ONLY) }),
        activeInteractiveFiction = gameId?.let {
            ActiveInteractiveFictionSnapshot(it, "snapshot-1", "文游")
        },
    )
}
