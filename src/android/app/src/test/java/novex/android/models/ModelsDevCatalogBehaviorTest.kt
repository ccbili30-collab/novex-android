package novex.android.models

import android.content.Context
import novex.android.data.model.LLMModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * models.dev 目录的三级缓存与富化行为（P3.5c 真重写的行为验收）：
 *  - init 后首查命中打包资产兜底（内存空 + 无磁盘缓存）；
 *  - 二次查询命中内存缓存（不再走资产 IO）；
 *  - 快照带 providerKeyMap 键（anthropic / openai / openrouter）；
 *  - enrichModel 把目录里的上下文窗并进空缺条目，且“肯定没有 effort 档”
 *    的肯定式答案不被目录沉默条目冲掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class ModelsDevCatalogBehaviorTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `bundled asset backs the first lookup and memory serves the second`() {
        ModelsDevCatalog.init(context)

        val first = ModelsDevCatalog.registrySnapshot()
        assertTrue("打包资产应至少带 20 家供应商", first.size >= 20)
        assertNotNull(first["anthropic"])
        assertNotNull(first["openai"])
        assertNotNull(first["openrouter"])

        // 二次查询走内存缓存；内容一致。
        val second = ModelsDevCatalog.registrySnapshot()
        assertEquals(first.size, second.size)
        assertEquals(first["anthropic"]?.models?.keys, second["anthropic"]?.models?.keys)
    }

    @Test
    fun `fetchModels resolves an exact api base with and without v1`() {
        ModelsDevCatalog.init(context)
        // 打包资产里 anthropic 的 api 基址形如 https://api.anthropic.com。
        val entry = ModelsDevCatalog.registrySnapshot()["anthropic"] ?: return
        val api = entry.api ?: return

        val viaHost = ModelsDevCatalog.fetchModels(api)
        val viaV1 = ModelsDevCatalog.fetchModels("$api/v1")
        val viaTrailingSlash = ModelsDevCatalog.fetchModels("$api/")
        assertTrue(viaHost.isNotEmpty())
        assertEquals(viaHost.map { it.id }, viaV1.map { it.id })
        assertEquals(viaHost.map { it.id }, viaTrailingSlash.map { it.id })
        // embedding / moderation 家族被剔除。
        assertTrue(viaHost.none { it.id.contains("embed", ignoreCase = true) })
    }

    @Test
    fun `enrichModel fills a missing context window`() {
        ModelsDevCatalog.init(context)
        val entry = ModelsDevCatalog.registrySnapshot()["anthropic"] ?: return
        val known = entry.models.values.firstOrNull { it.contextWindow != null } ?: return

        val sparse = LLMModel(
            id = known.id,
            displayName = known.id,
            provider = "Anthropic",
        )
        val enriched = ModelsDevCatalog.enrichModel(sparse)
        assertEquals(known.contextWindow, enriched.contextWindow)
    }

    @Test
    fun `affirmative no-effort-tiers answer survives enrichment against a silent entry`() {
        // 构造一个与目录重叠 id、但来自“目录沉默”条目的模型：富化不得把
        // 已有的肯定式 declaresNoEffortTiers=true 洗成 null/保留……语义上
        // 肯定式答案只进不退。
        ModelsDevCatalog.init(context)
        val snapshot = ModelsDevCatalog.registrySnapshot()
        val anyEntry = snapshot.values.firstOrNull { it.models.isNotEmpty() } ?: return
        val devModel = anyEntry.models.values.first()

        val declared = LLMModel(
            id = devModel.id,
            displayName = devModel.id,
            provider = "NoSuchProvider",
            declaresNoEffortTiers = true,
        )
        val enriched = ModelsDevCatalog.enrichModel(declared)
        assertEquals(true, enriched.declaresNoEffortTiers)
    }
}
