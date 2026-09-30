package com.openminis.app.data.repository

import android.app.Application
import novex.android.data.model.ModelGroup
import novex.android.data.model.ProviderCredential
import novex.android.data.model.ProviderInstance
import novex.android.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P3.5a 重写后的供应商配置中枢关键回路守护：
 *  - 内置模型表只播种官方端点 / OAuth，第三方兼容端不播；
 *  - 导出 / 导入 JSON 往返（标签撞车改名、模型与密钥随行）；
 *  - 最近使用条目的可见性解析；
 *  - 删实例的级联清理（分组成员、agent-loop 直钉）；
 *  - 实例排序的未知 id 丢弃与未提及补尾。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ProviderRepositoryRoundTripTest {

    private fun newRepository(): ProviderRepository = ProviderRepository(RuntimeEnvironment.getApplication())

    private fun officialOpenAi(id: String, label: String) = ProviderInstance(
        id = id, label = label, providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
    )

    @Test
    fun builtInSeedOnlyForOfficialEndpoints() {
        val repo = newRepository()
        repo.addInstance(officialOpenAi("official-1", "官方 OpenAI"))
        repo.addInstance(
            ProviderInstance(
                id = "relay-1", label = "第三方中转", providerType = ProviderType.openAI,
                credentialType = ProviderCredential.apiKey,
                customBaseURL = "https://relay.example.com/v1",
            ),
        )

        assertTrue(repo.entriesFor("official-1").isNotEmpty())
        // 第三方 OpenAI 兼容端绝不播种：上游没有那批 GPT id，等 /v1/models 拉取。
        assertTrue(repo.entriesFor("relay-1").isEmpty())
    }

    @Test
    fun exportImportRoundTripCarriesModelsAndSecret() {
        val repo = newRepository()
        repo.addInstance(officialOpenAi("export-src", "可迁移实例"))
        repo.saveApiKey("export-src", "sk-round-trip")
        val exported = repo.exportInstanceJSON("export-src")
        assertNotNull(exported)

        val importedLabel = repo.importInstanceJSON(exported!!)
        assertNotNull(importedLabel)
        // 标签撞车 → 自动加后缀。
        assertTrue(importedLabel!!.startsWith("可迁移实例"))
        val imported = repo.instances.first { it.label == importedLabel }
        assertTrue(repo.entriesFor(imported.id).isNotEmpty())
        assertEquals("sk-round-trip", repo.loadApiKey(imported.id))
    }

    @Test
    fun lastUsedEntryMustStayVisibleToResolve() {
        val repo = newRepository()
        repo.addInstance(officialOpenAi("last-used", "最近使用"))
        val entry = repo.visibleEntries("last-used").first()
        repo.lastUsedEntryId = entry.id
        assertEquals(entry.id, repo.lastUsedVisibleEntry()?.id)

        repo.lastUsedEntryId = "ghost-entry"
        assertNull(repo.lastUsedVisibleEntry())
        repo.lastUsedEntryId = null
        assertNull(repo.lastUsedVisibleEntry())
    }

    @Test
    fun removeInstancePrunesGroupMembersAndLoopPins() {
        val repo = newRepository()
        repo.addInstance(officialOpenAi("remove-a", "实例甲"))
        repo.addInstance(officialOpenAi("remove-b", "实例乙"))
        val aEntry = repo.entriesFor("remove-a").first()
        val bEntry = repo.entriesFor("remove-b").first()
        repo.addGroup(
            ModelGroup(id = "mixed-group", name = "混合组", memberEntryIds = mutableListOf(aEntry.id, bEntry.id)),
        )
        repo.setAgentLoopEntryIds(listOf(aEntry.id, bEntry.id))

        repo.removeInstance("remove-a")

        assertNull(repo.instance("remove-a"))
        // 分组保留，但只含幸存成员。
        val group = repo.group("mixed-group")!!
        assertEquals(listOf(bEntry.id), group.memberEntryIds)
        // agent-loop 直钉同步清理。
        assertEquals(listOf(bEntry.id), repo.config.value.agentLoopModelEntryIds)
        // 密钥随实例删除。
        assertNull(repo.loadApiKey("remove-a"))
    }

    @Test
    fun reorderDropsUnknownIdsAndAppendsUnmentioned() {
        val repo = newRepository()
        repo.addInstance(officialOpenAi("order-x", "甲"))
        repo.addInstance(officialOpenAi("order-y", "乙"))
        repo.addInstance(officialOpenAi("order-z", "丙"))

        repo.reorderInstances(listOf("order-z", "order-ghost", "order-x"))

        // 只看本用例种下的三个实例（Robolectric 同类内共享 filesDir，库里有别用例的残留）。
        assertEquals(
            listOf("order-z", "order-x", "order-y"),
            repo.instances.map { it.id }.filter { it.startsWith("order-") },
        )
    }
}
