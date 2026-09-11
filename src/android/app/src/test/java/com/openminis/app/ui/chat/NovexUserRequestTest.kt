package com.openminis.app.ui.chat
import com.openminis.app.data.model.*
import org.junit.Assert.*
import org.junit.Test

class NovexUserRequestTest {
    private fun user(id: String, text: String = "", parts: List<AgentContentPart> = emptyList()) =
        LLMMessage(LLMMessage.Role.USER, text, contentParts = parts, dbMessageId = id)
    @Test fun attachmentOnlyOwnsTheRequestInsteadOfPreviousText() {
        val attachment = user("file", parts = listOf(AgentContentPart.Text("<novex-document-receipts>document</novex-document-receipts>")))
        assertEquals("file", latestNovexUserRequest(listOf(user("previous", "hello"), attachment))?.dbMessageId)
        assertEquals("file", latestNovexUserRequest(listOf(attachment))?.dbMessageId)
    }
    @Test fun resultsAreNotUserRequestsEvenAfterReloadAndContinuation() {
        val attachment = user("file", parts = listOf(AgentContentPart.Text("receipt")))
        val result = user("tool-result", "read content", listOf(AgentContentPart.ToolResult("call", "document_read", "body")))
        val synthetic = LLMMessage(LLMMessage.Role.USER,"continue")
        assertEquals("file",latestNovexUserRequest(listOf(attachment,result,synthetic))?.dbMessageId)
        assertNull(latestNovexUserRequest(listOf(result,synthetic)))
    }
    @Test fun queuedAttachmentBecomesTheNewRequestAndAudioCounts() {
        val first=user("first","hello")
        val queued=user("queued",parts=listOf(AgentContentPart.Text("file receipt")))
        assertEquals("queued",latestNovexUserRequest(listOf(first,queued))?.dbMessageId)
        val audio=user("audio").copy(audioParts=listOf(LLMMessage.AudioPart("wav","data")))
        assertEquals("audio",latestNovexUserRequest(listOf(queued,audio))?.dbMessageId)
    }
    @Test fun persistedResumeReminderDoesNotStealAttachmentRequestOwnership() {
        val attachment=user("file",parts=listOf(AgentContentPart.Text("<novex-document-receipts>file</novex-document-receipts>")))
        val reminder="<system-reminder>继续此前未完成的回复</system-reminder>"
        val resumed=user("host-reminder",reminder,listOf(AgentContentPart.Text(reminder)))
        assertEquals("file",latestNovexUserRequest(listOf(attachment,resumed))?.dbMessageId)
    }
    @Test fun contextUnitsStayKAndM() {
        assertEquals("32K",compactContextTokens(32000))
        assertEquals("1M",compactContextTokens(1000000))
        assertEquals("1.5K",compactContextTokens(1500))
        assertEquals("990",compactContextTokens(990))
        assertEquals("339K",compactContextTokens(339250))
        assertTrue(compactContextTokens(Int.MAX_VALUE).length <= 5)
    }
}
