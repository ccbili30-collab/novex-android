package com.openminis.app.ui.chat

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.ui.sessions.SessionListViewModel
import com.openminis.app.ui.theme.MinisTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only on the independent QA emulator. Restores all temporarily disabled providers. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexNoModelDraftInteractionTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())

    @Test fun noModelCanEnterKeepDraftAndReopenWithoutSending() {
        // Match MainActivity's window contract. A default test Activity pans the
        // composer under the IME instead of delivering the resize insets it uses.
        ui.runOnUiThread {
            ui.activity.enableEdgeToEdge()
            ui.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.ensureRuntime().getOrThrow(); app.providerRepository.awaitConfigLoaded() }
        val instances = app.providerRepository.instances.map { it.copy() }
        val appearance = com.openminis.app.ui.settings.getAppearancePrefs(app)
        val meterKey = com.openminis.app.ui.settings.KEY_SHOW_CONTEXT_METER
        val oldMeter = appearance.getBoolean(meterKey, false)
        appearance.edit().putBoolean(meterKey, true).commit()
        val before = runBlocking { app.chatRepository.dao.listSessions().map { it.id }.toSet() }
        var route by mutableStateOf("")
        var visible by mutableStateOf(false)
        var openedSettings = false
        var keyboardVisible = false
        var hideKeyboard: () -> Unit = {}
        var persistedId: String? = null
        try {
            instances.forEach { app.providerRepository.updateInstance(it.copy(isEnabled = false)) }
            assertTrue(app.providerRepository.allVisibleEntries().isEmpty())
            val list = SessionListViewModel(app.chatRepository, app.providerRepository, app)
            route = requireNotNull(list.createNewSession())
            visible = true
            ui.setContent { MinisTheme(darkTheme = false) {
                val keyboard = LocalSoftwareKeyboardController.current
                val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                SideEffect { keyboardVisible = imeVisible; hideKeyboard = { keyboard?.hide() } }
                if (visible) ChatScreen(route, app.chatRepository, app.providerRepository,
                    onBack = { visible = false }, onBackReturnsToList = true,
                    onSettings = { openedSettings = true })
                else Text("对话列表")
            } }
            ui.waitUntil(30_000) { ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
            val meter = ui.onNodeWithContentDescription("上下文占用尚无记录", substring = true).getUnclippedBoundsInRoot()
            val send = ui.onNodeWithContentDescription("Send").getUnclippedBoundsInRoot()
            val clock = ui.onNodeWithContentDescription("深度求索峰谷计时", substring = true).getUnclippedBoundsInRoot()
            assertTrue("Context meter belongs immediately left of send", meter.right <= send.left)
            assertTrue("Context meter shares the composer row", kotlin.math.abs((meter.top.value + meter.bottom.value) - (send.top.value + send.bottom.value)) < 24)
            assertTrue("Clock remains in the header", clock.bottom < meter.top)
            val screenshot = java.io.File(app.cacheDir, "novex-indicators.png")
            ui.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                screenshot.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            val original = "写给邮差的信，先保留这份草稿。"
            ui.onNode(hasSetTextAction()).performTextInput(original)
            ui.waitUntil(10_000) { keyboardVisible }
            ui.waitForIdle()
            ui.onNodeWithContentDescription("Send").performTouchInput { click() }
            ui.waitUntil(10_000) { ui.onAllNodesWithText("连接模型后再发送").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("连接模型后再发送").assertIsDisplayed()
            ui.onNodeWithText("继续编辑").performClick()
            ui.onNode(hasSetTextAction()).assertTextEquals(original)
            ui.waitUntil(30_000) { runBlocking {
                app.chatRepository.dao.listSessions().firstOrNull { it.id !in before && it.composerDraft == original }
                    ?.also { persistedId = it.id } != null
            } }
            val id = requireNotNull(persistedId)
            assertEquals(0, runBlocking { app.chatRepository.messageCount(id) })
            ui.onNodeWithContentDescription("Send").performTouchInput { click() }
            ui.waitUntil(15_000) { ui.onAllNodesWithText("连接模型", substring = false).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("连接模型", substring = false).performClick()
            assertTrue(openedSettings)
            ui.onNode(hasSetTextAction()).assertTextEquals(original)
            // Android consumes Back to dismiss an open IME. Close it before testing page exit.
            ui.runOnIdle { hideKeyboard() }
            ui.waitUntil(10_000) { !keyboardVisible }
            // Leave through the actual system-back boundary; then discard the cached controller.
            ui.waitUntil(10_000) { ui.onAllNodesWithText("连接模型后再发送").fetchSemanticsNodes().isEmpty() }
            ui.waitForIdle()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            ui.waitUntil(10_000) { !visible }
            runBlocking { ChatViewModelStore.stopAndJoin(id); ChatViewModelStore.finishDeletion(id, false) }
            ui.runOnIdle { route = id; visible = true }
            // Loading the saved draft is asynchronous; field creation is not restoration completion.
            ui.waitUntil(30_000) { ui.onAllNodes(hasSetTextAction() and hasText(original)).fetchSemanticsNodes().size == 1 }
            ui.onNode(hasSetTextAction()).assertTextEquals(original)
            assertEquals(0, runBlocking { app.chatRepository.messageCount(id) })
        } catch (failure: Throwable) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                java.io.File(app.cacheDir, "draft-navigation-failure.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            println("Draft navigation failure: visible=$visible keyboard=$keyboardVisible settings=$openedSettings")
            println(ui.onRoot().printToString(maxDepth = 5))
            throw failure
        } finally {
            ui.runOnIdle { visible = false }
            persistedId?.let { id -> runBlocking {
                ChatViewModelStore.stopAndJoin(id)
                app.chatRepository.deleteSession(id)
                ChatViewModelStore.finishDeletion(id, true)
            } }
            instances.forEach(app.providerRepository::updateInstance)
            appearance.edit().putBoolean(meterKey, oldMeter).commit()
        }
    }
}
