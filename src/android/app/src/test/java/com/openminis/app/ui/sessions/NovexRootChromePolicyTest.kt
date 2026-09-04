package com.openminis.app.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class NovexRootChromePolicyTest {
    @Test
    fun everyRootUsesTheSameTopActionsAndOnlyChangesItsTitle() {
        NovexRootSpace.entries.forEach { space ->
            val chrome = novexRootChrome(space)
            assertEquals(
                listOf(
                    NovexRootTopAction.SETTINGS,
                    NovexRootTopAction.UPDATE,
                    NovexRootTopAction.SEARCH,
                    NovexRootTopAction.CREATE,
                ),
                chrome.actions,
            )
        }
        assertEquals("Novex", novexRootChrome(NovexRootSpace.CONVERSATIONS).title)
        assertEquals("世界", novexRootChrome(NovexRootSpace.WORLDS).title)
        assertEquals("角色", novexRootChrome(NovexRootSpace.CHARACTERS).title)
        assertEquals("文游", novexRootChrome(NovexRootSpace.INTERACTIVE_FICTION).title)
    }

    @Test
    fun conversationCreateMenuStartsOneConversationWithOptionalContextOrTool() {
        assertEquals(
            listOf(
                NovexConversationStart.EMPTY,
                NovexConversationStart.WORLD_CONTEXT,
                NovexConversationStart.CREATION_TOOL,
            ),
            novexConversationCreateMenu(),
        )
    }

    @Test
    fun backFromAnyLibraryReturnsToConversationsBeforeLeavingRoot() {
        assertEquals(
            NovexRootBackAction.SWITCH_TO_CONVERSATIONS,
            novexRootBackAction(NovexRootSpace.WORLDS, searchActive = false),
        )
        assertEquals(
            NovexRootBackAction.SWITCH_TO_CONVERSATIONS,
            novexRootBackAction(NovexRootSpace.CHARACTERS, searchActive = false),
        )
        assertEquals(
            NovexRootBackAction.SWITCH_TO_CONVERSATIONS,
            novexRootBackAction(NovexRootSpace.INTERACTIVE_FICTION, searchActive = false),
        )
        assertEquals(
            NovexRootBackAction.LEAVE_APPLICATION,
            novexRootBackAction(NovexRootSpace.CONVERSATIONS, searchActive = false),
        )
    }
}
