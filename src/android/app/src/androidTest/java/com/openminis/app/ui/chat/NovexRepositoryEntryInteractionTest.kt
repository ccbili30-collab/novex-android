package com.openminis.app.ui.chat

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.data.model.*
import com.openminis.app.data.creative.WorkspaceCreativeArtifactBridge
import com.openminis.app.novex.domain.*
import com.openminis.app.tools.NovexWorkspaceAgentTools
import com.openminis.app.ui.navigation.AppNavigation
import com.openminis.app.ui.navigation.Routes
import com.openminis.app.ui.novex.NovexLibraryPicker
import com.openminis.app.ui.sessions.NovexWorkGroupControls
import com.openminis.app.ui.theme.MinisTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexRepositoryEntryInteractionTest {
    private val darkTheme get() = InstrumentationRegistry.getArguments().getString("novexDark") == "true"
    @get:Rule val ui = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ui.mainClock.advanceTimeBy(600)
        ui.waitForIdle()
        Thread.sleep(250)
        val image = instrumentation.uiAutomation.takeScreenshot()
        val file = java.io.File(instrumentation.targetContext.cacheDir, "repository-repair/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private fun systemBack() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input keyevent 4")).use { it.readBytes() }
        ui.mainClock.advanceTimeBy(700)
        ui.waitForIdle()
        Thread.sleep(500)
    }

    @Test fun promotedDraftOpensTheSameRepositoryThatBelongsToTheSavedConversation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.ensureRuntime().getOrThrow() }
        val providerId = "repository-test-${UUID.randomUUID()}"
        val model = LLMModel("repository-local", "本地仓库验收", "openai", supportsTools = true)
        app.providerRepository.addInstance(ProviderInstance(providerId, "本地仓库验收", ProviderType.openAI,
            ProviderCredential.apiKey, customBaseURL = "http://127.0.0.1:1"))
        app.providerRepository.addEntry(ModelEntry(providerId, model, isCustom = true))
        val entry = app.providerRepository.entriesFor(providerId).single()
        val group = ModelGroup(name = "本地仓库验收", memberEntryIds = mutableListOf(entry.id))
        app.providerRepository.addGroup(group)
        val before = runBlocking { app.chatRepository.observeSessions().first().map { it.id }.toSet() }
        var savedId: String? = null
        val route = Routes.chat("__new__${UUID.randomUUID()}")
        try {
            ui.setContent { MinisTheme(darkTheme = darkTheme) {
                AppNavigation(app.chatRepository, app.providerRepository,
                    initialRoute = route)
            } }
            ui.waitUntil(30_000) {
                savedId = runBlocking { app.chatRepository.observeSessions().first().firstOrNull { it.id !in before }?.id }
                savedId != null
            }
            val id = requireNotNull(savedId)
            val titles = listOf("镇口说明", "人物档案", "历史记录")
            runBlocking { titles.forEach { title ->
                val imported = NovexWorkspaceImport(app.conversationWorkspaceStore).save(id, title, "text/plain",
                    "$title 的原始正文".byteInputStream())
                WorkspaceCreativeArtifactBridge(app.conversationWorkspaceStore, app.creativeArtifactRepository)
                    .registerImported(imported.entry)
                val result = NovexWorkspaceAgentTools(app.conversationWorkspaceStore).execute("workspace_search",
                    org.json.JSONObject().put("query", title).toString(), NovexWorkspaceImport.scope(id),
                    NovexWorkspaceProvenance(id, NovexConversationWorkspaceScope.ROOT_BRANCH),
                    app.creativeArtifactRepository.visibleSourcePaths(id))
                assertTrue(result.output, result.success)
                val matches = org.json.JSONObject(result.output).getJSONObject("data").getJSONArray("entries")
                assertEquals("工具应命中同一份资料：${result.output}", 1, matches.length())
                assertTrue(matches.toString(), matches.toString().contains(title))
            } }
            ui.mainClock.advanceTimeBy(700)
            ui.waitForIdle()
            ui.onNodeWithContentDescription("更多操作").performClick()
            ui.waitUntil(10_000) { ui.onAllNodesWithText("资料与存档").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("资料与存档").performTouchInput { click() }
            ui.onNodeWithText("本对话文件").performTouchInput { click() }
            ui.waitUntil(15_000) { ui.onAllNodesWithText("对话仓库").fetchSemanticsNodes().isNotEmpty() }
            try { ui.waitUntil(10_000) { ui.onAllNodesWithText(titles.first()).fetchSemanticsNodes().isNotEmpty() } }
            finally { capture("repository-open"); println(ui.onRoot(useUnmergedTree = true).printToString()) }
            titles.forEach { ui.onNodeWithText(it).assertIsDisplayed() }
        } finally {
            savedId?.let { runBlocking { app.chatRepository.deleteSession(it) } }
            app.providerRepository.removeGroup(group.id)
            app.providerRepository.removeInstance(providerId)
        }
    }

    @Test fun dismissingLibraryPickerInsideFolderDoesNotLeaveATransparentTouchBlocker() {
        val address = NovexContentAddress.world("touch-world")
        var settingsClicks = 0
        var dismissed = false
        ui.setContent { MinisTheme(darkTheme = darkTheme) {
            var open by remember { mutableStateOf(false) }
            Column {
                Button(onClick = { settingsClicks++ }) { Text("设置验收") }
                Button(onClick = { open = true }) { Text("打开选库") }
            }
            if (open) NovexLibraryPicker("选择内容",
                listOf(NovexLibraryEntry(address, "镇口设定", "世界")),
                listOf(NovexWorkGroup("library", "西幻资料", setOf(address),
                    listOf(NovexLibraryFolder("folder", "小镇")), mapOf(address to "folder"))),
                onDismiss = { open = false; dismissed = true }, onConfirm = { open = false })
        } }
        ui.onNodeWithText("打开选库").performTouchInput { click() }
        ui.onNodeWithText("创作库").performTouchInput { click() }
        ui.onNodeWithText("西幻资料").performTouchInput { click() }
        ui.onNodeWithText("小镇").performTouchInput { click() }
        ui.onNodeWithText("镇口设定").assertIsDisplayed()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        systemBack()
        capture("picker-dismissed")
        // Inject through Android's window manager, not directly into the underlying Compose root.
        val center = ui.onNodeWithText("设置验收").fetchSemanticsNode().boundsInWindow.center
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, center.x, center.y, 0)
        val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, center.x, center.y, 0)
        try { automation.injectInputEvent(down, true); automation.injectInputEvent(up, true) }
        finally { down.recycle(); up.recycle() }
        ui.waitForIdle()
        assertEquals("下层第一次点击必须可用", 1, settingsClicks)
        assertTrue("关闭弹层必须结束选择流程", dismissed)
    }

    @Test fun closingLibraryManagementInsideFolderReleasesItsActualWindow() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.ensureRuntime().getOrThrow() }
        var clicked = 0
        val library = NovexWorkGroup("dismiss-library", "关闭验收库", emptySet(),
            listOf(NovexLibraryFolder("child", "验收文件夹")))
        ui.setContent { MinisTheme(darkTheme = darkTheme) { Column {
            Button(onClick = { clicked++ }) { Text("底部导航验收") }
            NovexWorkGroupControls(NovexWorkGroupSnapshot(listOf(library), library.id), openMembersRequest = 1)
        } } }
        ui.waitUntil(15_000) { ui.onAllNodesWithText("验收文件夹").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("验收文件夹").performClick()
        ui.onNodeWithText("关闭验收库 / 验收文件夹").assertIsDisplayed()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        systemBack()
        capture("management-dismissed")
        val center = ui.onNodeWithText("底部导航验收").fetchSemanticsNode().boundsInWindow.center
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, center.x, center.y, 0)
        val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, center.x, center.y, 0)
        try { automation.injectInputEvent(down, true); automation.injectInputEvent(up, true) }
        finally { down.recycle(); up.recycle() }
        ui.waitForIdle()
        assertEquals("创作库关闭后底层操作必须可用", 1, clicked)
        ui.onNodeWithText("关闭验收库 / 验收文件夹").assertDoesNotExist()
    }
}
