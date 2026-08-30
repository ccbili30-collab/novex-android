package com.openminis.app.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test

class NovexErrorTranslatorTest {
    @Test
    fun `translated upstream error retains status and response detail`() {
        val translated = novexErrorMessage("HTTP 503: upstream overloaded; request_id=req-42")

        assertTrue(translated.contains("503"))
        assertTrue(translated.contains("upstream overloaded"))
        assertTrue(translated.contains("request_id=req-42"))
    }
}
