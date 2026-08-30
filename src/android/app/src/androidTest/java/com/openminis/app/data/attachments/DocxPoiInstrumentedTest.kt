package com.openminis.app.data.attachments

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocxPoiInstrumentedTest {
    @Test
    fun realProducerDocumentsParseOnAndroidRuntime() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixtureAssets = instrumentation.context.assets
        val cases = mapOf(
            "microsoft-word-hyperlink.docx" to "http://poi.apache.org/",
            "libreoffice-comment.docx" to "This is the first line",
            "wps-office-official-template.docx" to "没有可提取的可见正文",
            "google-docs-sample.docx" to "The Canons of Rhetoric",
        )

        cases.forEach { (assetName, expectedText) ->
            val localFile = File(context.cacheDir, "instrumented-$assetName")
            fixtureAssets.open("docx/$assetName").use { input ->
                localFile.outputStream().use(input::copyTo)
            }

            val result = requireNotNull(
                DocumentTextExtractor.extract(context, localFile, null, assetName),
            )

            assertTrue("$assetName did not use POI: ${result.extractionEngine}", result.extractionEngine == "poi-on-android")
            assertTrue("$assetName did not contain $expectedText", result.text.contains(expectedText))
        }
    }
}
