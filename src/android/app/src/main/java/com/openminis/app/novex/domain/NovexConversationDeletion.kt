package com.openminis.app.novex.domain

import com.openminis.app.data.creative.WorkspaceCreativeArtifactBridge
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One lifecycle for deletion from the home, chat menu and grouped session list. */
class NovexConversationDeletion(
    private val chats: ChatRepository,
    private val workspace: NovexWorkspace,
    private val files: FileNovexConversationWorkspaceStore,
    private val artifacts: WorkspaceCreativeArtifactBridge,
    private val execution: NovexToolExecution,
    private val stopRuntime: suspend (String) -> Unit,
    private val clearStatus: (String) -> Unit = {},
    private val finishRuntime: suspend (String, Boolean) -> Unit = { _, _ -> },
) {
    private val lock = Mutex()

    suspend fun delete(conversationId: String) = lock.withLock {
        var deleted = false
        try {
        stopRuntime(conversationId)
        // Inventory each retained branch separately: sibling outputs with the same filename
        // are distinct works and must not be hidden by active-path overlay resolution.
        files.inspectConversation(conversationId).forEach { artifacts.reconcile(it.scope) }
        execution.closeConversation(conversationId)
        workspace.conversationDrafts(conversationId)?.pendingWrites.orEmpty().forEach {
            workspace.apply(NovexCommand.ReleaseConversationDraftWrite(conversationId, it.id))
        }
        workspace.apply(NovexCommand.FinalizeConversationDrafts(conversationId, emptySet()))
        chats.deleteSession(conversationId)
        deleted = true
        clearStatus(conversationId)
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { finishRuntime(conversationId, deleted) }
        }
    }
}
