package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.character.*
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.NovexTestWorkspaceFactory
import java.io.File
import java.util.Base64
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
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NovexWorldGameExportCompletenessTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun worldPackageRetainsEveryModuleAndEditableExtension() = checkPackage(NovexCardKind.WORLD)
    @Test fun gamePackageRetainsEveryModuleAndEditableExtension() = checkPackage(NovexCardKind.GAME)

    private fun checkPackage(kind: NovexCardKind) = runBlocking {
        fun database() = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val sourceDatabase = database()
        val targetDatabase = database()
        try {
            val source = NovexTestWorkspaceFactory.create(sourceDatabase, File(files.root, "source"))
            val target = NovexTestWorkspaceFactory.create(targetDatabase, File(files.root, "target"))
            val id = when (kind) {
                NovexCardKind.WORLD -> source.apply(NovexCommand.CreateWorld("完整世界", "世界简介")).requireWorld().id
                else -> source.apply(NovexCommand.SaveInteractiveFictionPage(null, "完整文游", "文游简介")).requireInteractiveFiction().id
            }
            val owner = if (kind == NovexCardKind.WORLD) ModuleOwner.world(id) else ModuleOwner.interactiveFiction(id)
            val types = ContentModuleCatalog.definitions(if (kind == NovexCardKind.WORLD) ContentModuleScope.WORLD else ContentModuleScope.INTERACTIVE_FICTION).map { it.type }
            val texts = types.mapIndexed { index, _ -> "第 $index 模块\n" + "完整正文；不应变成摘要。\n".repeat(200) }
            types.forEachIndexed { index, type ->
                source.apply(NovexCommand.AddModule(owner, type, "章节 $index",
                    JSONObject().put("kind", "article").put("text", texts[index])
                        .put("authorNotes", JSONObject().put("unresolved", "甲本与乙本冲突，尚未裁定").put("index", index))
                        .toString()))
            }
            val first = source.modules(owner).modules.first()
            val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jI9sAAAAASUVORK5CYII=")
            source.apply(NovexCommand.AttachImage(ModuleOwner.contentModule(first.id), MediaAssetSlot.MODULE_IMAGE, png, "image/png"))
            val exported = source.apply(NovexCommand.ExportNativeSelection(NovexCardCopyKey(kind, id))).requireNativeCard()
            // Exercise the actual archive boundary, not only the in-memory transfer object.
            val archive = NovexCardPackageCodec.encode(exported)
            val decoded = NovexCardPackageCodec.decode(archive)
            assertArrayEquals(png, decoded.media.single().bytes)
            val modules = JSONObject(decoded.documentJson).getJSONArray("modules")
            assertEquals(texts.size, modules.length())
            texts.forEachIndexed { index, text ->
                val content = modules.getJSONObject(index).getJSONObject("content")
                assertEquals(text, content.getString("text"))
                assertEquals("Editable JSON fields must survive export", index, content.getJSONObject("authorNotes").getInt("index"))
            }
            val imported = target.apply(NovexCommand.ImportNativeCard(NovexCardTransferParser.parse(decoded))).requireNativeImport()
            val importedOwner = if (kind == NovexCardKind.WORLD) ModuleOwner.world(imported.localId) else ModuleOwner.interactiveFiction(imported.localId)
            val restored = target.modules(importedOwner).modules
            assertEquals(texts, restored.map { (ContentModuleDocumentCodec.decode(it.type, it.contentJson) as ContentModuleDocument.Article).text })
            val again = target.apply(NovexCommand.ExportNativeSelection(NovexCardCopyKey(kind, imported.localId))).requireNativeCard()
            val againModules = JSONObject(again.documentJson).getJSONArray("modules")
            texts.indices.forEach { index -> assertEquals(index, againModules.getJSONObject(index).getJSONObject("content").getJSONObject("authorNotes").getInt("index")) }
            assertArrayEquals(png, NovexCardPackageCodec.decode(NovexCardPackageCodec.encode(again)).media.single().bytes)
        } finally { sourceDatabase.close(); targetDatabase.close() }
    }
}
