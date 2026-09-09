package com.openminis.app.novex.domain

import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class NovexCheckpointJourneyTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun legacyBubbleSaveIsRecoveredOnlyForItsPersistedSourceMessage() {
        val store = FileNovexConversationWorkspaceStore(files.root)
        val config = NovexConversationConfigurationSnapshot("chat", activePlaythroughId = "play",
            activeInteractiveFiction = ActiveInteractiveFictionSnapshot("game", "snapshot", "文游"))
        val oldScope = NovexConversationWorkspaceScope("chat", listOf("user", "reply"), "assistant_123")
        val checkpoint = NovexPlaythroughCheckpointFactory.create("legacy", config, listOf("user", "reply"), "assistant_123", "开场", "地点：邮局", "{}", 1)
        val old = NovexPlaythroughCheckpointWriter(store).save(oldScope, checkpoint, NovexWorkspaceProvenance("chat", "assistant_123", "reply", "call"))
        val original = store.readBytes(oldScope, old.workspaceRef)
        val reopened = FileNovexConversationWorkspaceStore(files.root)
        val sibling = NovexConversationWorkspaceScope("chat", listOf("user", "other-reply"), NovexConversationWorkspaceScope.ROOT_BRANCH)
        assertTrue(NovexCheckpointContinuation(reopened).inspect(sibling).isEmpty())
        val selected = sibling.copy(visibleBranchIds = listOf("user", "reply"))
        val records = NovexCheckpointContinuation(reopened).inspect(selected)
        assertEquals(1, records.size)
        assertEquals("reply", records.single().checkpoint!!.branchId)
        assertEquals("地点：邮局", records.single().checkpoint!!.summary)
        assertNotNull(NovexCheckpointContinuation(reopened).prepare(config, selected))
        assertTrue(reopened.readBytes(selected, records.single().entry.workspaceRef).isNotEmpty())
        assertArrayEquals(original, reopened.readBytes(oldScope, old.workspaceRef))
        assertEquals(1, NovexCheckpointContinuation(reopened).inspect(selected).size)
        assertTrue(NovexCheckpointContinuation(reopened).inspect(sibling).isEmpty())
    }
}
