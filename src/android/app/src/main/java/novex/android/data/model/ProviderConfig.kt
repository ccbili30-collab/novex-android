package novex.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/*
 * The provider configuration object graph: connections (instances), the
 * models offered under them (entries), ordered routing groups, and the
 * per-device pointers that select defaults. This graph is persisted twice —
 * once as rows in the provider database and once as the legacy JSON mirror
 * — so every field name and enum name below is part of the persisted
 * format: rename nothing here without a migration on both sides.
 */

@Serializable
enum class ProviderType(val displayName: String) {
    anthropic("Anthropic"),
    gemini("Google Gemini"),
    openAI("OpenAI"),
    openRouter("OpenRouter"),
    xAI("xAI (Grok)"),
    // Appending is safe: rows store the enum NAME, never the ordinal.
    kimiCode("Kimi Code");

    val builtInModels: List<LLMModel>
        get() = when (this) {
            anthropic -> LLMModel.allAnthropic
            gemini -> LLMModel.allGemini
            openAI -> LLMModel.allOpenAI
            openRouter -> LLMModel.allOpenRouter
            xAI -> LLMModel.allXAI
            kimiCode -> LLMModel.allKimi
        }
}

@Serializable
enum class ProviderCredential {
    apiKey,
    oauth,
}

/**
 * Thinking intensity. Encoded by NAME string, so new cases must be appended
 * at the tail; older installs then read an unknown name through
 * [decoded] and clamp instead of crashing.
 */
@Serializable
enum class ThinkingLevel {
    OFF, LOW, MEDIUM, HIGH, XHIGH, MAX, ULTRA;

    val isEnabled: Boolean get() = this != OFF

    val displayName: String
        get() = when (this) {
            OFF -> "Off"
            LOW -> "Low"
            MEDIUM -> "Medium"
            HIGH -> "High"
            XHIGH -> "XHigh"
            MAX -> "Max"
            ULTRA -> "Ultra"
        }

    /** Declaration-order rank, used for clamp and intersection math. */
    val rank: Int get() = ordinal

    companion object {
        /**
         * Read-side decode for values that may have been written by a newer
         * build: never throws, clamps to the top level this build knows.
         */
        fun decoded(raw: String): ThinkingLevel =
            runCatching { valueOf(raw) }.getOrDefault(XHIGH)
    }
}

@Serializable
enum class RoutingStrategy {
    fallback,
    loadBalance,
}

/**
 * Route selection for image generation on OpenAI-compatible providers.
 * Wire names match the iOS sibling so exported configs interoperate.
 */
@Serializable
enum class ImageEndpointMode {
    auto,
    @SerialName("images_generations") imagesGenerations,
    @SerialName("chat_completions") chatCompletions,
}

/** When a group should move on to its next member. */
@Serializable
enum class FallbackStrategy {
    default,
    always,
}

@Serializable
data class ModelGroup(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val memberEntryIds: MutableList<String> = mutableListOf(),
    var strategy: RoutingStrategy = RoutingStrategy.fallback,
    var fallbackStrategy: FallbackStrategy = FallbackStrategy.default,
    // Per-group session defaults; null = keep the session-level behaviour.
    var defaultThinkingLevel: ThinkingLevel? = null,
    var contextLimitTokens: Int? = null,
    // Last slider position, restored when the limit switch toggles back on.
    var lastContextLimitTokens: Int? = null,
)

@Serializable
data class ProviderInstance(
    val id: String,
    var label: String,
    val providerType: ProviderType,
    val credentialType: ProviderCredential,
    var isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    var customBaseURL: String? = null,
    var appendV1Suffix: Boolean = true,
    // Relay gateways that reject generic user agents; null/blank = default UA.
    var customUserAgent: String? = null,
    // Route this OpenAI instance through /v1/responses instead of chat.
    var useResponsesAPI: Boolean = false,
    // Key-acquisition link for built-in presets; export/import round-trips it.
    var keyHelpUrl: String? = null,
    // Sticky retry-with-responses switch for presets whose chat route is flaky.
    var autoResponsesFallback: Boolean = false,
    var imageEndpointMode: ImageEndpointMode = ImageEndpointMode.auto,
    // Endpoint that last worked under auto mode; null = never probed.
    var imageEndpointResolved: ImageEndpointMode? = null,
    // Azure auth (api-key header, deployment-path routing) on OpenAI instances.
    var azureMode: Boolean = false,
) {
    /** Custom base with the "/v1" suffix applied when asked for. */
    val effectiveBaseURL: String?
        get() {
            val trimmed = customBaseURL?.trimEnd('/') ?: return null
            return when {
                appendV1Suffix && !trimmed.endsWith("/v1") -> "$trimmed/v1"
                else -> trimmed
            }
        }

    /**
     * An empty key is only legitimate against a self-hosted or relay
     * endpoint — never against a vendor's official API and never for OAuth
     * (its credential is the token itself).
     */
    val allowsEmptyAPIKey: Boolean
        get() = credentialType == ProviderCredential.apiKey &&
            !customBaseURL.isNullOrBlank() &&
            providerType in setOf(ProviderType.openAI, ProviderType.anthropic)

    /** The image-endpoint picker only applies to the OpenAI-compatible types. */
    val supportsImageEndpointSetting: Boolean
        get() = providerType in setOf(ProviderType.openAI, ProviderType.openRouter, ProviderType.xAI)

    /** Azure applies to key-authenticated OpenAI instances in either API format. */
    val supportsAzureMode: Boolean
        get() = providerType == ProviderType.openAI && credentialType == ProviderCredential.apiKey

    /**
     * Custom thinking rules only bite on the chat-completions route;
     * Anthropic/Gemini, Responses mode and Codex-shaped OAuth instances
     * never consult the registry, so the UI must not offer an interactive
     * list there.
     */
    val supportsCustomThinkingContracts: Boolean
        get() = when (providerType) {
            ProviderType.anthropic, ProviderType.gemini -> false
            else -> !useResponsesAPI && !isCodexShapedOAuth()
        }

    private fun isCodexShapedOAuth(): Boolean =
        credentialType == ProviderCredential.oauth && customBaseURL.isNullOrBlank()
}

/**
 * Per-entry deltas layered over the catalog model. Null means "inherit the
 * base"; [isEmpty] is true when nothing is set, which lets the effective
 * model short-circuit to the base object.
 */
@Serializable
data class ModelOverrides(
    val displayName: String? = null,
    val maxOutputTokens: Int? = null,
    val contextWindow: Int? = null,
    val supportsReasoning: Boolean? = null,
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null,
    // Ceiling for thinking intensity; top priority in the resolution chain.
    val maxThinkingLevel: ThinkingLevel? = null,
    val supportsTools: Boolean? = null,
    val imageEndpointMode: ImageEndpointMode? = null,
    // Auto-probe cache, kept here so it round-trips without a schema change.
    val imageEndpointResolved: ImageEndpointMode? = null,
) {
    val isEmpty: Boolean
        get() = displayName == null &&
            maxOutputTokens == null &&
            contextWindow == null &&
            supportsReasoning == null &&
            inputModalities == null &&
            outputModalities == null &&
            maxThinkingLevel == null &&
            supportsTools == null &&
            imageEndpointMode == null &&
            imageEndpointResolved == null
}

@Serializable
data class ModelEntry(
    val providerInstanceId: String,
    @SerialName("model")
    val baseModel: LLMModel,
    val overrides: ModelOverrides = ModelOverrides(),
    val isCustom: Boolean = false,
    val isHidden: Boolean = false,
    val uuid: String = UUID.randomUUID().toString(),
    val userModifiedAt: Long? = null,
) {
    val id: String get() = uuid

    /** The base model with every non-null override substituted in. */
    val model: LLMModel
        get() = if (overrides.isEmpty) {
            baseModel
        } else {
            baseModel.copy(
                displayName = overrides.displayName ?: baseModel.displayName,
                maxOutputTokens = overrides.maxOutputTokens ?: baseModel.maxOutputTokens,
                contextWindow = overrides.contextWindow ?: baseModel.contextWindow,
                supportsReasoning = overrides.supportsReasoning ?: baseModel.supportsReasoning,
                inputModalities = overrides.inputModalities ?: baseModel.inputModalities,
                outputModalities = overrides.outputModalities ?: baseModel.outputModalities,
                supportsTools = overrides.supportsTools ?: baseModel.supportsTools,
            )
        }

    /** True when the entry reflects deliberate user choices, not catalog data. */
    val isUserModified: Boolean
        get() = isCustom || isHidden || !overrides.isEmpty
}

@Serializable
data class ProviderConfig(
    val instances: MutableList<ProviderInstance> = mutableListOf(),
    val modelEntries: MutableList<ModelEntry> = mutableListOf(),
    val modelGroups: MutableList<ModelGroup> = mutableListOf(),
    var defaultPrimaryGroupId: String? = null,
    var defaultSubGroupId: String? = null,
    var voiceInputGroupId: String? = null,
    var voiceOutputGroupId: String? = null,
    var visionGroupId: String? = null,
    val agentLoopModelEntryIds: MutableList<String> = mutableListOf(),
    val agentLoopGroupIds: MutableList<String> = mutableListOf(),
    val imageGenerationGroupIds: MutableList<String> = mutableListOf(),
    val imageGenerationProviderInstanceIds: MutableList<String> = mutableListOf(),
    // In-memory identity only: bumped on every save so change detection can
    // compare a scalar instead of walking lists that writers mutate in
    // place. Default must be unique — a constant default made a freshly
    // loaded config compare equal to the empty placeholder once, and the
    // app then ran with zero providers despite a full database.
    @kotlinx.serialization.Transient var revision: Long = nextRevision(),
) {
    companion object {
        private val revisionSeq = AtomicLong(1L)

        fun nextRevision(): Long = revisionSeq.getAndIncrement()
    }

    /**
     * Revision-only equality (identity first). Comparing the mutable lists
     * instead once raced a background import appending to a list while the
     * main thread compared inside StateFlow emission, and the resulting
     * ConcurrentModificationException crashed the app from inside collect.
     * The revision counter changes on every save, so it is a strictly
     * sufficient change signal.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProviderConfig) return false
        return revision == other.revision
    }

    override fun hashCode(): Int = revision.hashCode()
}
