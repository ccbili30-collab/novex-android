package com.openminis.app.data

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class TextStylePresetsTest {

    @Test
    fun genreCardsLoadFromManifestWithFrontmatterStripped() {
        val presets = TextStylePresets.load(RuntimeEnvironment.getApplication())
        assertEquals("manifest lists 32 genre cards", 32, presets.size)
        assertTrue(
            " 东方仙侠 card present: ${presets.map { it.id }}",
            presets.any { it.id == "东方仙侠" },
        )
        val xianxia = presets.first { it.id == "东方仙侠" }
        assertTrue("frontmatter stripped", !xianxia.content.startsWith("---"))
        assertTrue("body survives", xianxia.content.contains("正文提示词"))
        assertTrue("no empty cards", presets.all { it.content.length > 100 })
    }
}
