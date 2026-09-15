package com.openminis.app.novex.adapter

import com.openminis.app.data.ConversationSettingsSnapshot
import com.openminis.app.novex.domain.NovexConversationConfigurationCodec
import com.openminis.app.novex.domain.NovexConversationConfigurationSnapshot
import com.openminis.app.novex.domain.PlaythroughState
import com.openminis.app.novex.domain.PlaythroughValue
import kotlinx.coroutines.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-settings-save-merge] 保存撞锁改字段归并（用户 2026-09-15 报告：对话输出
 * 期间在设置页点保存必失败——AI 每调一次状态工具都改写配置 JSON，旧守卫对整
 * 份 JSON 做相等比对）。归并规则：运行态区块（状态快照等）永远取当前最新，
 * 页面可编辑区块照请求写入；只有页面可编辑区块被别处改过才弹冲突。
 */
class NovexConversationSettingsMergeTest {
    private fun configJson(config: NovexConversationConfigurationSnapshot): String =
        NovexConversationConfigurationCodec.encode(config)

    private fun snapshot(config: NovexConversationConfigurationSnapshot): ConversationSettingsSnapshot =
        ConversationSettingsSnapshot(conversationPrompt = "p", novexConfigurationJson = configJson(config))

    private fun store(
        currentSnapshot: () -> ConversationSettingsSnapshot,
        written: MutableList<ConversationSettingsSnapshot> = mutableListOf(),
    ) = NovexConversationSettingsStore(
        mutex = Mutex(),
        sessionId = { "chat-1" },
        current = currentSnapshot,
        transaction = { block -> block() },
        adopt = { it },
        write = { _, value -> written += value },
        install = { },
    )

    @Test fun runtimeChurnDuringEditMergesInsteadOfRejecting() = runTest {
        val base = NovexConversationConfigurationSnapshot("chat-1")
        // 设置页打开时的快照（expected）。
        val expected = base
        // 编辑期间 AI 更新了本局状态（输出中的状态工具写回）。
        val current = base.copy(
            playthroughStates = mapOf("t9" to PlaythroughState("t9", mapOf("hp" to PlaythroughValue.Number(72.0, 100.0)))),
        )
        var latest = snapshot(current)
        val written = mutableListOf<ConversationSettingsSnapshot>()
        val result = store({ latest }, written).update(
            settings = snapshot(expected.copy(contextLimitTokens = 999)),
            expectedConfigurationJson = configJson(expected),
        )
        // 保存成功：运行态取最新（hp 快照保留），页面字段（上下文上限）生效。
        assertEquals(999, result.contextLimitTokens)
        assertEquals(72.0, (result.playthroughStates["t9"]!!.values["hp"] as PlaythroughValue.Number).value, 0.0)
        assertTrue(written.isNotEmpty())
        latest = written.last()
    }

    @Test fun screenFieldChangedElsewhereDuringEditStillConflicts() = runTest {
        val base = NovexConversationConfigurationSnapshot("chat-1")
        // 编辑期间“页面可编辑区块”被别处改动（例如另一入口改了执行模式）——
        // 这才是需要人介入的真冲突，保留原提示。
        val current = base.copy(executionMode = com.openminis.app.novex.domain.NovexExecutionMode.APPROVAL)
        try {
            store({ snapshot(current) }).update(
                settings = snapshot(base),
                expectedConfigurationJson = configJson(base),
            )
            fail("预期因可编辑字段冲突抛出 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("已在编辑期间更新"))
        }
    }
}
