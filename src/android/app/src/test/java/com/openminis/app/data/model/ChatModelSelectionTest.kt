package com.openminis.app.data.model

import org.junit.Assert.*
import org.junit.Test

class ChatModelSelectionTest {
    private fun entry(id: String, outputs: List<String>? = null, provider: String = "relay") =
        ModelEntry(provider, LLMModel("same-model", "3.8", "relay", outputModalities = outputs), uuid = id)
    private fun config(vararg entries: ModelEntry) = ProviderConfig(
        instances = mutableListOf(ProviderInstance("relay", "Relay", ProviderType.openAI, ProviderCredential.apiKey),
            ProviderInstance("other", "Other", ProviderType.openAI, ProviderCredential.apiKey)),
        modelEntries = entries.toMutableList())

    @Test fun imageFirstGroupAndHiddenEntriesCannotBecomeChatRoute() {
        val image = entry("image", listOf("image", "text"))
        val hidden = entry("hidden").copy(isHidden = true)
        val chat = entry("chat")
        val cfg = config(image, hidden, chat)
        val group = ModelGroup("group", "Default", mutableListOf(image.id, hidden.id, chat.id))
        assertEquals(listOf(chat), ChatModelSelection.members(cfg, group))
        assertThrows(IllegalArgumentException::class.java) { ChatModelSelection.resolve(cfg, image.id) }
    }
    @Test fun exactEntryIdentitySurvivesDuplicateDisplayAndModelNames() {
        val a = entry("first")
        val b = entry("second", provider = "other")
        val cfg = config(a, b)
        val (selected, instance) = ChatModelSelection.resolve(cfg, b.id)
        assertEquals("second", selected.id)
        assertEquals("other", instance.id)
        assertThrows(IllegalArgumentException::class.java) { ChatModelSelection.resolve(cfg, "same-model") }
    }
    @Test fun disabledAndDeletedConnectionsDoNotRetainPreviousRoute() {
        val chat = entry("chat")
        val cfg = config(chat)
        cfg.instances.first().isEnabled = false
        assertThrows(IllegalArgumentException::class.java) { ChatModelSelection.resolve(cfg, chat.id) }
        cfg.instances.clear()
        assertThrows(IllegalArgumentException::class.java) { ChatModelSelection.resolve(cfg, chat.id) }
    }
    @Test fun imageIdentifierCannotBecomeChatThroughMissingMetadataOrTextOverride() {
        val image = entry("image").copy(baseModel = LLMModel("gemini-3.1-flash-image-preview", "3.8", "relay"),
            overrides = ModelOverrides(outputModalities = listOf("text")))
        assertFalse(ChatModelSelection.eligible(image))
        val imageByMetadata = entry("image-metadata", listOf("image")).copy(
            overrides = ModelOverrides(outputModalities = listOf("text")))
        assertFalse(ChatModelSelection.eligible(imageByMetadata))
    }

    @Test fun imageInputStillAllowsNormalChat() {
        val vision = entry("vision").copy(baseModel = LLMModel("vision", "Vision", "relay",
            inputModalities = listOf("image", "text"), outputModalities = listOf("text")))
        assertTrue(ChatModelSelection.eligible(vision))
    }
}
