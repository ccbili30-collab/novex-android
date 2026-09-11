package com.openminis.app.data.model

/** One reserved configuration, independent of every user-created provider and default. */
object TemporaryPreviewModel {
    const val INSTANCE_ID = "novex-private-preview-deepseek-20260911"
    const val MODEL_ID = "deepseek-flash"
    const val ENTRY_ID = "$INSTANCE_ID/$MODEL_ID"
    const val GROUP_ID = "$INSTANCE_ID/group"
    const val DISPLAY_NAME = "DeepSeek API"
    val enabled: Boolean get() = com.openminis.app.BuildConfig.TEMPORARY_DEEPSEEK_KEY.isNotBlank()

    fun install(config: ProviderConfig, enabled: Boolean = this.enabled): ProviderConfig {
        val instances = config.instances.filterNot { it.id == INSTANCE_ID }.toMutableList()
        val entries = config.modelEntries.filterNot { it.providerInstanceId == INSTANCE_ID || it.id == ENTRY_ID }.toMutableList()
        val groups = config.modelGroups.filterNot { it.id == GROUP_ID }.map { group ->
            if (!enabled && ENTRY_ID in group.memberEntryIds) group.copy(
                memberEntryIds = group.memberEntryIds.filterNot { it == ENTRY_ID }.toMutableList(),
            ) else group
        }.toMutableList()
        if (enabled) {
            instances += ProviderInstance(INSTANCE_ID, DISPLAY_NAME, ProviderType.openAI, ProviderCredential.apiKey,
                createdAt = 0L, customBaseURL = "https://api.deepseek.com/v1", appendV1Suffix = false)
            entries += ModelEntry(INSTANCE_ID, LLMModel(MODEL_ID, DISPLAY_NAME, "deepseek",
                contextWindow = 1_048_576, maxOutputTokens = 8192, supportsReasoning = true,
                interleavedReasoningField = "reasoning_content", supportsTools = true), uuid = ENTRY_ID)
            groups += ModelGroup(GROUP_ID, DISPLAY_NAME, mutableListOf(ENTRY_ID))
        }
        fun keepGroup(id: String?) = id.takeUnless { !enabled && it == GROUP_ID }
        return config.copy(instances = instances, modelEntries = entries, modelGroups = groups,
            defaultPrimaryGroupId = keepGroup(config.defaultPrimaryGroupId), defaultSubGroupId = keepGroup(config.defaultSubGroupId),
            voiceInputGroupId = keepGroup(config.voiceInputGroupId), voiceOutputGroupId = keepGroup(config.voiceOutputGroupId),
            visionGroupId = keepGroup(config.visionGroupId),
            agentLoopModelEntryIds = config.agentLoopModelEntryIds.filterNot { !enabled && it == ENTRY_ID }.toMutableList(),
            agentLoopGroupIds = config.agentLoopGroupIds.filterNot { !enabled && it == GROUP_ID }.toMutableList(),
            revision = ProviderConfig.nextRevision())
    }
}
