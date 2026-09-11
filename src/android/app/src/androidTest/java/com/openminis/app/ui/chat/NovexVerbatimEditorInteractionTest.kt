package com.openminis.app.ui.chat

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.data.character.*
import com.openminis.app.novex.domain.*
import com.openminis.app.ui.settings.*
import com.openminis.app.ui.theme.MinisTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexVerbatimEditorInteractionTest {
    @get:Rule val ui = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    @Test fun worldOpensRawTextEditsAndRoundTrips() = run(NovexCardKind.WORLD)
    @Test fun characterOpensRawTextEditsAndRoundTrips() = run(NovexCardKind.CHARACTER)
    @Test fun gameOpensRawTextEditsAndRoundTrips() = run(NovexCardKind.GAME)

    private fun run(kind: NovexCardKind) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.ensureRuntime().getOrThrow() }
        val workspace = app.novexWorkspace
        val original = "Unknown structure: retain this entire source.\n{not a native card}"
        val imported = NovexExternalCardImport.decode(kind, original.toByteArray(), "verbatim.txt")
        val id = runBlocking { workspace.apply(NovexCommand.ImportNativeCard(imported)).requireNativeImport().localId }
        var saved = 0
        var generation by mutableStateOf(0)
        fun onSaved(ignored: String) { saved++ }
        ui.setContent { key(generation) { MinisTheme(darkTheme = false) {
            when (kind) {
                NovexCardKind.WORLD -> CatalogWorldEditorScreen(id, {}, {}, ::onSaved, {})
                NovexCardKind.CHARACTER -> NovexCharacterEditorScreen(id, null, null, false, {}, {}, ::onSaved, {})
                NovexCardKind.GAME -> CatalogInteractiveFictionEditorScreen(id, {}, {}, ::onSaved, {})
            }
        } } }
        fun bodyField(text: String) = ui.onNode(hasSetTextAction() and hasText(text))
        val edited = original + "\nEdited by hand."
        try {
            ui.waitUntil(15_000) { ui.onAllNodes(hasSetTextAction() and hasText(original)).fetchSemanticsNodes().isNotEmpty() }
            bodyField(original).performScrollTo().assertIsDisplayed().performTextReplacement(edited)
            ui.onNodeWithText("世界观概述").assertDoesNotExist()
            ui.onNodeWithText("可选资料").assertDoesNotExist()
            ui.onNodeWithText("固定玩家身份（可留空）").assertDoesNotExist()
            ui.onNodeWithText("添加模块").performScrollTo().performClick()
            ui.onNodeWithText("保存", useUnmergedTree = true).performClick()
            ui.waitUntil(20_000) { saved == 1 }
            ui.runOnIdle { generation++ }
            ui.waitUntil(15_000) { ui.onAllNodes(hasSetTextAction() and hasText(edited)).fetchSemanticsNodes().isNotEmpty() }
            bodyField(edited).performScrollTo().assertIsDisplayed()
            val screenshot = java.io.File(app.cacheDir, "card-foundation-${kind.name.lowercase()}.png")
            screenshot.outputStream().use {
                ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            runBlocking {
                val export = workspace.apply(when (kind) {
                    NovexCardKind.WORLD -> NovexCommand.ExportNativeWorld(id)
                    NovexCardKind.CHARACTER -> NovexCommand.ExportNativeCharacter(id)
                    NovexCardKind.GAME -> NovexCommand.ExportNativeInteractiveFiction(id)
                }).requireNativeCard()
                val parsed = NovexExternalCardImport.decode(kind, NovexCardPackageCodec.encode(export), "card.${kind.extension}")
                val modules = when (val doc = parsed.document) {
                    is NovexWorldImportDocument -> doc.modules
                    is NovexCharacterImportDocument -> doc.versions.single().modules
                    is NovexInteractiveFictionImportDocument -> doc.modules
                }
                assertEquals(2, modules.size)
                assertEquals(edited, (modules.first().document as ContentModuleDocument.Article).text)
                assertArrayEquals(original.toByteArray(), NovexExternalCardImport.original(JSONObject(parsed.document.originalJson))!!.second)
            }
        } finally {
            runBlocking { workspace.apply(when (kind) {
                NovexCardKind.WORLD -> NovexCommand.DeleteWorld(id)
                NovexCardKind.CHARACTER -> NovexCommand.DeleteCharacter(id)
                NovexCardKind.GAME -> NovexCommand.DeleteInteractiveFiction(id)
            }) }
        }
    }
}
