package com.openminis.app.ui.chat

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.ui.theme.MinisTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tests the rendering seam without constructing a chat controller or executing a tool. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexTranscriptRowInteractionTest {
    @get:Rule val ui = createComposeRule()

    @Test fun choiceOnlyPrefillsAndBranchControlsKeepTheirMessageAndLimits() {
        val actions = mutableListOf<ChatTranscriptAction>()
        var row by mutableStateOf<FlatChatItem>(FlatChatItem.AssistantFallbackChoices("reply", listOf("查看信封", "先观察邮局")))
        ui.setContent { MinisTheme(darkTheme = false) {
            ChatTranscriptRow(row, ChatTranscriptRowState(false, false, ThinkingLevel.OFF, false),
                remember { SelectionController() }, remember { PanelExpansionState() }, actions::add)
        } }
        ui.onNodeWithText("查看信封").performTouchInput { click() }
        assertEquals(listOf(ChatTranscriptAction.Prefill("查看信封")), actions)
        ui.runOnIdle { row = FlatChatItem.BranchSwitcher("original-reply", 1, 3) }
        ui.onNodeWithContentDescription("上一分支").assertIsNotEnabled()
        ui.onNodeWithContentDescription("下一分支").performTouchInput { click() }
        assertEquals(ChatTranscriptAction.SwitchBranch("original-reply", 1), actions.last())
        ui.runOnIdle { row = FlatChatItem.BranchSwitcher("last-reply", 3, 3) }
        ui.onNodeWithContentDescription("下一分支").assertIsNotEnabled()
        ui.onNodeWithContentDescription("上一分支").performTouchInput { click() }
        assertEquals(ChatTranscriptAction.SwitchBranch("last-reply", -1), actions.last())
        assertEquals(3, actions.size)
    }
}
