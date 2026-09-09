package com.openminis.app.share

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.novex.adapter.NovexConversationContextAdoption
import com.openminis.app.novex.adapter.NovexTestWorkspaceFactory as NovexWorkspaceFactory
import com.openminis.app.novex.domain.*
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NovexConversationBundleExporterTest {
    @get:Rule val files = TemporaryFolder()
    @Test(timeout = 60_000) fun `story image versions from both reply branches remain in the export`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repository = ChatRepository(database.chatDao())
            val session = repository.createSession("model")
            repository.appendMessage(session.id, "user", """[{"type":"text","value":"看看镇口"}]""", messageId = "root")
            val media = listOf("old", "new").map { branch ->
                val picture = File(context.filesDir, "novex/adopted-media/${session.id}-$branch.media").apply {
                    parentFile.mkdirs(); writeText("$branch 图片的原字节")
                }
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(picture.readBytes()).joinToString("") { "%02x".format(it) }
                val image = NovexSnapshotMedia(NovexRetainedMedia(branch, picture.absolutePath, "image/png", hash),
                    com.openminis.app.data.character.MediaAssetSlot.MODULE_IMAGE, moduleId = "module", label = "镇口")
                if (branch == "new") repository.forkReplyFrom(session.id, "root")
                val parts = com.openminis.app.ui.chat.encodeAssistantTurnParts(
                    listOf(com.openminis.app.data.model.AgentContentPart.Text("$branch 剧情")),
                    mapOf("image" to com.openminis.app.ui.chat.storyImageBlock(image, branch)))
                repository.appendMessage(session.id, "assistant", parts, messageId = branch)
                picture
            }
            val workspace = NovexWorkspaceFactory.create(database, File(context.filesDir, "novex-media"))
            val runtime = JSONObject().put("conversationId", session.id).put("configurationJson",
                NovexConversationConfigurationCodec.encode(NovexConversationConfigurationSnapshot(session.id)))
            val result = NovexConversationBundleExporter(context, database, workspace).export(session.id, runtime)
            ZipFile(result.file).use { zip ->
                fun read(name: String) = zip.getInputStream(zip.getEntry(name)).readBytes()
                val index = JSONArray(read("media/index.json").toString(Charsets.UTF_8))
                assertEquals(2, index.length())
                media.forEach { picture ->
                    val entry = (0 until index.length()).map(index::getJSONObject).single { it.getString("reference") == picture.absolutePath }
                    assertArrayEquals(picture.readBytes(), read(entry.getString("path")))
                }
                val rows = read("database/messages.jsonl").toString(Charsets.UTF_8)
                assertEquals(2, Regex("novexStoryImage").findAll(rows).count())
            }
        } finally { database.close() }
    }

    @Test(timeout = 60_000) fun `bundle keeps raw branches and adopted environment apart from current library with file hashes`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val path = File(files.root, "bundle.db").absolutePath
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, path).allowMainThreadQueries().build()
        var database = open()
        try {
            var workspace = NovexWorkspaceFactory.create(database, File(context.filesDir, "novex-media"))
            var repository = ChatRepository(database.chatDao())
            val session = repository.createSession("model-id")
            val world = workspace.apply(NovexCommand.CreateWorld("测试环境", "已采用的旧正文")).requireWorld()
            val nativeModuleBody = "{ \"kind\" : \"article\", \"text\" : \"逐字保留的模块\" }"
            workspace.apply(NovexCommand.AddModule(ModuleOwner.world(world.id), ContentModuleType.CUSTOM, "规则", nativeModuleBody))
            val adopted = NovexConversationContextAdoption(workspace).adopt(NovexConversationConfigurationSnapshot(session.id,
                backgroundSettings = listOf(BackgroundSetting(NovexContentAddress.world(world.id)))))
            val configuration = NovexConversationConfigurationCodec.encode(adopted)
            database.chatDao().insertSession(database.chatDao().getSession(session.id)!!.copy(novexConfigurationJson = configuration,
                conversationPrompt = "自定义原话\n第二行", imageStylePrompt = "原图片风格"))
            val relative = "2026/09/07/${session.id}/source.txt"
            val original = File(context.filesDir, "media/$relative").apply { parentFile.mkdirs(); writeText("附件原文\n没有删节") }
            val raw = JSONArray().put(JSONObject().put("type", "text").put("value", "原话  \n<system-reminder>仍保留</system-reminder>\n用户自己输入的凭据文字不改写"))
                .put(JSONObject().put("type", "mediaRef").put("value", JSONObject().put("relativePath", relative).put("mimeType", "text/plain")))
                .put(JSONObject().put("type", "futureUnknownPart").put("value", JSONObject().put("preserve", true))).toString()
            repository.appendMessage(session.id, "user", raw, messageId = "user")
            repository.appendMessage(session.id, "assistant", """[{"type":"text","value":"旧分支原话"},{"type":"toolUse","value":{"toolUseId":"call","name":"inspect","input":"{}"}}]""", messageId = "old")
            repository.forkReplyFrom(session.id, "user")
            repository.appendMessage(session.id, "assistant", """[{"type":"text","value":"当前分支原话"}]""", messageId = "new")
            val store = FileNovexConversationWorkspaceStore(File(context.filesDir, "novex/conversation-workspaces"))
            for(branch in listOf("old", "new")) store.writeText(NovexConversationWorkspaceScope(session.id, listOf("user", branch), branch),
                NovexWorkspaceArea.OUTPUTS, "同名文件.md", "$branch 分支成果", "text/markdown", NovexWorkspaceProvenance(session.id, branch, "user", "tool-$branch"))
            val unrelated = repository.createSession("other-model")
            repository.appendMessage(unrelated.id, "user", """[{"type":"text","value":"其他对话不能进入此包"}]""")
            val journal = NovexOperationJournal(File(context.filesDir, "novex-operations"))
            val operation = NovexToolOperation(session.id, "new", "write", "novex_write_card", """{ "name" : "原名" }""", "保存卡片")
            journal.save(NovexOperationRecord(operation, NovexOperationStatus.FAILED,
                com.openminis.app.tools.ToolExecutionResult("原回执\n未保存", false)))
            journal.save(NovexOperationRecord(operation.copy(conversationId = unrelated.id), NovexOperationStatus.WAITING))
            File(context.filesDir, "provider-credentials.json").writeText("NEVER_EXPORT_ACCOUNT_SECRET")
            workspace.apply(NovexCommand.SaveWorld(world.copy(overview = "导出时的新正文")))
            database.close(); database = open(); repository = ChatRepository(database.chatDao())
            workspace = NovexWorkspaceFactory.create(database, File(context.filesDir, "novex-media"))
            val runtime = JSONObject().put("conversationId", session.id).put("configurationJson", configuration).put("modelId", "model-id")
            val result = NovexConversationBundleExporter(context, database, workspace).export(session.id, runtime)
            assertEquals(3, result.messageCount)
            assertEquals(1, result.missing.size)
            assertTrue(result.missing.single().contains("未找到配套装配记录"))
            ZipFile(result.file).use { zip ->
                fun text(name: String) = zip.getInputStream(zip.getEntry(name)).bufferedReader().readText()
                assertEquals(journal.exportRecords(session.id).getValue(operation.id), text("execution/operations/${operation.id}.json"))
                assertNull(zip.getEntry("execution/operations/${operation.copy(conversationId = unrelated.id).id}.json"))
                val rows = text("database/messages.jsonl").lineSequence().filter { it.isNotBlank() }.map { JSONObject(it) }.toList()
                assertEquals(raw, rows.single { it.getString("id") == "user" }.getString("parts_json"))
                assertEquals(setOf("user", "old", "new"), rows.map { it.getString("id") }.toSet())
                assertEquals("user", rows.single { it.getString("id") == "old" }.getString("parent_message_id"))
                assertTrue(text("environment/current-runtime.json").contains("已采用的旧正文"))
                val manifest = JSONObject(text("manifest.json"))
                val entries = manifest.getJSONArray("entries")
                repeat(entries.length()) { i ->
                    val entry = entries.getJSONObject(i)
                    val bytes = zip.getInputStream(zip.getEntry(entry.getString("path"))).readBytes()
                    val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    assertEquals(entry.getString("sha256"), sha)
                }
                val workspaceRows = JSONArray(text("workspace/index.json"))
                assertEquals(2, workspaceRows.length())
                assertEquals(setOf("old 分支成果", "new 分支成果"), (0 until workspaceRows.length()).map { text(workspaceRows.getJSONObject(it).getString("path")) }.toSet())
                val media = JSONArray(text("media/index.json"))
                assertEquals(original.readText(), text(media.getJSONObject(0).getString("path")))
                val rawPrefix = "library-at-export/raw/${NovexFrozenContextCodec.digest(NovexContentAddress.world(world.id).toString())}"
                assertEquals(nativeModuleBody, JSONObject(text("$rawPrefix/modules.jsonl").trim()).getString("content_json"))
                assertTrue(text("$rawPrefix/subject.jsonl").contains("导出时的新正文"))
                val allText = zip.entries().asSequence().filter { it.name.endsWith("json") || it.name.endsWith("jsonl") }.joinToString { zip.getInputStream(it).bufferedReader().readText() }
                assertFalse(allText.contains("NEVER_EXPORT_ACCOUNT_SECRET"))
                assertFalse(allText.contains("其他对话不能进入此包"))
            }
            val repeated = NovexConversationBundleExporter(context, database, workspace).export(session.id, runtime)
            assertNotEquals(result.file, repeated.file)
            assertTrue(result.file.isFile)
            assertEquals(raw, repository.loadMessages(session.id).single { it.id == "user" }.partsJson)
        } finally { database.close() }
    }

    @Test(timeout = 60_000) fun `missing and escaped attachment references are explicit without reading credential files`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repository = ChatRepository(database.chatDao())
            val session = repository.createSession("model")
            File(context.filesDir, "provider-credentials.json").writeText("SECRET_NOT_AN_ATTACHMENT")
            val raw = JSONArray(listOf("missing.txt", "../provider-credentials.json").map { ref -> JSONObject().put("type", "mediaRef")
                .put("value", JSONObject().put("relativePath", ref).put("mimeType", "text/plain")) }).toString()
            repository.appendMessage(session.id, "user", raw)
            val workspace = NovexWorkspaceFactory.create(database, File(context.filesDir, "novex-media"))
            val runtime = JSONObject().put("conversationId", session.id).put("configurationJson", NovexConversationConfigurationCodec.encode(NovexConversationConfigurationSnapshot(session.id)))
            val result = NovexConversationBundleExporter(context, database, workspace).export(session.id, runtime)
            assertEquals(2, result.missing.size)
            ZipFile(result.file).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertTrue(names.none { it.contains("..") || it.startsWith("/") })
                assertFalse(names.any { it.contains("provider-credentials") })
                assertTrue(names.none { it.startsWith("media/") && it != "media/index.json" })
            }
        } finally { database.close() }
    }
}
