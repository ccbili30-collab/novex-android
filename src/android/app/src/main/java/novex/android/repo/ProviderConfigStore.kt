package novex.android.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import novex.android.data.NovexProviderDatabase
import novex.android.data.model.ProviderConfig
import novex.android.data.provider.ProviderMetaKeys
import novex.android.data.provider.ProviderRowsSnapshot
import novex.android.data.provider.ProviderStoreDao
import novex.android.data.provider.entryCompositeId
import novex.android.data.provider.toConfig
import novex.android.data.provider.toRows

/**
 * 供应商配置的持久化内核 —— provider.db（Room，权威）+ provider_config
 * SharedPreferences 的 JSON 镜像（降级保险）双写，与状态流的发布。
 *
 * 设计意图（每条都对应过真实事故，不是装饰）：
 *
 *  - **双写与同步哈希**：镜像 key = "config"；每次落盘把镜像串的 SHA-256
 *    写进 DB meta（json_sync_hash）。装载时哈希对得上 → DB 是最新；对不上
 *    → 旧版本构建在降级窗口里绕过 DB 直写过 JSON，以 JSON 为准重建 DB。
 *    镜像让老构建永远读得到当前配置，DB 让本构建读得快。
 *  - **镜像用 commit() 同步写**：DB 里存的哈希对应「这次写出的镜像串」。
 *    若 apply() 异步排队且进程死在落盘前，下次启动会看到 DB(新哈希) +
 *    磁盘(旧镜像) → 误判为降级窗口 → 用旧镜像把 DB 已持久化的写入冲掉。
 *  - **读失败拒绝空配置**：DAO 读失败 ≠ 库为空。把异常吞成 0 会让下一次
 *    变更把「空」写满全库 —— 18 个供应商变 5 个的 Pixel 6 事故。读失败
 *    就抛，装载保持未完成态，下次访问重试。
 *  - **写路径全程单锁**：读-改-写变更器共享 configLock；状态流发布的
 *    对象永不就地改（写时复制），Compose 读侧无锁也不会撞
 *    ConcurrentModificationException。
 *  - **revision 单调递增**：data class 的结构相等会因内部列表被就地改过
 *    而误判「没变」，StateFlow 压掉发布；revision 保证必然重发。
 */
internal class ProviderConfigStore(private val context: Context) {

    private val logTag = "NovexProviderStore"

    /** SharedPreferences 名与 config 键是磁盘事实（老构建靠它读配置）。 */
    private val prefs: SharedPreferences =
        context.getSharedPreferences("provider_config", Context.MODE_PRIVATE)

    /**
     * coerceInputValues 是关键：未知枚举值（新构建写入的 ThinkingLevel）
     * 在旧构建解码时回退到默认，而不是抛 SerializationException 把整个
     * 镜像解码炸成空配置。ignoreUnknownKeys 只管未知键，不管未知枚举值。
     */
    val codec: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    /** provider.db 独立成库：降级到不认识这些表的构建时不至于打不开 minis.db。首帧不依赖它，惰性开。 */
    private val dao: ProviderStoreDao by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NovexProviderDatabase.getInstance(context).providerStoreDao()
    }

    val lock = Any()

    private val _config = MutableStateFlow(ProviderConfig())
    val config: StateFlow<ProviderConfig> = _config.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private var current: ProviderConfig
        get() = _config.value
        set(value) { _config.value = value }

    private var loadDone: Boolean
        get() = _loaded.value
        set(value) { _loaded.value = value }

    /** 首次异步装载完成（含失败路径）后置位；启动期消费者（每日刷新）等它而不是赌空档。 */
    private val firstLoad = CompletableDeferred<Unit>()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { loadOffMainThread() }
    }

    suspend fun awaitLoaded() = firstLoad.await()

    private fun markLoaded() {
        if (!firstLoad.isCompleted) firstLoad.complete(Unit)
    }

    /**
     * 冷启动装载（#753）：同步 prefs 读 + 大 JSON 解码合计可达 11s+，绝不
     * 可在构造器里跑（构造在主线程）。这里发空占位、IO 线程装载、就绪即
     * 发布；响应式消费者自动跟上，动作时机的直读容忍短暂空窗（装载前的
     * 发送等同新装机的无模型状态）。
     */
    private suspend fun loadOffMainThread() {
        try {
            val fromDisk = loadConfigBlocking()
            synchronized(lock) {
                // 装载窗口内若有写入（罕见——写入本身也需要配置），写入方
                // 已置 loadDone 并优先生效；磁盘快照不得回头覆盖它。
                if (!loadDone) {
                    rideOpenCodeSunset(fromDisk)
                    current = fromDisk
                    loadDone = true
                    Log.i(logTag, "async load adopted ${fromDisk.instances.size} instance(s)")
                }
            }
        } catch (e: Exception) {
            // loadConfigBlocking 只在「库不可读」时抛。这里吞掉：逃逸会击穿
            // 默认未捕获处理器让冷启动崩且循环。loadDone 保持 false，
            // ensureLoaded 会在下次访问重试。
            Log.e(logTag, "initial load refused; staying unloaded for retry: ${e.message}")
        }
        markLoaded()
    }

    /**
     * 读-改-写变更器的装载闸：变更器动 _config 前先同步装载。没有它，
     * 装载窗口内落进的写入会改到空占位并落盘 —— 等于清空用户配置。
     * 已装载时只是一次 volatile 读。
     */
    fun ensureLoaded() {
        if (loadDone) return
        synchronized(lock) {
            if (loadDone) return
            val fromDisk = try {
                loadConfigBlocking()
            } catch (e: Exception) {
                Log.e(logTag, "ensureLoaded refused to read store; will retry: ${e.message}")
                return
            }
            rideOpenCodeSunset(fromDisk)
            current = fromDisk
            loadDone = true
        }
        markLoaded()
    }

    /** OpenCode 下线迁移搭首次装载的车：所有配置路径都过这里，无需 UI 调用点。 */
    private fun rideOpenCodeSunset(config: ProviderConfig) {
        if (applyOpenCodeSunset(config)) {
            runCatching { runBlocking { persist(config) } }
        }
    }

    private fun loadConfigBlocking(): ProviderConfig =
        novex.android.data.model.NovexDeepSeekModelMetadata.repairCatalog(
            runBlocking { reconcile() },
        )

    /**
     * 三方对账（DB / 镜像 / 同步哈希），决策树见类注释。返回权威配置；
     * 两库皆空且 DB 读失败时抛出（拒绝空配置覆盖）。
     */
    private suspend fun reconcile(): ProviderConfig {
        val mirrorText = prefs.getString("config", null)
        var daoFailed = false
        val dbCount = try {
            dao.instanceCount()
        } catch (e: Exception) {
            Log.w(logTag, "instanceCount failed: ${e.message}")
            daoFailed = true
            0
        }
        Log.i(logTag, "load: dbInstances=$dbCount daoFailed=$daoFailed mirrorBytes=${mirrorText?.length ?: -1}")

        var dbConfig: ProviderConfig? = null
        var storedHash: String? = null
        if (dbCount > 0) {
            try {
                val snapshot = ProviderRowsSnapshot(
                    instanceRows = dao.instanceRows(),
                    modelRows = dao.modelRows(),
                    groupRows = dao.groupRows(),
                    loopRows = dao.agentLoopRows(),
                    metaRows = dao.metaRows(),
                )
                dbConfig = snapshot.toConfig(codec)
                storedHash = snapshot.metaRows
                    .firstOrNull { it.key == ProviderMetaKeys.JSON_SYNC_HASH }?.value
            } catch (e: Exception) {
                Log.w(logTag, "DB snapshot failed, will try mirror: ${e.message}")
                daoFailed = true
            }
        }

        if (dbConfig != null) {
            val mirrorHash = mirrorText?.let(::sha256Hex)
            if (mirrorHash == storedHash) {
                return dbConfig
            }
            Log.i(
                logTag,
                "sync hash mismatch (stored=${storedHash?.take(8)} mirror=${mirrorHash?.take(8)}) — re-importing mirror",
            )
        }

        if (mirrorText != null) {
            val parsed = try {
                codec.decodeFromString<ProviderConfig>(mirrorText)
            } catch (e: Exception) {
                Log.w(logTag, "mirror decode failed: ${e.message}")
                null
            }
            if (parsed != null) {
                val canonical = try {
                    persist(parsed)
                } catch (e: Exception) {
                    Log.w(logTag, "mirror -> DB import failed: ${e.message}")
                    parsed
                }
                migrateLegacyLastUsedEntry(parsed)
                Log.i(
                    logTag,
                    "mirror imported: ${canonical.instances.size} instance(s), ${canonical.modelEntries.size} entry(ies)",
                )
                return canonical
            }
        }

        if (dbConfig != null) {
            // 镜像读不回来（损坏/半写）但 DB 还有行：DB 权威。宁可镜像陈旧，
            // 不可让下一个变更器把空配置写满库。
            Log.w(logTag, "mirror unusable; keeping ${dbConfig.instances.size} DB instance(s) authoritative")
            return dbConfig
        }

        if (daoFailed) {
            Log.e(logTag, "both stores unreadable; refusing to fabricate an empty config")
            throw IllegalStateException(
                "provider store unreadable (DB read failed, JSON mirror unusable) — " +
                    "refusing to load an empty config that would overwrite existing providers",
            )
        }
        return ProviderConfig()
    }

    /**
     * 老格式 lastUsedEntryId（随机 uuid）→ 新复合键 "{instanceId}/{modelId}"。
     * 不迁移的话升级后首启不认识用户的最近选择，新会话回退到「最新供应商」
     * —— 看起来像升级把选择弄丢了。
     */
    private fun migrateLegacyLastUsedEntry(parsed: ProviderConfig) {
        val legacy = prefs.getString(KEY_LAST_USED_ENTRY, null) ?: return
        if (legacy.contains('/')) return
        val rewritten = parsed.modelEntries
            .firstOrNull { it.uuid == legacy }
            ?.let { entryCompositeId(it.providerInstanceId, it.baseModel.id) }
            ?: return
        prefs.edit().putString(KEY_LAST_USED_ENTRY, rewritten).apply()
        Log.i(logTag, "lastUsedEntryId rewritten ${legacy.take(8)} -> $rewritten")
    }

    /**
     * DB + 镜像 + 同步哈希一次落定；返回快照回读后的规范化配置（条目
     * uuid 为复合键形态）。调用方必须以返回值为新的权威内存态。
     */
    suspend fun persist(config: ProviderConfig): ProviderConfig {
        val mirror = codec.encodeToString(ProviderConfig.serializer(), config)
        val snapshot = config.toRows(codec, jsonSyncHash = sha256Hex(mirror))
        dao.overwriteConfigTables(
            instances = snapshot.instanceRows,
            models = snapshot.modelRows,
            groups = snapshot.groupRows,
            loopTargets = snapshot.loopRows,
            meta = snapshot.metaRows,
        )
        // commit()（同步）：见类注释「镜像用 commit() 同步写」。返回 false
        // （盘满/权限/坏 XML）只记日志 —— DB 已权威持有新状态，下次成功
        // 保存会把镜像与哈希重新对齐。
        if (!prefs.edit().putString("config", mirror).commit()) {
            Log.w(logTag, "mirror commit() rejected (disk full? prefs corruption?); DB stays authoritative")
        }
        return snapshot.toConfig(codec)
    }

    /**
     * 保存 + 发布。落盘失败不抛（沿用历史 fire-and-forget 契约），内存态
     * 照发 —— UI 反映用户意图，下次成功保存对齐一切。发布的是防御性深
     * 拷贝 + 新 revision。
     */
    fun save(config: ProviderConfig) {
        synchronized(lock) {
            val canonical = try {
                runBlocking { persist(config) }
            } catch (e: Exception) {
                Log.e(logTag, "persist failed; emitting in-memory state only: ${e.message}", e)
                config
            }
            current = canonical.defensivelyCopied(revision = ProviderConfig.nextRevision())
        }
    }

    /** 直读快照（拷贝）：读侧遍历不允许撞上变更器的就地修改。 */
    fun snapshotInstances(): List<novex.android.data.model.ProviderInstance> =
        synchronized(lock) { current.instances.toList() }

    /**
     * 变更器的私有工作副本：写时复制到列表级 + 分组成员级（成员 id 列表
     * 也是 MutableList，浅拷贝仍会把发布态暴露给读侧迭代）。
     */
    fun workingCopy(): ProviderConfig {
        val live = current
        return live.copy(
            instances = live.instances.toMutableList(),
            modelEntries = live.modelEntries.toMutableList(),
            modelGroups = live.modelGroups
                .map { group -> group.copy(memberEntryIds = group.memberEntryIds.toMutableList()) }
                .toMutableList(),
            agentLoopModelEntryIds = live.agentLoopModelEntryIds.toMutableList(),
            agentLoopGroupIds = live.agentLoopGroupIds.toMutableList(),
            imageGenerationGroupIds = live.imageGenerationGroupIds.toMutableList(),
            imageGenerationProviderInstanceIds = live.imageGenerationProviderInstanceIds.toMutableList(),
        )
    }

    private fun sha256Hex(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val KEY_LAST_USED_ENTRY = "lastUsedModelEntryId"
    }
}

/** 发布态防御性拷贝：所有内部列表换新实例 + revision 前进。 */
private fun ProviderConfig.defensivelyCopied(revision: Long): ProviderConfig = copy(
    instances = instances.toMutableList(),
    modelEntries = modelEntries.toMutableList(),
    modelGroups = modelGroups.toMutableList(),
    agentLoopModelEntryIds = agentLoopModelEntryIds.toMutableList(),
    agentLoopGroupIds = agentLoopGroupIds.toMutableList(),
    imageGenerationGroupIds = imageGenerationGroupIds.toMutableList(),
    imageGenerationProviderInstanceIds = imageGenerationProviderInstanceIds.toMutableList(),
    revision = revision,
)
