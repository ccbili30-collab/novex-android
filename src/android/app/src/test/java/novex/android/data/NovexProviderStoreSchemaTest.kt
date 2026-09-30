package novex.android.data

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import novex.android.data.model.FallbackStrategy
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
import novex.android.data.model.ThinkingLevel
import novex.android.data.provider.ProviderStoreDao
import novex.android.data.provider.toConfig
import novex.android.data.provider.toRows
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/*
 * Pins the provider settings database: the persisted table shapes
 * (frozen), and the row-level round trip for provider configuration and
 * thinking rules through the codec.
 */

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NovexProviderStoreSchemaTest {
    private lateinit var database: NovexProviderDatabase
    private lateinit var dao: ProviderStoreDao

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), NovexProviderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.providerStoreDao()
    }

    @After
    fun tearDown() = database.close()

    private fun columnNames(table: String): List<String> =
        database.openHelper.readableDatabase
            .query("PRAGMA table_info($table)").use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(nameColumn))
                }
            }

    @Test
    fun databaseVersionAndFileRemainPinned() {
        assertEquals(5, database.openHelper.readableDatabase.version)
        assertEquals("provider.db", NovexProviderDatabase.DB_NAME)
    }

    @Test
    fun providerTablesKeepFrozenColumns() {
        assertEquals(
            listOf(
                "id", "label", "provider_type", "credential_type", "custom_base_url", "append_v1_suffix",
                "use_responses_api", "azure_mode", "image_endpoint_mode", "image_endpoint_resolved",
                "custom_user_agent", "key_help_url", "auto_responses_fallback", "is_enabled",
                "sort_order", "created_at",
            ),
            columnNames("provider_instances"),
        )
        assertEquals(
            listOf(
                "id", "provider_instance_id", "label", "scope_kind", "scope_pattern",
                "wire_format_json", "reasoning_echo_json", "sort_order",
            ),
            columnNames("provider_thinking_rules"),
        )
        assertEquals(listOf("key", "value"), columnNames("provider_config_meta"))
        assertEquals(listOf("kind", "target_id", "sort_order"), columnNames("provider_agent_loop_ids"))
    }

    @Test
    fun configRoundTripsThroughRowsWithCompositeEntryIds() = runBlocking {
        val config = ProviderConfig(
            instances = mutableListOf(
                ProviderInstance(
                    id = "prov", label = "中转", providerType = ProviderType.openAI,
                    credentialType = ProviderCredential.apiKey, isEnabled = true, createdAt = 7,
                    customBaseURL = "https://relay.example", azureMode = true,
                    imageEndpointMode = ImageEndpointMode.chatCompletions,
                ),
                ProviderInstance(id = "off", label = "停用", providerType = ProviderType.anthropic,
                    credentialType = ProviderCredential.apiKey, isEnabled = false, createdAt = 8),
            ),
            modelEntries = mutableListOf(
                ModelEntry(
                    providerInstanceId = "prov",
                    baseModel = LLMModel("glm-5.3", "GLM 5.3", "relay"),
                    overrides = ModelOverrides(displayName = "自定名"),
                    uuid = "legacy-uuid",
                ),
            ),
            modelGroups = mutableListOf(
                ModelGroup(
                    id = "g1", name = "主力",
                    memberEntryIds = mutableListOf("legacy-uuid"),
                    strategy = RoutingStrategy.fallback,
                    fallbackStrategy = FallbackStrategy.always,
                    defaultThinkingLevel = ThinkingLevel.HIGH,
                ),
            ),
            defaultPrimaryGroupId = "g1",
            voiceOutputGroupId = "g1",
        )

        val snapshot = config.toRows(json, jsonSyncHash = "hash-1")
        dao.overwriteConfigTables(
            instances = snapshot.instanceRows,
            models = snapshot.modelRows,
            groups = snapshot.groupRows,
            loopTargets = snapshot.loopRows,
            meta = snapshot.metaRows,
        )

        // Persisted shape: composite entry id and translated group member.
        assertEquals("prov/glm-5.3", dao.modelRows().single().id)
        assertEquals(
            listOf("prov/glm-5.3"),
            json.decodeFromString<List<String>>(dao.groupRows().single().memberEntryIdsJson),
        )

        val reread = novex.android.data.provider.ProviderRowsSnapshot(
            instanceRows = dao.instanceRows(),
            modelRows = dao.modelRows(),
            groupRows = dao.groupRows(),
            loopRows = dao.agentLoopRows(),
            metaRows = dao.metaRows(),
        ).toConfig(json)

        assertEquals(2, reread.instances.size)
        assertTrue(reread.instances.first().azureMode)
        assertEquals(ImageEndpointMode.chatCompletions, reread.instances.first().imageEndpointMode)
        assertEquals("自定名", reread.modelEntries.single().model.displayName)
        assertEquals("prov/glm-5.3", reread.modelEntries.single().uuid)
        assertEquals(ThinkingLevel.HIGH, reread.modelGroups.single().defaultThinkingLevel)
        assertEquals(listOf("prov/glm-5.3"), reread.modelGroups.single().memberEntryIds)
        assertEquals("g1", reread.defaultPrimaryGroupId)
        assertEquals("g1", reread.voiceOutputGroupId)
        assertEquals("hash-1", reread.let { dao.metaRows().first { row -> row.key == "json_sync_hash" }.value })
    }
    // [P3.3 裁军] thinkingRulesRoundTripInOrderAndReorderAtomically（自定义
    // 思考规则的写/读/重排 DAO 回路测试）随 CUSTOM 写路径退役删除；
    // provider_thinking_rules 表与列由本文件的 schema 钉面继续保留。
}
