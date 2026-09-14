package com.openminis.app.ui.chat

import com.openminis.app.novex.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovexConversationStatusTest {
    @Test fun gameSourcesAreVisibleEvenWithEmptyTopLevelBackgroundArray() {
        val source = NovexFrozenContext(NovexReferenceTarget(NovexContentAddress.world("world")), listOf(
            NovexContextCandidate("setting", "世界设定", "海上城市", ContextSourceKind.BACKGROUND_MODULE)))
        val game = ActiveInteractiveFictionSnapshot("game", "snapshot", "航海",
            JSONObject().put("linkedContext", JSONArray().put(NovexFrozenContextCodec.encode(source))).toString())
        val configuration = NovexConversationConfigurationSnapshot("chat", activeInteractiveFiction = game)
        val status = NovexConversationStatus.read(configuration)
        assertEquals("航海", status.game)
        assertEquals(1, status.sources)
        assertTrue(configuration.backgroundSettings.isEmpty())
        assertTrue(configuration.adoptedContexts.isEmpty())
    }

    @Test fun managementAloneIsNotShownAsAdoptedBackground() {
        val configuration = NovexConversationConfigurationSnapshot("chat", managedSubjects = listOf(
            ManagedSubject(NovexContentAddress.world("managed"), ManagedAccess.EDIT)))
        val status = NovexConversationStatus.read(configuration)
        assertEquals(0, status.sources)
        assertNull(status.game)
        assertEquals("Nova（诺瓦）", status.answer)
    }
}
