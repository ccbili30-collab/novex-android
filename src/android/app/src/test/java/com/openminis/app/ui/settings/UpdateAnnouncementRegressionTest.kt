package com.openminis.app.ui.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAnnouncementRegressionTest {
    private val source by lazy {
        File("src/main/java/com/openminis/app/ui/settings/CheckUpdateSection.kt").readText()
    }

    @Test
    fun `new version automatically opens its full announcement once`() {
        assertTrue(source.contains("NovexUpdateAnnouncementStore"))
        assertTrue(source.contains("LaunchedEffect(detectedUpdate?.versionName"))
        assertTrue(source.contains("markShown"))
    }

    @Test
    fun `detected update label and icon share one click target`() {
        assertTrue(source.contains(".clickable(enabled = !checking)"))
    }
}
