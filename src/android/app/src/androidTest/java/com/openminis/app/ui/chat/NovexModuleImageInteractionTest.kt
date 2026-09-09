package com.openminis.app.ui.chat

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.data.character.*
import com.openminis.app.novex.domain.*
import com.openminis.app.ui.settings.CatalogContentModuleDetailScreen
import com.openminis.app.ui.theme.MinisTheme
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexModuleImageInteractionTest {
    @get:Rule val ui = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    private fun settle() { ui.mainClock.advanceTimeBy(600); ui.waitForIdle() }
    @Test fun removingAnEntryPicturePreservesTheModulePictureAndEntryText() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.ensureRuntime().getOrThrow() }
        val workspace = app.novexWorkspace
        val world = runBlocking { workspace.apply(NovexCommand.CreateWorld("条目图片验收", "")).requireWorld() }
        val module = runBlocking {
            val value = workspace.apply(NovexCommand.AddModule(ModuleOwner.world(world.id), ContentModuleType.CUSTOM, "地点",
                ContentModuleDocumentCodec.encode(ContentModuleDocument.Collection(listOf(ContentModuleCollectionItem(
                    id = "postoffice", name = "邮局", description = "保留这段说明")))))).requireModule()
            val bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jI9sAAAAASUVORK5CYII=")
            workspace.apply(NovexCommand.AttachImage(ModuleOwner.contentModule(value.id), MediaAssetSlot.MODULE_IMAGE, bytes, "image/png"))
            workspace.apply(NovexCommand.AttachImage(ModuleOwner.contentModuleItem(value.id, "postoffice"), MediaAssetSlot.MODULE_IMAGE, bytes, "image/png"))
            value
        }
        try {
            ui.setContent { MinisTheme(darkTheme = false) { CatalogContentModuleDetailScreen(module.id, onBack = {}, onHelpCreate = {}) } }
            ui.waitUntil(15_000) { ui.onAllNodesWithText("移除图片").fetchSemanticsNodes().isNotEmpty() }; settle()
            ui.onNodeWithContentDescription("邮局图片").assertExists()
            repeat(6) {
                if (!ui.onNodeWithText("移除图片").isDisplayed()) {
                    ui.onRoot().performTouchInput { swipeUp() }; settle()
                }
            }
            ui.onNodeWithText("移除图片").assertIsDisplayed().performTouchInput { click() }
            ui.waitUntil(15_000) { runBlocking { workspace.module(module.id) }!!.itemImages.isEmpty() }; settle()
            val detail = runBlocking { workspace.module(module.id) }!!
            assertNotNull(detail.image)
            assertTrue(File(detail.image!!.managedPath).isFile)
            assertTrue(detail.module.contentJson.contains("保留这段说明"))
            ui.waitUntil(15_000) { ui.onAllNodesWithText("添加图片").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("添加图片").assertExists()
            ui.onNodeWithText("更换代表图").assertExists()
        } finally {
            File(app.cacheDir, "entry-image-semantics.txt").writeText(ui.onRoot().printToString())
            runBlocking { workspace.apply(NovexCommand.DeleteWorld(world.id)) }
        }
    }

    @Test fun previewLinkThenSaveImageIntoTheActualCardDirectory() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.ensureRuntime().getOrThrow() }
        val workspace = app.novexWorkspace
        val world = runBlocking { workspace.apply(NovexCommand.CreateWorld("图片归属验收", "雾中邮局")).requireWorld() }
        val module = runBlocking { workspace.apply(NovexCommand.AddModule(ModuleOwner.world(world.id), ContentModuleType.CUSTOM, "镇口", """{"text":"原正文"}""")).requireModule() }
        val png = java.io.ByteArrayOutputStream().also { stream ->
            val bitmap = android.graphics.Bitmap.createBitmap(640, 360, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bitmap).apply {
                drawColor(android.graphics.Color.rgb(36, 63, 88))
                drawText("雾中邮局", 70f, 190f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 56f })
            }
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream); bitmap.recycle()
        }.toByteArray()
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>分享页</html>"))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png)))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png)))
            ui.setContent { MinisTheme(darkTheme = false) { CatalogContentModuleDetailScreen(module.id, onBack = {}, onHelpCreate = {}) } }
            ui.waitUntil(15_000) { ui.onAllNodesWithText("添加代表图（可选）").fetchSemanticsNodes().isNotEmpty() }; settle()
            ui.onNodeWithText("添加代表图（可选）").performClick(); settle()
            ui.onNodeWithText("粘贴图片直链").performClick(); settle()
            ui.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput(server.url("/postoffice.png").toString())
            ui.onNodeWithText("预览图片").performClick()
            ui.waitUntil(15_000) { ui.onAllNodesWithText("这不是可用图片", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertNull(runBlocking { workspace.module(module.id) }!!.image)
            ui.onNodeWithText("预览图片").performClick()
            ui.waitUntil(15_000) { ui.onAllNodesWithText("保存图片").fetchSemanticsNodes().isNotEmpty() }; settle()
            assertNull(runBlocking { workspace.module(module.id) }!!.image)
            ui.onNodeWithText("取消").performClick(); settle()
            assertNull(runBlocking { workspace.module(module.id) }!!.image)
            ui.onNodeWithText("添加代表图（可选）").performClick(); settle()
            ui.onNodeWithText("粘贴图片直链").performClick(); settle()
            ui.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput(server.url("/postoffice.png").toString())
            ui.onNodeWithText("预览图片").performClick()
            ui.waitUntil(15_000) { ui.onAllNodesWithText("保存图片").fetchSemanticsNodes().isNotEmpty() }; settle()
            ui.onNodeWithText("保存图片").performClick()
            ui.waitUntil(15_000) { ui.onAllNodesWithText("更换代表图").fetchSemanticsNodes().isNotEmpty() }; settle()
            val directory = runBlocking { workspace.cardDirectory(NovexContentAddress.world(world.id)) }!!
            assertTrue(directory.walkTopDown().any { it.isFile && it.extension == "png" && it.readBytes().contentEquals(png) })
            assertTrue(File(directory, "card.json").readText().contains("原正文"))
            ui.onNodeWithText("剧情展示").performScrollTo().performClick(); settle()
            val fields = ui.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))
            fields[0].performTextInput("雾中邮局")
            fields[2].performScrollTo().performTextInput("你看见雾中邮局的灯")
            ui.onNodeWithText("命中关键词", substring = true).assertExists()
            ui.onNodeWithText("完成").performClick(); settle()
            assertNull(NovexStoryIllustrations.read(runBlocking { workspace.module(module.id) }!!.module.contentJson, "main"))
            // Use bounded touch scrolling here: the semantic scroll-to loop stalled after dialog dismissal.
            repeat(3) { ui.onRoot().performTouchInput { swipeUp() }; settle() }
            ui.onNodeWithText("保存", substring = false).assertIsDisplayed().performTouchInput { click() }
            ui.waitUntil(15_000) { runBlocking { workspace.module(module.id) }!!.module.contentJson.contains("illustrations") }; settle()
            assertEquals(listOf("雾中邮局"), NovexStoryIllustrations.decode(NovexStoryIllustrations.read(runBlocking { workspace.module(module.id) }!!.module.contentJson, "main"))!!.keys)
            repeat(5) { ui.onRoot().performTouchInput { swipe(start = androidx.compose.ui.geometry.Offset(8f, height * 0.25f), end = androidx.compose.ui.geometry.Offset(8f, height * 0.85f)) }; settle() }
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val capture = File(app.cacheDir, "card-images/module-with-image.png"); capture.parentFile!!.mkdirs()
            capture.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; screenshot.recycle()
        } finally { server.shutdown(); runBlocking { workspace.apply(NovexCommand.DeleteWorld(world.id)) } }
    }
}
