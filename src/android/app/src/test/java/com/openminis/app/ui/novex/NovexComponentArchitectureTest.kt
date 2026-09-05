package com.openminis.app.ui.novex

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexComponentArchitectureTest {
    @Test
    fun businessPagesUseTheNovexVisualModuleInsteadOfMaterialPageComponentsDirectly() {
        val roots = listOf(File("src/main/java/com/openminis/app/ui"))
        val forbiddenImports = listOf(
            "androidx.compose.material3.AlertDialog",
            "androidx.compose.material3.Button",
            "androidx.compose.material3.DropdownMenu",
            "androidx.compose.material3.ModalBottomSheet",
            "androidx.compose.material3.OutlinedButton",
            "androidx.compose.material3.OutlinedTextField",
            "androidx.compose.material3.Scaffold",
            "androidx.compose.material3.Switch",
            "androidx.compose.material3.TextButton",
            "androidx.compose.material3.TopAppBar",
            "androidx.compose.material3.Card",
            "androidx.compose.material3.Checkbox",
            "androidx.compose.material3.RadioButton",
            "androidx.compose.material3.DropdownMenuItem",
            "androidx.compose.material3.FilterChip",
            "androidx.compose.material3.AssistChip",
            "androidx.compose.material3.Slider",
            "androidx.compose.material3.SegmentedButton",
            "androidx.compose.material3.SingleChoiceSegmentedButtonRow",
            "androidx.compose.material3.ListItem",
            "androidx.compose.material3.FloatingActionButton",
        )
        val violations = roots.asSequence().flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.name in setOf("NovexMaterialControls.kt", "NovexChoiceControls.kt") }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val direct = forbiddenImports.firstOrNull {
                        Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(line.substringBefore("//"))
                    }
                    when {
                        direct != null -> "${file.name}:${index + 1}: $direct"
                        line.trim() == "import androidx.compose.material3.*" ->
                            "${file.name}:${index + 1}: Material3（材料设计 3）通配导入"
                        else -> null
                    }
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
        assertTrue(menu.contains("NovexPopupMenu("))
        assertTrue(File(controls, "DialogTextField.kt").readText().contains("NovexInputSurface("))
        assertTrue(File(controls, "SectionTextField.kt").readText().contains("NovexInputSurface("))
        assertTrue(File(controls, "SectionDropdown.kt").readText().contains("NovexSearchableSelectionSheet("))
    }

    @Test
    fun productIconsHaveOneSourceAndAdvancedConversationActionsRemainReachable() {
        val roots = File("src/main/java/com/openminis/app/ui")
        val pattern = Regex("\\bIcons\\.(?:AutoMirrored\\.)?(?:Default|Filled|Outlined|Rounded)\\.\\w+")
        val violations = roots.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { pattern.containsMatchIn(it.readText().replace(Regex("/\\*.*?\\*/|//[^\\n]*", RegexOption.DOT_MATCHES_ALL), "")) }
            .map { it.name }.toList()
        assertTrue("旧图标仍在产品页面中：$violations", violations.isEmpty())
        assertTrue(
            "业务页面不能用基础输入框绕过统一外观",
            roots.walkTopDown().filter { it.isFile && it.extension == "kt" }
                .none { it.readText().contains("OutlinedTextFieldDefaults.DecorationBox(") },
        )
        val navigation = File("src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt").readText()
        assertTrue(navigation.contains("onSelectModelsClick ="))
        assertTrue(navigation.contains("onScheduledTasksClick ="))
    }
}
