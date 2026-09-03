package com.openminis.app.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexAnnouncementTest {
    @Test
    fun `mourning announcement preserves the approved wording`() {
        assertEquals("特别致哀", CurrentNovexAnnouncement.title)
        assertEquals(
            "愿山河无恙，愿人间皆安",
            CurrentNovexAnnouncement.closing,
        )
        assertTrue(CurrentNovexAnnouncement.paragraphs.first().startsWith("今年以来，台风、暴雨、洪涝与地质灾害"))
        assertTrue(CurrentNovexAnnouncement.paragraphs.last().contains("重建家园"))
    }

    @Test
    fun `home action opens announcement and announcement can check updates`() {
        val source = File("src/main/java/com/openminis/app/ui/settings/CheckUpdateSection.kt").readText()

        assertTrue(source.contains("Icons.Outlined.Campaign"))
        assertTrue(source.contains("AnnouncementDialog"))
        assertTrue(source.contains("onCheckUpdate"))
        assertTrue(source.contains("检查更新"))
    }
}
