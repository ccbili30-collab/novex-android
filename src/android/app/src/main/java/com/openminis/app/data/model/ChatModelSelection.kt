package com.openminis.app.data.model

/** A chat route must be selected by entry identity, never by its display label. */
object ChatModelSelection {
    fun imageGenerationId(modelId: String): Boolean {
        val id = modelId.trim().lowercase()
        return listOf("gpt-image", "dall-e", "imagen", "image-gen", "image_generation", "flux", "seedream", "nano-banana")
            .any(id::contains) || (id.contains("gemini") && Regex("(^|[-_/.])image($|[-_/.])").containsMatchIn(id))
    }

    fun imageOutput(model: LLMModel): Boolean = imageGenerationId(model.id) ||
        model.outputModalities.orEmpty().any { it.trim().equals("image", ignoreCase = true) }

    fun eligible(entry: ModelEntry): Boolean = !entry.isHidden && entry.model.isTextOutput &&
        !imageOutput(entry.model) && !imageOutput(entry.baseModel)

    fun members(config: ProviderConfig, group: ModelGroup): List<ModelEntry> =
        group.memberEntryIds.mapNotNull { id -> config.modelEntries.find { it.id == id } }
            .filter { entry -> eligible(entry) && config.instances.any { it.id == entry.providerInstanceId && it.isEnabled } }

    fun resolve(config: ProviderConfig, entryId: String): Pair<ModelEntry, ProviderInstance> {
        val entry = requireNotNull(config.modelEntries.find { it.id == entryId }) { "所选模型已不存在，请重新选择" }
        require(eligible(entry)) { "所选模型用于图片或其他输出，请选择聊天模型" }
        val instance = requireNotNull(config.instances.find { it.id == entry.providerInstanceId }) { "所选模型的连接已不存在" }
        require(instance.isEnabled) { "所选模型的连接已关闭" }
        return entry to instance
    }
}
