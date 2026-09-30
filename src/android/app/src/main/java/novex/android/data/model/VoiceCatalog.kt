package novex.android.data.model

/*
 * Speech-side model conventions: sentinel ids for the on-device System
 * engines, virtual catalog entries so voice-group members resolve in
 * pickers, and the modality shape rules that distinguish a dedicated ASR or
 * TTS model from an ordinary chat model that happens to accept audio.
 *
 * Sentinel ids and member-id shapes must stay byte-identical across
 * platforms so a voice group survives a config move intact.
 */

object SystemVoiceIds {
    const val BUILTIN_PROVIDER_ID = "__builtin_system_speech__"
    const val SYSTEM_ASR_ONLINE = "system-asr-online"
    const val SYSTEM_ASR_OFFLINE = "system-asr-offline"
    const val SYSTEM_TTS = "system-tts"
}

object VoiceModality {
    private val asrHints = listOf(
        "-asr", "asr-", "_asr", "asr_", "whisper", "transcrib", "speech-to-text",
        "speech2text", "stt-", "-stt", "_stt", "stt_", "voice-input", "voice_input",
    )
    private val ttsHints = listOf(
        "tts", "-tts", "_tts", "text-to-speech", "text2speech", "audio-gen",
        "audio-generation", "seed-tts", "voice-output", "voice_output",
    )

    /**
     * Exact modality pair for a dedicated speech model, or null when the id
     * names neither. The paired text side is always filled in — a
     * single-flag shape is reserved for template seeds (see
     * [LLMModel.isVoiceTemplateSeedShape]) and must not collide with an
     * inferred model.
     */
    fun inferDedicatedVoiceModality(id: String, displayName: String): Pair<List<String>, List<String>>? {
        val haystack = "$id $displayName".lowercase()
        return when {
            asrHints.any(haystack::contains) -> listOf("audio") to listOf("text")
            ttsHints.any(haystack::contains) -> listOf("text") to listOf("audio")
            else -> null
        }
    }
}

/**
 * Virtual entries for the built-in speech engines. They are never persisted
 * in config.modelEntries; they are synthesized so a voice-group member id
 * resolves to a real-looking row in pickers instead of a stale ghost.
 */
object SystemVoiceEntries {
    private fun entry(modelId: String, display: String, modality: String, output: Boolean = false) = ModelEntry(
        providerInstanceId = SystemVoiceIds.BUILTIN_PROVIDER_ID,
        baseModel = LLMModel(
            id = modelId,
            displayName = display,
            provider = "system",
            inputModalities = if (output) null else listOf(modality),
            outputModalities = if (output) listOf(modality) else null,
        ),
        isHidden = true,
        uuid = "${SystemVoiceIds.BUILTIN_PROVIDER_ID}/$modelId",
    )

    val asrOnline = entry(SystemVoiceIds.SYSTEM_ASR_ONLINE, "System Recognition (Online)", "audio")
    val asrOffline = entry(SystemVoiceIds.SYSTEM_ASR_OFFLINE, "System Recognition (Offline)", "audio")
    val tts = entry(SystemVoiceIds.SYSTEM_TTS, "System Voice (Auto)", "audio", output = true)

    val all: List<ModelEntry> = listOf(asrOnline, asrOffline, tts)

    fun resolve(memberId: String): ModelEntry? = all.find { it.id == memberId }

    fun isSystemEntryId(id: String): Boolean = id.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)

    /** A never-persisted stand-in instance so pickers can head the section. */
    fun syntheticInstance(label: String = "System") = ProviderInstance(
        id = SystemVoiceIds.BUILTIN_PROVIDER_ID,
        label = label,
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        isEnabled = true,
    )
}

private val LLMModel.normalizedInputs: List<String>? get() = inputModalities.normalizeModalities()
private val LLMModel.normalizedOutputs: List<String>? get() = outputModalities.normalizeModalities()

/**
 * Conservative vision-name check for model lists that carry no modality
 * metadata at all: only explicit vision/visual/vl tokens count, so ordinary
 * chat ids and image generators do not gain image input by accident.
 */
fun looksLikeVisionInputModel(id: String, displayName: String = ""): Boolean {
    val identifier = "$id $displayName".trim().lowercase()
    return Regex("(^|[-_./\\s])(vision|visual|vl)([-_./\\s]|$)").containsMatchIn(identifier)
}

/** Consumes audio — an ASR model or an audio-capable chat model. */
val LLMModel.hasAudioInput: Boolean
    get() = normalizedInputs?.contains("audio") == true

/** Produces audio — a TTS model or an omni model. */
val LLMModel.hasAudioOutput: Boolean
    get() = normalizedOutputs?.contains("audio") == true

/** Natively consumes images; drives the Vision Group member filter. */
val LLMModel.hasImageInput: Boolean
    get() = normalizedInputs?.contains("image") == true || looksLikeVisionInputModel(id, displayName)

/** Any audio modality on either side — the "voice model" predicate. */
val LLMModel.hasVoiceModality: Boolean
    get() = hasAudioInput || hasAudioOutput

/**
 * The exact single-audio-flag shape reserved for voice-template seeds:
 * ASR seed = only inputs audio, TTS seed = only outputs audio. Real API
 * models always carry the paired text side, which keeps this discriminator
 * unambiguous.
 */
val LLMModel.isVoiceTemplateSeedShape: Boolean
    get() = (normalizedInputs == listOf("audio") && normalizedOutputs == null) ||
        (normalizedInputs == null && normalizedOutputs == listOf("audio"))

/** Fills in a dedicated ASR/TTS shape when both modality fields are unset. */
fun LLMModel.withInferredVoiceModality(): LLMModel {
    if (inputModalities != null || outputModalities != null) return this
    val (ins, outs) = VoiceModality.inferDedicatedVoiceModality(id, displayName) ?: return this
    return copy(inputModalities = ins, outputModalities = outs)
}
