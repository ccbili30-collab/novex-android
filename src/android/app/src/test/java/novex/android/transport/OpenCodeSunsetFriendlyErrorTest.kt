package novex.android.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-opencode-sunset] 403 free-tier 文案识别（自上游 openai 包测试迁入，随 P3.1e
 * 整包删除落地）：命中（含大小写不敏感）给人话停服提示（存量绑定旧会话的防御）；
 * 不命中（普通密钥错误）不越权改写。
 */
class OpenCodeSunsetFriendlyErrorTest {

    @Test fun `free tier message maps to sunset copy`() {
        val e = openCodeSunsetFriendlyError(
            "HTTP 403: Error from provider (Console): OpenCode's FreeTier — free tier can only be used from within OpenCode",
        )
        assertNotNull(e)
        assertEquals("OpenCode 免费模型已停止服务：官方已限制仅 OpenCode 客户端内使用，请切换其他模型", e!!.message)
    }

    @Test fun `ordinary auth errors pass through`() {
        assertNull(openCodeSunsetFriendlyError("HTTP 403: invalid x-api-key"))
        assertNull(openCodeSunsetFriendlyError("HTTP 401: unauthorized"))
    }
}
