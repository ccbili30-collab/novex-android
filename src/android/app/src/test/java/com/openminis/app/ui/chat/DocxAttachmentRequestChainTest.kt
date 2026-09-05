package com.openminis.app.ui.chat

import com.openminis.app.data.attachments.NovexDocumentSnapshotExtractor
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.novex.domain.InMemoryNovexDocumentSnapshotCache
import com.openminis.app.provider.openai.OpenAIProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocxAttachmentRequestChainTest {
    @Test
    fun `selected docx receipt reaches provider request without injecting its body`() = runBlocking {
        val file = fixture("docx/libreoffice-comment.docx")
        val snapshot = requireNotNull(
            NovexDocumentSnapshotExtractor(InMemoryNovexDocumentSnapshotCache())
                .extract(null, file, null, "review.docx"),
        )
        val attachmentContext = requireNotNull(
            buildUserAttachedFilesPrompt(
                listOf(
                    UserAttachedFilePromptMeta(
                        linuxPath = "/var/minis/attachments/uploads/review.docx",
                        size = file.length(),
                        modifiedIso = "2026-08-31T00:00:00Z",
                        documentSnapshot = snapshot,
                    ),
                ),
            ),
        )

        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(
                        """
                        data: {"choices":[{"delta":{"content":"批注内容是 This is the first line"}}]}

                        data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                        data: [DONE]

                        """.trimIndent(),
                    ),
            )
            val provider = OpenAIProvider(
                apiKey = "test-key",
                model = LLMModel.gpt4oMini,
                basePath = server.url("/v1").toString().trimEnd('/'),
            )

            val response = provider.sendMessage(
                messages = listOf(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "批注写了什么？\n\n$attachmentContext",
                    ),
                ),
                systemPrompt = null,
                maxTokens = 256,
            )

            val requestBody = server.takeRequest().body.readUtf8()
            assertTrue(requestBody.contains(snapshot.ref.value))
            assertTrue(requestBody.contains("document_inspect"))
            assertTrue(requestBody.contains("document_read"))
            assertTrue(!requestBody.contains("This is the first line"))
            assertEquals("批注内容是 This is the first line", response.text)
        } finally {
            server.shutdown()
        }
    }

    private fun fixture(path: String): File = File(
        requireNotNull(javaClass.classLoader?.getResource(path)) { "Missing fixture: $path" }.toURI(),
    )
}
