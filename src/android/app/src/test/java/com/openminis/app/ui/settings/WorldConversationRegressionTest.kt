package com.openminis.app.ui.settings

import com.openminis.app.data.character.WorldEntity
import com.openminis.app.data.character.toStoryWorldSnapshot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldConversationRegressionTest {
    @Test
    fun `new catalog world can create a world-only conversation`() {
        val source = File("src/main/java/com/openminis/app/ui/settings/WorldCatalogScreens.kt").readText()
        val navigation = File("src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt").readText()
        val viewModel = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt").readText()

        assertTrue(source.contains("Text(\"开始世界对话\")"))
        assertFalse(source.contains("if (legacyWorld != null) {\n                    SettingsSection(header = \"Nova 世界助手\")"))
        assertTrue(navigation.contains("buildChatDraftId("))
        assertTrue(viewModel.contains("createWorldProfile(initialWorldId, draftPersona)"))
    }

    @Test
    fun `catalog world snapshot keeps its editable overview and managed background`() {
        val world = WorldEntity(
            id = "world-new",
            name = "群星海",
            overview = "群岛漂浮在永夜天空中。",
            createdAt = 10,
            updatedAt = 20,
        )

        val snapshot = world.toStoryWorldSnapshot(
            moduleText = "时间线\n第一颗星在纪元元年熄灭。",
            backgroundPath = "/managed/world.jpg",
        )

        assertEquals("world-new", snapshot.id)
        assertEquals("群星海", snapshot.name)
        assertTrue(snapshot.description.contains("群岛漂浮在永夜天空中。"))
        assertTrue(snapshot.description.contains("第一颗星在纪元元年熄灭。"))
        assertEquals("/managed/world.jpg", snapshot.backgroundPath)
    }
}
