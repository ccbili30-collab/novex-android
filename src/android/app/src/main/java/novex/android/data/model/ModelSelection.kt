package novex.android.data.model

/*
 * Which models may carry a chat. A route is chosen by entry identity, never
 * by display label; eligibility excludes hidden entries, non-text models
 * and anything the image-generation heuristics claim.
 */

object ChatModelSelection {
    /** Broad id-shape test for "this model produces images, not prose". */
    fun imageGenerationId(modelId: String): Boolean {
        val id = modelId.trim().lowercase()
        val imageMarkers = listOf(
            "gpt-image", "dall-e", "imagen", "image-gen", "image_generation",
            "flux", "seedream", "nano-banana",
        )
        val geminiImageTag = id.contains("gemini") && Regex("(^|[-_/.])image($|[-_/.])").containsMatchIn(id)
        return imageMarkers.any(id::contains) || geminiImageTag
    }

    fun imageOutput(model: LLMModel): Boolean =
        imageGenerationId(model.id) ||
            model.outputModalities.orEmpty().any { it.trim().equals("image", ignoreCase = true) }

    fun eligible(entry: ModelEntry): Boolean =
        !entry.isHidden &&
            entry.model.isTextOutput &&
            !imageOutput(entry.model) &&
            !imageOutput(entry.baseModel)

    /** Group members that are eligible and whose provider connection is on. */
    fun members(config: ProviderConfig, group: ModelGroup): List<ModelEntry> {
        val liveProviders = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
        return group.memberEntryIds.mapNotNull { memberId ->
            config.modelEntries.find { entry -> entry.id == memberId }
        }.filter { entry -> eligible(entry) && entry.providerInstanceId in liveProviders }
    }

    fun resolve(config: ProviderConfig, entryId: String): Pair<ModelEntry, ProviderInstance> {
        val entry = requireNotNull(config.modelEntries.find { it.id == entryId }) { "所选模型已不存在，请重新选择" }
        require(eligible(entry)) { "所选模型用于图片或其他输出，请选择聊天模型" }
        val instance = requireNotNull(config.instances.find { it.id == entry.providerInstanceId }) { "所选模型的连接已不存在" }
        require(instance.isEnabled) { "所选模型的连接已关闭" }
        return entry to instance
    }
}

/** True when at least one configured group has a usable, enabled text model. */
fun ProviderConfig.hasUsableNovexModel(): Boolean {
    val providersOn = instances.asSequence().filter { it.isEnabled }.map { it.id }.toSet()
    if (providersOn.isEmpty()) return false

    val selectableEntries = modelEntries.asSequence()
        .filter { !it.isHidden && it.providerInstanceId in providersOn }
        .map { it.id }
        .toSet()
    if (selectableEntries.isEmpty()) return false

    return modelGroups.any { group -> group.memberEntryIds.any { it in selectableEntries } }
}
