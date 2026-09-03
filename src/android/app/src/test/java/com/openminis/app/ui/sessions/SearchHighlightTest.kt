package com.openminis.app.ui.sessions

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHighlightTest {
    @Test
    fun pureBuilderHighlightsEveryCaseInsensitiveMatch() {
        val result = buildHighlightedAnnotatedString(
            text = "Novex novEX",
            query = "novex",
            highlightBg = Color.Yellow,
            highlightFg = Color.Black,
        )

        assertEquals("Novex novEX", result.text)
        assertEquals(listOf(0 until 5, 6 until 11), result.spanStyles.map { it.start until it.end })
    }

    @Test
    fun blankQueryReturnsPlainTextWithoutSpans() {
        val result = buildHighlightedAnnotatedString(
            text = "Novex",
            query = " ",
            highlightBg = Color.Yellow,
            highlightFg = Color.Black,
        )

        assertEquals("Novex", result.text)
        assertEquals(emptyList<Any>(), result.spanStyles)
    }
}
