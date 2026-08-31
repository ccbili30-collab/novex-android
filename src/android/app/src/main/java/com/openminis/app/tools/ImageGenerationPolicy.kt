package com.openminis.app.tools

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import java.util.UUID

internal fun isImageGenerationEntry(entry: ModelEntry): Boolean =
    entry.model.outputModalities.orEmpty().any { it.equals("image", ignoreCase = true) }

internal fun resolveImageGenerationEntries(config: ProviderConfig): List<ModelEntry> {
    val enabledProviders = config.instances.filter { it.isEnabled }.mapTo(mutableSetOf()) { it.id }
    val entriesById = config.modelEntries.associateBy { it.id }
    val seen = mutableSetOf<String>()
    return buildList {
        for (groupId in config.imageGenerationGroupIds.distinct()) {
            val group = config.modelGroups.firstOrNull { it.id == groupId } ?: continue
            for (entryId in group.memberEntryIds) {
                val entry = entriesById[entryId] ?: continue
                if (entry.providerInstanceId !in enabledProviders || entry.isHidden) continue
                if (!isImageGenerationEntry(entry) || !seen.add(entry.id)) continue
                add(entry)
            }
        }
    }
}

internal fun resolveOrdinaryAgentLoopEntries(config: ProviderConfig): List<ModelEntry> {
    val enabledProviders = config.instances.filter { it.isEnabled }.mapTo(mutableSetOf()) { it.id }
    val entriesById = config.modelEntries.associateBy { it.id }
    val seen = mutableSetOf<String>()
    return buildList {
        fun consider(entryId: String) {
            val entry = entriesById[entryId] ?: return
            if (entry.providerInstanceId !in enabledProviders || entry.isHidden) return
            if (isImageGenerationEntry(entry) || !seen.add(entry.id)) return
            add(entry)
        }
        config.agentLoopModelEntryIds.forEach(::consider)
        for (groupId in config.agentLoopGroupIds) {
            if (groupId in config.imageGenerationGroupIds) continue
            config.modelGroups.firstOrNull { it.id == groupId }
                ?.memberEntryIds
                ?.forEach(::consider)
        }
    }
}

/**
 * Move legacy image-output entries out of the ordinary agent loop. Existing
 * image-only groups are preserved; loose entries are placed into one dedicated
 * migration group. The input object is never mutated.
 */
internal fun migrateLegacyImageGenerationConfig(source: ProviderConfig): ProviderConfig {
    val imageEntryIds = source.modelEntries.filter(::isImageGenerationEntry).mapTo(linkedSetOf()) { it.id }
    if (imageEntryIds.isEmpty()) return source

    val groups = source.modelGroups
        .map { it.copy(memberEntryIds = it.memberEntryIds.toMutableList()) }
        .toMutableList()
    val enabledImageGroups = source.imageGenerationGroupIds
        .filter { id -> groups.any { it.id == id } }
        .distinct()
        .toMutableList()

    if (enabledImageGroups.isEmpty()) {
        groups.filter { group ->
            group.memberEntryIds.isNotEmpty() && group.memberEntryIds.all { it in imageEntryIds }
        }.forEach { enabledImageGroups += it.id }
    }

    val assigned = enabledImageGroups.flatMapTo(linkedSetOf()) { groupId ->
        groups.firstOrNull { it.id == groupId }?.memberEntryIds.orEmpty()
    }
    val loose = imageEntryIds - assigned
    if (loose.isNotEmpty()) {
        val migrated = ModelGroup(
            id = "image-generation-migrated-${UUID.randomUUID()}",
            name = "已迁移生图",
            memberEntryIds = loose.toMutableList(),
        )
        groups += migrated
        enabledImageGroups += migrated.id
    }

    for (index in groups.indices) {
        val group = groups[index]
        groups[index] = if (group.id in enabledImageGroups) {
            group.copy(memberEntryIds = group.memberEntryIds.filter { it in imageEntryIds }.toMutableList())
        } else {
            group.copy(memberEntryIds = group.memberEntryIds.filterNot { it in imageEntryIds }.toMutableList())
        }
    }

    val imageOnlyProviderIds = source.instances.mapNotNull { instance ->
        val entries = source.modelEntries.filter { it.providerInstanceId == instance.id }
        instance.id.takeIf { entries.isNotEmpty() && entries.all(::isImageGenerationEntry) }
    }
    val ordinaryGroupIds = groups
        .filterNot { it.id in enabledImageGroups }
        .filter { it.memberEntryIds.isNotEmpty() }
        .map { it.id }
    val primary = source.defaultPrimaryGroupId
        .takeUnless { it in enabledImageGroups }
        ?: ordinaryGroupIds.firstOrNull()
    val sub = source.defaultSubGroupId.takeUnless { it in enabledImageGroups }

    return source.copy(
        modelGroups = groups,
        defaultPrimaryGroupId = primary,
        defaultSubGroupId = sub,
        agentLoopModelEntryIds = source.agentLoopModelEntryIds.filterNot { it in imageEntryIds }.toMutableList(),
        agentLoopGroupIds = source.agentLoopGroupIds.filterNot { it in enabledImageGroups }.toMutableList(),
        imageGenerationGroupIds = enabledImageGroups,
        imageGenerationProviderInstanceIds = (
            source.imageGenerationProviderInstanceIds + imageOnlyProviderIds
        ).distinct().toMutableList(),
    )
}

internal data class ImageGenerationFallbackSuccess<T>(
    val entry: ModelEntry,
    val value: T,
    val failures: List<String>,
)

internal suspend fun <T> runImageGenerationFallback(
    entries: List<ModelEntry>,
    invoke: suspend (ModelEntry) -> Result<T>,
): Result<ImageGenerationFallbackSuccess<T>> {
    val failures = mutableListOf<String>()
    for (entry in entries) {
        val result = runCatching { invoke(entry) }.getOrElse { Result.failure(it) }
        result.getOrNull()?.let { value ->
            return Result.success(ImageGenerationFallbackSuccess(entry, value, failures.toList()))
        }
        val failure = result.exceptionOrNull()
        failures += "${entry.model.id}：${failure?.message ?: failure?.javaClass?.simpleName ?: "未知错误"}"
    }
    return Result.failure(
        IllegalStateException(
            if (failures.isEmpty()) "没有启用且可用的生图模型"
            else "所有生图模型均失败：${failures.joinToString("；")}",
        ),
    )
}
