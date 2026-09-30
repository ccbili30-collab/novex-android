package novex.android.repo

import com.openminis.app.data.repository.ProviderRepository

import java.util.UUID
import novex.android.data.model.LLMModel
import novex.android.data.model.ModelEntry
import novex.android.data.model.ModelGroup
import novex.android.data.model.ModelOverrides
import novex.android.data.model.ProviderConfig

/**
 * 生图来源管理 —— 「按供应商一组」的生图模型选择面。
 *
 * 模型：每个启用生图的供应商对应一个来源分组（id 约定 `image-source-<instanceId>`，
 * 确定性 id 优先；找不到时按「分组内含该实例条目」启发式定位 —— 兼容
 * 确定性 id 出现之前的用户数据）。分组只是选择面，真正的启停落在条目的
 * isHidden + 分组成员表上；换目录（replaceModels）永远不动已勾选状态，
 * 只把上游已下架且未勾选的陈旧行清掉。
 */
internal class ImageSourceAdmin(private val repo: ProviderRepository) {

    private fun deterministicGroupId(instanceId: String) = "image-source-$instanceId"

    private fun indexOfSourceGroup(config: ProviderConfig, instanceId: String): Int {
        val deterministic = config.modelGroups.indexOfFirst { it.id == deterministicGroupId(instanceId) }
        if (deterministic >= 0) return deterministic
        val entriesOfInstance = config.modelEntries
            .filter { it.providerInstanceId == instanceId }
            .mapTo(mutableSetOf()) { it.id }
        return config.modelGroups.indexOfFirst { group ->
            group.id in config.imageGenerationGroupIds &&
                group.memberEntryIds.any { it in entriesOfInstance }
        }
    }

    fun groupFor(instanceId: String): ModelGroup? {
        val snapshot = repo.config.value
        snapshot.modelGroups.firstOrNull { it.id == deterministicGroupId(instanceId) }
            ?.let { return it }
        // 兼容确定性 id 之前的旧数据：按「启用生图分组内含该实例条目」定位。
        val entriesOfInstance = snapshot.modelEntries
            .filter { it.providerInstanceId == instanceId }
            .mapTo(mutableSetOf()) { it.id }
        return snapshot.modelGroups.firstOrNull { group ->
            group.id in snapshot.imageGenerationGroupIds &&
                group.memberEntryIds.any { it in entriesOfInstance }
        }
    }

    fun ensureGroupFor(instanceId: String, name: String): ModelGroup = synchronized(repo.store.lock) {
        repo.store.ensureLoaded()
        val config = repo.store.workingCopy()
        val existingIndex = indexOfSourceGroup(config, instanceId)
        if (existingIndex >= 0) {
            val existing = config.modelGroups[existingIndex]
            if (existing.name == name) return@synchronized existing
            config.modelGroups[existingIndex] = existing.copy(name = name)
            repo.store.save(config)
            return@synchronized config.modelGroups[existingIndex]
        }
        val created = ModelGroup(id = deterministicGroupId(instanceId), name = name)
        config.modelGroups.add(created)
        repo.store.save(config)
        created
    }

    fun setGroupEnabled(groupId: String, enabled: Boolean): Unit = synchronized(repo.store.lock) {
        repo.store.ensureLoaded()
        val config = repo.store.workingCopy()
        if (enabled) {
            if (config.modelGroups.none { it.id == groupId }) return@synchronized
            if (groupId !in config.imageGenerationGroupIds) config.imageGenerationGroupIds.add(groupId)
        } else {
            config.imageGenerationGroupIds.removeAll { it == groupId }
        }
        repo.store.save(config)
    }

    fun reorderGroups(groupIds: List<String>): Unit = synchronized(repo.store.lock) {
        repo.store.ensureLoaded()
        val config = repo.store.workingCopy()
        val valid = groupIds.filter { id -> config.modelGroups.any { it.id == id } }.distinct()
        config.imageGenerationGroupIds.clear()
        config.imageGenerationGroupIds.addAll(valid)
        repo.store.save(config)
    }

    fun setProviderEnabled(instanceId: String, enabled: Boolean): Unit = synchronized(repo.store.lock) {
        repo.store.ensureLoaded()
        val config = repo.store.workingCopy()
        if (enabled) {
            if (config.instances.none { it.id == instanceId }) return@synchronized
            if (instanceId !in config.imageGenerationProviderInstanceIds) {
                config.imageGenerationProviderInstanceIds.add(instanceId)
            }
        } else {
            config.imageGenerationProviderInstanceIds.removeAll { it == instanceId }
        }
        repo.store.save(config)
    }

    /**
     * 供应商排序覆写。生图分组列表跟随供应商顺序：每个供应商的来源分组
     * 按其所在位置重排到前面，未涉及的原启用分组保持相对序补在后面。
     */
    fun reorderProviders(instanceIds: List<String>): Unit = synchronized(repo.store.lock) {
        repo.store.ensureLoaded()
        val config = repo.store.workingCopy()
        val valid = instanceIds.filter { id -> config.instances.any { it.id == id } }.distinct()
        val previouslyEnabled = config.imageGenerationGroupIds.toList()
        config.imageGenerationProviderInstanceIds.clear()
        config.imageGenerationProviderInstanceIds.addAll(valid)
        val leading = valid.mapNotNull { instanceId ->
            config.modelGroups.getOrNull(indexOfSourceGroup(config, instanceId))
                ?.takeIf { it.id in config.imageGenerationGroupIds }
                ?.id
        }
        config.imageGenerationGroupIds.clear()
        config.imageGenerationGroupIds.addAll(leading + previouslyEnabled.filterNot { it in leading })
        repo.store.save(config)
    }

    /**
     * 换入新的来源目录：不改变任何勾选。未在上游目录中且未勾选的陈旧行
     * 可安全清除；自定义行与已勾选行即使这次没拉到也保留（瞬时不完整的
     * /models 响应不该丢用户选择）。
     */
    fun replaceModels(instanceId: String, models: List<LLMModel>): Unit = synchronized(repo.store.lock) {
        repo.store.ensureLoaded()
        val config = repo.store.workingCopy()
        var groupIndex = indexOfSourceGroup(config, instanceId)
        if (groupIndex < 0) {
            val fallbackName = config.instances.firstOrNull { it.id == instanceId }?.label ?: "生图来源"
            config.modelGroups.add(ModelGroup(id = deterministicGroupId(instanceId), name = fallbackName))
            groupIndex = config.modelGroups.lastIndex
        }
        val selectedIds = config.modelGroups[groupIndex].memberEntryIds.toSet()
        val existing = config.modelEntries.filter { it.providerInstanceId == instanceId }
        val priorByModel = existing.associateBy { it.baseModel.id }
        val incomingIds = models.mapTo(mutableSetOf()) { it.id }
        val refreshed = models.map { model ->
            val prior = priorByModel[model.id]
            ModelEntry(
                providerInstanceId = instanceId,
                baseModel = model,
                overrides = prior?.overrides ?: ModelOverrides(),
                isCustom = false,
                isHidden = prior?.let { it.id !in selectedIds } ?: true,
                uuid = prior?.id ?: UUID.randomUUID().toString(),
                userModifiedAt = prior?.userModifiedAt,
            )
        }
        val preserved = existing.filter {
            it.baseModel.id !in incomingIds && (it.isCustom || it.id in selectedIds)
        }
        config.modelEntries.removeAll { it.providerInstanceId == instanceId }
        config.modelEntries.addAll(refreshed + preserved)
        // 成员表收敛为「仍在该实例名下的已选条目」，保原序。
        val alive = config.modelEntries.associateBy { it.id }
        val orderedSelected = config.modelGroups[groupIndex].memberEntryIds.filter { id ->
            alive[id]?.providerInstanceId == instanceId
        }
        config.modelGroups[groupIndex] = config.modelGroups[groupIndex].copy(
            memberEntryIds = orderedSelected.toMutableList(),
        )
        repo.store.save(config)
    }

    /**
     * 单个生图模型的启停：条目 isHidden 翻转 + 启用时确保输出模态含
     * image + 分组成员表同步增删 + 分组本身保证在启用列表里。
     */
    fun setModelEnabled(instanceId: String, entryId: String, enabled: Boolean): Unit =
        synchronized(repo.store.lock) {
            repo.store.ensureLoaded()
            val config = repo.store.workingCopy()
            val entryIndex = config.modelEntries.indexOfFirst {
                it.id == entryId && it.providerInstanceId == instanceId
            }
            if (entryIndex < 0) return@synchronized
            var groupIndex = indexOfSourceGroup(config, instanceId)
            if (groupIndex < 0) {
                val fallbackName = config.instances.firstOrNull { it.id == instanceId }?.label ?: "生图来源"
                config.modelGroups.add(ModelGroup(id = deterministicGroupId(instanceId), name = fallbackName))
                groupIndex = config.modelGroups.lastIndex
            }
            val entry = config.modelEntries[entryIndex]
            val modalities = entry.overrides.outputModalities ?: entry.baseModel.outputModalities
            config.modelEntries[entryIndex] = entry.copy(
                isHidden = !enabled,
                overrides = entry.overrides.copy(
                    outputModalities = if (enabled && modalities.orEmpty().none { it.equals("image", true) }) {
                        listOf("image")
                    } else {
                        entry.overrides.outputModalities
                    },
                ),
                userModifiedAt = System.currentTimeMillis(),
            )
            val members = config.modelGroups[groupIndex].memberEntryIds.toMutableList()
            if (enabled && entryId !in members) members.add(entryId)
            if (!enabled) members.removeAll { it == entryId }
            config.modelGroups[groupIndex] = config.modelGroups[groupIndex].copy(memberEntryIds = members)
            val groupId = config.modelGroups[groupIndex].id
            if (enabled && groupId !in config.imageGenerationGroupIds) {
                config.imageGenerationGroupIds.add(groupId)
            }
            repo.store.save(config)
        }

    fun reorderModels(instanceId: String, entryIds: List<String>): Unit = synchronized(repo.store.lock) {
        repo.store.ensureLoaded()
        val config = repo.store.workingCopy()
        val groupIndex = indexOfSourceGroup(config, instanceId)
        if (groupIndex < 0) return@synchronized
        val valid = entryIds.filter { id ->
            config.modelEntries.any { it.id == id && it.providerInstanceId == instanceId }
        }.distinct()
        config.modelGroups[groupIndex] = config.modelGroups[groupIndex].copy(
            memberEntryIds = valid.toMutableList(),
        )
        repo.store.save(config)
    }
}
