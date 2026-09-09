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
        require(expectedConfigurationJson == null || expectedConfigurationJson == before.novexConfigurationJson) {
            "会话配置或本局状态已在编辑期间更新。请重新打开对话编辑后调整，避免覆盖新内容。"
        }
        val id = sessionId().also { require(it.isNotBlank()) { "对话尚未保存" } }
        val requested = settings ?: current()
        var committed = requested
        var configuration = NovexConversationConfigurationCodec.decode(requested.novexConfigurationJson, id)
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
}
