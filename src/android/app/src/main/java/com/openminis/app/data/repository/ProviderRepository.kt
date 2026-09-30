package com.openminis.app.data.repository


import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import novex.android.data.provider.ProviderStoreDao
import novex.android.data.provider.ProviderMetaKeys
import novex.android.data.provider.ProviderRowsSnapshot
import novex.android.data.provider.ThinkingRuleRow
import novex.android.thinking.ThinkingContract
import novex.android.thinking.ThinkingContractResolver
import novex.android.data.NovexProviderDatabase
import novex.android.data.provider.entryCompositeId
import novex.android.data.provider.toConfig
import novex.android.data.provider.toRows
import com.openminis.app.data.normalizeModelGroupOrder
import com.openminis.app.data.removeModelGroupAndBindings
import novex.android.data.model.ImageEndpointMode
import novex.android.data.model.LLMModel
import novex.android.data.model.ModelEntry
import novex.android.data.model.ModelOverrides
import novex.android.data.model.ModelGroup
import novex.android.data.model.ProviderConfig
import novex.android.data.model.ProviderCredential
import novex.android.data.model.ProviderInstance
import novex.android.data.model.ProviderType
import novex.android.data.model.RoutingStrategy
import novex.android.data.model.hasAudioInput
import novex.android.data.model.hasAudioOutput
import novex.android.data.model.hasImageInput
import com.openminis.app.provider.ModelReleaseIndex
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.ModelsCatalogApi
import com.openminis.app.provider.openrouter.OpenRouterModelsApi
import com.openminis.app.tools.migrateLegacyImageGenerationConfig
import com.openminis.app.tools.resolveImageGenerationEntries
import com.openminis.app.tools.resolveOrdinaryAgentLoopEntries
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

// Modality bit layout — must match src/ios/Providers/LLMTypes.swift
// ModelModality OptionSet rawValue exactly. Used by export/import to
// transmit modality info as a single Int that iOS can decode.
private const val MODALITY_BIT_TEXT_IN = 1 shl 0
private const val MODALITY_BIT_TEXT_OUT = 1 shl 1
private const val MODALITY_BIT_IMG_IN = 1 shl 2
private const val MODALITY_BIT_PDF_IN = 1 shl 3
private const val MODALITY_BIT_AUD_IN = 1 shl 4
private const val MODALITY_BIT_VID_IN = 1 shl 5
private const val MODALITY_BIT_IMG_OUT = 1 shl 6
private const val MODALITY_BIT_AUD_OUT = 1 shl 7
private const val MODALITY_BIT_VID_OUT = 1 shl 8

class ProviderRepository(private val context: Context) {

    // [T-android-thinking-level-arch] coerceInputValues makes kotlinx.serialization
    // fall back to a property's DEFAULT when it can't decode the wire value —
    // crucially, this covers an unknown ENUM value (e.g. a ThinkingLevel a newer
    // build wrote, like "MAX"/"ULTRA", read by an older build). Without it the
    // JSON-mirror decode throws SerializationException on that enum, and since
    // that mirror is the fallback for a failed Room DB load, the whole config
    // would come back empty (all providers/groups vanish from the UI).
    // `ignoreUnknownKeys` only skips unknown object keys, NOT unknown enum
    // values — coerceInputValues is the piece that handles those. Fields carrying
    // ThinkingLevel are nullable with a null default, so an unknown value coerces
    // cleanly to null.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("provider_config", Context.MODE_PRIVATE)

    // [T-android-provider-room-store] Per-row provider config DB. Lives in
    // its own provider.db file so a downgrade to a build that doesn't know
    // about provider tables can't crash the main minis.db open. The legacy
    // SharedPreferences JSON mirror under "provider_config / config" is
    // still written on every save so older builds keep reading current
    // config — losing nothing on downgrade. See [loadConfig] for the
    // downgrade-and-back-up reconciliation logic.
    // Opening provider.db is not a first-frame dependency. The initial config
    // loader already runs on Dispatchers.IO, so defer Room construction until
    // that loader first touches the DAO instead of blocking Application.onCreate.
    private val providerDao: ProviderStoreDao by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NovexProviderDatabase.getInstance(context).providerStoreDao()
    }

    companion object {
        /**
         * Per-instance model-cache TTL. Matches iOS's daily calendar-day
         * refresh window (24h rolling here — simpler than calendar-day math
         * and the behavioral difference at midnight is negligible). Used by
         * [triggerBackgroundRefreshIfStale] to decide whether a UI-triggered
         * stale-while-revalidate refresh should fire.
         */
        private const val MODEL_CACHE_TTL_MS = 24 * 60 * 60 * 1000L

        /**
         * （历史）前尘内置预设固定实例 id。预设已退役，常量随删——
         * 种子幂等（升级不重复建）；用户删除后靠 prefs 标记不再复活。
         */
        // [前尘删除 2026-09-30] 内置「前尘 API」预设整体退役（用户裁决）——
        // 常量/种子/思考席位/主机嗅探一并删除；已种到用户库里的实例行保留为
        // 普通自定义实例（key_help_url 与 auto_responses_fallback 是通用机制
        // 与冻结列，保留）。

        /** Per-instance `lastFetchAt` pref key. */
        private fun lastFetchKey(instanceId: String) = "modelsLastFetchAt_$instanceId"

        /** [T-newchat-default-model-fallback-android] Global last-used model entry id. */
        private const val KEY_LAST_USED_ENTRY = "lastUsedModelEntryId"

        // [P3.3 裁军] normalizedShadowKey（shadow voice 折叠去重的 base URL
        // 归一）随 Shadow Voice 区退役删除。
    }

    private fun markInstanceFetched(instanceId: String) {
        prefs.edit().putLong(lastFetchKey(instanceId), System.currentTimeMillis()).apply()
    }

    /** Whether the cached model list for [instanceId] is older than the TTL. */
    private fun isInstanceStale(instanceId: String): Boolean {
        val last = prefs.getLong(lastFetchKey(instanceId), 0L)
        return last == 0L || (System.currentTimeMillis() - last) > MODEL_CACHE_TTL_MS
    }

    /**
     * Clear the stored `lastFetchAt` for [instanceId] so the next
     * [triggerBackgroundRefreshIfStale] / [refreshAllModelsIfNeeded] call
     * refreshes it immediately. Called when instance config changes in ways
     * that could alter the set of available models (e.g. base URL rewrite,
     * credential swap). Mirrors iOS's implicit invalidation on
     * `updateInstance` / `removeInstance`.
     */
    fun invalidateModelCache(instanceId: String) {
        prefs.edit().remove(lastFetchKey(instanceId)).apply()
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        // T-android-keystore-aead-fail: route through the self-healing
        // factory so a corrupted master key on Samsung One UI / Android
        // 16 doesn't crash the app at first read.
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, "provider_secrets")
    }

    // [T-android-startup-config-stall] #753: loadConfig() does a synchronous
    // SharedPreferences read (~3s first-touch as the XML is parsed) + a
    // Json.decodeFromString<ProviderConfig> (~8s on a large config). It used to
    // run INLINE in this field initializer, i.e. inside ProviderRepository's
    // constructor, which MinisApp.onCreate() invokes on the MAIN THREAD — so
    // cold start hung >11s. Now we start with an empty placeholder (instant, no
    // I/O) and load the real config on Dispatchers.IO, emitting it when ready.
    // Reactive consumers (config.collectAsState) update automatically on emit;
    // action-time `config.value` reads tolerate the brief empty window (a send
    // before load just has no model entry, exactly like a fresh install).
    private val _config = MutableStateFlow(ProviderConfig())
    val config: StateFlow<ProviderConfig> = _config.asStateFlow()

    /**
     * [T-android-startup-config-stall] False until the persisted config has
     * been read off-thread and emitted. First-screen code that branches on
     * `instances.isEmpty()` (the onboarding gate) reads this to avoid flashing
     * a "no providers" / onboarding state for an existing user during the load
     * window. Distinguishes "empty because not loaded yet" from "empty because
     * the user genuinely has no providers".
     */
    private val _configLoaded = MutableStateFlow(false)
    val configLoaded: StateFlow<Boolean> = _configLoaded.asStateFlow()

    /** Internal scope for the one-shot async config load. */
    private val loadScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    /**
     * Completes once the initial off-thread load has emitted (or determined
     * there's nothing persisted). Lets startup consumers that genuinely need
     * the config (e.g. the daily model refresh) wait instead of racing the
     * empty placeholder.
     */
    private val configLoadComplete = kotlinx.coroutines.CompletableDeferred<Unit>()

    suspend fun awaitConfigLoaded() = configLoadComplete.await()

    init {
        loadScope.launch {
            // [T-android-provider-empty-load-wipe] loadConfig() THROWS when the
            // store is unreadable (rather than returning an empty config that a
            // later save would write over real data). Contain it here: an
            // uncaught throw in this coroutine reaches the default uncaught
            // handler and crashes cold start — in a LOOP, for as long as the DB
            // stays unreadable, which is precisely the post-crash state the
            // refusal exists for. Swallowing it leaves _configLoaded false, so
            // ensureConfigLoaded retries on the next access.
            try {
                val loaded = loadConfig()
                synchronized(configLock) {
                    // Only adopt the disk config if nothing has written in the
                    // meantime. A write during the load window (rare — writes come
                    // from user/refresh actions that themselves need config) flips
                    // _configLoaded true and takes precedence; we must not clobber
                    // it with the stale on-disk snapshot.
                    if (!_configLoaded.value) {
                        // [T-opencode-sunset]（净眼 P1）正常冷启动走的是本
                        // 异步装载分支，ensureConfigLoaded 会被 _configLoaded
                        // 短路——迁移必须挂在这里才必然执行。幂等、失败不
                        // 阻断装载（内存态已生效，下次启动重试落盘）。
                        if (applyOpenCodeSunset(loaded)) {
                            runCatching { saveConfig(loaded) }
                        }
                        _config.value = loaded
                        _configLoaded.value = true
                        android.util.Log.i(
                            "ProviderRepo",
                            "[ProviderStore] async loader adopted ${loaded.instances.size} instances",
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(
                    "ProviderRepo",
                    "[ProviderStore] initial load failed; leaving config unloaded for retry: ${e.message}",
                )
            }
            // Completed in every path, including the failure one: awaitConfigLoaded()
            // callers (refreshAllModelsIfNeeded) would otherwise suspend forever.
            if (!configLoadComplete.isCompleted) configLoadComplete.complete(Unit)
            // [P3.3 裁军] 语音厂商模板种子对账（ensureVoiceTemplateModels）随
            // 语音全家退役删除；[前尘删除 2026-09-30] 内置前尘 API 预设种子
            // 亦整体退役（用户裁决：产品不携带任何中转商特例）。
        }
    }

    /**
     * Lock guarding all mutate-and-save sequences against concurrent writers
     * (notably the `for instance in enabled { scope.launch { autoRefreshModels(...) } }`
     * fan-out in [refreshAllModelsIfNeeded] / [triggerBackgroundRefreshIfStale]).
     *
     * Without this lock, two parallel `replaceEntries(instanceA, …)` and
     * `replaceEntries(instanceB, …)` calls share the same `_config.value`
     * reference (its inner ArrayLists are the same objects across reads),
     * so one's `removeAll` / `addAll` runs while the other's `saveConfig`
     * is mid-`Json.encodeToString` — that's the
     * `ConcurrentModificationException → ArrayList.next` crash captured on
     * Pixel 6 / 4a.
     */
    private val configLock = Any()

    private fun loadConfig(): ProviderConfig =
        novex.android.data.model.NovexDeepSeekModelMetadata.repairCatalog(runBlocking { loadConfigSuspending() })

    /**
     * [T-android-provider-room-store] DB-first load with three-way
     * reconciliation between provider.db and the legacy JSON mirror:
     *
     *   - DB has rows AND meta.json_sync_hash matches the live mirror's
     *     hash → DB is in sync with what we last wrote. Use DB.
     *   - DB has rows but the hash mismatches → an older app build was
     *     installed at some point, wrote through the JSON path, and
     *     bypassed our DB. The JSON is fresher. Re-import JSON → rewrite
     *     DB → resync hash.
     *   - DB is empty but JSON exists → first launch on a build that
     *     knows about the DB. One-shot import from JSON → DB.
     *   - Both empty → empty config (fresh install).
     *
     * The JSON mirror is the durable downgrade safety net: we keep
     * writing it on every save so the old build always sees current
     * config; if the user round-trips through an old build, the
     * hash check above re-syncs DB to whatever JSON looks like now.
     */
    private suspend fun loadConfigSuspending(): ProviderConfig {
        val rawJson = prefs.getString("config", null)
        // [T-android-provider-empty-load-wipe] Distinguish "the DB says zero"
        // from "we could not ask the DB". Swallowing the exception as 0 made a
        // transient DAO failure (locked/mid-write file after a crash) look
        // exactly like a fresh install: the loader fell through to an empty
        // ProviderConfig(), and the next mutation's persistToDbAndMirror wrote
        // that emptiness over a fully populated store — wiping every provider.
        // Observed on Pixel 6 after a ConcurrentModificationException crash
        // left provider.db mid-write: 18 instances became 5.
        var daoReadFailed = false
        val instanceCount = try {
            providerDao.instanceCount()
        } catch (e: Exception) {
            android.util.Log.w("ProviderRepo", "[ProviderStore] DAO instanceCount failed: ${e.message}")
            daoReadFailed = true
            0
        }
        android.util.Log.i(
            "ProviderRepo",
            "[ProviderStore] load: dbInstances=$instanceCount daoFailed=$daoReadFailed " +
                "mirrorBytes=${rawJson?.length ?: -1}",
        )

        val (dbConfig, dbHashStored) = if (instanceCount > 0) {
            try {
                val snapshot = ProviderRowsSnapshot(
                    instanceRows = providerDao.instanceRows(),
                    modelRows = providerDao.modelRows(),
                    groupRows = providerDao.groupRows(),
                    loopRows = providerDao.agentLoopRows(),
                    metaRows = providerDao.metaRows(),
                )
                val cfg = snapshot.toConfig(json)
                val storedHash = snapshot.metaRows.firstOrNull {
                    it.key == ProviderMetaKeys.JSON_SYNC_HASH
                }?.value
                cfg to storedHash
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "[ProviderStore] DB load failed, falling back to JSON: ${e.message}")
                daoReadFailed = true
                null to null
            }
        } else {
            null to null
        }

        if (dbConfig != null) {
            val liveHash = rawJson?.let(::hashJsonMirror)
            if (liveHash == dbHashStored) {
                return dbConfig
            }
            // Hash mismatch: JSON has been written by an older build during
            // a downgrade window. Re-import JSON → reseed DB so DB catches
            // up to the user's actual current config.
            android.util.Log.i(
                "ProviderRepo",
                "[ProviderStore] hash mismatch (stored=${dbHashStored?.take(8)} live=${liveHash?.take(8)}) — re-importing JSON mirror",
            )
        }

        if (rawJson != null) {
            val parsed = try {
                json.decodeFromString<ProviderConfig>(rawJson)
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "[ProviderStore] JSON decode failed: ${e.message}")
                null
            }
            if (parsed != null) {
                val mirrored = try {
                    persistToDbAndMirror(parsed)
                } catch (e: Exception) {
                    android.util.Log.w("ProviderRepo", "[ProviderStore] JSON→DB import failed: ${e.message}")
                    parsed
                }
                // Migrate the per-user lastUsedEntryId SharedPreferences key
                // from the legacy random-uuid entry id form to the new
                // composite "{instanceId}/{modelId}" shape, using the
                // pre-canonicalization `parsed` entries as the uuid→composite
                // dictionary. Without this, the user's last-picked model on
                // the upgrade-first-launch isn't recognized by
                // lastUsedVisibleEntry() and the next new chat falls through
                // to the newest-provider fallback — i.e. it looks like the
                // upgrade "forgot" the user's recent selection.
                val legacyLastUsed = prefs.getString(KEY_LAST_USED_ENTRY, null)
                if (legacyLastUsed != null && !legacyLastUsed.contains('/')) {
                    val rewritten = parsed.modelEntries
                        .firstOrNull { it.uuid == legacyLastUsed }
                        ?.let { entryCompositeId(it.providerInstanceId, it.baseModel.id) }
                    if (rewritten != null) {
                        prefs.edit().putString(KEY_LAST_USED_ENTRY, rewritten).apply()
                        android.util.Log.i(
                            "ProviderRepo",
                            "[ProviderStore] lastUsedEntryId rewritten ${legacyLastUsed.take(8)} → $rewritten",
                        )
                    }
                }
                android.util.Log.i(
                    "ProviderRepo",
                    "[ProviderStore] migrated/synced ${mirrored.instances.size} instances " +
                        "${mirrored.modelEntries.size} entries from JSON → Room",
                )
                return mirrored
            }
        }

        // [T-android-provider-room-store] Last-resort fallback. If DB had
        // rows but the live JSON mirror is unparseable (disk corruption,
        // interrupted write, etc.) AND we couldn't re-import, KEEP THE DB
        // — losing user config is worse than running with a stale mirror.
        // The next successful save will rewrite the mirror and resync the
        // hash. Returning ProviderConfig() here would let the very next
        // mutator's persistToDbAndMirror overwrite the populated DB with
        // an empty config, silently wiping the user's providers.
        if (dbConfig != null) {
            android.util.Log.w(
                "ProviderRepo",
                "[ProviderStore] mirror unreadable + re-import failed; keeping " +
                    "${dbConfig.instances.size} DB instances as authoritative",
            )
            return dbConfig
        }

        // [T-android-provider-empty-load-wipe] Reaching here means BOTH stores
        // came back empty. That is legitimate on a fresh install — but if the
        // DB read actually FAILED (rather than honestly reporting zero rows),
        // an empty config is a lie we are about to persist over real data.
        // Refuse: throwing keeps _configLoaded false, so ensureConfigLoaded
        // retries on the next access instead of caching the empty value, and
        // no mutation can run against a phantom-empty config.
        if (daoReadFailed) {
            android.util.Log.e(
                "ProviderRepo",
                "[ProviderStore] REFUSING empty config — DB read failed and JSON mirror " +
                    "unusable; not overwriting a possibly-populated store",
            )
            throw IllegalStateException(
                "Provider store unreadable (DB read failed, JSON mirror unusable) — " +
                    "refusing to load an empty config that would overwrite existing providers",
            )
        }
        return ProviderConfig()
    }

    /**
     * Stable hash of the JSON mirror string, used as the in-DB synced-state
     * marker. SHA-256 hex so collisions are negligible. Returns null only
     * if [str] is null (caller normalizes).
     */
    private fun hashJsonMirror(str: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(str.toByteArray())
        return buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xFF
                append(Character.forDigit(v shr 4, 16))
                append(Character.forDigit(v and 0xF, 16))
            }
        }
    }

    /**
     * Atomically persist [config] to (DB) + (legacy JSON mirror), with
     * the meta.json_sync_hash kept in lockstep with the mirror we just
     * wrote. Returns the canonicalized config (entry uuids rewritten to
     * the composite "{instanceId}/{modelId}" shape via the snapshot
     * round-trip). Callers should treat the return value as the new
     * authoritative state — using the in-memory pre-call object would
     * leak the legacy uuid form into [_config.value].
     */
    private suspend fun persistToDbAndMirror(config: ProviderConfig): ProviderConfig {
        // First serialize without the hash so the meta row reflects the
        // exact string we put into prefs (the hash sees the mirror that
        // older builds will read, not a hash-of-itself).
        val mirrorStr = json.encodeToString(ProviderConfig.serializer(), config)
        val mirrorHash = hashJsonMirror(mirrorStr)
        val snapshot = config.toRows(json, jsonSyncHash = mirrorHash)
        providerDao.overwriteConfigTables(
            instances = snapshot.instanceRows,
            models = snapshot.modelRows,
            groups = snapshot.groupRows,
            loopTargets = snapshot.loopRows,
            meta = snapshot.metaRows,
        )
        // commit() not apply(): the json_sync_hash we just stored to DB is
        // a hash of THIS mirror string. If apply() queues the disk write
        // and the process dies before it flushes, the next launch sees
        // DB(hash=new) + JSON-on-disk(content=old) → the hash-mismatch
        // path interprets it as "old build wrote during downgrade" and
        // re-imports the stale JSON, blowing away the write that the DB
        // already persisted synchronously. commit() blocks the writer
        // (~5–30ms) but guarantees DB and JSON land together.
        //
        // commit() returns false (no exception) on disk-full / permission
        // failure / corrupted prefs XML. We log so the situation is
        // observable; the DB already holds the new state authoritatively,
        // and a subsequent successful save will resync the mirror + hash.
        // The downside if the next save never comes: a hash mismatch on
        // next cold-start would try to re-import the stale mirror — but
        // the dbConfig-fallback at the end of loadConfigSuspending keeps
        // the DB rows when re-import fails or is rejected, so the user's
        // data is not lost.
        val mirrorWritten = prefs.edit().putString("config", mirrorStr).commit()
        if (!mirrorWritten) {
            android.util.Log.w(
                "ProviderRepo",
                "[ProviderStore] mirror commit() returned false — DB updated " +
                    "but JSON write rejected (disk full? prefs corruption?); " +
                    "DB remains authoritative",
            )
        }
        // Return canonicalized form so the caller's _config.value reflects
        // entry uuids in composite-key shape from this write forward.
        return snapshot.toConfig(json)
    }

    /**
     * [T-android-startup-config-stall] Guard for read-modify-write mutators.
     * If the async load hasn't applied yet, load synchronously NOW (on the
     * caller's thread) before the mutator reads `_config.value`. Without this a
     * write that lands during the startup load window would read the empty
     * placeholder, mutate it, and persist — WIPING the user's real config.
     * Idempotent and cheap once loaded (just a volatile read). Synchronized on
     * [configLock] so it can't race the async loader's emit.
     */
    // internal (was private): the debug server's read-only provider.* handlers
    // read `config.value` directly and must be able to force the lazy load —
    // on a cold process they otherwise report an EMPTY config, which reads as
    // "all your providers are gone" rather than "not loaded yet".
    internal fun ensureConfigLoaded() {
        if (_configLoaded.value) return
        synchronized(configLock) {
            if (_configLoaded.value) return
            // [T-android-provider-empty-load-wipe] loadConfig() throws when the
            // store is unreadable. Do NOT let that escape into the caller: this
            // runs from UI handlers (every read-modify-write mutator calls it),
            // and a throw there crashes whichever gesture triggered it. Leaving
            // _configLoaded false means the next access retries, and callers
            // see the empty placeholder — which is safe, because the mutators'
            // saves cannot overwrite a store the loader refused to read.
            val loaded = try {
                loadConfig()
            } catch (e: Exception) {
                android.util.Log.e(
                    "ProviderRepo",
                    "[ProviderStore] ensureConfigLoaded failed; staying unloaded for retry: ${e.message}",
                )
                return
            }
            // [T-opencode-sunset] One-shot idempotent sunset migration rides
            // the first load — every config-touching path funnels through
            // here, so no UI call site is needed. Persist failure is
            // non-fatal: the in-memory disable already holds for this run.
            if (applyOpenCodeSunset(loaded)) {
                runCatching { saveConfig(loaded) }
            }
            _config.value = loaded
            _configLoaded.value = true
        }
        // [T-android-thinking-rules-phase2] Warm the resolver's custom-rule cache once
        // config is available, so the (sync) request builder can read user rules.
        // [P3.3 裁军] 自定义思考规则缓存装载随 CUSTOM 路径退役删除
        // （resolver 只跑内置座次表）。
        if (!configLoadComplete.isCompleted) configLoadComplete.complete(Unit)
    }

    private fun saveConfig(config: ProviderConfig) {
        // [T-android-provider-room-store] Double-write: DB + legacy JSON
        // mirror, both produced inside the same configLock window. The
        // mirror keeps older app builds able to read current config on
        // downgrade; the DB is the new authoritative store on this build.
        //
        // Serialize + persist + emit under [configLock] so we never serialize
        // a list that another writer is mutating. The fresh `.copy(…toMutableList())`
        // wrapper alone is not enough: data-class structural equals walks the
        // inner Lists and `prev` (already mutated in place by replaceEntries /
        // addEntry / removeEntry) compares equal to `next` → MutableStateFlow
        // suppresses the emission. T273 bumps `revision` so equals always
        // returns false and 18+ collectAsState callers see the new value.
        synchronized(configLock) {
            // [T-android-provider-empty-load-wipe] No "block empty saves" guard
            // here on purpose: mutators pass the SAME object as _config.value
            // and mutate it in place, so at this point an empty `config` and an
            // empty `_config.value` are the same list — a legitimate
            // "user deleted their last provider" is indistinguishable from a
            // phantom-empty load. The wipe is prevented at the source instead
            // (loadConfigSuspending refuses to return a blank config when the
            // DB read failed rather than honestly reporting zero rows).
            // persistToDbAndMirror returns the canonicalized config (entries'
            // uuid in composite "{instanceId}/{modelId}" form). Emit that so
            // subsequent in-memory reads — which compare entry.id by string
            // equality (e.g. group.memberEntryIds.contains(it.id)) — use one
            // consistent id shape rather than mixing legacy random uuids and
            // composite keys.
            //
            // Catch persistence failures so callers stay fire-and-forget
            // (matches the legacy `apply()` contract — pre-Room saveConfig
            // never threw). DB write fails are rare in practice (disk full,
            // SQLite corruption, transaction deadlock) but uncaught they'd
            // crash whichever UI handler triggered the mutation. Still
            // emit the in-memory state so the UI reflects the user's
            // intent even when the disk write didn't land; the next
            // successful save resyncs everything.
            val normalized = config
            val canonical = try {
                runBlocking { persistToDbAndMirror(normalized) }
            } catch (e: Exception) {
                android.util.Log.e(
                    "ProviderRepo",
                    "[ProviderStore] saveConfig persistence failed; emitting in-memory only: ${e.message}",
                    e,
                )
                normalized
            }
            _config.value = canonical.copy(
                instances = canonical.instances.toMutableList(),
                modelEntries = canonical.modelEntries.toMutableList(),
                modelGroups = canonical.modelGroups.toMutableList(),
                agentLoopModelEntryIds = canonical.agentLoopModelEntryIds.toMutableList(),
                agentLoopGroupIds = canonical.agentLoopGroupIds.toMutableList(),
                imageGenerationGroupIds = canonical.imageGenerationGroupIds.toMutableList(),
                imageGenerationProviderInstanceIds = canonical.imageGenerationProviderInstanceIds.toMutableList(),
                revision = ProviderConfig.nextRevision(),
            )
        }
    }

    // [T-android-provider-mutator-lock] Snapshot, not the live list. The
    // declared type is List, but the backing object is the MutableList that
    // mutators append to in place — so a caller iterating this (e.g.
    // ProvidersCollection.childIds) could take a ConcurrentModificationException
    // from a concurrent addInstance. Copying under the lock costs a few objects
    // and removes the hazard for every current and future caller.
    val instances: List<ProviderInstance>
        get() = synchronized(configLock) { _config.value.instances.toList() }

    /**
     * [T-android-provider-emitted-list-cow] A PRIVATE working copy of the
     * current config for a mutator to modify.
     *
     * The lock alone does not make mutation safe, because Compose readers do
     * not take it: `_config.value` is the object collectors are iterating, and
     * mutating its lists in place throws ConcurrentModificationException *in
     * the reader's* frame — reproduced on Pixel 6 as a crash inside
     * UnifiedModelPickerSheet's LazyColumn while three imports ran
     * concurrently:
     *
     *   ArrayList$Itr.checkForComodification → UnifiedModelPickerSheet
     *     → LazyListIntervalContent.<init> → Snapshot.observe
     *
     * saveConfig already EMITS defensive copies; the gap was that the next
     * mutator read that emitted object back and mutated it. Copy-on-write here
     * closes the loop: a mutator never touches a published object, so whatever
     * a reader is iterating stays frozen for the life of that iteration.
     */
    private fun workingCopy(): ProviderConfig {
        val live = _config.value
        return live.copy(
            instances = live.instances.toMutableList(),
            modelEntries = live.modelEntries.toMutableList(),
            // Deep-copy the groups too: ModelGroup.memberEntryIds is itself a
            // MutableList that removeEntry/removeGroup edit in place, so a
            // shallow list copy would still expose the published members to a
            // reader mid-iteration.
            modelGroups = live.modelGroups
                .map { g -> g.copy(memberEntryIds = g.memberEntryIds.toMutableList()) }
                .toMutableList(),
            agentLoopModelEntryIds = live.agentLoopModelEntryIds.toMutableList(),
            agentLoopGroupIds = live.agentLoopGroupIds.toMutableList(),
            imageGenerationGroupIds = live.imageGenerationGroupIds.toMutableList(),
            imageGenerationProviderInstanceIds = live.imageGenerationProviderInstanceIds.toMutableList(),
        )
    }

    fun addInstance(instance: ProviderInstance): Unit = synchronized(configLock) {
                ensureConfigLoaded()
        val config = workingCopy()
        config.instances.add(instance)
        // Seed built-in model entries ONLY when the seed is appropriate for this
        // instance. Mirrors iOS ProviderConfigStore.addInstance:
        //   - OAuth instances always get seeded (their /v1/models often requires
        //     a manual token or isn't reachable, so the static list is the
        //     baseline UX).
        //   - API-key instances on a third-party OpenAI-compatible base URL
        //     (e.g. xAI Grok https://api.x.ai, vLLM, Ollama, LiteLLM, DeepSeek
        //     via OpenAI shim) MUST NOT inherit `LLMModel.allOpenAI` — that's
        //     where the "Refresh on Grok returns GPT-5.5/5.3-codex" bug came
        //     from. For these, leave entries empty and let refreshModels()
        //     populate from the upstream /v1/models call.
        //   - API-key instances on an official endpoint (no customBaseURL, or
        //     a customBase that points at the canonical host) keep the seed so
        //     UI isn't blank during the first refresh round-trip.
        val shouldSeed = instance.credentialType == ProviderCredential.oauth ||
            !isThirdPartyOpenAICompat(instance)
        if (shouldSeed) {
            val entries = instance.providerType.builtInModels.map { model ->
                ModelEntry(providerInstanceId = instance.id, baseModel = model)
            }
            config.modelEntries.addAll(entries)
        } else {
            android.util.Log.i(
                "ProviderRepo",
                "[ModelList] addInstance: skip built-in seed for third-party OpenAI-compat base " +
                    "(label=${instance.label} base=${instance.effectiveBaseURL}) — " +
                    "models will populate from upstream /v1/models on refresh",
            )
        }
        // [P3.3 裁军] 语音厂商模板种子（VoiceVendorTemplate.mockEntries）随
        // 语音全家退役删除。
        saveConfig(config)
        // Stale from the start so the next background sweep (or an explicit
        // triggerBackgroundRefreshIfStale call) will fetch it.
        invalidateModelCache(instance.id)
    }


    // [P3.3 裁军] ensureVoiceTemplateModels（语音模板对账）随语音全家退役删除。


    /**
     * Whether [instance] points at a third-party OpenAI-compatible host
     * (xAI Grok, vLLM, Ollama, LiteLLM, DeepSeek via OpenAI shim, etc.).
     * For these instances we must never substitute `LLMModel.allOpenAI` as a
     * fallback / seed — those are GPT-only IDs that don't exist upstream.
     * Mirrors iOS `ProviderConfigStore.isThirdPartyOpenAICompat`.
     */
    private fun isThirdPartyOpenAICompat(instance: ProviderInstance): Boolean {
        if (instance.providerType != ProviderType.openAI) return false
        val custom = instance.customBaseURL?.lowercase() ?: return false
        val officialHosts = listOf("api.openai.com", "chatgpt.com")
        return officialHosts.none { custom.contains(it) }
    }

    fun updateInstance(instance: ProviderInstance): Unit = synchronized(configLock) {
                ensureConfigLoaded()
        val config = workingCopy()
        val idx = config.instances.indexOfFirst { it.id == instance.id }
        if (idx >= 0) {
            val prior = config.instances[idx]
            // [T-android-image-endpoint-mode] If the base URL or v1-suffix
            // changed, the previously probed image endpoint may not exist on the
            // new upstream — drop the cached resolution so auto mode re-probes.
            // Mirrors iOS ProviderConfigStore.updateInstance. Only touch it when
            // the caller didn't already set it (e.g. the UI clears it itself when
            // the user forces a non-auto mode).
            if ((prior.customBaseURL != instance.customBaseURL ||
                    prior.appendV1Suffix != instance.appendV1Suffix) &&
                instance.imageEndpointResolved != null
            ) {
                instance.imageEndpointResolved = null
            }
            config.instances[idx] = instance
            saveConfig(config)
            // Any change that could move the model list (base URL, credential
            // swap, enabled flag, API format) invalidates the cache. Cheap to
            // over-invalidate.
            if (prior.effectiveBaseURL != instance.effectiveBaseURL ||
                prior.credentialType != instance.credentialType ||
                prior.isEnabled != instance.isEnabled ||
                prior.useResponsesAPI != instance.useResponsesAPI
            ) {
                invalidateModelCache(instance.id)
            }
        }
    }

    fun removeInstance(instanceId: String): Unit = synchronized(configLock) {
                ensureConfigLoaded()
        invalidateModelCache(instanceId)
        val config = workingCopy()
        val removedEntryIds = config.modelEntries
            .filter { it.providerInstanceId == instanceId }
            .map { it.id }
            .toSet()
        config.instances.removeAll { it.id == instanceId }
        config.imageGenerationProviderInstanceIds.removeAll { it == instanceId }
        config.modelEntries.removeAll { it.providerInstanceId == instanceId }

        if (removedEntryIds.isNotEmpty()) {
            for (group in config.modelGroups) {
                group.memberEntryIds.removeAll { it in removedEntryIds }
            }
            config.agentLoopModelEntryIds.removeAll { it in removedEntryIds }
        }
        val emptyGroupIds = config.modelGroups.filter { it.memberEntryIds.isEmpty() }.map { it.id }.toSet()
        if (emptyGroupIds.isNotEmpty()) {
            config.modelGroups.removeAll { it.id in emptyGroupIds }
            config.imageGenerationGroupIds.removeAll { it in emptyGroupIds }
            if (config.defaultPrimaryGroupId in emptyGroupIds) config.defaultPrimaryGroupId = null
            if (config.defaultSubGroupId in emptyGroupIds) config.defaultSubGroupId = null
        }

        saveConfig(config)
        deleteApiKey(instanceId)
        // [P3.3 裁军] 删实例时的自定义思考规则级联清理（dropRulesFor +
        // setCustomRules）随 CUSTOM 路径退役删除；历史规则行留在
        // provider_thinking_rules 表中（schema 冻结），不再被读取。
    }

    /**
     * [T-android-image-endpoint-mode] Persist the endpoint that last worked for
     * `auto`-mode image generation on [instanceId]. Called after a successful
     * /images/generations call (cache `imagesGenerations`) or after a
     * route-missing fallback (cache `chatCompletions`). No-op when unchanged so
     * we don't churn the config / iCloud sync on every image call. Does not
     * invalidate the model cache — the endpoint choice never moves the model
     * list.
     */
    // [T-android-provider-mutator-lock] `ProviderInstance` has mutable `var`
    // fields, so `workingCopy()`'s list copy is not enough on its own — the
    // instance objects inside it are still the published ones. Replace the
    // element with a `.copy()` rather than writing through to the shared
    // object. Called from an offload worker thread during image generation,
    // so the unsynchronized write had no
    // happens-before with main-thread readers.
    fun setImageEndpointResolved(instanceId: String, endpoint: ImageEndpointMode): Unit =
        synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            val idx = config.instances.indexOfFirst { it.id == instanceId }
            if (idx < 0) return@synchronized
            if (config.instances[idx].imageEndpointResolved == endpoint) return@synchronized
            config.instances[idx] = config.instances[idx].copy(imageEndpointResolved = endpoint)
            saveConfig(config)
        }

    fun instance(id: String): ProviderInstance? =
        _config.value.instances.find { it.id == id }

    fun enabledInstances(providerType: ProviderType): List<ProviderInstance> =
        _config.value.instances.filter { it.providerType == providerType && it.isEnabled }

    fun entriesFor(instanceId: String): List<ModelEntry> =
        _config.value.modelEntries
            .filter { it.providerInstanceId == instanceId }
            .sortedWith(releaseRankOrder)

    fun visibleEntries(instanceId: String): List<ModelEntry> =
        _config.value.modelEntries
            .filter { it.providerInstanceId == instanceId && !it.isHidden }
            .sortedWith(releaseRankOrder)

    fun allVisibleEntries(): List<ModelEntry> =
        _config.value.let { config ->
            val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
            config.modelEntries
                .filter { it.providerInstanceId in enabledIds && !it.isHidden }
                .sortedWith(releaseRankOrder)
        }

    /**
     * [T-model-release-ranking] Newest / most capable model first, so a picker
     * never opens on a stale (or, as in OpenMinis#83, an uncallable) model.
     * These lists previously came back in raw config order, which is insertion
     * order from the provider's /models response — effectively arbitrary.
     * Falls back to the model id so the ordering is total and stable when two
     * entries rank identically; otherwise the list could visibly reshuffle
     * between reads. Mirrors iOS `ProviderConfigStore.releaseRankOrder`.
     */
    private val releaseRankOrder = Comparator<ModelEntry> { a, b ->
        val ra = ModelReleaseIndex.rank(
            a.baseModel.id, a.baseModel.displayName, a.baseModel.contextWindow
        )
        val rb = ModelReleaseIndex.rank(
            b.baseModel.id, b.baseModel.displayName, b.baseModel.contextWindow
        )
        val byRank = ModelReleaseIndex.comparator.compare(ra, rb)
        if (byRank != 0) byRank else a.baseModel.id.compareTo(b.baseModel.id)
    }

    // ── [T-newchat-default-model-fallback-android] last-used + newest-model ──

    /**
     * The model entry id the user last actively selected (model picker tap) or
     * sent a message with. Persisted globally (not per-session) so a brand-new
     * chat can fall back to "the model I was just using" when no default group
     * is configured. Null until the user has picked / used a model at least
     * once. Mirrors iOS #636 lastUsedModelEntryId.
     */
    var lastUsedEntryId: String?
        get() = prefs.getString(KEY_LAST_USED_ENTRY, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LAST_USED_ENTRY) else putString(KEY_LAST_USED_ENTRY, value)
            }.apply()
        }

    /**
     * Resolve [lastUsedEntryId] to a still-valid, visible, enabled-provider
     * entry — or null when the recorded id was deleted / hidden / its provider
     * disabled. Callers treat null as "fall through to the next tier".
     */
    fun lastUsedVisibleEntry(): ModelEntry? {
        val id = lastUsedEntryId ?: return null
        return allVisibleEntries().firstOrNull { it.id == id && novex.android.data.model.ChatModelSelection.eligible(it) }
    }

    /**
     * [T-newchat-default-model-fallback-android] Final-tier default for a new
     * chat: the newest text-output model from the newest-added enabled
     * provider. "Newest provider" = max [ProviderInstance.createdAt]; "newest
     * model" = last text-capable entry in add order (modelEntries is appended
     * in add order, so the instance's last matching entry is the most recently
     * added). Image/audio-only models are excluded — a fresh chat must default
     * to something that can produce a text reply.
     *
     * Walks providers newest→oldest so that if the newest provider somehow has
     * no text model (all image/audio), we still land on the newest text model
     * from the next provider rather than returning null.
     */
    fun newestProviderNewestTextEntry(): ModelEntry? {
        val config = _config.value
        val enabledProviders = config.instances
            .filter { it.isEnabled }
            .sortedByDescending { it.createdAt }
        for (instance in enabledProviders) {
            val textEntry = config.modelEntries
                .filter { it.providerInstanceId == instance.id && novex.android.data.model.ChatModelSelection.eligible(it) }
                .lastOrNull()
            if (textEntry != null) return textEntry
        }
        return null
    }

    /**
     * [T-disabled-provider-via-group-android] Members of [group] whose
     * provider instance is currently enabled. Used everywhere we previously
     * read `group.memberEntryIds` directly to resolve / display a model —
     * runtime selection must skip disabled providers, and the settings UI
     * uses this to surface "no available models" when every member sits
     * behind a disabled provider.
     *
     * Returns entries in the same order as [ModelGroup.memberEntryIds] so
     * the routing-strategy semantics (primary = first member) carry over.
     * Entries whose [ModelEntry.providerInstanceId] no longer maps to any
     * instance are also filtered — orphaned ids would otherwise pin the
     * group to a model that can't be instantiated.
     */
    fun enabledMemberEntries(group: ModelGroup): List<ModelEntry> {
        val config = _config.value
        val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
        return group.memberEntryIds.mapNotNull { entryId ->
            val entry = config.modelEntries.find { it.id == entryId }
            if (entry != null && entry.providerInstanceId in enabledIds) entry else null
        }
    }

    /** Convenience: first enabled member of [group] in declaration order. */
    fun firstEnabledMemberEntry(group: ModelGroup): ModelEntry? =
        enabledMemberEntries(group).firstOrNull()

    /**
     * [T-android-regenerate-title-submodel] The dedicated title-generation
     * sub-model entry: the first enabled member of the configured
     * `defaultSubGroupId` group. Returns null when no sub-group is configured or
     * every member sits behind a disabled provider (caller then falls back to
     * the primary model). Single source of truth for both the auto-title path
     * (ChatViewModel.resolveTitleProvider) and the manual Regenerate path
     * (SessionListViewModel.regenerateTitle), mirroring iOS resolveSubEntry.
     */
    fun resolveTitleSubEntry(): ModelEntry? {
        val subGroupId = defaultSubGroupId ?: return null
        val group = group(subGroupId) ?: return null
        return firstEnabledMemberEntry(group)
    }

    /**
     * [T-disabled-provider-via-group-android] True when [entryId] resolves
     * to an entry whose provider instance is currently enabled. Used by the
     * settings UI to dim members that won't be reachable at runtime.
     */
    fun isEntryProviderEnabled(entryId: String): Boolean {
        val config = _config.value
        val entry = config.modelEntries.find { it.id == entryId } ?: return false
        val instance = config.instances.find { it.id == entry.providerInstanceId } ?: return false
        return instance.isEnabled
    }

    fun replaceEntries(instanceId: String, models: List<LLMModel>) = synchronized(configLock) {
                ensureConfigLoaded()
        // Hot path for concurrent autoRefreshModels coroutines (one per
        // enabled instance) — without this lock, two replaceEntries() calls
        // race on the shared config.modelEntries ArrayList. The working copy
        // additionally keeps this refresh off the list Compose is iterating.
        val config = workingCopy()
        val existing = config.modelEntries.filter { it.providerInstanceId == instanceId }
        val existingEntryIds = existing.map { it.id }.toSet()

        // Build lookup: baseModel.id → existing entry (prefer non-custom if duplicates exist)
        val existingByModelId = mutableMapOf<String, ModelEntry>()
        for (entry in existing) {
            val key = entry.baseModel.id
            val current = existingByModelId[key]
            if (current == null || current.isCustom) {
                existingByModelId[key] = entry
            }
        }

        // Build new entries, carrying forward uuid / overrides / isHidden from prior entries
        val refreshedModelIds = models.map { it.id }.toSet()
        // [P3.3 裁军] 语音模板模态守护 + ASR/TTS 模态推断
        // （withInferredVoiceModality）随语音全家退役删除。
        val newEntries = models.map { model ->
            val prior = existingByModelId[model.id]
            val resolved = novex.android.data.model.NovexDeepSeekModelMetadata.official(
                model, config.instances.firstOrNull { it.id == instanceId }?.effectiveBaseURL,
            )
            ModelEntry(
                providerInstanceId = instanceId,
                baseModel = resolved,
                overrides = prior?.overrides ?: ModelOverrides(),
                isCustom = false,
                isHidden = prior?.isHidden ?: false,
                uuid = prior?.id ?: java.util.UUID.randomUUID().toString(),
                userModifiedAt = prior?.userModifiedAt,
            )
        }

        // Keep custom entries that weren't in the refreshed list
        val remainingCustom = existing.filter { it.isCustom && it.baseModel.id !in refreshedModelIds }

        // [P3.3 裁军] 语音模板种子保留段（preservedVoice，随
        // templateVoiceModelById 而去）随语音全家退役删除。
        config.modelEntries.removeAll { it.providerInstanceId == instanceId }
        config.modelEntries.addAll(newEntries)
        config.modelEntries.addAll(remainingCustom)

        // Prune stale group member references
        val survivingEntryIds = config.modelEntries.map { it.id }.toSet()
        val prunedEntryIds = existingEntryIds - survivingEntryIds
        if (prunedEntryIds.isNotEmpty()) {
            val suspiciousShrink = existing.size >= 4 && models.size * 2 < existing.size
            if (suspiciousShrink) {
                android.util.Log.w("ProviderRepo", "[ModelList] replaceEntries SUSPICIOUS SHRINK before=${existing.size} after=${models.size} — group references PRESERVED as stale")
            } else {
                for (i in config.modelGroups.indices) {
                    val before = config.modelGroups[i].memberEntryIds.size
                    config.modelGroups[i].memberEntryIds.removeAll { it in prunedEntryIds }
                    val removed = before - config.modelGroups[i].memberEntryIds.size
                    if (removed > 0) {
                        android.util.Log.i("ProviderRepo", "[ModelList] replaceEntries pruned $removed stale refs from group '${config.modelGroups[i].name}'")
                    }
                }
                // T171: same cascade for agent-loop direct-entry pins —
                // mirrors iOS ProviderConfigStore.replaceEntries (L716).
                // Skipped under the suspicious-shrink branch alongside the
                // group-member preservation, so a transient API hiccup
                // never silently nukes the user's curated agent-loop set.
                val agentBefore = config.agentLoopModelEntryIds.size
                config.agentLoopModelEntryIds.removeAll { it in prunedEntryIds }
                val agentRemoved = agentBefore - config.agentLoopModelEntryIds.size
                if (agentRemoved > 0) {
                    android.util.Log.i("ProviderRepo", "[ModelList] replaceEntries pruned $agentRemoved stale agent-loop entry pins")
                }
            }
        }

        saveConfig(config)
        // Stamp so staleness checks know this instance just refreshed.
        markInstanceFetched(instanceId)
    }

    // --- Model Entry management ---
    //
    // [T-android-provider-mutator-lock] Every read-modify-write mutator below
    // holds configLock for its WHOLE body, not just the saveConfig at the end.
    //
    // These mutate `_config.value`'s inner MutableLists IN PLACE — the very
    // lists already handed to collectors (the pickers filter over
    // config.modelEntries on the main thread). Mutating them from an IO thread
    // while a composition iterates throws ConcurrentModificationException in
    // the READER. 5399fe270 removed one such walk (ProviderConfig.equals inside
    // StateFlow emission) but left every consumer exposed; the shared mutable
    // list is the actual defect, and the lock is what serialises it against
    // saveConfig's defensive copy (which only snapshots AFTER the mutation).
    // configLock is a plain JVM monitor, so the nested saveConfig re-entry is
    // fine — replaceEntries / ensureVoiceTemplateModels already rely on that.

    fun addEntry(entry: ModelEntry): Unit = synchronized(configLock) {
                ensureConfigLoaded()
        val config = workingCopy()
        if (!entry.isCustom) {
            val exists = config.modelEntries.any {
                it.providerInstanceId == entry.providerInstanceId && it.baseModel.id == entry.baseModel.id
            }
            // Explicit label: a bare `return` in an expression body reads as if
            // it might only leave the synchronized lambda.
            if (exists) return@addEntry
        }
        config.modelEntries.add(entry)
        saveConfig(config)
    }

    fun updateEntry(entry: ModelEntry): Unit = synchronized(configLock) {
                ensureConfigLoaded()
        val config = workingCopy()
        val idx = config.modelEntries.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            config.modelEntries[idx] = entry.copy(userModifiedAt = System.currentTimeMillis())
            saveConfig(config)
        }
    }

    fun removeEntry(entryId: String): Unit = synchronized(configLock) {
                ensureConfigLoaded()
        val config = workingCopy()
        config.modelEntries.removeAll { it.id == entryId }
        config.modelGroups.forEach { group ->
            group.memberEntryIds.removeAll { it == entryId }
        }
        // T171: cascade-clean the agent-loop direct-entry pin so the
        // AgentLoopModelsScreen never surfaces a checkmark on a model that
        // no longer exists. Mirrors iOS ProviderConfigStore.removeEntry
        // (Providers/ProviderConfigStore.swift L268).
        config.agentLoopModelEntryIds.removeAll { it == entryId }
        saveConfig(config)
    }

    // --- Model Group management ---

    fun addGroup(group: ModelGroup): Unit = synchronized(configLock) {
                ensureConfigLoaded()
        val config = workingCopy()
        config.modelGroups.add(group)
        saveConfig(config)
    }

    fun updateGroup(group: ModelGroup): Unit = synchronized(configLock) {
                ensureConfigLoaded()
        val config = workingCopy()
        val idx = config.modelGroups.indexOfFirst { it.id == group.id }
        if (idx >= 0) {
            // Defensive re-wrap: callers routinely pass `published.copy(...)`,
            // and data-class copy() shares memberEntryIds BY REFERENCE with the
            // published group. Storing that object would put published inner
            // state back inside a working copy, quietly undoing the invariant.
            config.modelGroups[idx] = group.copy(
                memberEntryIds = group.memberEntryIds.toMutableList(),
            )
            saveConfig(config)
        }
    }

    fun removeGroup(groupId: String): Unit = synchronized(configLock) {
                ensureConfigLoaded()
        val config = workingCopy()
        config.removeModelGroupAndBindings(groupId)
        saveConfig(config)
    }

    /** Set the agent-loop-visible entry ID list (individual model entries). */
    fun setAgentLoopEntryIds(ids: List<String>): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.agentLoopModelEntryIds.clear()
        // [T-android-agentloop-dup-key-crash] Dedup at the sink (preserving
        // first-seen order). addAgentLoopEntry guards against dups, but any
        // path writing straight through this sink (cross-device sync merge,
        // reorder write-back, data migration) could otherwise persist a
        // duplicate id — which surfaces downstream as two pinnedEntries with
        // the same id, a duplicate LazyColumn key, and a crash on scroll.
        config.agentLoopModelEntryIds.addAll(ids.distinct())
        saveConfig(config)
    }

    /** Set the agent-loop-visible group ID list. */
    fun setAgentLoopGroupIds(ids: List<String>): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.agentLoopGroupIds.clear()
        // [T-android-agentloop-dup-key-crash] Dedup at the sink (see above) so
        // no write path can persist duplicate group ids → duplicate LazyColumn
        // key on the agent-loop groups list.
        config.agentLoopGroupIds.addAll(ids.distinct())
        saveConfig(config)
    }

    // T182: thin add/remove helpers used by AgentLoopModelsSection +
    // AddAgentLoopModelsSheet / AddAgentLoopGroupsSheet. These wrap the
    // set* functions so caller doesn't have to round-trip through the
    // existing list (mutating in place would race with a parallel
    // saveConfig). Keep insertion order — preserves the order the user
    // added items in the picker, mirrors iOS appendIfNeeded behaviour.

    /** Append [entryId] to the agent-loop direct-pin list if not already there. */
    fun addAgentLoopEntry(entryId: String) {
        val cur = _config.value.agentLoopModelEntryIds.toList()
        if (entryId in cur) return
        setAgentLoopEntryIds(cur + entryId)
    }

    /** Remove [entryId] from the agent-loop direct-pin list. No-op if absent. */
    fun removeAgentLoopEntry(entryId: String) {
        val cur = _config.value.agentLoopModelEntryIds.toList()
        if (entryId !in cur) return
        setAgentLoopEntryIds(cur.filterNot { it == entryId })
    }

    /** Append [groupId] to the agent-loop group-pin list if not already there. */
    fun addAgentLoopGroup(groupId: String) {
        val cur = _config.value.agentLoopGroupIds.toList()
        if (groupId in cur) return
        setAgentLoopGroupIds(cur + groupId)
    }

    /** Remove [groupId] from the agent-loop group-pin list. No-op if absent. */
    fun removeAgentLoopGroup(groupId: String) {
        val cur = _config.value.agentLoopGroupIds.toList()
        if (groupId !in cur) return
        setAgentLoopGroupIds(cur.filterNot { it == groupId })
    }

    // T186: reorder helpers — UI keeps a local mutable copy during
    // drag and pushes the final order through here on drop. Validates
    // that [newOrder] is a permutation of the current list to defend
    // against a stale dragged-from-different-snapshot reorder slipping
    // in mid-write (e.g. a cascade-cleanup raced with the drag).
    fun reorderAgentLoopEntries(newOrder: List<String>) {
        val cur = _config.value.agentLoopModelEntryIds.toSet()
        if (newOrder.toSet() != cur) return
        setAgentLoopEntryIds(newOrder)
    }

    fun reorderAgentLoopGroups(newOrder: List<String>) {
        val cur = _config.value.agentLoopGroupIds.toSet()
        if (newOrder.toSet() != cur) return
        setAgentLoopGroupIds(newOrder)
    }

    /**
     * [T-android-provider-reorder] Reorder provider instances (drag-to-sort in
     * the Providers list). Mirrors iOS `ProviderConfigStore.reorderInstances`.
     *
     * [newOrder] is a list of instance ids. Unknown ids are dropped and any
     * instance missing from it is appended in its existing relative order, so a
     * caller that only knows about ONE provider-type section can pass just that
     * section's ids and leave the rest untouched — which is exactly what the
     * per-section drag in ProviderListScreen does.
     *
     * Persistence: `sort_order` is derived from list position at save time
     * (ProviderConfigMapping writes `sortOrder = idx`) and the DAO reads back
     * `ORDER BY sort_order ASC`, so permuting the list IS the persistence — no
     * per-row column write is needed.
     *
     * Unlike iOS there is no dirty-marking step here: Android keeps provider
     * config local-only (Room + the JSON mirror), with no CloudKit upload, so
     * the "pure reorder doesn't mark dirty" bug iOS had to fix has no analogue.
     * `saveConfig` bumps `revision`, which is the Android-side equivalent
     * concern — it guarantees the StateFlow re-emits even though a pure
     * permutation compares equal under data-class structural equality.
     */
    fun reorderInstances(newOrder: List<String>): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val current = config.instances.toList()
        if (current.isEmpty()) return

        val byId = current.associateBy { it.id }
        val seen = LinkedHashSet<String>()
        val reordered = ArrayList<ProviderInstance>(current.size)
        for (id in newOrder) {
            val inst = byId[id] ?: continue      // drop unknown ids
            if (!seen.add(id)) continue          // drop duplicates
            reordered.add(inst)
        }
        // Anything the caller didn't mention keeps its existing relative order.
        for (inst in current) {
            if (seen.add(inst.id)) reordered.add(inst)
        }

        // No-op guard: skip the DB write + StateFlow churn when nothing moved.
        if (reordered.map { it.id } == current.map { it.id }) return

        // [T-android-reorder-unlocked-mutation] Mutate under configLock.
        //
        // `config.instances` is the shared MutableList inside _config.value,
        // and clear()/addAll() ran unprotected here — exactly the window the
        // configLock KDoc above was written to close (it cites the
        // "ConcurrentModificationException → ArrayList.next" crash seen on
        // Pixel 6 / 4a). Concurrent readers iterate that same list with no
        // lock: hasFoldedShadowDuplicates() and shadowVoiceSources() are
        // called from ProviderListScreen during composition — on the main
        // thread, and recomposition fires precisely BECAUSE this reorder just
        // emitted — while the background model-refresh fan-out reads it too.
        //
        // The empty window was the worse half: a saveConfig snapshotting
        // between clear() and addAll() would persist ZERO instances to Room
        // and the JSON mirror, wiping the user's providers.
        //
        // synchronized is reentrant, so the nested saveConfig (which takes the
        // same lock) is fine. Matches ensureVoiceTemplateModels / replaceEntries.
        synchronized(configLock) {
            config.instances.clear()
            config.instances.addAll(reordered)
            saveConfig(config)
        }
    }

    /**
     * [T-android-modelgroup-reorder] Reorder the user's Model Groups
     * (drag-to-sort in the Model Groups screen). Mirrors iOS
     * `ProviderConfigStore.reorderGroups` (4ba54ff5) and follows the exact
     * contract of [reorderInstances] above: unknown ids dropped, duplicates
     * collapsed, unmentioned groups appended in their existing relative order,
     * no-op guard, and the list mutation held under [configLock] — same
     * CME/empty-window rationale as documented on reorderInstances
     * ([T-android-reorder-unlocked-mutation]).
     *
     * Persistence needs nothing extra: ProviderConfigMapping writes each
     * group's `sort_order` from its list index at save time and the DAO reads
     * `ORDER BY sort_order ASC`, so list position IS the stored order.
     */
    fun reorderModelGroups(newOrder: List<String>): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val current = config.modelGroups.toList()
        if (current.isEmpty()) return

        val byId = current.associateBy { it.id }
        val reordered = normalizeModelGroupOrder(current.map { it.id }, newOrder)
            .mapNotNull(byId::get)

        // No-op guard: skip the DB write + StateFlow churn when nothing moved.
        if (reordered.map { it.id } == current.map { it.id }) return

        synchronized(configLock) {
            config.modelGroups.clear()
            config.modelGroups.addAll(reordered)
            saveConfig(config)
        }
    }

    /**
     * Resolve the effective model entries visible to the agent loop (minis-model-use).
     * Expands groups to their members, unions with individual entries, dedupes by ID,
     * and filters to entries of enabled provider instances. Mirrors iOS
     * ProviderConfigStore.resolvedAgentLoopEntries.
     */
    fun resolvedAgentLoopEntries(): List<ModelEntry> {
        ensureImageGenerationMigration()
        return resolveOrdinaryAgentLoopEntries(_config.value)
    }

    fun resolvedImageGenerationEntries(): List<ModelEntry> {
        ensureImageGenerationMigration()
        return resolveImageGenerationEntries(_config.value).filter { entry ->
            val instance = instance(entry.providerInstanceId) ?: return@filter false
            usableApiKey(instance) != null
        }
    }

    /**
     * [T-opencode-sunset] The builtin OpenCode Zen free instances were
     * sunset (2026-09-26): the upstream free tier 403s third-party callers
     * server-side. IDs inlined — OpenCodeFreeModelsApi is gone; this check
     * survives so the one-shot migration below (and list filtering) can
     * still recognize leftover instances in existing user configs.
     */
    fun isOpenCodeFreeInstance(instanceId: String): Boolean =
        isOpenCodeFreeInstanceId(instanceId)

    /**
     * [T-opencode-sunset] One-shot idempotent migration: disable the two
     * builtin free instances and hide their model entries. Nothing is
     * deleted — entries stay in config so the sunset remains reversible;
     * disabled instances simply stop appearing in provider/model pickers,
     * and sessions still bound to one get the friendly 403 copy.
     */
    fun ensureOpenCodeSunsetMigration(): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        if (applyOpenCodeSunset(config)) saveConfig(config)
    }


    fun ensureImageGenerationMigration(): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val current = _config.value
        val migrated = migrateLegacyImageGenerationConfig(current)
        if (migrated !== current && migrated != current) saveConfig(migrated)
    }

    fun setImageGenerationGroupEnabled(groupId: String, enabled: Boolean): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        if (enabled) {
            if (config.modelGroups.none { it.id == groupId }) return@synchronized
            if (groupId !in config.imageGenerationGroupIds) config.imageGenerationGroupIds.add(groupId)
        } else {
            config.imageGenerationGroupIds.removeAll { it == groupId }
        }
        saveConfig(config)
    }

    fun reorderImageGenerationGroups(groupIds: List<String>): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val valid = groupIds.filter { id -> config.modelGroups.any { it.id == id } }.distinct()
        config.imageGenerationGroupIds.clear()
        config.imageGenerationGroupIds.addAll(valid)
        saveConfig(config)
    }

    fun setImageGenerationProvider(instanceId: String, enabled: Boolean): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        if (enabled) {
            if (config.instances.none { it.id == instanceId }) return@synchronized
            if (instanceId !in config.imageGenerationProviderInstanceIds) {
                config.imageGenerationProviderInstanceIds.add(instanceId)
            }
        } else {
            config.imageGenerationProviderInstanceIds.removeAll { it == instanceId }
        }
        saveConfig(config)
    }

    fun reorderImageGenerationProviders(instanceIds: List<String>): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val valid = instanceIds.filter { id -> config.instances.any { it.id == id } }.distinct()
        val previouslyEnabledGroups = config.imageGenerationGroupIds.toList()
        config.imageGenerationProviderInstanceIds.clear()
        config.imageGenerationProviderInstanceIds.addAll(valid)
        val enabledGroups = valid.mapNotNull { instanceId ->
            config.modelGroups.getOrNull(imageSourceGroupIndex(config, instanceId))
                ?.takeIf { it.id in config.imageGenerationGroupIds }
                ?.id
        }
        config.imageGenerationGroupIds.clear()
        config.imageGenerationGroupIds.addAll(
            enabledGroups + previouslyEnabledGroups.filterNot { it in enabledGroups },
        )
        saveConfig(config)
    }

    private fun imageSourceGroupId(instanceId: String) = "image-source-$instanceId"

    private fun imageSourceGroupIndex(config: ProviderConfig, instanceId: String): Int {
        val deterministic = config.modelGroups.indexOfFirst { it.id == imageSourceGroupId(instanceId) }
        if (deterministic >= 0) return deterministic
        val entryIds = config.modelEntries
            .filter { it.providerInstanceId == instanceId }
            .mapTo(mutableSetOf()) { it.id }
        return config.modelGroups.indexOfFirst { group ->
            group.id in config.imageGenerationGroupIds && group.memberEntryIds.any { it in entryIds }
        }
    }

    fun imageGenerationGroupForProvider(instanceId: String): ModelGroup? {
        val config = _config.value
        return config.modelGroups.getOrNull(imageSourceGroupIndex(config, instanceId))
    }

    fun ensureImageGenerationGroupForProvider(instanceId: String, name: String): ModelGroup =
        synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            val deterministicId = imageSourceGroupId(instanceId)
            val existingIndex = imageSourceGroupIndex(config, instanceId)
            if (existingIndex >= 0) {
                val existing = config.modelGroups[existingIndex]
                if (existing.name != name) {
                    config.modelGroups[existingIndex] = existing.copy(name = name)
                    saveConfig(config)
                    return@synchronized config.modelGroups[existingIndex]
                }
                return@synchronized existing
            }
            val created = ModelGroup(id = deterministicId, name = name)
            config.modelGroups.add(created)
            saveConfig(config)
            created
        }

    /** Merge a fresh source catalog without changing existing checkboxes. */
    fun replaceImageGenerationModels(instanceId: String, models: List<LLMModel>): Unit =
        synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            var groupIndex = imageSourceGroupIndex(config, instanceId)
            if (groupIndex < 0) {
                val groupId = imageSourceGroupId(instanceId)
                val name = config.instances.firstOrNull { it.id == instanceId }?.label ?: "生图来源"
                config.modelGroups.add(ModelGroup(id = groupId, name = name))
                groupIndex = config.modelGroups.lastIndex
            }
            val selectedIds = config.modelGroups[groupIndex].memberEntryIds.toSet()
            val existing = config.modelEntries.filter { it.providerInstanceId == instanceId }
            val existingByModel = existing.associateBy { it.baseModel.id }
            val incomingIds = models.mapTo(mutableSetOf()) { it.id }
            val refreshed = models.map { model ->
                val prior = existingByModel[model.id]
                ModelEntry(
                    providerInstanceId = instanceId,
                    baseModel = model,
                    overrides = prior?.overrides ?: ModelOverrides(),
                    isCustom = false,
                    isHidden = prior?.let { it.id !in selectedIds } ?: true,
                    uuid = prior?.id ?: java.util.UUID.randomUUID().toString(),
                    userModifiedAt = prior?.userModifiedAt,
                )
            }
            // Keep manual entries and any currently selected model even when a
            // transiently incomplete /models response omits it. Unselected
            // stale catalog rows can be removed safely.
            val preserved = existing.filter {
                it.baseModel.id !in incomingIds && (it.isCustom || it.id in selectedIds)
            }
            config.modelEntries.removeAll { it.providerInstanceId == instanceId }
            config.modelEntries.addAll(refreshed + preserved)
            val survivingById = config.modelEntries.associateBy { it.id }
            val orderedSelected = config.modelGroups[groupIndex].memberEntryIds.filter { id ->
                survivingById[id]?.providerInstanceId == instanceId
            }
            config.modelGroups[groupIndex] = config.modelGroups[groupIndex].copy(
                memberEntryIds = orderedSelected.toMutableList(),
            )
            saveConfig(config)
        }

    fun setImageGenerationModelEnabled(instanceId: String, entryId: String, enabled: Boolean): Unit =
        synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            val entryIndex = config.modelEntries.indexOfFirst {
                it.id == entryId && it.providerInstanceId == instanceId
            }
            if (entryIndex < 0) return@synchronized
            var groupIndex = imageSourceGroupIndex(config, instanceId)
            if (groupIndex < 0) {
                val groupId = imageSourceGroupId(instanceId)
                val name = config.instances.firstOrNull { it.id == instanceId }?.label ?: "生图来源"
                config.modelGroups.add(ModelGroup(id = groupId, name = name))
                groupIndex = config.modelGroups.lastIndex
            }
            val current = config.modelEntries[entryIndex]
            val output = current.overrides.outputModalities ?: current.baseModel.outputModalities
            config.modelEntries[entryIndex] = current.copy(
                isHidden = !enabled,
                overrides = current.overrides.copy(
                    outputModalities = if (enabled && output.orEmpty().none { it.equals("image", true) }) {
                        listOf("image")
                    } else current.overrides.outputModalities,
                ),
                userModifiedAt = System.currentTimeMillis(),
            )
            val members = config.modelGroups[groupIndex].memberEntryIds.toMutableList()
            if (enabled && entryId !in members) members.add(entryId)
            if (!enabled) members.removeAll { it == entryId }
            config.modelGroups[groupIndex] = config.modelGroups[groupIndex].copy(memberEntryIds = members)
            val actualGroupId = config.modelGroups[groupIndex].id
            if (enabled && actualGroupId !in config.imageGenerationGroupIds) config.imageGenerationGroupIds.add(actualGroupId)
            saveConfig(config)
        }

    fun reorderImageGenerationModels(instanceId: String, entryIds: List<String>): Unit =
        synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            val groupIndex = imageSourceGroupIndex(config, instanceId)
            if (groupIndex < 0) return@synchronized
            val valid = entryIds.filter { id ->
                config.modelEntries.any { it.id == id && it.providerInstanceId == instanceId }
            }.distinct()
            config.modelGroups[groupIndex] = config.modelGroups[groupIndex].copy(
                memberEntryIds = valid.toMutableList(),
            )
            saveConfig(config)
        }

    fun setImageModelEndpointMode(entryId: String, mode: ImageEndpointMode?): Unit =
        synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            val index = config.modelEntries.indexOfFirst { it.id == entryId }
            if (index < 0) return@synchronized
            val entry = config.modelEntries[index]
            config.modelEntries[index] = entry.copy(
                overrides = entry.overrides.copy(
                    imageEndpointMode = mode,
                    imageEndpointResolved = null,
                ),
                userModifiedAt = System.currentTimeMillis(),
            )
            saveConfig(config)
        }

    fun setImageModelEndpointResolved(entryId: String, endpoint: ImageEndpointMode): Unit =
        synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            val index = config.modelEntries.indexOfFirst { it.id == entryId }
            if (index < 0) return@synchronized
            val entry = config.modelEntries[index]
            if (entry.overrides.imageEndpointResolved == endpoint) return@synchronized
            config.modelEntries[index] = entry.copy(
                overrides = entry.overrides.copy(imageEndpointResolved = endpoint),
            )
            saveConfig(config)
        }

    fun group(id: String): ModelGroup? =
        _config.value.modelGroups.find { it.id == id }

    var defaultPrimaryGroupId: String?
        get() = _config.value.defaultPrimaryGroupId
        set(value) = synchronized(configLock) {
            val config = workingCopy()
            config.defaultPrimaryGroupId = value
            saveConfig(config)
        }

    var defaultSubGroupId: String?
        get() = _config.value.defaultSubGroupId
        set(value) = synchronized(configLock) {
            val config = workingCopy()
            config.defaultSubGroupId = value
            saveConfig(config)
        }

    // [P3.3 裁军] 语音输入/输出分组绑定存取器（voiceInputGroupId/
    // voiceOutputGroupId setter）随语音全家退役删除；ProviderConfig 的
    // 同名字段为持久化格式（provider.db 快照）原样保留（schema 冻结）。

    // --- Vision group [T-android-vision-group / GH#182] ---

    var visionGroupId: String?
        get() = _config.value.visionGroupId
        set(value) = synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            config.visionGroupId = value
            saveConfig(config)
        }

    /** True when a Vision Group is bound AND still exists. Gates read_image
     *  tool exposure for main models that cannot natively see images. */
    fun hasVisionGroupConfigured(): Boolean {
        val gid = _config.value.visionGroupId ?: return false
        return _config.value.modelGroups.any { it.id == gid }
    }

    /** Bound Vision group's display name, or null. */
    fun visionGroupName(): String? {
        val gid = _config.value.visionGroupId ?: return null
        return _config.value.modelGroups.find { it.id == gid }?.name
    }

    /**
     * [T-android-vision-group] Ordered vision-capable fail-over candidates from
     * the bound Vision Group. Mirrors resolveVoiceInputCandidates: filters
     * members to enabled instances whose model declares image input, honours
     * the group's routing strategy (`fallback` keeps order; `loadBalance`
     * rotates the start by [loadBalanceSeed] so separate reads spread across
     * members). Returns [] when no group is bound or no member is usable — the
     * caller (ReadImageTool) then returns a clear failure text.
     */
    fun resolveVisionCandidates(loadBalanceSeed: Int = 0): List<Pair<ProviderInstance, ModelEntry>> {
        ensureConfigLoaded()
        val config = _config.value

        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled || !entry.model.hasImageInput) return null
            return inst to entry
        }

        val gid = config.visionGroupId ?: return emptyList()
        val group = config.modelGroups.find { it.id == gid } ?: return emptyList()
        var members = group.memberEntryIds.mapNotNull { providerEntry(it) }
        if (group.strategy == RoutingStrategy.loadBalance && members.size > 1) {
            val offset = kotlin.math.abs(loadBalanceSeed) % members.size
            members = members.drop(offset) + members.take(offset)
        }
        val out = mutableListOf<Pair<ProviderInstance, ModelEntry>>()
        for (m in members) {
            if (out.none { it.second.id == m.second.id }) out.add(m)
        }
        return out
    }

    // --- Thinking rules (custom) [T-android-thinking-rules-phase2] ---
    //
    // User-authored rules live in provider.db (provider_thinking_rules), keyed by
    // provider-instance id, in sort_order priority order. Built-in vendor rules are
    // never stored. On every mutation we publish the instance's rules into the
    // ThinkingContractResolver cache so the (sync) request-builder can read them.

    // [P3.3 裁军] 自定义思考规则读取面（thinkingContracts/thinkingContractIds）
    // 随 CUSTOM 路径退役删除；Room 表 provider_thinking_rules 本体保留
    // （schema 冻结），resolver 只跑内置座次表。

    // [P3.3 裁军] 语音输出选择面（voiceOutputOverrideEntryId/
    // VoiceOutputChoice/resolveVoiceOutputChoice/activeVoiceGroupMemberId/
    // voiceOutputGroupName/resolveVoiceOutputEntry）随语音全家退役删除。

    // [P3.3 裁军] Shadow Voice 全段（hasVoiceModels/isVoiceShadowDisabled/
    // setVoiceShadowDisabled/ShadowVoiceSource/shadowVoiceSources/
    // hasFoldedShadowDuplicates）随语音全家退役删除；companion 的
    // normalizedShadowKey（仅被 shadow 折叠使用）一并随葬。

    suspend fun refreshModels(instance: ProviderInstance) {
        // [T-opencode-sunset] Sunset instances are disabled and hidden;
        // refreshing them is a no-op (the free tier 403s third-party calls).
        if (isOpenCodeFreeInstance(instance.id)) return
        var apiKey = loadApiKey(instance.id)

        // For OAuth providers, try to refresh the token before using it (mirrors iOS validAccessToken)
        if (instance.credentialType == novex.android.data.model.ProviderCredential.oauth && apiKey != null) {
            try {
                val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                val freshToken = manager?.validAccessToken()
                if (freshToken != null && freshToken != apiKey) {
                    saveApiKey(instance.id, freshToken)
                    apiKey = freshToken
                    android.util.Log.i("ProviderRepo", "refreshModels: OAuth token refreshed")
                }
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "OAuth token refresh failed: ${e.message}")
            }
        }

        android.util.Log.i("ProviderRepo", "refreshModels: id=${instance.id} type=${instance.providerType} credential=${instance.credentialType} hasKey=${apiKey != null} keyLen=${apiKey?.length ?: 0} baseURL=${instance.effectiveBaseURL}")

        // OpenAI Codex OAuth: use static model list (OAuth tokens can't call /v1/models)
        if (instance.providerType == ProviderType.openAI
            && instance.credentialType == ProviderCredential.oauth
        ) {
            val models = ModelsCatalogApi.fetchOpenAiModelsOAuth()
            if (models.isNotEmpty()) {
                replaceEntries(instance.id, models)
                return
            }
        }

        // Step 1: Try provider API (requires API key)
        val customBase = instance.customBaseURL
        val isThirdParty = customBase != null
            && !customBase.lowercase().let {
                it.contains("api.openai.com") || it.contains("chatgpt.com") || it.contains("openrouter.ai")
            }

        if (apiKey != null) {
            val baseURL = instance.effectiveBaseURL
            val models = try {
                when (instance.providerType) {
                    ProviderType.anthropic -> ModelsCatalogApi.fetchAnthropicModels(
                        apiKey, baseURL,
                        isOAuth = instance.credentialType == novex.android.data.model.ProviderCredential.oauth,
                        // [T-provider-custom-user-agent] models-list UA override.
                        customUserAgent = instance.customUserAgent,
                    )
                    ProviderType.gemini -> ModelsCatalogApi.fetchGeminiModels(apiKey)
                    // [T-provider-custom-user-agent] models-list UA override.
                    ProviderType.openAI -> ModelsCatalogApi.fetchOpenAiModels(apiKey, baseURL, customUserAgent = instance.customUserAgent)
                    ProviderType.openRouter -> OpenRouterModelsApi.fetchModels(apiKey)
                    // xAI: the OAuth model list is fixed (no /v1/models gating
                    // call needed — XAIModelsApi exposes the spec-mandated set).
                    // For API-key users we still call the same static list; if
                    // xAI later exposes a dynamic /v1/models endpoint this is
                    // the place to swap in OpenAI-compatible fetch.
                    ProviderType.xAI -> com.openminis.app.provider.xai.XAIModelsApi.fetchModelsOAuth()
                    // [T-kimi-oauth] Kimi Code: unlike Codex OAuth, the Kimi
                    // OAuth token CAN call the models endpoint — real fetch
                    // from GET /coding/v1/models (OpenAI-compatible shape).
                    // The upstream lineup shifts across generations, so the
                    // live list replaces the minimal built-in fallback.
                    ProviderType.kimiCode -> ModelsCatalogApi.fetchOpenAiModels(
                        apiKey,
                        baseURL ?: "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1",
                        customUserAgent = instance.customUserAgent,
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("ProviderRepo", "refreshModels fetch error: ${e.message}", e)
                emptyList()
            }
            android.util.Log.i("ProviderRepo", "refreshModels: got ${models.size} models")

            // Step 2: If API returned results, use them
            if (models.isNotEmpty()) {
                replaceEntries(instance.id, models)
                return
            }
        }

        // Step 3: Fallback to models.dev by base URL.
        // We try this even for third-party hosts (DashScope/Bailian, etc.) — the
        // models.dev registry covers many "Anthropic-compatible" or "OpenAI-compatible"
        // gateways by hostname, and when there's no match `fetchModels` returns
        // empty so the existing list (vLLM/Ollama on a private host) is preserved.
        val fallbackBaseURL = modelsDevBaseURL(instance)
        val fallbackModels = ModelsDevApi.fetchModels(fallbackBaseURL)
        if (fallbackModels.isNotEmpty()) {
            android.util.Log.i("ProviderRepo", "models.dev fallback returned ${fallbackModels.size} models for ${instance.label}")
            replaceEntries(instance.id, fallbackModels)
        } else if (isThirdParty) {
            android.util.Log.i("ProviderRepo", "Third-party endpoint, no models.dev match — preserving existing models for ${instance.label}")
        }
    }

    /**
     * Auto-refresh variant: skips instances where the user has added custom models,
     * so we never overwrite hand-edited entries. Mirrors iOS `autoRefreshModels(for:)`.
     */
    private suspend fun autoRefreshModels(instance: ProviderInstance) {
        if (isOpenCodeFreeInstance(instance.id)) return
        val hasCustom = _config.value.modelEntries.any {
            it.providerInstanceId == instance.id && it.isCustom
        }
        if (hasCustom) {
            // Setup used to mark every selected model as custom. Refresh only matching
            // capacity metadata, without adding models, changing groups, or overriding users.
            if (instance.providerType == ProviderType.openAI) {
                val key = loadApiKey(instance.id) ?: return
                val fetched = ModelsCatalogApi.fetchOpenAiModels(key, instance.effectiveBaseURL, forceRefresh = true,
                    customUserAgent = instance.customUserAgent).associateBy { it.id }
                synchronized(configLock) {
                    val updated = workingCopy()
                    updated.modelEntries.replaceAll { entry ->
                        if (entry.providerInstanceId != instance.id) entry else {
                            val capacity = fetched[entry.baseModel.id]?.contextWindow
                            if (capacity == null) entry else entry.copy(baseModel = entry.baseModel.copy(contextWindow = capacity))
                        }
                    }
                    saveConfig(updated)
                }
            }
            return
        }
        refreshModels(instance)
    }

    /**
     * Stale-while-revalidate helper for UI code. Callers (e.g. the model
     * picker sheet) invoke this on open: the currently-persisted
     * `config.modelEntries` is returned immediately via the StateFlow
     * (no waiting), and if any instance's cache is older than
     * [MODEL_CACHE_TTL_MS] we kick a background refresh that updates the
     * StateFlow when the network fetch completes.
     *
     * Concurrent-call safe: per-instance `autoRefreshModels` is idempotent
     * and the global daily-refresh flag prevents cold-start double fetch.
     * This helper bypasses the daily flag because it runs per-instance — it's
     * meant for targeted "user is looking at this picker now" revalidation.
     */
    fun triggerBackgroundRefreshIfStale(scope: kotlinx.coroutines.CoroutineScope) {
        val stale = _config.value.instances.filter { it.isEnabled && isInstanceStale(it.id) }
        if (stale.isEmpty()) return
        android.util.Log.i("ProviderRepo", "[ModelList] SWR refresh — ${stale.size} stale instance(s)")
        for (instance in stale) {
            scope.launch { autoRefreshModels(instance) }
        }
    }

    /**
     * Refresh model lists for all enabled instances, at most once per calendar day.
     * Mirrors iOS `refreshAllModelsIfNeeded()` — called from Application.onCreate.
     * Refreshes run in parallel; failures are logged but don't block other instances.
     */
    fun refreshAllModelsIfNeeded(scope: kotlinx.coroutines.CoroutineScope) {
        val key = "lastModelsRefreshDate"
        val lastMs = prefs.getLong(key, 0L)
        val now = System.currentTimeMillis()
        if (lastMs > 0L && isSameCalendarDay(lastMs, now)) {
            android.util.Log.i("ProviderRepo", "[ModelList] refreshAllModelsIfNeeded SKIP — already refreshed today")
            return
        }

        // [T-android-startup-config-stall] Config now loads asynchronously, so
        // at cold start `_config.value` may still be the empty placeholder when
        // this fires from MinisApp.onCreate. Wait for the load before reading
        // the enabled-instance set, otherwise the daily refresh would no-op on
        // "no enabled instances" and skip this launch entirely. Runs on the
        // caller's (IO) scope — does not touch the main thread.
        scope.launch {
            awaitConfigLoaded()
            val enabled = _config.value.instances.filter { it.isEnabled }
            if (enabled.isEmpty()) {
                android.util.Log.i("ProviderRepo", "[ModelList] refreshAllModelsIfNeeded SKIP — no enabled instances")
                return@launch
            }

            android.util.Log.i("ProviderRepo", "[ModelList] refreshAllModelsIfNeeded FIRE — ${enabled.size} instances")
            prefs.edit().putLong(key, now).apply()

            for (instance in enabled) {
                scope.launch { autoRefreshModels(instance) }
            }
        }
    }

    private fun isSameCalendarDay(aMs: Long, bMs: Long): Boolean {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = aMs
        val aYear = cal.get(java.util.Calendar.YEAR)
        val aDay = cal.get(java.util.Calendar.DAY_OF_YEAR)
        cal.timeInMillis = bMs
        return aYear == cal.get(java.util.Calendar.YEAR)
            && aDay == cal.get(java.util.Calendar.DAY_OF_YEAR)
    }

    /** Resolve the models.dev lookup base URL for an instance. */
    private fun modelsDevBaseURL(instance: ProviderInstance): String {
        instance.effectiveBaseURL?.let { return it }
        return when (instance.providerType) {
            ProviderType.anthropic -> "https://api.anthropic.com/v1"
            ProviderType.gemini -> "https://generativelanguage.googleapis.com"
            ProviderType.openAI -> "https://api.openai.com/v1"
            ProviderType.openRouter -> "https://openrouter.ai/api/v1"
            ProviderType.xAI -> "https://api.x.ai/v1"
            ProviderType.kimiCode -> "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1"
        }
    }

    // API Key management
    fun saveApiKey(instanceId: String, key: String) {
        encryptedPrefs.edit().putString("apikey_$instanceId", key).apply()
    }

    fun loadApiKey(instanceId: String): String? {
        return encryptedPrefs.getString("apikey_$instanceId", null)
    }

    /**
     * [T-empty-key-compat-endpoints] The credential the ROUTING layers should
     * use: the stored key, or "" for instances where an empty key is a valid
     * configuration (third-party OpenAI/Anthropic-compatible endpoint — see
     * ProviderInstance.allowsEmptyAPIKey). Returns null only when the instance
     * genuinely has no usable credential, so existing `?: return/continue`
     * call sites keep their skip semantics for everything else (notably OAuth
     * instances without a token, which must stay unauthenticated).
     */
    fun usableApiKey(instance: ProviderInstance): String? =
        loadApiKey(instance.id) ?: if (instance.allowsEmptyAPIKey) "" else null

    fun deleteApiKey(instanceId: String) {
        encryptedPrefs.edit().remove("apikey_$instanceId").apply()
    }

    // -- Import / Export --

    /**
     * [T-android-provider-export-oauth-token] OAuth manager for [instance],
     * covering EVERY OAuth provider type — including gemini / antigravity, which
     * OAuthManager.forInstance deliberately omits (it's tuned for the
     * login/logout/manual-bearer UI paths). Used only by the export/import
     * round-trip so we don't widen forInstance's shared behavior.
     */
    private fun oauthManagerFor(
        instance: ProviderInstance,
    ): com.openminis.app.auth.OAuthManager? = when (instance.providerType) {
        ProviderType.anthropic -> com.openminis.app.auth.ClaudeOAuthManager(context, instance.id)
        ProviderType.openAI -> com.openminis.app.auth.OpenAIOAuthManager(context, instance.id)
        ProviderType.xAI -> com.openminis.app.auth.XAIOAuthManager(context, instance.id)
        ProviderType.gemini -> com.openminis.app.auth.GeminiOAuthManager(context, instance.id)
        ProviderType.kimiCode -> com.openminis.app.auth.KimiOAuthManager(context, instance.id)
        else -> null
    }

    /** Export an instance as shareable JSON (includes base64-encoded API key). */
    fun exportInstanceJSON(instanceId: String): String? {
        ensureConfigLoaded()
        val instance = instance(instanceId) ?: return null
        val entries = visibleEntries(instanceId) + _config.value.modelEntries.filter {
            it.providerInstanceId == instanceId && it.isHidden
        }

        val obj = JSONObject().apply {
            put("providerType", instance.providerType.name)
            put("label", instance.label)
            put("credentialType", instance.credentialType.name)
            val modelsArr = JSONArray()
            for (entry in entries) {
                modelsArr.put(JSONObject().apply {
                    put("modelId", entry.baseModel.id)
                    put("displayName", entry.baseModel.displayName)
                    put("isHidden", entry.isHidden)
                    if (entry.isCustom) put("isCustom", true)
                    entry.baseModel.contextWindow?.let { put("contextWindow", it) }
                    entry.baseModel.maxOutputTokens?.let { put("maxOutputTokens", it) }
                    entry.baseModel.supportsReasoning?.let { put("supportsReasoning", it) }
                    entry.baseModel.interleavedReasoningField?.let { put("interleavedReasoningField", it) }
                    // [T-provider-export-model-overrides] Serialize the FULL
                    // ModelOverrides layer, not just displayName/maxOutputTokens.
                    // contextWindow / supportsReasoning / modality (input+output)
                    // were previously dropped — a hand-corrected proxied model
                    // lost those edits on round-trip. Each key is additive +
                    // optional: older builds ignore unknown keys, and import
                    // below reads each independently so a partial override
                    // object restores exactly the fields present.
                    //
                    // For cross-platform interop with iOS we ALSO write a
                    // `modalityOverride` bitfield (Int, matching
                    // ios/Providers/LLMTypes.swift ModelModality OptionSet):
                    // textInput=1, textOutput=2, imageInput=4, pdfInput=8,
                    // audioInput=16, videoInput=32, imageOutput=64,
                    // audioOutput=128, videoOutput=256. Android natively
                    // carries inputModalities/outputModalities as string lists;
                    // the bitfield is purely an interop wire-format that iOS
                    // can consume directly. On import, the native list fields
                    // win when present (Android↔Android lossless), bitfield is
                    // the iOS→Android fallback.
                    if (!entry.overrides.isEmpty) {
                        val o = JSONObject()
                        entry.overrides.displayName?.let { o.put("displayName", it) }
                        entry.overrides.maxOutputTokens?.let { o.put("maxOutputTokens", it) }
                        entry.overrides.contextWindow?.let { o.put("contextWindow", it) }
                        entry.overrides.supportsReasoning?.let { o.put("supportsReasoning", it) }
                        entry.overrides.inputModalities?.let {
                            o.put("inputModalities", JSONArray(it))
                        }
                        entry.overrides.outputModalities?.let {
                            o.put("outputModalities", JSONArray(it))
                        }
                        val bitfield = modalityBitfieldFromLists(
                            entry.overrides.inputModalities,
                            entry.overrides.outputModalities,
                        )
                        if (bitfield != 0) o.put("modalityOverride", bitfield)
                        put("overrides", o)
                    }
                    // Mirror iOS export of baseModel.modalityOverride — when
                    // the base model itself carries explicit modality info,
                    // serialize an interop bitfield so iOS can faithfully
                    // restore it. Android's native baseModel uses string
                    // lists too; this is purely additive for iOS readers.
                    run {
                        val bf = modalityBitfieldFromLists(
                            entry.baseModel.inputModalities,
                            entry.baseModel.outputModalities,
                        )
                        if (bf != 0) put("modalityOverride", bf)
                    }
                    entry.baseModel.inputModalities?.let {
                        put("inputModalities", JSONArray(it))
                    }
                    entry.baseModel.outputModalities?.let {
                        put("outputModalities", JSONArray(it))
                    }
                })
            }
            put("models", modelsArr)
            loadApiKey(instanceId)?.let { key ->
                put("apiKey", Base64.encodeToString(key.toByteArray(), Base64.NO_WRAP))
            }
            // Export manual OAuth bearer token (mirrors iOS `manualOAuthToken` key).
            // Stored per-instance via OAuthManager; only present for OAuth providers
            // where the user pasted a static token via the Manual Bearer Token UI.
            run {
                val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                val manual = mgr?.loadManualBearerToken()
                if (!manual.isNullOrEmpty()) {
                    put("manualOAuthToken", Base64.encodeToString(manual.toByteArray(), Base64.NO_WRAP))
                }
            }
            // [T-android-provider-export-oauth-token] (XIN 38955) Export the
            // STRUCTURED OAuth-login credential (access_token / refresh_token /
            // expire_at) saved by the OAuth login flow under a separate pref than
            // apiKey / manualOAuthToken. Previously omitted, so an OAuth-logged-in
            // Claude / OpenAI / Gemini / xAI provider exported with no usable
            // credential and imported as not-authenticated. The whole token is one
            // JSON blob on Android (OAuthManager.loadStoredTokens); JSON-encode +
            // base64 it under "oauthToken", matching iOS 703ff4bc's field name and
            // the existing apiKey / manualOAuthToken base64 encoding. Covers every
            // OAuth provider type via oauthManagerFor (not just the forInstance set).
            run {
                val mgr = oauthManagerFor(instance)
                mgr?.exportStoredTokensJson()?.let { tokenJson ->
                    put("oauthToken", Base64.encodeToString(tokenJson.toByteArray(), Base64.NO_WRAP))
                }
                // Gemini also stores the resolved account email + GCP project as
                // separate OAuth strings (mirrors iOS oauthEmail / oauthGcpProject);
                // carry them so the imported instance can call the API.
                if (instance.providerType == ProviderType.gemini && mgr != null) {
                    mgr.exportOAuthString("email")?.takeIf { it.isNotEmpty() }?.let {
                        put("oauthEmail", Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP))
                    }
                    mgr.exportOAuthString("gcp_project")?.takeIf { it.isNotEmpty() }?.let {
                        put("oauthGcpProject", Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP))
                    }
                }
            }
            instance.customBaseURL?.let { put("customBaseURL", it) }
            if (!instance.appendV1Suffix) put("appendV1Suffix", false)
            if (instance.useResponsesAPI) put("useResponsesAPI", true)
            // [T-qianchen-preset] additive optional fields — old readers decode to defaults.
            instance.keyHelpUrl?.takeIf { it.isNotBlank() }?.let { put("keyHelpUrl", it) }
            if (instance.autoResponsesFallback) put("autoResponsesFallback", true)
            // [T-provider-custom-user-agent] Additive, optional. Only written
            // when set; old/new readers without the key decode to null →
            // default UA. Field name matches iOS for cross-platform interop.
            instance.customUserAgent?.takeIf { it.isNotBlank() }?.let { put("customUserAgent", it) }
        }
        return obj.toString(2)
    }

    /**
     * Import a provider from exported JSON. Returns the new instance label on success.
     * - Auto-renames on label conflict
     * - Decodes base64-encoded API key (falls back to plain text)
     */
    fun importInstanceJSON(jsonStr: String): String? {
        ensureConfigLoaded()
        val dict = try { JSONObject(jsonStr) } catch (_: Exception) { return null }
        val providerTypeRaw = dict.optString("providerType", "").ifEmpty { return null }
        val providerType = try { ProviderType.valueOf(providerTypeRaw) } catch (_: Exception) { return null }
        val label = dict.optString("label", "").ifEmpty { return null }

        val credentialType = try {
            ProviderCredential.valueOf(dict.optString("credentialType", "apiKey"))
        } catch (_: Exception) { ProviderCredential.apiKey }

        // Resolve label conflict
        val existingLabels = _config.value.instances.map { it.label }.toSet()
        var resolvedLabel = label
        if (resolvedLabel in existingLabels) {
            var suffix = 2
            while ("$label ($suffix)" in existingLabels) suffix++
            resolvedLabel = "$label ($suffix)"
        }

        val customBaseURL = dict.optString("customBaseURL", "").ifEmpty { null }
        val appendV1 = dict.optBoolean("appendV1Suffix", true)
        val useResponsesAPI = dict.optBoolean("useResponsesAPI", false)
        // [T-qianchen-preset] additive optional fields — absent on old exports → defaults.
        val keyHelpUrl = dict.optString("keyHelpUrl", "").ifEmpty { null }
        val autoResponsesFallback = dict.optBoolean("autoResponsesFallback", false)
        // [T-provider-custom-user-agent] Additive: old exports lack the key →
        // empty → null → default UA. Field name matches iOS.
        val customUserAgent = dict.optString("customUserAgent", "").ifEmpty { null }

        val instance = ProviderInstance(
            id = java.util.UUID.randomUUID().toString(),
            label = resolvedLabel,
            providerType = providerType,
            credentialType = credentialType,
            customBaseURL = customBaseURL,
            appendV1Suffix = appendV1,
            useResponsesAPI = useResponsesAPI,
            customUserAgent = customUserAgent,
            keyHelpUrl = keyHelpUrl,
            autoResponsesFallback = autoResponsesFallback,
        )
        addInstance(instance)

        // Decode API key (base64 or plain text)
        val keyValue = dict.optString("apiKey", "").ifEmpty { null }
        if (keyValue != null) {
            val apiKey = try {
                String(Base64.decode(keyValue, Base64.NO_WRAP))
            } catch (_: Exception) {
                keyValue // plain text fallback
            }
            saveApiKey(instance.id, apiKey)
        }

        // Decode manual OAuth bearer token (mirrors iOS `manualOAuthToken`).
        // base64-encoded UTF-8 string, with plain-text fallback for older exports.
        val manualTokenValue = dict.optString("manualOAuthToken", "").ifEmpty { null }
        if (manualTokenValue != null) {
            val manualToken = try {
                String(Base64.decode(manualTokenValue, Base64.NO_WRAP))
            } catch (_: Exception) {
                manualTokenValue
            }
            val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
            mgr?.saveManualBearerToken(manualToken)
        }

        // [T-android-provider-export-oauth-token] (XIN 38955) Restore the
        // structured OAuth-login credential so the imported instance is
        // authenticated. Decode base64 → JSON → write back via the OAuth
        // manager. Mirrors iOS 703ff4bc; purely additive alongside the
        // apiKey / manualOAuthToken restore above.
        val oauthTokenValue = dict.optString("oauthToken", "").ifEmpty { null }
        if (oauthTokenValue != null) {
            val tokenJson = try {
                String(Base64.decode(oauthTokenValue, Base64.NO_WRAP))
            } catch (_: Exception) {
                oauthTokenValue // plain-text fallback for hand-edited exports
            }
            oauthManagerFor(instance)?.importStoredTokensJson(tokenJson)
        }
        // Gemini account email + GCP project (base64-encoded OAuth strings).
        if (instance.providerType == ProviderType.gemini) {
            val mgr = oauthManagerFor(instance)
            dict.optString("oauthEmail", "").ifEmpty { null }?.let { b64 ->
                val email = try { String(Base64.decode(b64, Base64.NO_WRAP)) } catch (_: Exception) { b64 }
                mgr?.importOAuthString("email", email)
            }
            dict.optString("oauthGcpProject", "").ifEmpty { null }?.let { b64 ->
                val project = try { String(Base64.decode(b64, Base64.NO_WRAP)) } catch (_: Exception) { b64 }
                mgr?.importOAuthString("gcp_project", project)
            }
        }

        // Import models (replace built-in defaults)
        val models = dict.optJSONArray("models")
        if (models != null && models.length() > 0) {
            val entries = mutableListOf<ModelEntry>()
            for (i in 0 until models.length()) {
                val m = models.getJSONObject(i)
                val modelId = m.optString("modelId", "")
                if (modelId.isEmpty()) continue
                val displayName = m.optString("displayName", modelId)
                val isCustom = m.optBoolean("isCustom", false)
                val isHidden = m.optBoolean("isHidden", false)
                val contextWindow = if (m.has("contextWindow")) m.optInt("contextWindow").takeIf { it > 0 } else null
                val maxOutputTokens = if (m.has("maxOutputTokens")) m.optInt("maxOutputTokens").takeIf { it > 0 } else null
                val supportsReasoning = if (m.has("supportsReasoning")) m.optBoolean("supportsReasoning") else null
                val interleavedReasoningField = m.optString("interleavedReasoningField", "").ifEmpty { null }
                // [T-provider-export-model-overrides] Restore baseModel
                // modalities. Android-native list fields win when present;
                // otherwise fall back to iOS's `modalityOverride` bitfield so
                // a provider exported on iOS retains its capability info.
                val (baseIn, baseOut) = readModalitiesWithBitfieldFallback(m)
                val model = LLMModel(
                    id = modelId,
                    displayName = displayName,
                    provider = providerType.displayName,
                    contextWindow = contextWindow,
                    maxOutputTokens = maxOutputTokens,
                    supportsReasoning = supportsReasoning,
                    interleavedReasoningField = interleavedReasoningField,
                    inputModalities = baseIn,
                    outputModalities = baseOut,
                )
                val overridesObj = m.optJSONObject("overrides")
                val overrides = if (overridesObj != null) {
                    // [T-provider-export-model-overrides] Read the full
                    // overrides layer. Each key is read independently — a
                    // missing key (old export, partial object) simply stays
                    // null → the field falls back to baseModel / defaults.
                    val (ovIn, ovOut) = readModalitiesWithBitfieldFallback(overridesObj)
                    ModelOverrides(
                        displayName = overridesObj.optString("displayName", "").ifEmpty { null },
                        maxOutputTokens = if (overridesObj.has("maxOutputTokens")) overridesObj.optInt("maxOutputTokens").takeIf { it > 0 } else null,
                        contextWindow = if (overridesObj.has("contextWindow")) overridesObj.optInt("contextWindow").takeIf { it > 0 } else null,
                        supportsReasoning = if (overridesObj.has("supportsReasoning")) overridesObj.optBoolean("supportsReasoning") else null,
                        inputModalities = ovIn,
                        outputModalities = ovOut,
                    )
                } else {
                    ModelOverrides()
                }
                entries.add(ModelEntry(
                    providerInstanceId = instance.id,
                    baseModel = model,
                    overrides = overrides,
                    isCustom = isCustom,
                    isHidden = isHidden,
                ))
            }
            // Import replaces built-in entries directly (not via replaceEntries which takes LLMModel list).
            // [T-android-provider-mutator-lock] Lock + working copy, like every
            // other mutator. This function is the actual provider.import /
            // Share-import path, and it read `_config.value` back — the object
            // addInstance had just PUBLISHED — and mutated its live list. That
            // is the exact race behind both device CMEs; the earlier sweep
            // missed it because the audit went by function name and this one
            // isn't called add*/update*/remove*.
            synchronized(configLock) {
                val cfg = workingCopy()
                cfg.modelEntries.removeAll { it.providerInstanceId == instance.id }
                cfg.modelEntries.addAll(entries)
                saveConfig(cfg)
            }
        }

        return resolvedLabel
    }

    // -- Modality interop with iOS ----------------------------------------
    //
    // iOS encodes ModelModality as a single Int bitfield (OptionSet rawValue);
    // Android carries inputModalities / outputModalities as bare string lists
    // ("text" / "image" / "pdf" / "audio" / "video"). The export/import path
    // writes both encodings so the wire format is portable in either
    // direction without losing fidelity:
    //   - Android → Android: the native string lists round-trip exactly.
    //   - Android → iOS:    iOS reads `modalityOverride` Int and ignores
    //                       the unknown list keys (forward-compatible).
    //   - iOS → Android:    Android prefers the native list keys when
    //                       present (Android-original export); otherwise
    //                       decodes `modalityOverride` Int back into lists.
    //
    // Bit layout constants live at file scope above the class (Kotlin
    // forbids a second companion object, and ProviderRepository already
    // has one).

    private fun modalityBitfieldFromLists(
        inputs: List<String>?,
        outputs: List<String>?,
    ): Int {
        var bits = 0
        inputs?.forEach { raw ->
            when (raw.lowercase()) {
                "text" -> bits = bits or MODALITY_BIT_TEXT_IN
                "image" -> bits = bits or MODALITY_BIT_IMG_IN
                "pdf" -> bits = bits or MODALITY_BIT_PDF_IN
                "audio" -> bits = bits or MODALITY_BIT_AUD_IN
                "video" -> bits = bits or MODALITY_BIT_VID_IN
            }
        }
        outputs?.forEach { raw ->
            when (raw.lowercase()) {
                "text" -> bits = bits or MODALITY_BIT_TEXT_OUT
                "image" -> bits = bits or MODALITY_BIT_IMG_OUT
                "audio" -> bits = bits or MODALITY_BIT_AUD_OUT
                "video" -> bits = bits or MODALITY_BIT_VID_OUT
            }
        }
        return bits
    }

    private fun modalityListsFromBitfield(bits: Int): Pair<List<String>?, List<String>?> {
        if (bits == 0) return null to null
        val inputs = buildList {
            if (bits and MODALITY_BIT_TEXT_IN != 0) add("text")
            if (bits and MODALITY_BIT_IMG_IN != 0) add("image")
            if (bits and MODALITY_BIT_PDF_IN != 0) add("pdf")
            if (bits and MODALITY_BIT_AUD_IN != 0) add("audio")
            if (bits and MODALITY_BIT_VID_IN != 0) add("video")
        }
        val outputs = buildList {
            if (bits and MODALITY_BIT_TEXT_OUT != 0) add("text")
            if (bits and MODALITY_BIT_IMG_OUT != 0) add("image")
            if (bits and MODALITY_BIT_AUD_OUT != 0) add("audio")
            if (bits and MODALITY_BIT_VID_OUT != 0) add("video")
        }
        return inputs.ifEmpty { null } to outputs.ifEmpty { null }
    }

    /**
     * Read modality info from a JSON object. Returns (inputs, outputs):
     *   - native `inputModalities` / `outputModalities` list keys take
     *     precedence (Android-original export — lossless).
     *   - if neither list is present, decode iOS's `modalityOverride`
     *     bitfield as the fallback.
     *   - if neither shape is present, returns null pair (caller treats
     *     as "no modality info" → baseModel defaults apply).
     */
    private fun readModalitiesWithBitfieldFallback(
        obj: JSONObject,
    ): Pair<List<String>?, List<String>?> {
        val nativeIn = obj.optJSONArray("inputModalities")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.takeIf { it.isNotEmpty() }
        }
        val nativeOut = obj.optJSONArray("outputModalities")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.takeIf { it.isNotEmpty() }
        }
        if (nativeIn != null || nativeOut != null) return nativeIn to nativeOut
        if (!obj.has("modalityOverride")) return null to null
        val bits = obj.optInt("modalityOverride", 0)
        return modalityListsFromBitfield(bits)
    }
}

// ── [T-opencode-sunset] pure helpers (testable without Context/Room) ─────────

private val OPENCODE_FREE_INSTANCE_IDS = setOf(
    "builtin-opencode-free-chat",
    "builtin-opencode-free-responses",
)

fun isOpenCodeFreeInstanceId(instanceId: String): Boolean =
    instanceId in OPENCODE_FREE_INSTANCE_IDS

/**
 * In-place, idempotent: disable sunset instances, hide their model entries.
 * Returns true when anything changed (caller decides to persist). Nothing is
 * deleted — the sunset stays reversible.
 */
fun applyOpenCodeSunset(config: ProviderConfig): Boolean {
    var changed = false
    config.instances.forEachIndexed { index, instance ->
        if (isOpenCodeFreeInstanceId(instance.id) && instance.isEnabled) {
            config.instances[index] = instance.copy(isEnabled = false)
            changed = true
        }
    }
    config.modelEntries.forEachIndexed { index, entry ->
        if (isOpenCodeFreeInstanceId(entry.providerInstanceId) && !entry.isHidden) {
            config.modelEntries[index] = entry.copy(isHidden = true)
            changed = true
        }
    }
    return changed
}
