package com.openminis.app.ui.chat

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.novex.domain.NovexConversationWorkspaceScope
import com.openminis.app.novex.domain.NovexResourceRef
import java.io.File
import org.json.JSONArray

internal data class NovexLearningWorkspaceBinding(
    val scope: NovexConversationWorkspaceScope,
    val originals: Map<NovexResourceRef, File>,
)

/** Bind generated files to the saved attachment message, never the root or whichever reply is selected later. */
internal fun novexLearningWorkspaceBinding(collectionRef: NovexResourceRef, conversationId: String,
    activeMessages: List<MessageEntity>, mediaRoot: File): NovexLearningWorkspaceBinding? {
    val owner = activeMessages.firstOrNull { message ->
        message.sessionId == conversationId && message.role == "user" && runCatching {
            val parts = JSONArray(message.partsJson)
            (0 until parts.length()).any { index ->
                val part = parts.getJSONObject(index)
                part.optString("type") == "text" && collectionRef.value in novexSourceCollectionRefsInPrompt(part.optString("value"))
            }
        }.getOrDefault(false)
    } ?: return null
    val parts = JSONArray(owner.partsJson)
    val canonicalRoot = mediaRoot.canonicalFile
    val originals = (0 until parts.length()).mapNotNull { index ->
        val part = parts.getJSONObject(index)
        val value = part.takeIf { it.optString("type") == "mediaRef" }?.optJSONObject("value") ?: return@mapNotNull null
        val id = value.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val path = value.optString("relativePath").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val file = File(canonicalRoot, path).canonicalFile
        if (!file.path.startsWith(canonicalRoot.path + File.separator)) return@mapNotNull null
        NovexResourceRef("novex://sources/$id") to file
    }.toMap()
    return NovexLearningWorkspaceBinding(NovexConversationWorkspaceScope(conversationId,
        activeMessages.filter { it.sessionId == conversationId }.map { it.id }, owner.id), originals)
}
