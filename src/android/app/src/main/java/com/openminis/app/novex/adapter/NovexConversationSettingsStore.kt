package com.openminis.app.novex.adapter

import com.openminis.app.data.ConversationSettingsSnapshot
import com.openminis.app.novex.domain.NovexConversationConfigurationCodec
import com.openminis.app.novex.domain.NovexConversationConfigurationSnapshot
import com.openminis.app.novex.domain.NovexManagementTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One commit boundary for settings, identity, gameplay and tool-created management targets.
 * Mutation and source capture run inside the same database transaction as the settings write.
 * The live projection is installed only after commit, before a waiting editor can continue. */
class NovexConversationSettingsStore(
    private val mutex: Mutex,
    private val sessionId: suspend () -> String,
    private val current: () -> ConversationSettingsSnapshot,
    private val transaction: NovexManagementTransaction,
    private val adopt: suspend (NovexConversationConfigurationSnapshot) -> NovexConversationConfigurationSnapshot,
    private val write: suspend (String, ConversationSettingsSnapshot) -> Unit,
    private val install: suspend (ConversationSettingsSnapshot) -> Unit,
) {
    suspend fun update(
        settings: ConversationSettingsSnapshot? = null,
        expectedConfigurationJson: String? = null,
        captureSources: Boolean = false,
        change: suspend (NovexConversationConfigurationSnapshot) -> NovexConversationConfigurationSnapshot = { it },
    ): NovexConversationConfigurationSnapshot = mutex.withLock {
        val before = current()
        val id = sessionId().also { require(it.isNotBlank()) { "对话尚未保存" } }
        val requested = settings ?: current()
        var committed = requested
        var configuration = NovexConversationConfigurationCodec.decode(requested.novexConfigurationJson, id)
        if (expectedConfigurationJson != null && expectedConfigurationJson != before.novexConfigurationJson) {
            // [T-settings-save-merge] 用户 2026-09-15 报告：对话输出期间在设置页点
            // 保存必失败——AI 每调一次状态/注册工具都会改写配置 JSON，而旧守卫对
            // 整份 JSON 做相等比对。设置保存影响的是下一轮，不该被本轮流水的运
            // 行态写入挡住。改为字段归并：运行态区块（状态快照、工作记录、局次
            // 游戏绑定）永远取当前最新；页面可编辑区块若在编辑期间被别处改动，
            // 才是真正需要人介入的冲突。
            configuration = mergeScreenSaveOverRuntime(
                expected = NovexConversationConfigurationCodec.decode(expectedConfigurationJson, id),
                current = NovexConversationConfigurationCodec.decode(before.novexConfigurationJson, id),
                requested = configuration,
            )
        }
        transaction.run {
            configuration = change(configuration).copy(conversationId = id)
            if (captureSources) configuration = adopt(configuration)
            committed = requested.copy(novexConfigurationJson = NovexConversationConfigurationCodec.encode(configuration))
            if (committed != before) write(id, committed)
        }
        // A cancellation after durable commit must not leave the current conversation using old settings.
        if (committed != before) withContext(NonCancellable) { install(committed) }
        configuration
    }

    /** Runtime-owned sections: churn every turn via state/control tools; the freshest copy always wins. */
    private fun mergeScreenSaveOverRuntime(
        expected: NovexConversationConfigurationSnapshot,
        current: NovexConversationConfigurationSnapshot,
        requested: NovexConversationConfigurationSnapshot,
    ): NovexConversationConfigurationSnapshot {
        val conflict = expected.copy(
            playthroughStates = current.playthroughStates,
            completedPlaythroughs = current.completedPlaythroughs,
            controls = current.controls,
            activeInteractiveFiction = current.activeInteractiveFiction,
            activePlaythroughId = current.activePlaythroughId,
            preGameAnswerIdentity = current.preGameAnswerIdentity,
            preGamePlayerIdentity = current.preGamePlayerIdentity,
            preGameAdoptedIdentity = current.preGameAdoptedIdentity,
        )
        if (conflict != current) {
            throw IllegalArgumentException("会话配置或本局状态已在编辑期间更新。请重新打开对话编辑后调整，避免覆盖新内容。")
        }
        return requested.copy(
            playthroughStates = current.playthroughStates,
            completedPlaythroughs = current.completedPlaythroughs,
            controls = current.controls,
            activeInteractiveFiction = current.activeInteractiveFiction,
            activePlaythroughId = current.activePlaythroughId,
            preGameAnswerIdentity = current.preGameAnswerIdentity,
            preGamePlayerIdentity = current.preGamePlayerIdentity,
            preGameAdoptedIdentity = current.preGameAdoptedIdentity,
        )
    }
}
