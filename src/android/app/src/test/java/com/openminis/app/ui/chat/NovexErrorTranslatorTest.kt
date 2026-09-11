package com.openminis.app.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test

class NovexErrorTranslatorTest {
    @org.junit.Test fun localCapacityFailureDoesNotBecomeNetworkTroubleshooting() {
        val result=novexErrorMessage("本轮设定尚未准备完成，未发送模型请求：本轮剩余上下文不足以携带已采用或管理的卡片资料")
        org.junit.Assert.assertTrue(result.contains("调高对话容量"))
        org.junit.Assert.assertFalse(result.contains("网络"))
        org.junit.Assert.assertFalse(result.contains("请压缩"))
    }
    @org.junit.Test fun contextTokenNumbersCannotBeMistakenForHttpPermissionCodes() {
        val result=novexErrorMessage("Provider error: HTTP 400: maximum context length is 1048576 tokens; requested 403 tokens")
        org.junit.Assert.assertTrue(result.contains("上下文长度"))
        org.junit.Assert.assertFalse(result.contains("密钥没有访问"))
    }

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
