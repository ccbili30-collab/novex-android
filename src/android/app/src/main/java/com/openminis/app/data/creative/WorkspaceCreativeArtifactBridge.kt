package com.openminis.app.data.creative

import com.openminis.app.novex.domain.CreativeArtifactKind
import com.openminis.app.novex.domain.CreativeArtifactOrigin
import com.openminis.app.novex.domain.NovexConversationWorkspaceScope
import com.openminis.app.novex.domain.NovexConversationWorkspaceStore
import com.openminis.app.novex.domain.NovexWorkspaceEntry
import com.openminis.app.novex.domain.NovexWorkspaceFileRef
import java.security.MessageDigest
import org.json.JSONObject

/** Registers real workspace bytes; the durable file index also repairs interrupted registration. */
class WorkspaceCreativeArtifactBridge(
    private val store: NovexConversationWorkspaceStore,
    private val artifacts: CreativeArtifactRepository,
) {
    suspend fun capture(toolName: String, resultJson: String, scope: NovexConversationWorkspaceScope): CreativeArtifactRecord? {
        if (toolName !in setOf("workspace_write", "workspace_edit", "workspace_compute")) return null
        val result = JSONObject(resultJson)
        if (!result.optBoolean("ok") || result.optString("code") !in setOf("workspace.written", "workspace.edited", "workspace.computed")) return null
        val data = result.optJSONObject("data") ?: return null
        val ref = data.optString("workspace_ref").takeIf { it.isNotBlank() }?.let(NovexWorkspaceFileRef::parse) ?: return null
        val entry = requireNotNull(store.find(scope, ref)) { "工作空间成果已不存在或不属于当前可见范围" }
        require(data.getString("sha256") == entry.sha256) { "工作空间成果已经变化，稍后从文件索引重新登记" }
        return register(entry, scope, existing(scope)[ref.value])
    }

    suspend fun reconcile(scope: NovexConversationWorkspaceScope) {
        val previous = existing(scope)
        store.inspect(scope).entries.filter { it.workspaceRef.area.modelWritable }.forEach { entry ->
            register(entry, scope, previous[entry.workspaceRef.value])
        }
    }

    private suspend fun existing(scope: NovexConversationWorkspaceScope) =
        artifacts.list(CreativeArtifactQuery(conversationId = scope.conversationId, includeTrashed = true)).associateBy { it.sourcePath }

    private suspend fun register(entry: NovexWorkspaceEntry, scope: NovexConversationWorkspaceScope, previous: CreativeArtifactRecord?): CreativeArtifactRecord {
        if (previous?.revisions?.maxByOrNull { it.number }?.contentHash == entry.sha256) return previous
        val bytes = store.readBytes(scope, entry.workspaceRef)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(hash == entry.sha256) { "工作空间文件在登记期间发生变化，原文件仍保留" }
        val name = entry.workspaceRef.relativePath.substringAfterLast('/')
        val kind = when {
            entry.mimeType.startsWith("image/") -> CreativeArtifactKind.IMAGE
            name.substringAfterLast('.') in setOf("novexworld", "novexcharacter", "novexgame") -> CreativeArtifactKind.CARD_ARCHIVE
            entry.mimeType.startsWith("text/") || entry.mimeType == "application/json" -> CreativeArtifactKind.DOCUMENT
            else -> CreativeArtifactKind.OTHER
        }
        return artifacts.capture(name, kind, bytes, entry.mimeType,
            CreativeArtifactOrigin(entry.provenance.conversationId, entry.provenance.branchId,
                entry.provenance.messageId, entry.provenance.toolCallId),
            sourcePath = entry.workspaceRef.value)
    }
}
