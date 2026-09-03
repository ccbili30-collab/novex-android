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

    @Test
    fun `403 is reported as permission failure rather than bad key`() {
        val translated = novexErrorMessage("Invalid API key: HTTP 403: model access denied")

        assertTrue(translated.startsWith("当前密钥没有访问这个模型或接口的权限"))
        assertTrue(translated.contains("HTTP 403"))
        assertTrue(translated.contains("model access denied"))
    }
}
