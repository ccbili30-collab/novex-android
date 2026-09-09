package com.openminis.app.ui.chat

import android.graphics.Bitmap
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.data.creative.*
import com.openminis.app.novex.domain.*
import com.openminis.app.ui.creative.CreativeLibraryScreen
import com.openminis.app.ui.theme.MinisTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexConversationRepositoryTest {
    private val darkTheme get() = InstrumentationRegistry.getArguments().getString("novexDark") == "true"
    @get:Rule val ui = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as MinisApp
    private fun waitImported(id: String) = runBlocking {
        withTimeout(90_000) { app.conversationRepositoryImporter.status(id).first { !it.running } }
    }
    @Test fun stoppingKeepsSavedFilesAndReselectingFinishesWithoutDuplicates() {
        runBlocking { app.startupCoordinator.awaitMinimum().getOrThrow() }
        val id = runBlocking { app.chatRepository.createSession("test-model", title = "导入停止验收").id }
        app.contentResolver.call("com.noven.repository.testfixtures", "seed", "bulk", null)
        val uris = listOf(DocumentsContract.buildDocumentUri("com.noven.repository.testfixtures", "blocked.txt")) +
            (0 until 60).map { DocumentsContract.buildDocumentUri("com.noven.repository.testfixtures", "$it.txt") }
        try {
            app.conversationRepositoryImporter.start(id, files = uris)
            runBlocking { withTimeout(30_000) { app.conversationRepositoryImporter.status(id).first { it.saved > 0 } } }
            app.conversationRepositoryImporter.stop(id)
            val stopped = waitImported(id)
            assertTrue(stopped.saved in 1..59)
            assertEquals(stopped.saved, runBlocking { app.creativeArtifactRepository.list(CreativeArtifactQuery(conversationId = id)).size })
            app.conversationRepositoryImporter.start(id, files = uris)
            val resumed = waitImported(id)
            assertEquals(60, resumed.saved + resumed.reused)
            assertTrue(resumed.issues.any { it.contains("没有读取权限") })
            assertEquals(60, runBlocking { app.creativeArtifactRepository.list(CreativeArtifactQuery(conversationId = id)).size })
            assertEquals(0, runBlocking { app.chatRepository.messageCount(id) })
        } finally { runBlocking {
            app.conversationRepositoryImporter.stopAndJoin(id)
            app.creativeArtifactRepository.list(CreativeArtifactQuery(conversationId = id, includeTrashed = true)).forEach {
                app.creativeArtifactRepository.permanentlyDelete(it.artifact.id)
            }
            app.chatRepository.deleteSession(id)
        }; app.contentResolver.call("com.noven.repository.testfixtures", "clear", null, null) }
    }

    @Test fun folderImportDoesNotSendMessagesAndIsReadableAfterReopenWithoutExposingParsedDuplicates() {
        runBlocking { app.startupCoordinator.awaitMinimum().getOrThrow() }
        val id = runBlocking { app.chatRepository.createSession("test-model", title = "资料仓库验收").id }
        app.contentResolver.call("com.noven.repository.testfixtures", "seed", "folder", null)
        val tree = DocumentsContract.buildTreeDocumentUri("com.noven.repository.testfixtures", "root")
        try {
            app.conversationRepositoryImporter.start(id, folder = tree)
            val result = waitImported(id)
            assertEquals(result.toString(), 4, result.saved)
            assertTrue(result.issueCount >= 1)
            assertEquals(0, runBlocking { app.chatRepository.messageCount(id) })
            val records = runBlocking { app.creativeArtifactRepository.list(CreativeArtifactQuery(conversationId = id)) }
            assertEquals(4, records.size)
            assertTrue(records.all { it.sourcePath!!.contains("/sources/") })
            app.conversationRepositoryImporter.start(id, folder = tree)
            val retry = waitImported(id)
            assertEquals(0, retry.saved)
            assertEquals(4, retry.reused)
            val reopened = FileNovexConversationWorkspaceStore(File(app.filesDir, "novex/conversation-workspaces"))
            val scope = NovexWorkspaceImport.scope(id)
            val tools = NovexConversationWorkspaceTools(scope, NovexWorkspaceVisibility(reopened, records.mapNotNull { it.sourcePath }.toSet()))
            val found = tools.workspaceSearch(null, null, "第三纪元", null, 25)
            val entries = JSONObject(found.toJson()).getJSONObject("data").getJSONArray("entries")
            assertEquals(1, entries.length())
            val originalRef = NovexWorkspaceFileRef.parse(entries.getJSONObject(0).getString("workspace_ref"))
            assertTrue(tools.workspaceRead(NovexWorkspaceReadRequest(originalRef)).toJson().contains("城主是白榆"))
            ui.setContent { MinisTheme(darkTheme = darkTheme) {
                CreativeLibraryScreen(app.creativeArtifactRepository, app.creativeArtifactDeviceDirectory, app.novexWorkspace,
                    id, onBack = {}, onOpenArtifact = { _, _ -> })
            } }
            ui.waitUntil(15_000) { ui.onNodeWithText("项目资料").isDisplayed() }
            ui.onNodeWithText("导入资料", useUnmergedTree = true).performClick()
            ui.onNodeWithText("选择文件").assertExists()
            ui.onNodeWithText("选择文件夹").assertExists()
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            ui.waitUntil(15_000) { ui.onAllNodesWithText("选择文件").fetchSemanticsNodes().isEmpty() }
            ui.onNodeWithText("项目资料").performTouchInput { click() }
            ui.onNodeWithText("世界").assertExists()
            ui.onNodeWithText("人物.txt").assertExists()
            ui.onNodeWithText("世界").performTouchInput { click() }
            ui.onNodeWithText("历史.txt").assertExists()
            ui.onNodeWithText("返回上一级").performTouchInput { click() }
            ui.onAllNodes(hasSetTextAction()).onFirst().performTextInput("历史")
            ui.onNodeWithText("历史.txt").assertExists()
            ui.onNodeWithText("人物.txt").assertDoesNotExist()
            ui.onNodeWithText("查找文件名称").assertDoesNotExist()
            ui.onAllNodes(hasSetTextAction()).onFirst().performTextClearance()
            val accessibilityInfo = instrumentation.uiAutomation.serviceInfo
            accessibilityInfo.flags = accessibilityInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            instrumentation.uiAutomation.serviceInfo = accessibilityInfo
            fun keyboardVisible() = instrumentation.uiAutomation.windows.any {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.root?.isVisibleToUser == true
            }
            ui.waitUntil(10_000) { keyboardVisible() }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            ui.waitUntil(10_000) { !keyboardVisible() }
            ui.onNodeWithText("人物.txt").assertIsDisplayed()
            ui.waitForIdle()
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(400, 5_000)
            val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(app.cacheDir, "repository-ui/folder.png").apply { parentFile!!.mkdirs(); outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            image.recycle()
            val record = records.first { it.sourcePath == originalRef.value }
            runBlocking { app.creativeArtifactRepository.moveToTrash(record.artifact.id) }
            val remaining = runBlocking { app.creativeArtifactRepository.list(CreativeArtifactQuery(conversationId = id)) }.mapNotNull { it.sourcePath }.toSet()
            val removed = NovexConversationWorkspaceTools(scope, NovexWorkspaceVisibility(reopened, remaining))
            assertEquals("workspace.not_found", removed.workspaceRead(NovexWorkspaceReadRequest(originalRef)).code)
            assertEquals(0, runBlocking { app.chatRepository.messageCount(id) })
        } finally {
            runBlocking { app.conversationRepositoryImporter.stopAndJoin(id)
                app.creativeArtifactRepository.list(CreativeArtifactQuery(conversationId = id, includeTrashed = true)).forEach {
                    app.creativeArtifactRepository.permanentlyDelete(it.artifact.id)
                }
                app.chatRepository.deleteSession(id)
            }
            app.contentResolver.call("com.noven.repository.testfixtures", "clear", null, null)
        }
    }
}
