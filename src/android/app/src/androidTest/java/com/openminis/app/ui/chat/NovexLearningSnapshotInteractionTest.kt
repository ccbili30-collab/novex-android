package com.openminis.app.ui.chat

import android.graphics.Bitmap
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.novex.domain.FileNovexLearningRepository
import com.openminis.app.novex.domain.NovexLearningStateJsonCodec
import com.openminis.app.ui.theme.MinisTheme
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Saved production-runner snapshot with neutral labels and deterministic offline note bodies. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexLearningSnapshotInteractionTest {
    @get:Rule val ui = createComposeRule()
    @Test fun restoredProgressNotesAndSourceLinksRemainReadOnlyAndReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = NovexLearningStateJsonCodec.decode(instrumentation.context.assets
            .open("learning-recovery-snapshot.json").bufferedReader().use { it.readText() })
        val directory = File(context.cacheDir, "learning-ui-${UUID.randomUUID()}")
        val store = FileNovexLearningRepository(directory)
        store.save(original)
        val restored = requireNotNull(FileNovexLearningRepository(directory).find(original.collection.ref))
        // Ledger objects intentionally use identity equality; compare the complete persisted representation.
        val originalSnapshot = NovexLearningStateJsonCodec.encode(original)
        assertEquals(originalSnapshot, NovexLearningStateJsonCodec.encode(restored))
        var visible by mutableStateOf(true)
        var page by mutableStateOf("对话仍可操作")
        var continuation: Boolean? = null
        try {
            ui.setContent { MinisTheme(darkTheme = false) {
                Text(page)
                if (visible) NovexLearningDetailsDialog(restored, emptyList(), { visible = false },
                    onLatestResponse = { page = "原始返回入口"; visible = false },
                    onContinue = { continuation = it; visible = false },
                    onFiles = { page = "原文与成果文件入口"; visible = false })
            } }
            ui.onNodeWithText("长资料恢复验收").assertIsDisplayed()
            ui.onNodeWithText("已保存快照：整理 279 / 279", substring = true).performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("无法完整解析 1 项", substring = true).assertIsDisplayed()
            ui.onNodeWithText("当前笔记").performScrollTo().performTouchInput { click() }
            ui.onNodeWithText("笔记1").performTouchInput { click() }
            ui.onNodeWithText("离线控制响应，仅验证保存路径；不评价模型理解。").assertIsDisplayed()
            ui.onNodeWithText("来源：", substring = true).performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("返回笔记列表").performTouchInput { click() }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            ui.waitUntil(15_000) { ui.onAllNodesWithText("资料与整理计划").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("原文与成果文件").performScrollTo().performTouchInput { click() }
            ui.onNodeWithText("原文与成果文件入口").assertIsDisplayed()
            assertEquals(null, continuation)
            ui.runOnIdle { visible = true }
            ui.onNodeWithText("按当前模型续接").performScrollTo().performTouchInput { click() }
            assertEquals(false, continuation)
            ui.onNodeWithText("资料与整理计划").assertDoesNotExist()
            assertEquals(originalSnapshot, NovexLearningStateJsonCodec.encode(requireNotNull(
                FileNovexLearningRepository(directory).find(original.collection.ref))))
            ui.runOnIdle { visible = true }
            ui.onNodeWithText("已保存快照：整理 279 / 279", substring = true).performScrollTo().assertIsDisplayed()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            screenshot?.let { image -> File(context.cacheDir, "learning-restored-progress.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }; image.recycle() }
        } finally { ui.runOnIdle { visible = false }; directory.deleteRecursively() }
    }
}
