package com.openminis.app.novex.domain

import com.openminis.app.novex.adapter.NovexTestWorkspaceFactory as NovexWorkspaceFactory

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.*
import java.io.File
import kotlinx.coroutines.runBlocking
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
class NovexSnapshotMediaPersistenceTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `visible pictures follow current use and presentation resolves the retained avatar`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "originals"))
            val role = workspace.apply(NovexCommand.CreateCharacter("伏生")).requireCharacter()
            val owner = ModuleOwner.characterVersion(role.original.id)
            workspace.apply(NovexCommand.AddModule(owner, ContentModuleType.ROLE_INSTRUCTIONS, "扮演资料", """{"text":"只在扮演时使用"}""", id = "instructions"))
            workspace.apply(NovexCommand.AttachImage(owner, MediaAssetSlot.CHARACTER_AVATAR, byteArrayOf(1, 2, 3), "image/png"))
            workspace.apply(NovexCommand.AttachImage(ModuleOwner.contentModule("instructions"), MediaAssetSlot.MODULE_IMAGE, byteArrayOf(4, 5, 6), "image/png"))
            val adoption = NovexConversationContextAdoption(workspace, mediaStore = NovexSnapshotMediaStore(File(files.root, "adopted")))
            val acting = adoption.adopt(NovexConversationConfigurationSnapshot("chat", answerIdentity = AnswerIdentity.CharacterVersion(role.original.id)))
            val visible = NovexSnapshotMediaProjection.visible(acting)
            assertEquals(2, visible.size)
            val avatar = visible.single { it.slot == MediaAssetSlot.CHARACTER_AVATAR }
            val rendered = NovexSnapshotMediaProjection.profile(acting, com.openminis.app.data.character.ImmersiveChatProfile())
            assertEquals(avatar.asset.path, rendered.assistantAvatarPath)
            val explicit = NovexSnapshotMediaProjection.profile(acting, com.openminis.app.data.character.ImmersiveChatProfile(assistantAvatarPath = "user-avatar"))
            assertEquals("user-avatar", explicit.assistantAvatarPath)
            val inactive = NovexConversationConfiguration.open(acting).apply(NovexConversationCommand.SetAnswerIdentity(AnswerIdentity.Nova)).snapshot
            assertTrue(NovexSnapshotMediaProjection.visible(inactive).isEmpty())
            val background = adoption.adopt(NovexConversationConfiguration.open(inactive)
                .apply(NovexConversationCommand.AddBackground(NovexContentAddress.characterVersion(role.original.id))).snapshot)
            assertEquals(listOf(MediaAssetSlot.CHARACTER_AVATAR), NovexSnapshotMediaProjection.visible(background).map { it.slot })
        } finally { database.close() }
    }

    @Test
    fun `game retains its pictures and reuses already adopted background pictures`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "originals"))
            val world = workspace.apply(NovexCommand.CreateWorld("世界", "背景资料")).requireWorld()
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "文游")).requireInteractiveFiction()
            val bytes = byteArrayOf(1, 2, 3)
            workspace.apply(NovexCommand.AttachImage(ModuleOwner.world(world.id), MediaAssetSlot.WORLD_BACKGROUND, bytes, "image/png"))
            workspace.apply(NovexCommand.AttachImage(ModuleOwner.interactiveFiction(game.id), MediaAssetSlot.INTERACTIVE_FICTION_COVER, bytes + 4.toByte(), "image/png"))
            val store = NovexSnapshotMediaStore(File(files.root, "adopted"))
            val adopted = NovexConversationContextAdoption(workspace, mediaStore = store).adopt(NovexConversationConfigurationSnapshot("chat",
                backgroundSettings = listOf(BackgroundSetting(NovexContentAddress.world(world.id)))))
            val oldImage = adopted.adoptedContexts.single().sources.single().media.single()
            workspace.apply(NovexCommand.AttachImage(ModuleOwner.world(world.id), MediaAssetSlot.WORLD_BACKGROUND, bytes + 5.toByte(), "image/png"))
            val captured = NovexGameSnapshotAssembler(workspace, store).create(game.id, adopted.backgroundSettings, adopted.adoptedContexts)
            val images = org.json.JSONObject(captured.contentJson).optJSONArray("adoptedMedia")
            assertEquals(1, images?.length() ?: 0)
            val cover = NovexSnapshotMediaCodec.decode(images!!.getJSONObject(0))
            assertEquals(oldImage, NovexFrozenContextCodec.read(captured.contentJson).single().media.single())
            workspace.apply(NovexCommand.DeleteWorld(world.id))
            workspace.apply(NovexCommand.DeleteInteractiveFiction(game.id))
            assertArrayEquals(bytes, File(oldImage.asset.path).readBytes())
            assertArrayEquals(bytes + 4.toByte(), File(cover.asset.path).readBytes())
        } finally { database.close() }
    }

    @Test
    fun `background retains permitted media with configuration without copying companion images`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "originals"))
            val person = workspace.apply(NovexCommand.CreateCharacter("伏生")).requireCharacter()
            val owner = ModuleOwner.characterVersion(person.original.id)
            workspace.apply(NovexCommand.AddModule(owner, ContentModuleType.CUSTOM, "公开资料", """{"text":"公开配图"}""", id = "public"))
            workspace.apply(NovexCommand.AddModule(owner, ContentModuleType.ROLE_PLAYER_IDENTITY, "配套身份", """{"text":"未采用的身份"}""", id = "private"))
            val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
            workspace.apply(NovexCommand.AttachImage(owner, MediaAssetSlot.CHARACTER_AVATAR, png, "image/png"))
            workspace.apply(NovexCommand.AttachImage(ModuleOwner.contentModule("public"), MediaAssetSlot.MODULE_IMAGE, png + 1.toByte(), "image/png"))
            workspace.apply(NovexCommand.AttachImage(ModuleOwner.contentModule("private"), MediaAssetSlot.MODULE_IMAGE, png + 2.toByte(), "image/png"))
            val source = workspace.character(person.character.id)!!
            val originalPaths = source.mediaByVersion.values.flatMap { it.values } + source.moduleImages.values
            val configuration = NovexConversationConfigurationSnapshot("chat", backgroundSettings = listOf(BackgroundSetting(NovexContentAddress.characterVersion(person.original.id))))
            val adopted = NovexConversationContextAdoption(workspace, mediaStore = NovexSnapshotMediaStore(File(files.root, "adopted"))).adopt(configuration)
            val captured = adopted.adoptedContexts.flatMap { it.sources }.flatMap { it.media }
            assertEquals(2, captured.size)
            assertEquals(setOf(null, "public"), captured.map { it.moduleId }.toSet())
            assertFalse(captured.any { it.asset.sourceAssetId == source.moduleImages.getValue("private").id })
            val restored = NovexConversationConfigurationCodec.decode(NovexConversationConfigurationCodec.encode(adopted), "chat")
            assertEquals(captured, restored.adoptedContexts.flatMap { it.sources }.flatMap { it.media })
            workspace.apply(NovexCommand.DeleteCharacter(person.character.id))
            originalPaths.forEach { File(it.managedPath).delete() }
            assertTrue(captured.all { File(it.asset.path).isFile })
            assertArrayEquals(png, File(captured.single { it.slot == MediaAssetSlot.CHARACTER_AVATAR }.asset.path).readBytes())
        } finally { database.close() }
    }
}
