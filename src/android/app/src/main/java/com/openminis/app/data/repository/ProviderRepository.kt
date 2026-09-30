package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.openminis.app.data.normalizeModelGroupOrder
import com.openminis.app.data.removeModelGroupAndBindings
import com.openminis.app.tools.migrateLegacyImageGenerationConfig
import com.openminis.app.tools.resolveImageGenerationEntries
import com.openminis.app.tools.resolveOrdinaryAgentLoopEntries
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import novex.android.data.model.ChatModelSelection
import novex.android.data.model.ImageEndpointMode
import novex.android.data.model.LLMModel
import novex.android.data.model.ModelEntry
import novex.android.data.model.ModelGroup
import novex.android.data.model.ModelOverrides
import novex.android.data.model.ProviderConfig
import novex.android.data.model.ProviderCredential
import novex.android.data.model.ProviderInstance
import novex.android.data.model.ProviderType
import novex.android.data.model.RoutingStrategy
import novex.android.data.model.hasImageInput
import com.openminis.app.provider.ModelReleaseIndex

import novex.android.repo.ImageSourceAdmin
import novex.android.repo.ProviderConfigStore
import novex.android.repo.ProviderModelRefresher
import novex.android.repo.ProviderTransfer
import novex.android.repo.applyOpenCodeSunset
import novex.android.repo.isOpenCodeFreeInstanceId

/**
 * 供应商配置中枢 —— 实例 / 模型条目 / 模型分组 / 各类绑定（agent-loop、
 * 生图、视觉）的读改写门面。
 *
 * P3.5a 重写后的分工：
 *  - [ProviderConfigStore]：持久化内核（provider.db + JSON 镜像双写、装载
 *    对账、锁、状态流发布）；
 *  - [ProviderModelRefresher]：模型列表拉取与刷新节奏；
 *  - [ImageSourceAdmin]：生图来源分组管理；
 *  - [ProviderTransfer]：实例导出 / 导入（跨端 JSON）；
 *  - [applyOpenCodeSunset]：下线迁移纯函数。
 *
 * 门面自身保留两件事：对外 API 的稳定形状（调用方遍布全仓，含参数名的
 * 签名不可动），与变更器的统一纪律 ——「锁 + 装载闸 + 工作副本 + 有变更
 * 才落盘」。工作副本是写时复制的，发布态对象永不被就地修改，Compose 读
 * 侧无锁遍历因此安全。
 */
class ProviderRepository(private val context: Context) {

    private val logTag = "NovexProviderRepo"

    internal val store = ProviderConfigStore(context)

    /** 模型缓存节奏 / 每日刷新标记 / 最近使用条目 —— 与镜像共用同一 prefs 文件。 */
    private val prefs: SharedPreferences =
        context.getSharedPreferences("provider_config", Context.MODE_PRIVATE)

    private val refresher = ProviderModelRefresher(context, this)
    private val imageSources = ImageSourceAdmin(this)
    private val transfer = ProviderTransfer(context, this)

    /** 加密凭据库（自愈工厂：三星 One UI / Android 16 的坏主键不再炸首读）。 */
    private val secrets by lazy {
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, "provider_secrets")
    }

    val config: StateFlow<ProviderConfig> get() = store.config
    val configLoaded: StateFlow<Boolean> get() = store.loaded

    suspend fun awaitConfigLoaded() = store.awaitLoaded()

    /** 直读快照（拷贝）—— 读侧遍历不与变更器互撞。 */
    val instances: List<ProviderInstance>
        get() = store.snapshotInstances()

    // ── 模型缓存节奏 ───────────────────────────────────────────────────

    fun invalidateModelCache(instanceId: String) {
        prefs.edit().remove(fetchStampKey(instanceId)).apply()
    }

    private fun fetchStampKey(instanceId: String) = "modelsLastFetchAt_$instanceId"

    private fun stampFetched(instanceId: String) {
        prefs.edit().putLong(fetchStampKey(instanceId), System.currentTimeMillis()).apply()
    }

    private fun olderThanTtl(instanceId: String): Boolean {
        val last = prefs.getLong(fetchStampKey(instanceId), 0L)
        return last == 0L || System.currentTimeMillis() - last > MODEL_CACHE_TTL_MS
    }

    // ── 实例 ───────────────────────────────────────────────────────────

    /**
     * 新增实例。仅 OAuth 实例与「官方端点的 API-key 实例」播种内置模型表；
     * 第三方 OpenAI 兼容端（Grok/vLLM/Ollama/LiteLLM…）绝不播种 —— 那批
     * 内置 id 上游不存在，「刷新后冒出 GPT 列表」的 bug 即源于此，留空等
     * /v1/models 拉取。新实例缓存按过期处理，首次后台扫描即补齐。
     */
    fun addInstance(instance: ProviderInstance): Unit = synchronized(store.lock) {
        store.ensureLoaded()
        val config = store.workingCopy()
        config.instances.add(instance)
        val seed = instance.credentialType == ProviderCredential.oauth || !isThirdPartyOpenAICompat(instance)
        if (seed) {
            config.modelEntries.addAll(instance.providerType.builtInModels.map { model ->
                ModelEntry(providerInstanceId = instance.id, baseModel = model)
            })
        } else {
            Log.i(
                logTag,
                "no built-in seed for third-party OpenAI-compat base " +
                    "(label=${instance.label} base=${instance.effectiveBaseURL}); " +
                    "models will come from upstream /v1/models on refresh",
            )
        }
        store.save(config)
        invalidateModelCache(instance.id)
    }

    fun updateInstance(instance: ProviderInstance): Unit = synchronized(store.lock) {
        store.ensureLoaded()
        val config = store.workingCopy()
        val index = config.instances.indexOfFirst { it.id == instance.id }
        if (index >= 0) {
            val prior = config.instances[index]
            // 换了端点（base URL / v1 后缀）后，旧端点的生图路由探测结果作废，
            // 清掉让 auto 模式重新探测；调用方已显式设置时不动。
            if ((prior.customBaseURL != instance.customBaseURL ||
                    prior.appendV1Suffix != instance.appendV1Suffix) &&
                instance.imageEndpointResolved != null
            ) {
                instance.imageEndpointResolved = null
            }
            config.instances[index] = instance
            store.save(config)
            // 任何可能改变模型清单的变更都失效缓存 —— 宁可多失效。
            if (prior.effectiveBaseURL != instance.effectiveBaseURL ||
                prior.credentialType != instance.credentialType ||
                prior.isEnabled != instance.isEnabled ||
                prior.useResponsesAPI != instance.useResponsesAPI
            ) {
                invalidateModelCache(instance.id)
            }
        }
    }

    /** 删实例：级联清条目、各绑定列表、空分组与密钥；思考规则行留在表中（schema 冻结）。 */
    fun removeInstance(instanceId: String): Unit = synchronized(store.lock) {
        store.ensureLoaded()
        invalidateModelCache(instanceId)
        val config = store.workingCopy()
        val goneEntryIds = config.modelEntries
            .filter { it.providerInstanceId == instanceId }
            .mapTo(HashSet()) { it.id }
        config.instances.removeAll { it.id == instanceId }
        config.imageGenerationProviderInstanceIds.removeAll { it == instanceId }
        config.modelEntries.removeAll { it.providerInstanceId == instanceId }
        if (goneEntryIds.isNotEmpty()) {
            for (group in config.modelGroups) group.memberEntryIds.removeAll { it in goneEntryIds }
            config.agentLoopModelEntryIds.removeAll { it in goneEntryIds }
        }
        val emptied = config.modelGroups.filter { it.memberEntryIds.isEmpty() }.mapTo(HashSet()) { it.id }
        if (emptied.isNotEmpty()) {
            config.modelGroups.removeAll { it.id in emptied }
            config.imageGenerationGroupIds.removeAll { it in emptied }
            if (config.defaultPrimaryGroupId in emptied) config.defaultPrimaryGroupId = null
            if (config.defaultSubGroupId in emptied) config.defaultSubGroupId = null
        }
        store.save(config)
        deleteApiKey(instanceId)
    }

    /**
     * 指向第三方 OpenAI 兼容主机（xAI/vLLM/Ollama/LiteLLM/DeepSeek 桥接…）？
     * 这类实例的模型表永远来自上游 /v1/models，不能拿官方 GPT 表凑数。
     */
    private fun isThirdPartyOpenAICompat(instance: ProviderInstance): Boolean {
        if (instance.providerType != ProviderType.openAI) return false
        val custom = instance.customBaseURL?.lowercase() ?: return false
        return listOf("api.openai.com", "chatgpt.com").none { custom.contains(it) }
    }

    fun instance(id: String): ProviderInstance? =
        store.config.value.instances.find { it.id == id }

    fun enabledInstances(providerType: ProviderType): List<ProviderInstance> =
        store.config.value.instances.filter { it.providerType == providerType && it.isEnabled }

    /** 记录 auto 模式下实际可用的生图端点；未变化则不落盘（别为每次生图翻配置）。 */
    fun setImageEndpointResolved(instanceId: String, endpoint: ImageEndpointMode): Unit = commitIf { config ->
        val index = config.instances.indexOfFirst { it.id == instanceId }
        if (index < 0 || config.instances[index].imageEndpointResolved == endpoint) {
            false
        } else {
            config.instances[index] = config.instances[index].copy(imageEndpointResolved = endpoint)
            true
        }
    }

    /**
     * 实例排序（供应商列表拖拽）。未知 id 丢弃、重复折叠、没提到的按原相对
     * 序补尾 —— 分区拖拽只传自己那段的 id 也能工作。纯重排无变化则跳过落盘。
     * 持久化即列表位置：保存时 sort_order 取下标，读取按 sort_order 升序。
     */
    fun reorderInstances(newOrder: List<String>): Unit = commitIf { config ->
        val current = config.instances.toList()
        if (current.isEmpty()) return@commitIf false
        val byId = current.associateBy { it.id }
        val seen = LinkedHashSet<String>()
        val ordered = ArrayList<ProviderInstance>(current.size)
        for (id in newOrder) {
            val item = byId[id] ?: continue
            if (seen.add(id)) ordered.add(item)
        }
        for (item in current) if (seen.add(item.id)) ordered.add(item)
        if (ordered.map { it.id } == current.map { it.id }) {
            false
        } else {
            config.instances.clear()
            config.instances.addAll(ordered)
            true
        }
    }

    // ── 模型条目 ───────────────────────────────────────────────────────

    fun entriesFor(instanceId: String): List<ModelEntry> =
        store.config.value.modelEntries
            .filter { it.providerInstanceId == instanceId }
            .sortedWith(releaseRankOrder)

    fun visibleEntries(instanceId: String): List<ModelEntry> =
        store.config.value.modelEntries
            .filter { it.providerInstanceId == instanceId && !it.isHidden }
            .sortedWith(releaseRankOrder)

    fun allVisibleEntries(): List<ModelEntry> {
        val snapshot = store.config.value
        val live = snapshot.instances.filter { it.isEnabled }.mapTo(HashSet()) { it.id }
        return snapshot.modelEntries
            .filter { it.providerInstanceId in live && !it.isHidden }
            .sortedWith(releaseRankOrder)
    }

    /**
     * 新模型优先（T-model-release-ranking）：/models 的返回序实际上随意，
     * 选择器不该开在陈旧或不可调用的模型上。同级以 id 决胜负保证全序稳定，
     * 列表不会在两次读取间洗牌。
     */
    private val releaseRankOrder = Comparator<ModelEntry> { left, right ->
        val rankLeft = ModelReleaseIndex.rank(
            left.baseModel.id, left.baseModel.displayName, left.baseModel.contextWindow,
        )
        val rankRight = ModelReleaseIndex.rank(
            right.baseModel.id, right.baseModel.displayName, right.baseModel.contextWindow,
        )
        ModelReleaseIndex.comparator.compare(rankLeft, rankRight)
            .takeIf { it != 0 } ?: left.baseModel.id.compareTo(right.baseModel.id)
    }

    /**
     * 用上游 /models 的结果整体替换该实例的条目。保留：自定义条目（上游
     * 没提时）、既有条目的 uuid / 覆盖 / 隐藏位（识别键 = baseModel.id，
     * 同 id 撞车时偏信非自定义那条）。疑似异常缩水（原本 ≥4 且新表不足一半）
     * 时保留分组引用不动 —— 瞬时 API 抽风不该清空用户整理的分组。
     */
    fun replaceEntries(instanceId: String, models: List<LLMModel>): Unit = synchronized(store.lock) {
        store.ensureLoaded()
        val config = store.workingCopy()
        val existing = config.modelEntries.filter { it.providerInstanceId == instanceId }
        val previousIds = existing.mapTo(HashSet()) { it.id }

        val priorByModelId = HashMap<String, ModelEntry>()
        for (entry in existing) {
            val incumbent = priorByModelId[entry.baseModel.id]
            if (incumbent == null || incumbent.isCustom) priorByModelId[entry.baseModel.id] = entry
        }
        val instanceBase = config.instances.firstOrNull { it.id == instanceId }?.effectiveBaseURL
        val freshIds = models.mapTo(HashSet()) { it.id }
        val refreshed = models.map { model ->
            val prior = priorByModelId[model.id]
            ModelEntry(
                providerInstanceId = instanceId,
                baseModel = novex.android.data.model.NovexDeepSeekModelMetadata.official(model, instanceBase),
                overrides = prior?.overrides ?: ModelOverrides(),
                isCustom = false,
                isHidden = prior?.isHidden ?: false,
                uuid = prior?.id ?: UUID.randomUUID().toString(),
                userModifiedAt = prior?.userModifiedAt,
            )
        }
        val keptCustom = existing.filter { it.isCustom && it.baseModel.id !in freshIds }

        config.modelEntries.removeAll { it.providerInstanceId == instanceId }
        config.modelEntries.addAll(refreshed)
        config.modelEntries.addAll(keptCustom)

        val survivors = config.modelEntries.mapTo(HashSet()) { it.id }
        val dropped = previousIds - survivors
        if (dropped.isNotEmpty()) {
            val looksWrong = existing.size >= 4 && models.size * 2 < existing.size
            if (looksWrong) {
                Log.w(
                    logTag,
                    "replaceEntries: suspicious shrink ${existing.size} -> ${models.size}; group refs kept as-is",
                )
            } else {
                for (i in config.modelGroups.indices) {
                    val before = config.modelGroups[i].memberEntryIds.size
                    config.modelGroups[i].memberEntryIds.removeAll { it in dropped }
                    if (before != config.modelGroups[i].memberEntryIds.size) {
                        Log.i(logTag, "replaceEntries: pruned stale refs from '${config.modelGroups[i].name}'")
                    }
                }
                val loopBefore = config.agentLoopModelEntryIds.size
                config.agentLoopModelEntryIds.removeAll { it in dropped }
                if (loopBefore != config.agentLoopModelEntryIds.size) {
                    Log.i(logTag, "replaceEntries: pruned ${loopBefore - config.agentLoopModelEntryIds.size} agent-loop pin(s)")
                }
            }
        }
        store.save(config)
        stampFetched(instanceId)
    }

    /** 非自定义条目按 (实例, baseModel.id) 去重 —— 重复添加直接放弃。 */
    fun addEntry(entry: ModelEntry): Unit = commitIf { config ->
        val clash = !entry.isCustom && config.modelEntries.any {
            it.providerInstanceId == entry.providerInstanceId && it.baseModel.id == entry.baseModel.id
        }
        if (clash) {
            false
        } else {
            config.modelEntries.add(entry)
            true
        }
    }

    fun updateEntry(entry: ModelEntry): Unit = commitIf { config ->
        val index = config.modelEntries.indexOfFirst { it.id == entry.id }
        if (index < 0) {
            false
        } else {
            config.modelEntries[index] = entry.copy(userModifiedAt = System.currentTimeMillis())
            true
        }
    }

    /** 删条目：级联清各分组成员与 agent-loop 直钉（选择器不该给已删模型打勾）。 */
    fun removeEntry(entryId: String): Unit = commitIf { config ->
        config.modelEntries.removeAll { it.id == entryId }
        config.modelGroups.forEach { group -> group.memberEntryIds.removeAll { it == entryId } }
        config.agentLoopModelEntryIds.removeAll { it == entryId }
        true
    }

    fun setImageModelEndpointMode(entryId: String, mode: ImageEndpointMode?): Unit = commitIf { config ->
        val index = config.modelEntries.indexOfFirst { it.id == entryId }
        if (index < 0) {
            false
        } else {
            config.modelEntries[index] = config.modelEntries[index].copy(
                overrides = config.modelEntries[index].overrides.copy(
                    imageEndpointMode = mode,
                    imageEndpointResolved = null,
                ),
                userModifiedAt = System.currentTimeMillis(),
            )
            true
        }
    }

    fun setImageModelEndpointResolved(entryId: String, endpoint: ImageEndpointMode): Unit = commitIf { config ->
        val index = config.modelEntries.indexOfFirst { it.id == entryId }
        if (index < 0 || config.modelEntries[index].overrides.imageEndpointResolved == endpoint) {
            false
        } else {
            val entry = config.modelEntries[index]
            config.modelEntries[index] = entry.copy(
                overrides = entry.overrides.copy(imageEndpointResolved = endpoint),
            )
            true
        }
    }

    // ── 分组与 agent-loop 绑定 ─────────────────────────────────────────

    fun addGroup(group: ModelGroup): Unit = commitIf { config ->
        config.modelGroups.add(group)
        true
    }

    fun updateGroup(group: ModelGroup): Unit = commitIf { config ->
        val index = config.modelGroups.indexOfFirst { it.id == group.id }
        if (index < 0) {
            false
        } else {
            // 调用方常拿发布态 copy(...) 进来，成员列表与发布态共享引用 ——
            // 重包一层，把发布态挡在工作副本之外。
            config.modelGroups[index] = group.copy(memberEntryIds = group.memberEntryIds.toMutableList())
            true
        }
    }

    fun removeGroup(groupId: String): Unit = commitIf { config ->
        config.removeModelGroupAndBindings(groupId)
        true
    }

    fun group(id: String): ModelGroup? = store.config.value.modelGroups.find { it.id == id }

    /** 分组排序（拖拽）。契约同 [reorderInstances]；无变化不落盘。 */
    fun reorderModelGroups(newOrder: List<String>): Unit = commitIf { config ->
        val current = config.modelGroups.toList()
        if (current.isEmpty()) return@commitIf false
        val byId = current.associateBy { it.id }
        val ordered = normalizeModelGroupOrder(current.map { it.id }, newOrder).mapNotNull(byId::get)
        if (ordered.map { it.id } == current.map { it.id }) {
            false
        } else {
            config.modelGroups.clear()
            config.modelGroups.addAll(ordered)
            true
        }
    }

    /** 覆写 agent-loop 可见的条目直钉列表。入库前去重：重复 id 会变重复 LazyColumn key。 */
    fun setAgentLoopEntryIds(ids: List<String>): Unit = commitIf { config ->
        config.agentLoopModelEntryIds.clear()
        config.agentLoopModelEntryIds.addAll(ids.distinct())
        true
    }

    fun setAgentLoopGroupIds(ids: List<String>): Unit = commitIf { config ->
        config.agentLoopGroupIds.clear()
        config.agentLoopGroupIds.addAll(ids.distinct())
        true
    }

    fun addAgentLoopEntry(entryId: String) {
        val current = store.config.value.agentLoopModelEntryIds.toList()
        if (entryId !in current) setAgentLoopEntryIds(current + entryId)
    }

    fun removeAgentLoopEntry(entryId: String) {
        val current = store.config.value.agentLoopModelEntryIds.toList()
        if (entryId in current) setAgentLoopEntryIds(current.filterNot { it == entryId })
    }

    fun addAgentLoopGroup(groupId: String) {
        val current = store.config.value.agentLoopGroupIds.toList()
        if (groupId !in current) setAgentLoopGroupIds(current + groupId)
    }

    fun removeAgentLoopGroup(groupId: String) {
        val current = store.config.value.agentLoopGroupIds.toList()
        if (groupId in current) setAgentLoopGroupIds(current.filterNot { it == groupId })
    }

    /** 拖拽落盘。新序必须是现序的排列才收 —— 防陈旧快照的拖拽覆盖并发清理。 */
    fun reorderAgentLoopEntries(newOrder: List<String>) {
        val current = store.config.value.agentLoopModelEntryIds.toSet()
        if (newOrder.toSet() == current) setAgentLoopEntryIds(newOrder)
    }

    fun reorderAgentLoopGroups(newOrder: List<String>) {
        val current = store.config.value.agentLoopGroupIds.toSet()
        if (newOrder.toSet() == current) setAgentLoopGroupIds(newOrder)
    }

    // ── 绑定选择 / 候选解析 ────────────────────────────────────────────

    /**
     * 用户最近选中/发送过的条目（全局持久，非按会话）。新会话在没配默认
     * 分组时回落到它 —— null 表示还没用过，调用方继续走下一层。
     */
    var lastUsedEntryId: String?
        get() = prefs.getString(KEY_LAST_USED_ENTRY, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LAST_USED_ENTRY) else putString(KEY_LAST_USED_ENTRY, value)
            }.apply()
        }

    /** lastUsedEntryId 仍有效（可见 + 可选中）才返回它。 */
    fun lastUsedVisibleEntry(): ModelEntry? {
        val wanted = lastUsedEntryId ?: return null
        return allVisibleEntries().firstOrNull { it.id == wanted && ChatModelSelection.eligible(it) }
    }

    /**
     * 新会话的末层默认：最新添加的启用供应商里最新加入的文本模型。逐供应
     * 商从新到旧扫，第一家没有文本模型也能落到下一家，而不是返回空。
     */
    fun newestProviderNewestTextEntry(): ModelEntry? {
        val snapshot = store.config.value
        val live = snapshot.instances.filter { it.isEnabled }.sortedByDescending { it.createdAt }
        for (instance in live) {
            val entry = snapshot.modelEntries
                .filter { it.providerInstanceId == instance.id && ChatModelSelection.eligible(it) }
                .lastOrNull()
            if (entry != null) return entry
        }
        return null
    }

    /**
     * 分组里「供应商当前启用」的成员，按成员声明序（路由语义里 primary =
     * 首成员）。孤儿 id（实例已删）也滤掉 —— 分组不能钉在一个调不出的模型上。
     */
    fun enabledMemberEntries(group: ModelGroup): List<ModelEntry> {
        val snapshot = store.config.value
        val live = snapshot.instances.filter { it.isEnabled }.mapTo(HashSet()) { it.id }
        return group.memberEntryIds.mapNotNull { entryId ->
            snapshot.modelEntries.find { it.id == entryId }
                ?.takeIf { it.providerInstanceId in live }
        }
    }

    fun firstEnabledMemberEntry(group: ModelGroup): ModelEntry? =
        enabledMemberEntries(group).firstOrNull()

    /** 标题生成专用子模型：默认子分组的第一个启用成员；没有则回落主模型。 */
    fun resolveTitleSubEntry(): ModelEntry? {
        val subGroup = group(defaultSubGroupId ?: return null) ?: return null
        return firstEnabledMemberEntry(subGroup)
    }

    fun isEntryProviderEnabled(entryId: String): Boolean {
        val snapshot = store.config.value
        val entry = snapshot.modelEntries.find { it.id == entryId } ?: return false
        return snapshot.instances.find { it.id == entry.providerInstanceId }?.isEnabled == true
    }

    var defaultPrimaryGroupId: String?
        get() = store.config.value.defaultPrimaryGroupId
        set(value) = commitIf { it.defaultPrimaryGroupId = value; true }

    var defaultSubGroupId: String?
        get() = store.config.value.defaultSubGroupId
        set(value) = commitIf { it.defaultSubGroupId = value; true }

    var visionGroupId: String?
        get() = store.config.value.visionGroupId
        set(value) = commitIf { it.visionGroupId = value; true }

    /** 视觉分组已绑定且仍存在，才给不能原生看图的主模型暴露 read_image。 */
    fun hasVisionGroupConfigured(): Boolean {
        val bound = store.config.value.visionGroupId ?: return false
        return store.config.value.modelGroups.any { it.id == bound }
    }

    fun visionGroupName(): String? {
        val bound = store.config.value.visionGroupId ?: return null
        return store.config.value.modelGroups.find { it.id == bound }?.name
    }

    /**
     * 视觉分组里的有序故障转移候选：成员须为启用实例 + 模型声明图片输入；
     * 分组策略 loadBalance 时按 [loadBalanceSeed] 轮转起点分摊，fallback
     * 保持声明序。按条目 id 去重保序。空表 = 调用方明确失败。
     */
    fun resolveVisionCandidates(loadBalanceSeed: Int = 0): List<Pair<ProviderInstance, ModelEntry>> {
        store.ensureLoaded()
        val snapshot = store.config.value

        fun pairFor(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = snapshot.modelEntries.find { it.id == memberId } ?: return null
            val owner = snapshot.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!owner.isEnabled || !entry.model.hasImageInput) return null
            return owner to entry
        }

        val bound = snapshot.visionGroupId ?: return emptyList()
        val group = snapshot.modelGroups.find { it.id == bound } ?: return emptyList()
        var members = group.memberEntryIds.mapNotNull(::pairFor)
        if (group.strategy == RoutingStrategy.loadBalance && members.size > 1) {
            val shift = kotlin.math.abs(loadBalanceSeed) % members.size
            members = members.drop(shift) + members.take(shift)
        }
        val unique = ArrayList<Pair<ProviderInstance, ModelEntry>>(members.size)
        for (candidate in members) {
            if (unique.none { it.second.id == candidate.second.id }) unique.add(candidate)
        }
        return unique
    }

    // ── 迁移 / 下线 ────────────────────────────────────────────────────

    fun isOpenCodeFreeInstance(instanceId: String): Boolean = isOpenCodeFreeInstanceId(instanceId)

    /** 手动触发一次下线迁移（幂等；正常路径由首次装载自动携带）。 */
    fun ensureOpenCodeSunsetMigration(): Unit = synchronized(store.lock) {
        store.ensureLoaded()
        val config = store.workingCopy()
        if (applyOpenCodeSunset(config)) store.save(config)
    }

    fun ensureImageGenerationMigration(): Unit = synchronized(store.lock) {
        store.ensureLoaded()
        val current = store.config.value
        val migrated = migrateLegacyImageGenerationConfig(current)
        if (migrated !== current && migrated != current) store.save(migrated)
    }

    /** agent-loop（minis-model-use）实际可见的条目：分组展开 ∪ 直钉，去重，滤禁用供应商。 */
    fun resolvedAgentLoopEntries(): List<ModelEntry> {
        ensureImageGenerationMigration()
        return resolveOrdinaryAgentLoopEntries(store.config.value)
    }

    /** 生图可见条目：解析后仍要求实例存在且凭据可用。 */
    fun resolvedImageGenerationEntries(): List<ModelEntry> {
        ensureImageGenerationMigration()
        return resolveImageGenerationEntries(store.config.value).filter { entry ->
            val owner = instance(entry.providerInstanceId) ?: return@filter false
            usableApiKey(owner) != null
        }
    }

    // ── 生图来源分组（实现于 [ImageSourceAdmin]，经成员透出保 API 形状）──

    fun setImageGenerationGroupEnabled(groupId: String, enabled: Boolean): Unit =
        imageSources.setGroupEnabled(groupId, enabled)

    fun reorderImageGenerationGroups(groupIds: List<String>): Unit =
        imageSources.reorderGroups(groupIds)

    fun setImageGenerationProvider(instanceId: String, enabled: Boolean): Unit =
        imageSources.setProviderEnabled(instanceId, enabled)

    fun reorderImageGenerationProviders(instanceIds: List<String>): Unit =
        imageSources.reorderProviders(instanceIds)

    fun imageGenerationGroupForProvider(instanceId: String): ModelGroup? =
        imageSources.groupFor(instanceId)

    fun ensureImageGenerationGroupForProvider(instanceId: String, name: String): ModelGroup =
        imageSources.ensureGroupFor(instanceId, name)

    fun replaceImageGenerationModels(instanceId: String, models: List<LLMModel>): Unit =
        imageSources.replaceModels(instanceId, models)

    fun setImageGenerationModelEnabled(instanceId: String, entryId: String, enabled: Boolean): Unit =
        imageSources.setModelEnabled(instanceId, entryId, enabled)

    fun reorderImageGenerationModels(instanceId: String, entryIds: List<String>): Unit =
        imageSources.reorderModels(instanceId, entryIds)

    // ── 模型刷新（实现于 [ProviderModelRefresher]，成员透出）───────────

    suspend fun refreshModels(instance: ProviderInstance) = refresher.refreshNow(instance)

    /** UI 触发的 stale-while-revalidate：打开选择器时对过期实例补后台刷新。 */
    fun triggerBackgroundRefreshIfStale(scope: kotlinx.coroutines.CoroutineScope) {
        val stale = store.config.value.instances.filter { it.isEnabled && olderThanTtl(it.id) }
        if (stale.isEmpty()) return
        Log.i(logTag, "SWR refresh due: ${stale.size} stale instance(s)")
        for (instance in stale) scope.launch { refresher.autoRefresh(instance) }
    }

    /** 每日一次的全量刷新（Application.onCreate 调用）；等装载完成再定启停。 */
    fun refreshAllModelsIfNeeded(scope: kotlinx.coroutines.CoroutineScope) {
        val key = "lastModelsRefreshDate"
        val last = prefs.getLong(key, 0L)
        val now = System.currentTimeMillis()
        if (last > 0L && sameCalendarDay(last, now)) {
            Log.i(logTag, "daily refresh skipped — already ran today")
            return
        }
        scope.launch {
            awaitConfigLoaded()
            val enabled = store.config.value.instances.filter { it.isEnabled }
            if (enabled.isEmpty()) {
                Log.i(logTag, "daily refresh skipped — no enabled instances")
                return@launch
            }
            Log.i(logTag, "daily refresh firing for ${enabled.size} instance(s)")
            prefs.edit().putLong(key, now).apply()
            for (instance in enabled) scope.launch { refresher.autoRefresh(instance) }
        }
    }

    private fun sameCalendarDay(aMs: Long, bMs: Long): Boolean {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = aMs
        val yearA = calendar.get(java.util.Calendar.YEAR)
        val dayA = calendar.get(java.util.Calendar.DAY_OF_YEAR)
        calendar.timeInMillis = bMs
        return yearA == calendar.get(java.util.Calendar.YEAR) &&
            dayA == calendar.get(java.util.Calendar.DAY_OF_YEAR)
    }

    // ── 凭据 ───────────────────────────────────────────────────────────

    fun saveApiKey(instanceId: String, key: String) {
        secrets.edit().putString("apikey_$instanceId", key).apply()
    }

    fun loadApiKey(instanceId: String): String? = secrets.getString("apikey_$instanceId", null)

    fun deleteApiKey(instanceId: String) {
        secrets.edit().remove("apikey_$instanceId").apply()
    }

    /**
     * 路由层实际可用的凭据：存了的 key；否则「空 key 合法」的第三方兼容
     * 端点给 ""。只有真没有凭据才 null —— 各调用点的跳过语义保持不变
     * （尤其无 token 的 OAuth 实例必须继续按未认证处理）。
     */
    fun usableApiKey(instance: ProviderInstance): String? =
        loadApiKey(instance.id) ?: if (instance.allowsEmptyAPIKey) "" else null

    // ── 导出 / 导入（实现于 [ProviderTransfer]，成员透出）──────────────

    fun exportInstanceJSON(instanceId: String): String? = transfer.exportInstance(instanceId)

    /** 导入导出 JSON。成功返回（自动改名后的）新实例标签。 */
    fun importInstanceJSON(jsonStr: String): String? = transfer.importInstance(jsonStr)

    // ── 变更器小工具 ───────────────────────────────────────────────────

    /**
     * 标准变更器：锁 + 装载闸 + 工作副本 + 「有变更才落盘」。body 返回
     * false 表示无事发生（未找到 / 无变化 / 空表），直接放弃 —— 纯读取
     * 级的调用不应该引起落盘与状态流重发。
     */
    private inline fun commitIf(body: (ProviderConfig) -> Boolean): Unit = synchronized(store.lock) {
        store.ensureLoaded()
        val config = store.workingCopy()
        if (body(config)) store.save(config)
    }

    private companion object {
        const val KEY_LAST_USED_ENTRY = "lastUsedModelEntryId"

        /** 模型缓存 TTL：24h 滚动窗口（与 iOS 的日刷新窗口对齐）。 */
        const val MODEL_CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    }
}

/**
 * 旧包名顶层函数的委托：实现（与说明）在 novex.android.repo.ProviderOpenCodeSunset。
 * ui 的全限定内联调用与旧包测试以本包名引用，待 UI 战线重写后随迁。
 */
fun isOpenCodeFreeInstanceId(instanceId: String): Boolean =
    novex.android.repo.isOpenCodeFreeInstanceId(instanceId)

fun applyOpenCodeSunset(config: ProviderConfig): Boolean =
    novex.android.repo.applyOpenCodeSunset(config)
