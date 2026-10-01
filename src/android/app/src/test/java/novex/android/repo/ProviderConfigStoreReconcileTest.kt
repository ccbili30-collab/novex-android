package novex.android.repo

import android.app.Application
import android.content.Context
import novex.android.data.NovexProviderDatabase
import novex.android.data.model.LLMModel
import novex.android.data.model.ModelEntry
import novex.android.data.model.ProviderConfig
import novex.android.data.model.ProviderCredential
import novex.android.data.model.ProviderInstance
import novex.android.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * P0（审计 D2）：ProviderConfigStore 三方对账（DB / JSON 镜像 / 同步哈希）
 * 失败分支的直接测试。此前只有 ProviderRepositoryRoundTrip 顺带扫过装载
 * 快乐路径；对账决策树的每个失败出口（镜像损毁、DB 读失败、拒空配置、
 * legacy lastUsed 改写、镜像重导入落库）出 bug 都是静默丢配置级别。
 *
 * 口径对齐 P3.5a 冻结面：provider_config prefs 名与键集（config 镜像、
 * lastUsedModelEntryId）、provider.db 独立库、双写与同步哈希语义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ProviderConfigStoreReconcileTest {

    /** 与 [ProviderConfigStore.codec] 同参：测试自产镜像串必须可被其解码。 */
    private val mirrorCodec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private fun app(): Application = RuntimeEnvironment.getApplication()

    /**
     * 构造即等初始异步装载落地（awaitLoaded 是 store 的启动契约）。不等的话，
     * 迟到的装载协程可能带着陈旧快照在测试摆盘之后才 persist，把手工布置的
     * 镜像态覆写回去——CI 实跑即在 sync-hash 用例踩中（哈希被回写对齐、
     * mirror-only 丢失）。产品侧消费方走变更器纪律，无此形态。
     */
    private fun newStore(): ProviderConfigStore {
        val store = ProviderConfigStore(app())
        runBlocking { store.awaitLoaded() }
        return store
    }

    private fun prefs() = app().getSharedPreferences("provider_config", Context.MODE_PRIVATE)

    private fun instance(id: String) = ProviderInstance(
        id = id, label = id, providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
    )

    private fun entry(instanceId: String, modelId: String, uuid: String) = ModelEntry(
        providerInstanceId = instanceId,
        baseModel = LLMModel(id = modelId, displayName = modelId, provider = "OpenAI"),
        uuid = uuid,
    )

    private fun mirrorOf(config: ProviderConfig): String =
        mirrorCodec.encodeToString(ProviderConfig.serializer(), config)

    /** 每例独立起盘：重置 Room 单例（Robolectric 同类内共享 statics）+ 删 provider.db 全家 + 清镜像 prefs。 */
    @Before
    fun resetStores() {
        resetDatabaseSingleton()
        wipeProviderDbFiles()
        prefs().edit().clear().commit()
    }

    /** 连 -wal/-shm/-journal 一起删：残留 WAL 回放到新库会复现 DROP TABLE，重试场景即假失败。 */
    private fun wipeProviderDbFiles() {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val f = File(app().getDatabasePath(NovexProviderDatabase.DB_NAME).parentFile, NovexProviderDatabase.DB_NAME + suffix)
            if (f.isDirectory) f.deleteRecursively() else f.delete()
        }
    }

    private fun resetDatabaseSingleton() {
        runCatching {
            // companion 内的 @Volatile var 由 Kotlin 落成外围类上的真 static 字段。
            val instanceField = NovexProviderDatabase::class.java.getDeclaredField("instance")
            instanceField.isAccessible = true
            instanceField.set(null, null)
        }
    }

    /**
     * 模拟「DB 降级读失败/库内损坏」：先让 Room 建好正经 provider.db，再抽掉
     * 实例表但保留 room_master_table——Room 认为结构没变不会重建，首查即抛。
     * （Robolectric 的 SQLite 会把损坏字节文件静默重建，只能走这条真破坏路径。）
     */
    private fun breakProviderTables() {
        val app = app()
        kotlinx.coroutines.runBlocking {
            NovexProviderDatabase.getInstance(app).providerStoreDao().instanceCount()
        }
        NovexProviderDatabase.getInstance(app).openHelper.writableDatabase
            .execSQL("DROP TABLE provider_instances")
    }

    @Test
    fun bothStoresUnreadableRefusesEmptyConfigAndRetriesLater() {
        breakProviderTables() // DB 读失败（表被抽走）
        prefs().edit().remove("config").commit() // 镜像也不存在

        val store = newStore()
        store.ensureLoaded()

        // 拒绝捏造空配置：装载保持未完成态，内存态仍是空占位（非「已装载的空库」）。
        assertFalse(store.loaded.value)
        assertTrue(store.config.value.instances.isEmpty())
        assertTrue(store.config.value.modelEntries.isEmpty())

        // 恢复可读后，下一次访问重试成功（两库皆空 → 合法空配置）。
        resetDatabaseSingleton()
        wipeProviderDbFiles()
        val healed = newStore()
        healed.ensureLoaded()
        assertTrue(healed.loaded.value)
    }

    @Test
    fun corruptedMirrorKeepsDbRowsAsAuthority() {
        val store = newStore()
        store.save(
            ProviderConfig(
                instances = mutableListOf(instance("db-authority")),
                modelEntries = mutableListOf(entry("db-authority", "m1", "db-authority/m1")),
            ),
        )

        // 镜像写坏（半写/损坏）：同步哈希必然对不上且解码必败。
        prefs().edit().putString("config", "{\"instances\": [broken").commit()

        val reopened = newStore()
        reopened.ensureLoaded()
        assertTrue(reopened.loaded.value)
        // DB 行仍是权威——绝不让下一个变更器把空/坏镜像写满全库。
        assertEquals(listOf("db-authority"), reopened.config.value.instances.map { it.id })
        assertEquals(1, reopened.config.value.modelEntries.size)
    }

    @Test
    fun syncHashMismatchReimportsMirrorAndRewritesDb() {
        val store = newStore()
        store.save(
            ProviderConfig(
                instances = mutableListOf(instance("hash-base")),
                modelEntries = mutableListOf(entry("hash-base", "m1", "hash-base/m1")),
            ),
        )

        // 降级窗口语义：旧构建绕过 DB 直写镜像 → 哈希错位，以镜像为准重建 DB。
        val mirrorOnly = ProviderConfig(
            instances = mutableListOf(instance("hash-base"), instance("mirror-only")),
            modelEntries = mutableListOf(
                entry("hash-base", "m1", "hash-base/m1"),
                entry("mirror-only", "m2", "mirror-only/m2"),
            ),
        )
        prefs().edit().putString("config", mirrorOf(mirrorOnly)).commit()

        val reopened = newStore()
        reopened.ensureLoaded()
        assertTrue(reopened.loaded.value)
        assertTrue(reopened.config.value.instances.any { it.id == "mirror-only" })

        // 重导入必须落回 DB：抹掉镜像后第三开，仅靠 DB 也读得到 mirror-only。
        prefs().edit().remove("config").commit()
        val third = newStore()
        third.ensureLoaded()
        assertTrue(third.config.value.instances.any { it.id == "mirror-only" })
    }

    @Test
    fun daoFailureFallsBackToMirrorAndStaysLoaded() {
        breakProviderTables() // DB 读失败（降级构建打不开新表）
        val mirrorOnly = ProviderConfig(
            instances = mutableListOf(instance("mirror-life")),
            modelEntries = mutableListOf(entry("mirror-life", "m1", "legacy-uuid")),
        )
        prefs().edit().putString("config", mirrorOf(mirrorOnly)).commit()

        val store = newStore()
        store.ensureLoaded()

        // 镜像救命：即使「镜像→DB 回写」也失败（库仍打不开），返回解析值，
        // 装载完成、老构建看得到配置。
        assertTrue(store.loaded.value)
        assertTrue(store.config.value.instances.any { it.id == "mirror-life" })
    }

    @Test
    fun legacyLastUsedEntryRewrittenToCompositeIdOnMirrorImport() {
        // 老格式 lastUsedModelEntryId（随机 uuid）→ 复合键 "{instanceId}/{modelId}"。
        val config = ProviderConfig(
            instances = mutableListOf(instance("legacy-inst")),
            modelEntries = mutableListOf(entry("legacy-inst", "m1", "legacy-uuid")),
        )
        prefs().edit()
            .putString("config", mirrorOf(config))
            .putString(ProviderConfigStore.KEY_LAST_USED_ENTRY, "legacy-uuid")
            .commit()

        val store = newStore()
        store.ensureLoaded()

        assertEquals(
            "legacy-inst/m1",
            prefs().getString(ProviderConfigStore.KEY_LAST_USED_ENTRY, null),
        )
    }
}
