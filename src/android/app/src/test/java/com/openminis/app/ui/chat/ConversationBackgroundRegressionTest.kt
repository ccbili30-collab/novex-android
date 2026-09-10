package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationBackgroundRegressionTest {
    @Test
    fun `conversation settings expose background preview pick reset and save wiring`() {
        val screen = File("src/main/java/com/openminis/app/ui/chat/ConversationSettingsScreen.kt").readText()
        val viewModel = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt").readText()
        val repository = File("src/main/java/com/openminis/app/data/repository/ChatRepository.kt").readText()

        assertTrue(screen.contains("SettingsTitle(\"对话背景\")"))
        assertTrue(screen.contains("\"选择背景\""))
        assertTrue(screen.contains("Text(\"恢复来源背景\")"))
        assertTrue(screen.contains("backgroundPath = backgroundOverride"))
        assertTrue(viewModel.contains("_conversationBackgroundPathOverride"))
        assertTrue(repository.contains("chatBackgroundPath = value.backgroundPath"))
    }
}
