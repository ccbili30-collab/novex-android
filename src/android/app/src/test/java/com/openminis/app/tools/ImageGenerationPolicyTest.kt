package com.openminis.app.tools

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.db.toSnapshot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationPolicyTest {
    private val firstProvider = ProviderInstance(
        id = "provider-a",
        label = "A",
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
    )
    private val secondProvider = firstProvider.copy(id = "provider-b", label = "B")
    private val firstEntry = imageEntry("entry-a", firstProvider.id, "image-a")
    private val secondEntry = imageEntry("entry-b", secondProvider.id, "image-b")

    @Test
    fun `only enabled image groups resolve and ordinary model loop stays image free`() {
        val imageGroup = ModelGroup("image-group", "生图一", mutableListOf(firstEntry.id))
        val disabledGroup = ModelGroup("disabled-group", "生图二", mutableListOf(secondEntry.id))
        val config = ProviderConfig(
            instances = mutableListOf(firstProvider, secondProvider),
            modelEntries = mutableListOf(firstEntry, secondEntry),
            modelGroups = mutableListOf(imageGroup, disabledGroup),
            imageGenerationGroupIds = mutableListOf(imageGroup.id),
            agentLoopModelEntryIds = mutableListOf(firstEntry.id, secondEntry.id),
            agentLoopGroupIds = mutableListOf(imageGroup.id),
        )

        assertEquals(listOf(firstEntry.id), resolveImageGenerationEntries(config).map { it.id })
        assertTrue(resolveOrdinaryAgentLoopEntries(config).none { "image" in it.model.outputModalities.orEmpty() })
    }

    @Test
    fun `legacy image entries migrate into one enabled image group and leave ordinary loop`() {
        val textEntry = ModelEntry(
            providerInstanceId = firstProvider.id,
            baseModel = LLMModel("chat", "Chat", "A", outputModalities = listOf("text")),
            uuid = "text-entry",
        )
        val mixedGroup = ModelGroup("mixed", "默认模型", mutableListOf(textEntry.id, firstEntry.id))
        val config = ProviderConfig(
            instances = mutableListOf(firstProvider),
            modelEntries = mutableListOf(textEntry, firstEntry),
            modelGroups = mutableListOf(mixedGroup),
            defaultPrimaryGroupId = mixedGroup.id,
            agentLoopModelEntryIds = mutableListOf(firstEntry.id),
        )

        val migrated = migrateLegacyImageGenerationConfig(config)

        assertEquals(listOf(textEntry.id), migrated.modelGroups.first { it.id == mixedGroup.id }.memberEntryIds)
        assertFalse(firstEntry.id in migrated.agentLoopModelEntryIds)
        assertEquals(1, migrated.imageGenerationGroupIds.size)
        val imageGroup = migrated.modelGroups.first { it.id == migrated.imageGenerationGroupIds.single() }
        assertEquals(listOf(firstEntry.id), imageGroup.memberEntryIds)
    }

    @Test
    fun `generation fallback continues after a failed model and reports attempts`() = runBlocking {
        val attempted = mutableListOf<String>()

        val result = runImageGenerationFallback<String>(listOf(firstEntry, secondEntry)) { entry ->
            attempted += entry.id
            if (entry.id == firstEntry.id) Result.failure(IllegalStateException("HTTP 503"))
            else Result.success("/var/minis/attachments/generated.png")
        }

        assertEquals(listOf(firstEntry.id, secondEntry.id), attempted)
        assertTrue(result.isSuccess)
        assertEquals(secondEntry.id, result.getOrThrow().entry.id)
        assertEquals(listOf("image-a：HTTP 503"), result.getOrThrow().failures)
    }

    @Test
    fun `authentication failure skips remaining models from the same source`() = runBlocking {
        val sameSource = imageEntry("entry-a2", firstProvider.id, "image-a2")
        val attempted = mutableListOf<String>()

        val result = runImageGenerationFallback(listOf(firstEntry, sameSource, secondEntry)) { entry ->
            attempted += entry.id
            if (entry.providerInstanceId == firstProvider.id) Result.failure(IllegalStateException("HTTP 401 invalid API key"))
            else Result.success("ok")
        }

        assertTrue(result.isSuccess)
        assertEquals(listOf(firstEntry.id, secondEntry.id), attempted)
    }

    @Test
    fun `attempt diagnostics bind source model endpoint and irreversible key identity`() {
        val firstFingerprint = imageCredentialFingerprint("first-secret")
        val secondFingerprint = imageCredentialFingerprint("second-secret")
        val detail = imageGenerationAttemptDiagnostic(
            sourceLabel = "JMR",
            modelId = "gpt-image-2",
            endpoint = "https://jmrai.net/v1/images/generations?api_key=secret",
        )

        assertEquals(12, firstFingerprint.length)
        assertFalse(firstFingerprint == secondFingerprint)
        assertFalse(detail.contains("secret"))
        assertTrue(detail.contains("JMR"))
        assertTrue(detail.contains("gpt-image-2"))
        assertTrue(detail.contains("/v1/images/generations"))
        assertTrue(detail.contains("<已隐藏>"))
    }

    @Test
    fun `content policy rejection stops without forwarding prompt to another source`() = runBlocking {
        val attempted = mutableListOf<String>()

        val result = runImageGenerationFallback<String>(listOf(firstEntry, secondEntry)) { entry ->
            attempted += entry.id
            Result.failure(IllegalStateException("content policy violation"))
        }

        assertTrue(result.isFailure)
        assertEquals(listOf(firstEntry.id), attempted)
    }

    @Test
    fun `image group and dedicated provider bindings survive database mapping`() {
        val group = ModelGroup("image-group", "生图", mutableListOf(firstEntry.id))
        val config = ProviderConfig(
            instances = mutableListOf(firstProvider),
            modelEntries = mutableListOf(firstEntry),
            modelGroups = mutableListOf(group),
            imageGenerationGroupIds = mutableListOf(group.id),
            imageGenerationProviderInstanceIds = mutableListOf(firstProvider.id),
        )
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        val restored = config.toSnapshot(json).toProviderConfig(json)

        assertEquals(listOf(group.id), restored.imageGenerationGroupIds)
        assertEquals(listOf(firstProvider.id), restored.imageGenerationProviderInstanceIds)
    }

    @Test
    fun `per-model image endpoint survives database mapping`() {
        val entry = firstEntry.copy(
            overrides = ModelOverrides(
                imageEndpointMode = ImageEndpointMode.chatCompletions,
                imageEndpointResolved = ImageEndpointMode.chatCompletions,
            ),
        )
        val config = ProviderConfig(
            instances = mutableListOf(firstProvider),
            modelEntries = mutableListOf(entry),
        )
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        val restored = config.toSnapshot(json).toProviderConfig(json).modelEntries.single()

        assertEquals(ImageEndpointMode.chatCompletions, restored.overrides.imageEndpointMode)
        assertEquals(ImageEndpointMode.chatCompletions, restored.overrides.imageEndpointResolved)
    }

    @Test
    fun `agent tool registry exposes one generation tool only when configured`() {
        assertFalse(AgentTools.makeAgentTools(imageGenerationConfigured = false).any { it.name == GenerateImageTool.NAME })
        assertEquals(1, AgentTools.makeAgentTools(imageGenerationConfigured = true).count { it.name == GenerateImageTool.NAME })
    }

    private fun imageEntry(uuid: String, providerId: String, modelId: String) = ModelEntry(
        providerInstanceId = providerId,
        baseModel = LLMModel(
            id = modelId,
            displayName = modelId,
            provider = providerId,
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("image"),
        ),
        uuid = uuid,
    )
}
