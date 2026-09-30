package novex.android.data.model

import kotlinx.serialization.Serializable

/*
 * The static model catalog plus the per-model heuristics that fill gaps the
 * catalog leaves open. Catalog ids, display names and capability stamps are
 * vendor facts and stay verbatim; everything computed (context fallbacks,
 * display-name formatting, prompt fragments) is heuristic and clearly
 * separated below.
 */

@Serializable
data class LLMModel(
    val id: String,
    val displayName: String,
    val provider: String,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val supportsReasoning: Boolean? = null,
    val interleavedReasoningField: String? = null,
    // Effort tiers the vendor documents (models.dev "effort"); non-empty
    // means the model takes a reasoning_effort parameter with these values.
    val reasoningEffortValues: List<String>? = null,
    // Tri-state: null = catalog never heard of this model, true = the
    // catalog affirms NO effort tiers. Only the affirmative case may
    // suppress the request field; unknown stays permissive.
    val declaresNoEffortTiers: Boolean? = null,
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null,
    val supportsTools: Boolean? = null,
) {
    companion object {
        // ---- vendor catalogs (facts, order = picker order) -----------------

        val anthropicCatalog = listOf(
            LLMModel("claude-fable-5", "Claude Fable 5", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true),
            LLMModel("claude-opus-4-8", "Claude Opus 4.8", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true),
            LLMModel("claude-opus-4-6", "Claude Opus 4.6", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true),
            LLMModel("claude-sonnet-5", "Claude Sonnet 5", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 64_000, supportsReasoning = true),
            LLMModel("claude-sonnet-4-6", "Claude Sonnet 4.6", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 64_000, supportsReasoning = true),
            LLMModel("claude-haiku-4-5", "Claude Haiku 4.5", "Anthropic", contextWindow = 200_000, maxOutputTokens = 64_000, supportsReasoning = true),
        )

        val geminiCatalog = listOf(
            LLMModel("gemini-3-pro-preview", "Gemini 3 Pro (Preview)", "Google"),
            LLMModel("gemini-3-flash-preview", "Gemini 3 Flash (Preview)", "Google"),
            LLMModel("gemini-2.5-pro", "Gemini 2.5 Pro", "Google"),
            LLMModel("gemini-2.5-flash", "Gemini 2.5 Flash", "Google"),
            LLMModel("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite", "Google"),
        )

        val openAICatalog = listOf(
            LLMModel("gpt-5.5", "GPT-5.5", "OpenAI", supportsReasoning = true),
            LLMModel("gpt-5.3-codex", "GPT-5.3 Codex", "OpenAI", supportsReasoning = true),
            LLMModel("gpt-5.2-codex", "GPT-5.2 Codex", "OpenAI", supportsReasoning = true),
            LLMModel("gpt-5.1-codex-max", "GPT-5.1 Codex Max", "OpenAI", supportsReasoning = true),
            LLMModel("gpt-5.2", "GPT-5.2", "OpenAI", supportsReasoning = true),
            LLMModel("gpt-4o", "GPT-4o", "OpenAI"),
            LLMModel("gpt-4o-mini", "GPT-4o Mini", "OpenAI"),
            LLMModel("o3", "o3", "OpenAI", supportsReasoning = true),
            LLMModel("o4-mini", "o4 Mini", "OpenAI", supportsReasoning = true),
            LLMModel("codex-mini-latest", "Codex Mini", "OpenAI", supportsReasoning = true),
        )

        val openRouterCatalog = listOf(
            LLMModel("anthropic/claude-sonnet-4", "Claude Sonnet 4", "OpenRouter"),
            LLMModel("google/gemini-2.5-flash", "Gemini 2.5 Flash", "OpenRouter"),
            LLMModel("openai/gpt-4o", "GPT-4o", "OpenRouter"),
            LLMModel("meta-llama/llama-4-maverick", "Llama 4 Maverick", "OpenRouter"),
        )

        val xaiCatalog = listOf(
            LLMModel("grok-4.5", "Grok 4.5", "xAI", supportsReasoning = true),
            LLMModel("grok-4.3", "Grok 4.3", "xAI", supportsReasoning = true),
            LLMModel("grok-4.20-0309-reasoning", "Grok 4.20 Reasoning", "xAI", supportsReasoning = true),
            LLMModel("grok-4.20-0309-non-reasoning", "Grok 4.20", "xAI"),
            LLMModel("grok-4.20-multi-agent-0309", "Grok 4.20 Multi-Agent", "xAI", supportsReasoning = true),
            LLMModel("grok-build-0.1", "Grok Build 0.1", "xAI"),
            LLMModel("grok-3-mini", "Grok 3 Mini", "xAI", supportsReasoning = true),
            LLMModel("grok-3-mini-fast", "Grok 3 Mini Fast", "xAI", supportsReasoning = true),
            LLMModel("grok-composer-2.5-fast", "Grok Composer 2.5 Fast", "xAI"),
            LLMModel("grok-4-fast", "Grok 4 Fast", "xAI", supportsReasoning = true),
            LLMModel("grok-4-fast-non-reasoning", "Grok 4 Fast (Non-Reasoning)", "xAI"),
            LLMModel("grok-code-fast-1", "Grok Code Fast 1", "xAI", supportsReasoning = true),
        )

        // Kimi Code fallback: deliberately minimal, only verified entries;
        // the live /models fetch replaces it right after login.
        val kimiCatalog = listOf(
            LLMModel("kimi-k3", "Kimi K3", "Kimi"),
            LLMModel("kimi-k2", "Kimi K2", "Kimi"),
        )

        val claudeFable5 = anthropicCatalog[0]
        val claudeOpus48 = anthropicCatalog[1]
        val claudeOpus46 = anthropicCatalog[2]
        val claudeSonnet5 = anthropicCatalog[3]
        val claudeSonnet46 = anthropicCatalog[4]
        val claudeHaiku45 = anthropicCatalog[5]

        val allAnthropic: List<LLMModel> = anthropicCatalog
        val gemini3Pro = geminiCatalog[0]
        val gemini3Flash = geminiCatalog[1]
        val gemini25Pro = geminiCatalog[2]
        val gemini25Flash = geminiCatalog[3]
        val gemini25FlashLite = geminiCatalog[4]

        val allGemini: List<LLMModel> = geminiCatalog
        val gpt55 = openAICatalog[0]
        val gpt53Codex = openAICatalog[1]
        val gpt52Codex = openAICatalog[2]
        val gpt51CodexMax = openAICatalog[3]
        val gpt52 = openAICatalog[4]
        val gpt4o = openAICatalog[5]
        val gpt4oMini = openAICatalog[6]
        val o3 = openAICatalog[7]
        val o4Mini = openAICatalog[8]
        val codexMini = openAICatalog[9]

        val allOpenAI: List<LLMModel> = openAICatalog
        val orClaudeSonnet4 = openRouterCatalog[0]
        val orGemini25Flash = openRouterCatalog[1]
        val orGpt4o = openRouterCatalog[2]
        val orLlamaMaverick = openRouterCatalog[3]

        val allOpenRouter: List<LLMModel> = openRouterCatalog
        val grok45 = xaiCatalog[0]
        val grok43 = xaiCatalog[1]
        val grok420Reasoning = xaiCatalog[2]
        val grok420NonReasoning = xaiCatalog[3]
        val grok420MultiAgent = xaiCatalog[4]
        val grokBuild01 = xaiCatalog[5]
        val grok3Mini = xaiCatalog[6]
        val grok3MiniFast = xaiCatalog[7]
        val grokComposer25Fast = xaiCatalog[8]
        val grok4Fast = xaiCatalog[9]
        val grok4FastNonReasoning = xaiCatalog[10]
        val grokCodeFast1 = xaiCatalog[11]

        val allXAI: List<LLMModel> = xaiCatalog
        val kimiK3 = kimiCatalog[0]
        val kimiK2 = kimiCatalog[1]

        val allKimi: List<LLMModel> = kimiCatalog

        val allModels: List<LLMModel> =
            anthropicCatalog + geminiCatalog + openAICatalog + openRouterCatalog + xaiCatalog + kimiCatalog

        // ---- display-name formatting --------------------------------------

        private val upperCaseTokens = setOf(
            "gpt", "glm", "oss", "ai", "xl", "vl", "llm", "moe", "api",
            "hd", "sd", "rp", "sft", "rl", "dpo", "gguf", "fp16", "bf16", "int4", "int8",
        )
        private val brandNames = mapOf(
            "openai" to "OpenAI",
            "deepseek" to "DeepSeek",
            "chatgpt" to "ChatGPT",
            "llama" to "Llama",
            "gemma" to "Gemma",
            "phi" to "Phi",
            "mistral" to "Mistral",
            "mixtral" to "Mixtral",
            "qwen" to "Qwen",
            "yi" to "Yi",
        )

        /**
         * Human label for a raw API id: '/'-separated paths become
         * " / "-separated groups, '-'-separated tokens keep their hyphens,
         * acronyms go upper-case, known brands get their official spelling,
         * everything else is title-cased.
         */
        fun modelDisplayName(fromId: String): String {
            if (fromId.isBlank()) return fromId
            return fromId.split('/').joinToString(" / ") { group ->
                group.split('-').joinToString("-") { token -> labelForToken(token) }
            }
        }

        private fun labelForToken(token: String): String {
            val lowered = token.lowercase()
            return when {
                brandNames.containsKey(lowered) -> brandNames.getValue(lowered)
                upperCaseTokens.contains(lowered) -> lowered.uppercase()
                token.isEmpty() -> token
                else -> token.replaceFirstChar { it.titlecase() }
            }
        }
    }

    /**
     * True when the model's declared outputs include text (a null or empty
     * declaration counts as text-only, which excludes pure generators).
     */
    val isTextOutput: Boolean
        get() = outputModalities.normalizeModalities()?.let { "text" in it } ?: true

    /**
     * Context capacity in tokens: the declared value when the catalog or a
     * user override set one, otherwise the id-shape fallback below. The
     * fallback deliberately over-guesses rather than under-guesses — an
     * underestimated window triggers premature compaction.
     */
    val contextWindowTokens: Int
        get() {
            contextWindow?.takeIf { it > 0 }?.let { return it }
            return inferContextWindowTokens(this)
        }

    /**
     * Capability sentence pair appended to the system prompt so the model
     * knows what it natively consumes and what it must ask the user to
     * supply differently. Null when the model is fully multimodal. The
     * wording matches the iOS sibling so identical models produce
     * identical conversations on both platforms.
     */
    fun capabilityPromptFragment(): String? {
        val declaredInputs = inputModalities?.map(String::lowercase) ?: emptyList()
        val accepts = linkedMapOf(
            "images" to (hasImageInput || "image" in declaredInputs),
            "PDFs" to ("pdf" in declaredInputs),
            "audio" to ("audio" in declaredInputs),
            "video" to ("video" in declaredInputs),
        )
        if (accepts.values.all { it }) return null
        val nativeNames = accepts.filterValues { it }.keys.toList()
        val missingNames = accepts.filterValues { !it }.keys.toList()

        return buildString {
            if (nativeNames.isNotEmpty()) {
                append("You can natively process ").append(nativeNames.joinToString(", ")).append(". ")
            }
            append("You cannot natively process ").append(missingNames.joinToString(", "))
            append(". Ask the user to attach those formats in a supported form instead.")
        }
    }

    /**
     * Family-specific nudge for the agent loop: Gemini ignores implicit
     * tool invitations, Codex models tend to stop at analysis.
     */
    fun agentBehaviorPromptFragment(): String? {
        val idLower = id.lowercase()
        val providerLower = provider.lowercase()

        if (providerLower == "google" || idLower.contains("gemini")) {
            return "When you need to use a tool, invoke it via the function-calling mechanism directly. " +
                "Do not emit tool invocations as plain text — they will not be executed."
        }
        val codexShaped = idLower.contains("codex") || Regex("gpt-5(?:\\.\\d+)?-codex").containsMatchIn(idLower)
        if (codexShaped) {
            return "Act autonomously: don't stop at analysis, don't ask for permission on reversible local actions, " +
                "and never announce \"I will use tool X\" without actually calling X. Keep iterating until the task is fully complete."
        }
        return null
    }
}

/** Bare-form modality name: strips _input/_output decorations and lowercases. */
fun String.normalizeModalityName(): String =
    removeSuffix("_input").removeSuffix("_output").lowercase()

/** Whole-list normalization to distinct bare names; null for null/empty input. */
fun List<String>?.normalizeModalities(): List<String>? =
    this?.map { it.normalizeModalityName() }?.distinct()?.takeIf { it.isNotEmpty() }
