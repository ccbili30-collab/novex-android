package com.openminis.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatDraftRouteTest {
    @Test
    fun roleChatDraftKeepsCharacterAndPersona() {
        assertEquals(
            "__new__draft-1__char__role-1__persona__player-1",
            buildChatDraftId(
                draftId = "draft-1",
                context = ChatDraftContext(
                    worldId = "world-1",
                    characterId = "role-1",
                    personaId = "player-1",
                ),
            ),
        )
    }

    @Test
    fun worldNovaxDraftKeepsWorldAndPersonaWithoutInventingRole() {
        assertEquals(
            "__new__draft-2__world__world-1__persona__player-1",
            buildChatDraftId(
                draftId = "draft-2",
                context = ChatDraftContext(worldId = "world-1", personaId = "player-1"),
            ),
        )
    }

    @Test
    fun generalNovaxDraftStaysGeneral() {
        assertEquals("__new__draft-3", buildChatDraftId("draft-3"))
    }
}
