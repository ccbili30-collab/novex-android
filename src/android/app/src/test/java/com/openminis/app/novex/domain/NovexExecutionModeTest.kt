package com.openminis.app.novex.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovexExecutionModeTest {
    @Test fun `new conversation defaults to free without adopting or managing cards`() {
        val fresh = NovexConversationConfigurationCodec.decode(null, "new-chat")
        assertEquals(NovexExecutionMode.FREE, fresh.executionMode)
        assertTrue(fresh.executionMode.exposesTools)
        assertTrue(fresh.managedSubjects.isEmpty())
        assertTrue(fresh.backgroundSettings.isEmpty())
        assertNull(fresh.activeInteractiveFiction)
        assertEquals(fresh, NovexConversationConfigurationCodec.decode(
            NovexConversationConfigurationCodec.encode(fresh), "new-chat"))
    }

    @Test fun `old edit access does not migrate to free execution and identities survive`() {
        val original = NovexConversationConfigurationSnapshot("chat",
            answerIdentity = AnswerIdentity.PersonaPreset("custom", "我的身份", "保持这段原话"),
            managedSubjects = listOf(ManagedSubject(NovexContentAddress.world("world"), ManagedAccess.EDIT)))
        val old = JSONObject(NovexConversationConfigurationCodec.encode(original)).apply { remove("executionMode") }
        val restored = NovexConversationConfigurationCodec.decode(old.toString(), "chat")
        assertEquals(NovexExecutionMode.APPROVAL, restored.executionMode)
        assertEquals(original.answerIdentity, restored.answerIdentity)
        assertEquals(original.managedSubjects, restored.managedSubjects)
    }

    @Test fun `mode is per conversation survives encode and only readonly removes tools`() {
        for (mode in NovexExecutionMode.entries) {
            val changed = NovexConversationConfiguration.empty("chat").apply(NovexConversationCommand.SetExecutionMode(mode)).snapshot
            assertEquals(mode, NovexConversationConfigurationCodec.decode(NovexConversationConfigurationCodec.encode(changed), "chat").executionMode)
            assertEquals(mode != NovexExecutionMode.READ_ONLY, mode.exposesTools)
            assertEquals(NovexExecutionMode.FREE, NovexConversationConfiguration.empty("other").snapshot.executionMode)
        }
    }
}
