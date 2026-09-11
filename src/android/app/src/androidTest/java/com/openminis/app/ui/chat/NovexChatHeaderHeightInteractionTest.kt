package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openminis.app.ui.novex.TopAppBar
import com.openminis.app.ui.theme.MinisTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexChatHeaderHeightInteractionTest {
    @get:Rule val ui = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())

    @Test fun threeChatHeaderRowsReceiveTheHeightRequestedByTheChatPage() {
        ui.setContent { MinisTheme(darkTheme = false) {
            TopAppBar(modifier = Modifier.testTag("chat-header"), expandedHeight = chatTopBarExpandedHeightDp(1f).dp,
                windowInsets = WindowInsets(0), title = { Column { Text("对话标题"); Text("模型组"); Text("提供商与模型") } })
        } }
        ui.onNodeWithTag("chat-header").assertHeightIsEqualTo(76.dp)
    }
}
