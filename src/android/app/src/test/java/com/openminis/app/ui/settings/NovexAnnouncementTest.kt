package com.openminis.app.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(source.contains("Icons.Outlined.FileDownload"))
        assertTrue(source.contains("AnnouncementDialog"))
        assertTrue(source.contains("onCheckUpdate"))
        assertTrue(source.contains("检查更新"))
        assertTrue(source.contains("MarkdownText("))
        assertTrue(source.contains("ReleaseNotesList"))
        assertTrue(source.contains("包含的往期更新"))
        assertFalse(source.contains("text = update.changelog.ifBlank"))
        assertFalse(source.contains("if (detectedUpdate == null) \"公告\""))
    }

    @Test
    fun `dismissed update restores announcement action until user checks again`() {
        assertEquals(
            NovexHomeAction.UPDATE,
            resolveNovexHomeAction(detectedVersion = "0.2.10", dismissedVersion = null),
        )
        assertEquals(
            NovexHomeAction.ANNOUNCEMENT,
            resolveNovexHomeAction(detectedVersion = "0.2.10", dismissedVersion = "0.2.10"),
        )
        assertEquals(
            NovexHomeAction.ANNOUNCEMENT,
            resolveNovexHomeAction(detectedVersion = null, dismissedVersion = null),
        )
    }
}
