package com.openminis.app.ui.chat

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.novex.domain.NovexResourceRef
import com.openminis.app.novex.domain.NovexSourceCollectionPromptReceipt
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovexLearningWorkspaceBindingTest {
    @Test fun `learning workspace belongs to persisted attachment message and rejects outside media paths`() {
        val root = Files.createTempDirectory("learning-binding").toFile()
        try {
            val ref = NovexResourceRef("novex://source-collections/${"a".repeat(64)}")
            val parts = JSONArray().put(JSONObject().put("type", "text").put("value", NovexSourceCollectionPromptReceipt.build(ref, 2)))
            listOf("safe" to "file.docx", "escape" to "../outside.docx").forEach { (id, path) ->
                parts.put(JSONObject().put("type", "mediaRef").put("value", JSONObject().put("id", id).put("relativePath", path)))
            }
            val owner = MessageEntity("attachment", "chat", "user", parts.toString(), 0, sortOrder = 0)
            val reply = MessageEntity("reply", "chat", "assistant", "[]", 1, sortOrder = 1)
            val binding = requireNotNull(novexLearningWorkspaceBinding(ref, "chat", listOf(owner, reply), root))
            assertEquals("attachment", binding.scope.writeBranchId)
            assertEquals(setOf(NovexResourceRef("novex://sources/safe")), binding.originals.keys)
            assertNull(novexLearningWorkspaceBinding(ref, "chat", listOf(reply), root))
            assertNull(novexLearningWorkspaceBinding(ref, "other", listOf(owner), root))
        } finally { root.deleteRecursively() }
    }
}
