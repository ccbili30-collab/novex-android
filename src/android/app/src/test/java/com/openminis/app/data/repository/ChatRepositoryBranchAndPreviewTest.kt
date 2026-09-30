package com.openminis.app.data.repository

import android.app.Application
import androidx.room.Room
import java.io.File
import novex.android.data.NovexMainDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONArray
import org.json.JSONObject

/**
 * P3.5a 重写后的会话仓库关键回路守护（Robolectric + 磁盘 Room）：
 *  - 会话预览跟随活跃路径与工具调用摘要；
 *  - 超长 parts_json 的写入侧截断（Issue #17 闸门）；
 *  - 分支分裂 / 切换 / 删除的活跃路径读写；
 *  - 助手回合正式化（execution 标记清除）。
 * 侧边对话的创建/上限/级联另有 NovexSideConversationForkTest 全量钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ChatRepositoryBranchAndPreviewTest {

    @get:Rule val files = TemporaryFolder()

    private fun open(): Pair<NovexMainDatabase, ChatRepository> {
        val path = File(files.root, "chat-${System.nanoTime()}.db").absolutePath
        val db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), NovexMainDatabase::class.java, path)
            .allowMainThreadQueries().build()
        return db to ChatRepository(db.chatDao())
    }

    private fun textPart(value: String) = """[{"type":"text","value":"$value"}]"""

    @Test
    fun toolCallTurnDrivesSessionPreview() = kotlinx.coroutines.runBlocking {
        val (db, repo) = open()
        try {
            val session = repo.createSession("m", title = "预览实验")
            val toolUse = JSONArray().put(
                JSONObject().put("type", "toolUse").put(
                    "value",
                    JSONObject().put("name", "browser_use")
                        .put("input", JSONObject().put("action", "open").put("url", "https://example.com").toString()),
                ),
            ).toString()
            repo.updateSessionPreview(session.id, toolUse)
            assertEquals("open https://example.com", repo.sessionById(session.id)!!.lastMessage)

            // 无可提取内容时不覆盖既有预览。
            repo.updateSessionPreview(session.id, """[{"type":"text","value":""}]""")
            assertEquals("open https://example.com", repo.sessionById(session.id)!!.lastMessage)
        } finally {
            db.close()
        }
    }

    @Test
    fun oversizedPartsJsonIsTruncatedIntoSingleTextPart() = kotlinx.coroutines.runBlocking {
        val (db, repo) = open()
        try {
            val session = repo.createSession("m", title = "截断实验")
            val bomb = textPart("x".repeat(ChatRepository.MAX_MESSAGE_PARTS_JSON_LENGTH + 50_000))
            val row = repo.appendMessage(session.id, "user", bomb)

            assertTrue(row.partsJson.length <= ChatRepository.MAX_MESSAGE_PARTS_JSON_LENGTH + 200)
            val parsed = JSONArray(row.partsJson)
            assertEquals(1, parsed.length())
            assertEquals("text", parsed.getJSONObject(0).optString("type"))
            assertTrue(parsed.getJSONObject(0).optString("value").contains("Content truncated at"))
        } finally {
            db.close()
        }
    }

    @Test
    fun forkSwitchDeleteKeepActivePathConsistent() = kotlinx.coroutines.runBlocking {
        val (db, repo) = open()
        try {
            val session = repo.createSession("m", title = "分支实验")
            val user = repo.appendMessage(session.id, "user", textPart("问题"))
            val first = repo.appendMessage(session.id, "assistant", textPart("答案A"))

            // 从同一用户回合分裂出第二个回复：重入后活跃路径停在用户行。
            val forked = repo.forkReplyFrom(session.id, user.id)
            assertEquals(user.id, forked.graph.activeLeafId)
            assertEquals(listOf(user.id), forked.activeMessages.map { it.id })
            val second = repo.appendMessage(session.id, "assistant", textPart("答案B"))
            assertEquals("答案B", previewTail(repo, session.id))

            // 切回旧分支：活跃路径尾变回 A。
            val switched = repo.switchMessageSibling(session.id, second.id, -1)
            assertEquals(listOf("问题", "答案A"), switched.activeMessages.mapNotNull { body(repo, it) })

            // 删除 B 分支：返回被删行，路径仍完整。
            val deletion = repo.deleteMessageBranch(session.id, second.id)
            assertEquals(listOf(second.id), deletion.deletedMessages.map { it.id })
            assertNull(repo.findMessageById(second.id))
            assertEquals(listOf("问题", "答案A"), deletion.conversation.activeMessages.mapNotNull { body(repo, it) })
            assertFalse(first.id == second.id)
        } finally {
            db.close()
        }
    }

    @Test
    fun assistantFormalizationClearsExecutionFlags() = kotlinx.coroutines.runBlocking {
        val (db, repo) = open()
        try {
            val session = repo.createSession("m", title = "正式化实验")
            val parts = JSONArray().put(
                JSONObject().put("type", "text").put("value", "草稿回复").put("execution", true),
            ).toString()
            val row = repo.appendMessage(session.id, "assistant", parts)

            repo.markAssistantTextFormal(row.id)

            val stored = repo.findMessageById(row.id)!!
            val flag = JSONArray(stored.partsJson).getJSONObject(0).optBoolean("execution", false)
            assertEquals(false, flag)
            assertEquals("草稿回复", JSONArray(stored.partsJson).getJSONObject(0).optString("value"))
        } finally {
            db.close()
        }
    }

    @Test
    fun previewRefreshFromHistoryClearsWhenNothingRemains() = kotlinx.coroutines.runBlocking {
        val (db, repo) = open()
        try {
            val session = repo.createSession("m", title = "预览重算")
            repo.appendMessage(session.id, "user", textPart("第一条"))
            assertEquals("第一条", repo.sessionById(session.id)!!.lastMessage)

            repo.refreshSessionPreviewFromHistory(session.id, remainingMessages = emptyList())
            assertNull(repo.sessionById(session.id)!!.lastMessage)
        } finally {
            db.close()
        }
    }

    private suspend fun previewTail(repo: ChatRepository, sessionId: String): String? =
        repo.loadActiveMessages(sessionId).lastOrNull()?.let { row ->
            JSONArray(row.partsJson).optJSONObject(0)?.optString("value")
        }

    private fun body(repo: ChatRepository, row: novex.android.data.chat.MessageRow): String? =
        JSONArray(row.partsJson).optJSONObject(0)?.takeIf { it.optString("type") == "text" }?.optString("value")
}
