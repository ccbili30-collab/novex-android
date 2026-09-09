package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.NovexTestWorkspaceFactory as NovexWorkspaceFactory
import com.openminis.app.novex.adapter.NovexConversationContextAdoption
import com.openminis.app.novex.adapter.WorkspaceNovexContextLoader
import java.io.File
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
class NovexTavernWorldbookPersistenceTest {
    @get:Rule val files = TemporaryFolder()
    private fun profile(body: String): String = JSONObject().put("summary", "角色公开资料")
        .put("_novexTavernSource", JSONObject().put("rawJson", JSONObject().put("data", JSONObject()
            .put("character_book", JSONObject().put("entries", JSONArray().put(JSONObject().put("name", "采用规则")
                .put("content", body).put("enabled", true).put("constant", true).put("keys", JSONArray()))))).toString())).toString()
    @Test fun `adopted book survives reopen while mutable edits and background-only roles cannot inject it`() = runBlocking {
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
            File(files.root, "book.db").absolutePath).allowMainThreadQueries().build()
        var db = open()
        try {
            var workspace = NovexWorkspaceFactory.create(db, File(files.root, "media"))
            val role = workspace.apply(NovexCommand.CreateCharacter("回答角色", profile("旧规则私有正文"))).requireCharacter()
            val address = NovexContentAddress.characterVersion(role.original.id)
            val adoption = NovexConversationContextAdoption(workspace)
            val background = adoption.adopt(NovexConversationConfigurationSnapshot("background", backgroundSettings = listOf(BackgroundSetting(address))))
            assertNull(NovexTavernWorldbook.adopted(background))
            assertTrue(background.adoptedContexts.flatMap { it.sources }.all { it.tavernWorldbookJson == null })
            assertFalse(WorkspaceNovexContextLoader(workspace).load(background).any { it.content.contains("旧规则私有正文") })
            val acting = adoption.adopt(NovexConversationConfigurationSnapshot("acting", answerIdentity = AnswerIdentity.CharacterVersion(role.original.id)))
            val saved = File(files.root, "configuration.json").apply { writeText(NovexConversationConfigurationCodec.encode(acting)) }
            workspace.apply(NovexCommand.SaveCharacterVersion(role.character.id, role.original.id, "回答角色", role.original.label, profile("新规则")))
            db.close(); db = open(); workspace = NovexWorkspaceFactory.create(db, File(files.root, "media"))
            val restored = NovexConversationConfigurationCodec.decode(saved.readText(), "acting")
            val book = NovexTavernWorldbook.adopted(restored)!!
            assertEquals(listOf("旧规则私有正文"), NovexTavernWorldbook.evaluate(book.first, book.second, emptyList(), 100, String::length).fragments.map { it.text })
            assertNull(NovexTavernWorldbook.adopted(restored.copy(answerIdentity = AnswerIdentity.Nova)))
            val refreshed = NovexConversationContextAdoption(workspace).refresh(restored, address, true)
            val latest = NovexTavernWorldbook.adopted(refreshed)!!
            assertEquals(listOf("新规则"), NovexTavernWorldbook.evaluate(latest.first, latest.second, emptyList(), 100, String::length).fragments.map { it.text })
            assertEquals(restored.playerIdentity, refreshed.playerIdentity)
        } finally { db.close() }
    }
}
