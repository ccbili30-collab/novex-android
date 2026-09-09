package com.openminis.app.ui.chat

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.share.*
import com.openminis.app.ui.novex.NovexConversationExportDialog
import com.openminis.app.ui.theme.MinisTheme
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexConversationExportInteractionTest {
    @get:Rule val ui = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    @Test fun cancelledSaveDoesNothingAndSuccessfulSaveKeepsExactPackageBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "shared/export-test-${UUID.randomUUID()}").also { it.mkdirs() }
        val source = File(folder, "source.zip")
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("messages.json")); zip.write("原话与原环境，不替换正文。".toByteArray()); zip.closeEntry()
        }
        val original = source.readBytes()
        val destination = File(folder, "destination.zip")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destination)
        var cancel = true
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                val intent = contract.createIntent(context, input)
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
                assertEquals("application/zip", intent.type)
                assertTrue(intent.getStringExtra(Intent.EXTRA_TITLE)!!.startsWith("对话包-"))
                dispatchResult(requestCode, if (cancel) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                    if (cancel) null else Intent().setData(uri))
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        try {
            ui.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { MinisTheme(darkTheme = false) {
                NovexConversationExportDialog(NovexConversationExportState(result = NovexConversationBundleResult(source, 1, 1, emptyList(), 0)), {}, {})
            } } }
            ui.waitUntil(15_000) { ui.onAllNodesWithText("保存到文件").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("保存到文件").performClick()
            ui.waitForIdle()
            assertFalse(destination.exists())
            ui.onNodeWithText("对话包已保存到所选位置").assertDoesNotExist()
            cancel = false
            ui.onNodeWithText("保存到文件").performClick()
            ui.waitUntil(15_000) { ui.onAllNodesWithText("对话包已保存到所选位置").fetchSemanticsNodes().isNotEmpty() }
            assertArrayEquals(original, destination.readBytes())
            assertArrayEquals(original, source.readBytes())
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                File(context.cacheDir, "conversation-export-save.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            source.delete()
            ui.onNodeWithText("保存到文件").performClick()
            ui.waitUntil(15_000) { ui.onAllNodesWithText("对话包已失效，请重新导出").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("对话包已保存到所选位置").assertDoesNotExist()
            assertArrayEquals(original, destination.readBytes())
        } finally { folder.deleteRecursively() }
    }
}
