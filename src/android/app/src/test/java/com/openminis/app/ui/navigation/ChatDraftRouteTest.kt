package com.openminis.app.ui.navigation

import com.openminis.app.novex.domain.NovexContentAddress
import com.openminis.app.novex.domain.NovexConversationConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexChatDraftRouteTest {
    @Test
    fun normalizedWorldRoleDraftKeepsConcreteVersionAndPersona() {
        assertEquals(
            "__new__draft-0__version__version-1__world__world-1__persona__player-1",
            buildChatDraftId(
                draftId = "draft-0",
                context = ChatDraftContext(
                    worldId = "world-1",
                    characterVersionId = "version-1",
                    personaId = "player-1",
                ),
            ),
        )
    }

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

    @Test
    fun creationDraftCarriesEditableWorldMountWithoutTurningItIntoBackground() {
        val route = buildChatDraftId(
            draftId = "draft-world",
            context = ChatDraftContext(
                managedSubjects = listOf(NovexContentAddress.world("world-7")),
            ),
        )

        assertEquals("__new__draft-world__managedWorld__world-7", route)
        assertEquals(
            listOf(NovexContentAddress.world("world-7")),
            managedSubjectsFromChatDraftId(route),
        )
    }

    @Test
    fun creationDraftCanTargetConcreteCharacterVersionOrInteractiveFiction() {
        val route = buildChatDraftId(
            draftId = "draft-managed",
            context = ChatDraftContext(
                managedSubjects = listOf(
                    NovexContentAddress.characterVersion("version-3"),
                    NovexContentAddress.interactiveFiction("game-2"),
                ),
            ),
        )

        assertEquals(
            "__new__draft-managed__managedVersion__version-3__managedGame__game-2",
            route,
        )
        assertEquals(
            listOf(
                NovexContentAddress.characterVersion("version-3"),
                NovexContentAddress.interactiveFiction("game-2"),
            ),
            managedSubjectsFromChatDraftId(route),
        )
    }

    @Test
    fun creationDraftMountsTargetsForEditingButDoesNotInjectThemAsBackground() {
        val route = buildChatDraftId(
            draftId = "draft-create",
            context = ChatDraftContext(
                managedSubjects = listOf(NovexContentAddress.world("world-9")),
            ),
        )

        val configured = applyDraftManagedSubjects(
            draftId = route,
            configuration = NovexConversationConfiguration.empty(route).snapshot,
        )

        assertTrue(configured.backgroundSettings.isEmpty())
        assertEquals(NovexContentAddress.world("world-9"), configured.managedSubjects.single().subject)
        assertEquals(com.openminis.app.novex.domain.ManagedAccess.EDIT, configured.managedSubjects.single().access)
    }
}
