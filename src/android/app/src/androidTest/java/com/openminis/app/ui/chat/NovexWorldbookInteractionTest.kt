package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.novex.domain.*
import com.openminis.app.ui.novex.NovexGameWorldbookSection
import com.openminis.app.ui.theme.MinisTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexWorldbookInteractionTest {
    @get:Rule val ui = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    private fun settle() { ui.mainClock.advanceTimeBy(700); ui.waitForIdle() }
    private fun capture(name: String) {
        settle(); Thread.sleep(250)
        val inst = InstrumentationRegistry.getInstrumentation()
        val bitmap = inst.uiAutomation.takeScreenshot()
        val file = java.io.File(inst.targetContext.cacheDir, "worldbook-ui/$name.png")
        file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun selectTwoExistingWorldbooksAndSaveOrCancelTheirDefaults() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.ensureRuntime().getOrThrow() }
        val workspace = app.novexWorkspace
        val suffix = UUID.randomUUID().toString().take(4)
        val world1 = runBlocking { workspace.apply(NovexCommand.CreateWorld("夜钟$suffix", "钟楼设定")).requireWorld() }
        val world2 = runBlocking { workspace.apply(NovexCommand.CreateWorld("文风$suffix", "叙事规则")).requireWorld() }
        val game = runBlocking { workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "邮差$suffix", "送信冒险")).requireInteractiveFiction() }
        val module1 = runBlocking { workspace.apply(NovexCommand.AddModule(com.openminis.app.data.character.ModuleOwner.world(world1.id),
            com.openminis.app.data.character.ContentModuleType.CUSTOM, "钟楼规则", """{"text":"钟楼规则"}""")).requireModule() }
        val module2 = runBlocking { workspace.apply(NovexCommand.AddModule(com.openminis.app.data.character.ModuleOwner.world(world1.id),
            com.openminis.app.data.character.ContentModuleType.CUSTOM, "邮局规则", """{"text":"邮局规则"}""")).requireModule() }
        val service = NovexGameWorldbooks(workspace) { block -> app.database.withTransaction { block() } }
        var fullCard by mutableStateOf(false)
        ui.setContent { MinisTheme(darkTheme = false) {
            if (fullCard) com.openminis.app.ui.navigation.AppNavigation(app.chatRepository, app.providerRepository,
                initialRoute = com.openminis.app.ui.navigation.Routes.interactiveFiction(game.id))
            else Column { NovexGameWorldbookSection(game.id) }
        } }
        ui.onNodeWithText("选择世界书").performClick()
        ui.waitUntil(15_000) { ui.onAllNodesWithText("保存选择").fetchSemanticsNodes().isNotEmpty() }; settle()
        ui.onAllNodesWithText("选择世界书").onLast().performClick(); settle()
        ui.onNodeWithText("世界库").performClick(); settle()
        ui.onNodeWithText(world1.name).performClick(); ui.onNodeWithText(world2.name).performClick()
        capture("01-select-two")
        ui.onNodeWithText("完成（已选 2 项）").performClick(); settle()
        assertTrue(runBlocking { service.load(game.id) }.isEmpty())
        ui.onNodeWithText("保存选择").performClick()
        ui.waitUntil(15_000) { runBlocking { service.load(game.id).size == 2 } }; settle()
        capture("02-saved-defaults")
        ui.onNodeWithText("已选 2 项").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("保存选择").fetchSemanticsNodes().isNotEmpty() }; settle()
        ui.onAllNodes(isToggleable()).onFirst().performClick()
        ui.onNodeWithText("取消").performClick(); settle()
        assertTrue(runBlocking { service.load(game.id) }.all { it.enabled })
        ui.onNodeWithText("已选 2 项").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("保存选择").fetchSemanticsNodes().isNotEmpty() }; settle()
        ui.onAllNodes(isToggleable()).onFirst().performClick()
        capture("03-disable-draft")
        assertTrue(runBlocking { service.load(game.id) }.all { it.enabled })
        ui.onNodeWithText("保存选择").performClick()
        ui.waitUntil(15_000) { runBlocking { service.load(game.id).count { it.enabled } == 1 } }; settle()
        capture("04-disabled-saved")
        ui.onNodeWithText("已选 2 项").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("保存选择").fetchSemanticsNodes().isNotEmpty() }; settle()
        ui.onAllNodesWithText(world1.name).onLast().performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("整本世界书").fetchSemanticsNodes().isNotEmpty() }; settle()
        ui.onNodeWithText("钟楼规则").performClick()
        ui.onNodeWithText("邮局规则").performClick()
        capture("06-module-ranges")
        ui.onNodeWithText("使用选中的范围").performClick(); settle()
        assertEquals(2, runBlocking { service.load(game.id).size })
        ui.onNodeWithText("保存选择").performClick()
        ui.waitUntil(15_000) { runBlocking { service.load(game.id).size == 3 } }; settle()
        assertEquals(setOf(module1.id, module2.id), runBlocking { service.load(game.id).filter { it.target.subject.id == world1.id }.map { it.target.moduleId }.toSet() })
        ui.runOnIdle { fullCard = true }
        ui.waitUntil(15_000) { ui.onAllNodesWithText("使用的世界书").fetchSemanticsNodes().isNotEmpty() }; settle()
        ui.onNodeWithText("使用的世界书").performScrollTo(); settle()
        capture("05-card-detail")
    }
}
