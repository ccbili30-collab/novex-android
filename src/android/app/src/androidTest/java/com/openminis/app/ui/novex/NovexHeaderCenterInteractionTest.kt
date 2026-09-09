package com.openminis.app.ui.novex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openminis.app.ui.theme.MinisTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NovexHeaderCenterInteractionTest {
    @get:Rule val ui = createComposeRule()

    @Test fun titleUsesScreenCenterWithUnequalActionsAndLongNames() {
        var trailingWidth by mutableStateOf(96.dp)
        var label by mutableStateOf("仓库分页验收")
        ui.setContent { MinisTheme(darkTheme = false) {
            NovexTopBarSurface(
                modifier = Modifier.testTag("header"),
                title = { Box(Modifier.fillMaxWidth().testTag("title"), contentAlignment = Alignment.Center) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } },
                navigation = { Box(Modifier.size(48.dp).testTag("leading")) },
                actions = { Box(Modifier.size(trailingWidth, 48.dp).testTag("trailing")) },
            )
        } }
        fun check() {
            val header = ui.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
            val title = ui.onNodeWithTag("title").fetchSemanticsNode().boundsInRoot
            val leading = ui.onNodeWithTag("leading").fetchSemanticsNode().boundsInRoot
            val trailing = ui.onNodeWithTag("trailing").fetchSemanticsNode().boundsInRoot
            assertEquals("标题必须按屏幕中线居中", header.center.x, title.center.x, 1f)
            assertTrue(title.left >= leading.right)
            assertTrue(title.right <= trailing.left)
        }
        check()
        ui.runOnIdle { trailingWidth = 144.dp; label = "这是一个需要省略但不能挤压时钟和菜单按钮的长对话标题" }
        check()
        ui.runOnIdle { trailingWidth = 48.dp }
        check()
    }
}
