package com.openminis.app.ui.chat

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.novex.domain.*
import com.openminis.app.ui.novex.NovexLibraryPicker
import com.openminis.app.ui.sessions.NovexWorkGroupControls
import com.openminis.app.ui.theme.MinisTheme
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexLibraryInteractionTest {
    private val darkTheme get() = InstrumentationRegistry.getArguments().getString("novexDark") == "true"
    @get:Rule val ui = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    private fun tapVisible(text: String, unmerged: Boolean = false) {
        val node = ui.onNodeWithText(text, useUnmergedTree = unmerged)
        ui.waitUntil(15_000) { node.isDisplayed() }
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // A closing native dialog and the newly exposed sheet must finish their
        // window transition before screen coordinates are used for a real touch.
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val automation = instrumentation.uiAutomation
        val bounds = android.graphics.Rect()
        ui.waitUntil(15_000) {
            // Compose exposes virtual children; Android's text-search shortcut
            // does not traverse every virtual provider. Walk the active tree.
            val pending = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let(pending::add)
            var found = false
            while (pending.isNotEmpty()) {
                val candidate = pending.removeFirst()
                val label = candidate.text?.toString() ?: candidate.contentDescription?.toString().orEmpty()
                if (!found && candidate.isVisibleToUser && candidate.isEnabled && label.lineSequence().any { it == text }) {
                    candidate.getBoundsInScreen(bounds)
                    found = !bounds.isEmpty
                }
                for (index in 0 until candidate.childCount) candidate.getChild(index)?.let(pending::add)
                candidate.recycle()
            }
            found
        }
        // Use the active native window's screen coordinates and real event time
        // when crossing dialog windows, rather than a prior Compose root's clock.
        val downAt = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(downAt, android.os.SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Native touch must be accepted", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            // Native dispatch acknowledgement does not drain the queued Compose
            // pointer coroutine. Consume DOWN before sending UP.
            ui.waitForIdle()
        }
    }
    private fun screenshot(name: String) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(400, 5_000)
        assertEquals(instrumentation.targetContext.packageName, instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(instrumentation.targetContext.cacheDir, "library-ui/$name.png")
        file.parentFile!!.mkdirs()
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (name == "sources" || name == "folder-selection") {
                // These fixtures have a flat background: no bright horizontal stroke belongs
                // in the scrim above the sheet. Keep the screenshot even when this fails.
                val xs = listOf(.08, .25, .5, .75, .92).map { (bitmap.width * it).toInt() }
                for (y in (bitmap.height * .06).toInt() until (bitmap.height * .5).toInt()) {
                    val unexpectedLine = xs.all { x ->
                        val current = android.graphics.Color.red(bitmap.getPixel(x, y))
                        current > android.graphics.Color.red(bitmap.getPixel(x, y - 2)) + 10 &&
                            current > android.graphics.Color.red(bitmap.getPixel(x, y + 2)) + 10
                    }
                    assertFalse("The sheet scrim contains a stray horizontal border at $y", unexpectedLine)
                }
            }
        } finally { bitmap.recycle() }
    }

    @Test fun referenceBrowserSeparatesLibrariesAndFilesAndKeepsSelectionsAcrossFolders() {
        val game = NovexContentAddress.interactiveFiction("fixture-game")
        val file = NovexContentAddress(NovexContentKind.CREATIVE_ARTIFACT, "fixture-file")
        val entries = listOf(NovexLibraryEntry(game, "西幻人生模拟器", "文游"), NovexLibraryEntry(file, "资料与主题索引", "文件"))
        val library = NovexWorkGroup("lib", "西幻", setOf(game, file), listOf(NovexLibraryFolder("folder", "资料")), mapOf(file to "folder"))
        var chosen: Set<NovexContentAddress>? = null
        ui.setContent { MinisTheme(darkTheme = darkTheme) {
            NovexLibraryPicker("选择管理内容", entries, listOf(library), onDismiss = {}, onConfirm = { chosen = it })
        } }
        ui.onNodeWithText("世界库").assertDoesNotExist()
        ui.onNodeWithText("角色库").assertDoesNotExist()
        ui.onNodeWithText("资料与主题索引").assertDoesNotExist()
        ui.waitUntil(15_000) { ui.onNodeWithText("文游库").isDisplayed() }
        screenshot("sources")
        ui.onNodeWithText("文游库").performTouchInput { click() }
        ui.onNodeWithText("西幻人生模拟器").performTouchInput { click() }
        ui.onNodeWithText("资料与主题索引").assertDoesNotExist()
        ui.onNodeWithText("返回上一级").performTouchInput { click() }
        ui.onNodeWithText("创作库").performTouchInput { click() }
        ui.onNodeWithText("西幻").performTouchInput { click() }
        ui.onNodeWithText("资料", useUnmergedTree = true).performTouchInput { click() }
        ui.onNodeWithText("资料与主题索引").performTouchInput { click() }
        screenshot("folder-selection")
        ui.onNodeWithText("完成（已选 2 项）").performTouchInput { click() }
        ui.runOnIdle { assertEquals(setOf(game, file), chosen) }
    }

    @Test fun settingsOverviewShowsFourGroupsAndOpensOnlyTheChosenEditor() {
        var page by mutableStateOf("")
        ui.setContent { MinisTheme(darkTheme = darkTheme) {
            com.openminis.app.ui.novex.NovexDetailScaffold("对话设置", onBack = {}) {
                ConversationSettingsOverview("诺瓦", "未设置", 0, "未启动", 0, "批准", false, { page = it }, { page = "permission" })
            }
        } }
        ui.onNodeWithText("身份与回答").assertExists()
        ui.onNodeWithText("使用的设定").assertExists()
        ui.onNodeWithText("内容与工具").assertExists()
        ui.onNodeWithText("其他设置").assertExists()
        ui.onNodeWithText("空白世界卡").assertDoesNotExist()
        screenshot("settings-overview")
        ui.onNodeWithText("我的身份").performTouchInput { click() }
        ui.runOnIdle { assertEquals("player", page) }
        ui.onNodeWithText("工具权限").performScrollTo().performTouchInput { click() }
        ui.runOnIdle { assertEquals("permission", page) }
    }

    @Test fun gameLibraryKeepsTheCoverAndHasOneCreationEntry() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.awaitMinimum().getOrThrow() }
        val groups = com.openminis.app.data.creative.RoomNovexWorkGroups(app.database)
        val oldSelection = runBlocking { groups.snapshots.first().selection }
        val title = "超高自由度真实西幻人生模拟器 V1.3"
        val card = runBlocking {
            groups.select(NovexWorkGroupSnapshot.ALL)
            app.novexWorkspace.apply(NovexCommand.SaveInteractiveFictionPage(null, title, "在持续运转的西幻世界中，开启你的人生。")).requireInteractiveFiction()
        }
        var opened: String? = null
        var created = false
        try {
            ui.setContent { MinisTheme(darkTheme = darkTheme) {
                com.openminis.app.ui.sessions.NovexInteractiveFictionLibraryRoot(
                    onOpenInteractiveFiction = { opened = it }, onCreateInteractiveFiction = { created = true },
                    onOpenSettings = {}, onConfigureConversation = {})
            } }
            ui.waitUntil(15_000) { ui.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            ui.waitUntil(15_000) { ui.onNodeWithText(title).isDisplayed() }
            ui.onNodeWithText("新建文游").assertDoesNotExist()
            ui.onNodeWithText("导入文游卡").assertDoesNotExist()
            screenshot("game-library")
            ui.onNodeWithText(title).performTouchInput { click() }
            ui.runOnIdle { assertEquals(card.id, opened) }
            ui.onNodeWithText("新建").performTouchInput { click() }
            ui.onNodeWithText("新建文游").performTouchInput { click() }
            ui.runOnIdle { assertTrue(created) }
        } finally { runBlocking {
            app.novexWorkspace.apply(NovexCommand.DeleteInteractiveFiction(card.id)); groups.select(oldSelection)
        } }
    }

    @Test fun fileLibraryShowsReadableNamesAndReceivesNewFilesWhileOpen() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.awaitMinimum().getOrThrow() }
        val groups = com.openminis.app.data.creative.RoomNovexWorkGroups(app.database)
        val oldSelection = runBlocking { groups.snapshots.first().selection }
        val repository = app.creativeArtifactRepository
        val ids = mutableListOf<String>()
        val rawName = "超高自由度真实西幻人生模拟器.txt-" + "a".repeat(64) + ".md"
        val cleanName = "超高自由度真实西幻人生模拟器.md"
        var opened: String? = null
        try {
            runBlocking {
                groups.select(NovexWorkGroupSnapshot.ALL)
                // Clean only this test's synthetic artifacts after an interrupted instrumentation run.
                repository.list(com.openminis.app.data.creative.CreativeArtifactQuery(conversationId = "library-ui-fixture")).forEach {
                    repository.moveToTrash(it.artifact.id); repository.permanentlyDelete(it.artifact.id)
                }
                ids += repository.capture(rawName, CreativeArtifactKind.DOCUMENT, "原始资料".toByteArray(), "text/markdown",
                    CreativeArtifactOrigin("library-ui-fixture", "main")).artifact.id
            }
            ui.setContent { MinisTheme(darkTheme = darkTheme) {
                com.openminis.app.ui.creative.CreativeLibraryScreen(repository, app.creativeArtifactDeviceDirectory,
                    app.novexWorkspace, null, onBack = {}, onOpenArtifact = { record, file ->
                        opened = record.artifact.id
                        assertEquals("原始资料", file.readText())
                    })
            } }
            ui.waitUntil(15_000) { ui.onAllNodesWithText(cleanName).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(rawName).assertDoesNotExist()
            runBlocking {
                ids += repository.capture("资料与主题索引.json", CreativeArtifactKind.DOCUMENT, "{}".toByteArray(), "application/json",
                    CreativeArtifactOrigin("library-ui-fixture", "main")).artifact.id
            }
            try {
                ui.waitUntil(15_000) { ui.onAllNodesWithText("资料与主题索引.json").fetchSemanticsNodes().isNotEmpty() }
            } catch (notVisible: androidx.compose.ui.test.ComposeTimeoutException) {
                screenshot("new-file-not-visible")
                File(app.cacheDir, "library-ui/new-file-not-visible-tree.txt").writeText(ui.onRoot().printToString())
                // Inserting above a stable visible row need not move the user's viewport.
                // Searching the lazy list distinguishes an off-screen new row from a missed refresh.
                repeat(3) {
                    ui.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }
                    ui.mainClock.advanceTimeBy(500)
                    ui.waitForIdle()
                }
                ui.onNodeWithText("资料与主题索引.json").assertIsDisplayed()
            }
            screenshot("readable-file-library")
            ui.onNodeWithText(cleanName).performTouchInput { click() }
            ui.waitUntil(15_000) { ui.runOnIdle { opened != null } }
            ui.runOnIdle { assertEquals(ids.first(), opened) }
        } finally { runBlocking {
            ids.forEach { repository.moveToTrash(it); repository.permanentlyDelete(it) }
            groups.select(oldSelection)
        } }
    }

    @Test fun realLibraryCreatesNestedFoldersAndKeepsTheirParentAfterSaving() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.awaitMinimum().getOrThrow() }
        val groups = com.openminis.app.data.creative.RoomNovexWorkGroups(app.database)
        val oldSelection = runBlocking { groups.snapshots.first().selection }
        val id = runBlocking { groups.create("界面验收库") }
        try {
            ui.setContent { MinisTheme(darkTheme = darkTheme) {
                val snapshot by groups.snapshots.collectAsState(initial = null)
                NovexWorkGroupControls(snapshot, openMembersRequest = 1)
            } }
            ui.waitUntil(15_000) { ui.onAllNodesWithText("新建文件夹").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("新建文件夹").performScrollTo().performTouchInput { click() }
            ui.onNode(hasSetTextAction()).performTextInput("资料")
            tapVisible("保存", unmerged = true)
            ui.waitUntil(15_000) { ui.onAllNodesWithText("保存", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            ui.waitUntil(15_000) { ui.onAllNodesWithText("资料", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("资料", useUnmergedTree = true).performScrollTo()
            tapVisible("资料", unmerged = true)
            try {
                ui.waitUntil(15_000) { ui.onAllNodesWithText("界面验收库 / 资料").fetchSemanticsNodes().isNotEmpty() }
            } catch (failure: Throwable) {
                screenshot("library-folder-open-failure")
                throw failure
            }
            ui.onNodeWithText("新建文件夹").performScrollTo().performTouchInput { click() }
            ui.onNode(hasSetTextAction()).performTextInput("人物")
            tapVisible("保存", unmerged = true)
            ui.waitUntil(15_000) { ui.onAllNodesWithText("保存", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            ui.waitUntil(15_000) { ui.onAllNodesWithText("人物", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            screenshot("library-folders")
            val saved = runBlocking { groups.snapshots.first().groups.first { it.id == id } }
            assertEquals(2, saved.folders.size)
            assertEquals(saved.folders.first { it.name == "资料" }.id, saved.folders.first { it.name == "人物" }.parentId)
        } finally { runBlocking { groups.dissolve(id); groups.select(oldSelection) } }
    }
}
