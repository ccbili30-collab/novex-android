package com.openminis.app.ui.chat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.data.character.NovexCardKind
import com.openminis.app.novex.domain.*
import com.openminis.app.ui.settings.*
import com.openminis.app.ui.theme.MinisTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real OpenDocument contract, resolver, preview and repository. The OS picker result
 * is supplied by a test registry; no provider account or model request is involved. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexExternalCardImportInteractionTest {
    @get:Rule val ui = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())

    @Test fun worldSourceImportsOnlyAfterConfirmationAndOffersOrganization() = run(NovexCardKind.WORLD)
    @Test fun characterSourceImportsOnlyAfterConfirmationAndOffersOrganization() = run(NovexCardKind.CHARACTER)

    private fun run(kind: NovexCardKind) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.ensureRuntime().getOrThrow() }
        val authority = "com.noven.repository.testfixtures"
        context.contentResolver.call(Uri.parse("content://$authority"), "seed", null, null)
        val uri = DocumentsContract.buildDocumentUri(authority, "人物.txt")
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) {
                val intent = contract.createIntent(context, input)
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
                assertTrue(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.contains("*/*"))
                dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        var imported by mutableStateOf<String?>(null)
        var organized: String? = null
        var confirmations = 0
        ui.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            MinisTheme(darkTheme = false) {
                val importer = rememberNovexNativeCardImporter(kind) { imported = it; confirmations++ }
                val id = imported
                if (id == null) TextButton(onClick = importer.launch) { Text("导入测试资料") }
                else if (kind == NovexCardKind.WORLD) CatalogWorldDetailScreen(id, emptyList(), {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, {},
                    onOrganizeImportedCard = { organized = id })
                else CatalogCharacterDetailScreen(id, onOpenSession = {}, onBack = {}, onEditVersion = {}, onHelpCreate = {},
                    onCreateVariant = {}, onDuplicated = {}, onOpenModule = {}, onOrganizeImportedCard = { organized = it })
            }
        } }
        try {
            ui.onNodeWithText("导入测试资料").performClick()
            ui.waitUntil(15_000) { ui.onAllNodesWithText("确认导入").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("按原文导入，可直接使用。以后需要时再让人工智能整理。").assertIsDisplayed()
            assertNull(imported)
            ui.onNodeWithText("取消").performClick()
            assertEquals(0, confirmations)
            ui.onNodeWithText("导入测试资料").performClick()
            ui.waitUntil(15_000) { ui.onAllNodesWithText("确认导入").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("确认导入").performClick()
            ui.waitUntil(20_000) { ui.onAllNodesWithText("帮我整理").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, confirmations)
            ui.onNodeWithText("原始设定").assertExists()
            ui.onNodeWithText("帮我整理").performClick()
            val id = requireNotNull(imported)
            runBlocking {
                val module = if (kind == NovexCardKind.WORLD) app.novexWorkspace.world(id)!!.modules.single()
                    else app.novexWorkspace.character(id)!!.let { page ->
                        assertEquals(page.character.original.id, organized)
                        page.modulesByVersion[page.character.original.id]!!.single()
                    }
                if (kind == NovexCardKind.WORLD) assertEquals(id, organized)
                assertTrue(module.contentJson.contains("白榆"))
            }
        } finally {
            imported?.let { id -> runBlocking {
                app.novexWorkspace.apply(if (kind == NovexCardKind.WORLD) NovexCommand.DeleteWorld(id) else NovexCommand.DeleteCharacter(id))
            } }
            context.contentResolver.call(Uri.parse("content://$authority"), "clear", null, null)
        }
    }
}
