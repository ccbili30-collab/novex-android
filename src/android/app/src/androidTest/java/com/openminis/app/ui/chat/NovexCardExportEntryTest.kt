package com.openminis.app.ui.chat

import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.data.character.*
import com.openminis.app.novex.domain.*
import com.openminis.app.ui.novex.NovexCardExportDialog
import com.openminis.app.ui.theme.MinisTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Real export UI and provider file; intercept only the external recipient chooser. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexCardExportEntryTest {
    @get:Rule val ui = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())

    @Suppress("DEPRECATION")
    @Test fun worldAndGameShareTheCompletePreparedPackage() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.ensureRuntime().getOrThrow() }
        val sent = AtomicReference<Intent?>(null)
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { sent.set(intent) }
        }
        var chosen by mutableStateOf<NovexCardCopyKey?>(null)
        ui.setContent { CompositionLocalProvider(LocalContext provides context) {
            MinisTheme(darkTheme = false) { chosen?.let { key -> NovexCardExportDialog(key) { chosen = null } } }
        } }
        for (kind in listOf(NovexCardKind.WORLD, NovexCardKind.GAME)) {
            val name = if (kind == NovexCardKind.WORLD) "分享完整世界" else "分享完整文游"
            val id = runBlocking {
                if (kind == NovexCardKind.WORLD) app.novexWorkspace.apply(NovexCommand.CreateWorld(name, "简介")).requireWorld().id
                else app.novexWorkspace.apply(NovexCommand.SaveInteractiveFictionPage(null, name, "简介")).requireInteractiveFiction().id
            }
            val owner = if (kind == NovexCardKind.WORLD) ModuleOwner.world(id) else ModuleOwner.interactiveFiction(id)
            val texts = listOf("第一章\n" + "保留完整正文与限定条件。\n".repeat(300), "第二章\n尚未裁定的关系不能写成确定结论。")
            try {
                runBlocking { texts.forEachIndexed { index, text ->
                    app.novexWorkspace.apply(NovexCommand.AddModule(owner, ContentModuleType.CUSTOM, "章节 $index",
                        JSONObject().put("kind", "article").put("text", text).put("authorNotes", JSONObject().put("index", index)).toString()))
                } }
                val first = runBlocking { app.novexWorkspace.modules(owner) }.modules.first()
                val image = android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jI9sAAAAASUVORK5CYII=", android.util.Base64.DEFAULT)
                runBlocking { app.novexWorkspace.apply(NovexCommand.AttachImage(ModuleOwner.contentModule(first.id), MediaAssetSlot.MODULE_IMAGE, image, "image/png")) }
                sent.set(null)
                ui.runOnIdle { chosen = NovexCardCopyKey(kind, id) }
                ui.waitUntil(15_000) { ui.onAllNodesWithText("生成预览").fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithText("生成预览").performTouchInput { click() }
                ui.waitUntil(30_000) { ui.onAllNodesWithText("分享此预览包").fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithText("分享此预览包").performTouchInput { click() }
                ui.waitUntil(10_000) { sent.get() != null }
                val chooser = requireNotNull(sent.get())
                assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                val payload = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
                assertEquals(Intent.ACTION_SEND, payload.action)
                assertTrue(payload.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                val uri = requireNotNull(payload.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                val bytes = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                File(app.cacheDir, "card-export-ui").also { it.mkdirs() }.resolve("$name.${kind.extension}").writeBytes(bytes)
                val card = NovexCardPackageCodec.decode(bytes)
                val modules = JSONObject(card.documentJson).getJSONArray("modules")
                assertEquals(2, modules.length())
                texts.forEachIndexed { index, text ->
                    val content = modules.getJSONObject(index).getJSONObject("content")
                    assertEquals(text, content.getString("text"))
                    assertEquals(index, content.getJSONObject("authorNotes").getInt("index"))
                }
                assertArrayEquals(image, card.media.single().bytes)
            } finally {
                ui.runOnIdle { chosen = null }
                runBlocking {
                    if (kind == NovexCardKind.WORLD) app.novexWorkspace.apply(NovexCommand.DeleteWorld(id))
                    else app.novexWorkspace.apply(NovexCommand.DeleteInteractiveFiction(id))
                }
            }
        }
    }
}
