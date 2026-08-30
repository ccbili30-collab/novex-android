package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerAtomFallbackTest {

    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <entry>
            <link rel="alternate" type="text/html" href="https://github.com/ccbili30-collab/novex-android/releases/tag/v0.2.0-preview"/>
            <title>Novex 0.2 Preview</title>
          </entry>
          <entry>
            <link rel="alternate" type="text/html" href="https://github.com/ccbili30-collab/novex-android/releases/tag/v0.1.0-preview"/>
            <title>Novex 0.1 Preview</title>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `newer Atom release becomes downloadable update`() {
        val result = UpdateChecker.parseAtomReleaseFeed(feed, "0.1.0")

        assertTrue(result is UpdateChecker.CheckResult.UpdateAvailable)
        result as UpdateChecker.CheckResult.UpdateAvailable
        assertEquals("v0.2.0-preview", result.tagName)
        assertEquals("0.2.0", result.versionName)
        assertEquals(
            "https://github.com/ccbili30-collab/novex-android/releases/download/v0.2.0-preview/novex.apk",
            result.apkUrl,
        )
    }

    @Test
    fun `same Atom release reports up to date`() {
        val result = UpdateChecker.parseAtomReleaseFeed(feed, "0.2.0")
        assertEquals(UpdateChecker.CheckResult.UpToDate, result)
    }
}
