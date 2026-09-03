package com.openminis.app.ui.novex

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexComponentArchitectureTest {
    @Test
    fun businessPagesUseTheNovexVisualModuleInsteadOfMaterialPageComponentsDirectly() {
        val roots = listOf(
            File("src/main/java/com/openminis/app/ui/settings"),
            File("src/main/java/com/openminis/app/ui/novex"),
        )
        val forbiddenImports = listOf(
            "androidx.compose.material3.AlertDialog",
            "androidx.compose.material3.Button",
            "androidx.compose.material3.DropdownMenu",
            "androidx.compose.material3.ModalBottomSheet",
            "androidx.compose.material3.OutlinedButton",
            "androidx.compose.material3.OutlinedTextField",
            "androidx.compose.material3.Scaffold",
            "androidx.compose.material3.TextButton",
            "androidx.compose.material3.TopAppBar",
        )
        val violations = roots.asSequence().flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.name == "NovexMaterialControls.kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    forbiddenImports.firstOrNull { line.trim() == "import $it" }
                        ?.let { "${file.name}:${index + 1}: $it" }
                }
            }
            .toList()

        assertTrue(
            "业务页面必须从 Novex 视觉模块取得页面级控件：\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun bothApplicationEntryPointsUseTheSingleAppearanceModule() {
        val lightweightHome = File("src/main/java/com/openminis/app/NovexHomeSurface.kt").readText()
        val fullRuntime = File("src/main/java/com/openminis/app/MainActivity.kt").readText()

        assertTrue(lightweightHome.contains("NovexAppTheme"))
        assertTrue(fullRuntime.contains("NovexAppTheme"))
        assertTrue(!lightweightHome.contains("MinisTheme("))
        assertTrue(!fullRuntime.contains("MinisTheme("))
    }

    @Test
    fun compatibilityControlsOnlyForwardToTheNovexVisualModule() {
        val controls = File("src/main/java/com/openminis/app/ui/components")
        val buttons = File(controls, "MinisButton.kt").readText()
        val dialog = File(controls, "MinisAlertDialog.kt").readText()
        val menu = File(controls, "MinisMenu.kt").readText()

        assertTrue(buttons.contains("com.openminis.app.ui.novex.Button"))
        assertTrue(buttons.contains("com.openminis.app.ui.novex.OutlinedButton"))
        assertTrue(buttons.contains("com.openminis.app.ui.novex.TextButton"))
        assertTrue(dialog.contains("com.openminis.app.ui.novex.AlertDialog"))
        assertTrue(menu.contains("NovexColors.Surface"))
    }
}
