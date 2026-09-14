package com.openminis.app.diagnostics

import android.app.Application
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ChatModelSelection
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ModelRequestAuditTest {
    @get:Rule val files = TemporaryFolder()

    @Test(timeout = 30000) fun failedFirstRequestRetainsActualWireRouteWithoutCredentials() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val key = "private-test-credential"
            server.enqueue(MockResponse().setResponseCode(400).setBody(
                JSONObject().put("error", JSONObject().put("code", "unsupported_model")
                    .put("message", "Unsupported model, token=$key")).toString()))
            val root = files.newFolder()
            val audit = ModelRequestAudit(root, "conversation", "first-user-message",
                JSONObject().put("displayModelId", "display-only"))
            val actual = ModelEntry("actual-instance", LLMModel("actual-model", "3.8", "relay"), uuid = "chosen")
            val other = actual.copy(providerInstanceId = "other-instance", uuid = "not-chosen")
            val config = ProviderConfig(instances = mutableListOf(
                ProviderInstance("other-instance", "Same label", ProviderType.openAI, ProviderCredential.apiKey,
                    customBaseURL = "https://wrong.example/v1"),
                ProviderInstance("actual-instance", "Same label", ProviderType.openAI, ProviderCredential.apiKey,
                    customBaseURL = server.url("/v1").toString())), modelEntries = mutableListOf(other, actual))
            val (entry, instance) = ChatModelSelection.resolve(config, "chosen")
            val provider = ProviderFactory.create(instance, key, entry.model)
            val failure = runCatching {
                withContext(audit) {
                    provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "1")), systemPrompt = null, maxTokens = 128)
                }
            }.exceptionOrNull()
            assertNotNull(failure)
            val sent = server.takeRequest()
            val wire = JSONObject(sent.body.readUtf8())
            val record = JSONObject(ModelRequestAudit.export(root, "conversation").single().second)
            val events = record.getJSONArray("events")
            val request = (0 until events.length()).map(events::getJSONObject)
                .single { it.getString("stage") == "wire_request" }.getJSONObject("details")
            assertEquals(wire.getString("model"), request.getString("modelId"))
            assertEquals("actual-model", request.getString("modelId"))
            assertEquals("actual-instance", request.getString("providerInstanceId"))
            assertEquals(sent.requestUrl.toString(), request.getString("url"))
            assertEquals("chat_completions", request.getString("protocol"))
            assertTrue((0 until events.length()).map(events::getJSONObject).any {
                it.getString("stage") == "http_error" && it.getJSONObject("details").getInt("status") == 400 })
            assertEquals("first-user-message", record.getString("requestMessageId"))
            assertFalse(record.toString().contains(key))
            assertTrue(ModelRequestAudit.export(root, "different-conversation").isEmpty())
        } finally { server.shutdown() }
    }

    @Test fun failedDraftEvidenceFollowsTheSavedConversation() {
        val root = files.newFolder()
        ModelRequestAudit(root, "draft", null, JSONObject()).event("preparation_failed")
        ModelRequestAudit.promoteDraft(root, "draft", "saved")
        assertTrue(ModelRequestAudit.export(root, "draft").isEmpty())
        val record = JSONObject(ModelRequestAudit.export(root, "saved").single().second)
        assertEquals("saved", record.getString("conversationId"))
        assertEquals("draft", record.getString("draftConversationId"))
    }

    @Test fun unwritableDiagnosticsDoNotBreakTheModelRun() {
        val file = files.newFile()
        val audit = ModelRequestAudit(file, "conversation", null, JSONObject())
        audit.event("failed")
    }

    @Test fun endpointOmitsCredentialsAndQuery() {
        assertEquals("https://relay.test/v1/chat/completions",
            ModelRequestAudit.safeUrl("https://name:password@relay.test/v1/chat/completions?key=private#secret"))
    }
}
