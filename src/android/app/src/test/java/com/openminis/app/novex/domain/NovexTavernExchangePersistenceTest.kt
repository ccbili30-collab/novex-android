package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.character.*
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.NovexWorkspaceFactory
import com.openminis.app.novex.adapter.WorkspaceNovexContextLoader
import java.io.File
import kotlinx.coroutines.runBlocking
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
class NovexTavernExchangePersistenceTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `png with both metadata versions retains the richer third version`() {
        val output = java.io.ByteArrayOutputStream()
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) {
            val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
            java.io.DataOutputStream(output).apply {
                writeInt(data.size); write(typeBytes); write(data)
                writeInt(java.util.zip.CRC32().apply { update(typeBytes); update(data) }.value.toInt())
            }
        }
        val v2 = """{"spec":"chara_card_v2","data":{"name":"旧兼容层"}}"""
        val v3 = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"第三版原件","nickname":"保留昵称","extensions":{}}}"""
        chunk("IHDR", ByteArray(13))
        listOf("chara" to v2, "ccv3" to v3).forEach { (key, value) ->
            chunk("tEXt", key.toByteArray() + byteArrayOf(0) + java.util.Base64.getEncoder().encode(value.toByteArray()))
        }
        chunk("IEND", byteArrayOf())
        val preview = NovexTavernExchange.importCharacter(output.toByteArray())
        assertEquals("第三版原件", preview.displayName)
        val profile = (preview.document as NovexCharacterImportDocument).versions.single().profileJson
        assertEquals(v3, NovexTavernExchange.originalSource(profile))
        assertArrayEquals(output.toByteArray(), preview.media.values.single().bytes)
    }

    @Test
    fun `native public modules export while companion identity stays local and source reads require management scope`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val role = workspace.apply(NovexCommand.CreateCharacter("伏生", """{"name":"伏生","summary":"人物简介"}""")).requireCharacter()
            val owner = ModuleOwner.characterVersion(role.original.id)
            workspace.apply(NovexCommand.AddModule(owner, ContentModuleType.CUSTOM, "制度背景", """{"text":"公开条目"}"""))
            workspace.apply(NovexCommand.AddModule(owner, ContentModuleType.ROLE_INSTRUCTIONS, "扮演要求", """{"text":"角色专属要求"}"""))
            workspace.apply(NovexCommand.AddModule(owner, ContentModuleType.ROLE_PLAYER_IDENTITY, "配套玩家", """{"text":"不要导出我的配套身份"}"""))
            val json = NovexTavernExchange.exportCharacter(workspace, role.original.id)
            val data = JSONObject(json).getJSONObject("data")
            assertEquals("", data.getString("creator"))
            assertEquals("", data.getString("creator_notes"))
            assertEquals("", data.getString("character_version"))
            assertEquals(0, data.getJSONArray("alternate_greetings").length())
            assertTrue(json.contains("公开条目"))
            assertTrue(json.contains("角色专属要求"))
            assertFalse(json.contains("不要导出我的配套身份"))
            val copy = workspace.apply(NovexCommand.ImportNativeCard(NovexTavernExchange.importCharacter(json.toByteArray()))).requireNativeImport()
            val imported = workspace.character(copy.localId)!!.character.original
            val subject = NovexContentAddress.characterVersion(imported.id)
            val service = NovexManagementService(workspace, com.openminis.app.data.creative.CreativeArtifactRepository(database,
                com.openminis.app.data.creative.CreativeArtifactFileStore(File(files.root, "artifacts"))))
            val background = NovexConversationConfigurationSnapshot("chat", backgroundSettings = listOf(BackgroundSetting(subject)))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.readExchangeSource(background, subject, 0, 80, null) } }
            val managed = background.copy(managedSubjects = listOf(ManagedSubject(subject, ManagedAccess.READ_ONLY)))
            val overview = service.inspect(managed, subject, null).toToolJson().toString()
            assertFalse(overview.contains("角色专属要求"))
            val first = service.readExchangeSource(managed, subject, 0, 80, null)
            assertEquals(json.take(80), first.getString("text"))
            val next = service.readExchangeSource(managed, subject, first.getInt("next_offset"), 80, first.getString("revision"))
            assertEquals(80, next.getInt("read_start"))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.readExchangeSource(managed, subject, 80, 80, "old-source") } }
            assertEquals(AnswerIdentity.Nova, managed.answerIdentity)
        } finally { database.close() }
    }

    @Test
    fun `native character exchange retains source triggers unknown fields and scoped edits across restart`() = runBlocking {
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
            File(files.root, "exchange.db").absolutePath).allowMainThreadQueries().build()
        var database = open()
        val raw = """{"spec":"chara_card_v2","spec_version":"2.0","root_future":{"a":1},"data":{"name":"伏生","description":"公开生平","personality":"角色表达方式","system_prompt":"只在扮演时采用","creator":"原作者","character_version":"作者修订七","creator_notes":"作者说明不能成为角色指令","extensions":{"future":{"enabled":true}},"character_book":{"extensions":{"vendor":"保持"},"entries":[{"id":7,"keys":["帝议"],"secondary_keys":["记言"],"selective":true,"content":"条件触发正文","enabled":true,"insertion_order":3,"extensions":{"depth":4}},{"id":9,"content":"停用条目","enabled":false}]}}}"""
        try {
            var workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val imported = workspace.apply(NovexCommand.ImportNativeCard(NovexTavernExchange.importCharacter(raw.toByteArray()))).requireNativeImport()
            val page = workspace.character(imported.localId)!!
            val version = page.character.original
            val modules = page.modulesByVersion.getValue(version.id)
            val description = modules.single { it.name == "人物背景" }
            workspace.apply(NovexCommand.SaveModule(description.id, description.name,
                ContentModuleDocumentCodec.edit(description.contentJson, ContentModuleDocument.Article("补充后的公开生平"))))
            val privateInstruction = modules.single { it.name == "角色专属指令" }
            workspace.apply(NovexCommand.SaveModule(privateInstruction.id, privateInstruction.name,
                ContentModuleDocumentCodec.edit(privateInstruction.contentJson, ContentModuleDocument.Article("修改后的专属指令"))))
            val background = WorkspaceNovexContextLoader(workspace).load(NovexConversationConfigurationSnapshot("chat",
                backgroundSettings = listOf(BackgroundSetting(NovexContentAddress.characterVersion(version.id)))))
            val text = background.joinToString("\n") { it.content }
            assertTrue(text.contains("补充后的公开生平"))
            assertFalse(text.contains("修改后的专属指令"))
            assertFalse(text.contains("条件触发正文"))
            assertFalse(text.contains("停用条目"))
            assertFalse(text.contains("作者说明不能成为角色指令"))
            database.close(); database = open()
            workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val exported = JSONObject(NovexTavernExchange.exportCharacter(workspace, version.id))
            assertEquals(1, exported.getJSONObject("root_future").getInt("a"))
            val data = exported.getJSONObject("data")
            assertEquals("补充后的公开生平", data.getString("description"))
            assertEquals("修改后的专属指令", data.getString("system_prompt"))
            assertEquals("原作者", data.getString("creator"))
            assertEquals("作者修订七", data.getString("character_version"))
            assertTrue(data.getJSONObject("extensions").getJSONObject("future").getBoolean("enabled"))
            assertEquals(JSONObject(raw).getJSONObject("data").getJSONObject("character_book").toString(), data.getJSONObject("character_book").toString())
            val native = workspace.apply(NovexCommand.ExportNativeCharacter(imported.localId)).requireNativeCard()
            val copied = workspace.apply(NovexCommand.ImportNativeCard(NovexCardTransferParser.parse(
                NovexCardPackageCodec.decode(NovexCardPackageCodec.encode(native))))).requireNativeImport()
            val second = workspace.character(copied.localId)!!.character.original
            assertNotEquals(version.id, second.id)
            val back = JSONObject(NovexTavernExchange.exportCharacter(workspace, second.id))
            assertEquals("修改后的专属指令", back.getJSONObject("data").getString("system_prompt"))
            assertEquals(data.getJSONObject("character_book").toString(), back.getJSONObject("data").getJSONObject("character_book").toString())
        } finally { database.close() }
    }
}
