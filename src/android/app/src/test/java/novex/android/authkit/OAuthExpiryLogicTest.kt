package novex.android.authkit

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject

/**
 * OAuth 令牌刷新与过期语义（P3.5c 真重写的行为验收）：
 *  - expires_in 在写入时折算绝对 expire_at；
 *  - 手工 bearer 最高优先——设了就不刷新、原样返回；
 *  - 临期 + 刷新成功 → 返回新令牌；
 *  - 已过期 + 刷新失败 → 清凭据返回 null（登出语义）；
 *  - 导入的 iOS camelCase 包归一为 snake_case，参考纪元秒换算 epoch ms。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class OAuthExpiryLogicTest {

    /** 端点全指向不可达占位——本测试的所有网络路径都被覆写短路。 */
    private class ScriptedFlow(context: Context, instanceId: String) :
        VendorLoginFlow(context, instanceId) {

        override val authorizeEndpoint = "https://example.invalid/authorize"
        override val tokenEndpoint = "https://example.invalid/token"
        override val clientIdValue = "test-client"
        override val clientSecretValue: String? = null
        override val loopbackPort = 0
        override val redirectTail = "/cb"
        override val scopeValue = "test"

        var refreshResult: Boolean = false
        var refreshCalls = 0

        /** 测试播种口：走真实写入路径（expires_in 折算绝对 expire_at）。 */
        fun seed(access: String, expiresIn: Long) {
            vault.writeTokens(
                JSONObject().put("access_token", access).put("refresh_token", "r1")
                    .put("expires_in", expiresIn),
            )
        }

        /** 播种一枚已绝对过期（expire_at 在过去）的令牌。 */
        fun seedExpired(access: String) {
            vault.writeTokens(
                JSONObject().put("access_token", access).put("refresh_token", "r1")
                    .put("expire_at", System.currentTimeMillis() - 60_000),
            )
        }

        override suspend fun refreshToken(): Boolean {
            refreshCalls += 1
            if (refreshResult) {
                vault.writeTokens(
                    JSONObject().put("access_token", "fresh-token").put("expires_in", 3600),
                )
            }
            return refreshResult
        }
    }

    private fun flow(id: String = "inst-1") = ScriptedFlow(RuntimeEnvironment.getApplication(), id)

    private fun storeToken(f: ScriptedFlow, access: String, expiresIn: Long) = f.seed(access, expiresIn)

    @Test
    fun `expires_in is folded into absolute expire_at on write`() {
        val f = flow()
        val before = System.currentTimeMillis()
        f.seed("a", 7200)
        val expireAt = f.exportStoredTokensJson()?.let { JSONObject(it).optLong("expire_at") } ?: 0L
        // 写入时刻 + 7200s；给前后 5s 的时钟容差。
        assertTrue(expireAt in (before + 7195_000)..(before + 7205_000))
    }

    @Test
    fun `manual bearer wins verbatim and skips refresh`() = kotlinx.coroutines.runBlocking {
        val f = flow()
        storeToken(f, "oauth-token", 1) // 已过期，正常会触发刷新/清库
        f.saveManualBearerToken("static-bearer")

        assertEquals("static-bearer", f.validAccessToken())
        assertEquals(0, f.refreshCalls)
        assertTrue(f.isAuthenticated())
    }

    @Test
    fun `fresh token is returned without refresh`() = kotlinx.coroutines.runBlocking {
        val f = flow()
        storeToken(f, "still-good", 24 * 3600)
        assertEquals("still-good", f.validAccessToken())
        assertEquals(0, f.refreshCalls)
    }

    @Test
    fun `near expiry refreshes and surfaces the new token`() = kotlinx.coroutines.runBlocking {
        val f = flow()
        storeToken(f, "aging-token", 1800) // 4h 窗内
        f.refreshResult = true

        assertEquals("fresh-token", f.validAccessToken())
        assertEquals(1, f.refreshCalls)
    }

    @Test
    fun `expired token with failed refresh clears credentials`() = kotlinx.coroutines.runBlocking {
        val f = flow()
        f.seedExpired("dead-token") // 已过期 1 分钟
        f.refreshResult = false

        assertNull(f.validAccessToken())
        assertFalse(f.isAuthenticated())
    }

    @Test
    fun `ios camelCase import is normalized with epoch conversion`() {
        val f = flow("import-1")
        // 100.0 是苹果参考纪元（2001-01-01）起的秒。
        f.importStoredTokensJson(
            """{"accessToken":"acc","refreshToken":"ref","expireDate":100.0}""",
        )
        val stored = JSONObject(f.exportStoredTokensJson()!!)
        assertEquals("acc", stored.optString("access_token"))
        assertEquals("ref", stored.optString("refresh_token"))
        val appleEpochMs = 978307200000L
        assertEquals(appleEpochMs + 100_000L, stored.optLong("expire_at"))
    }

    @Test
    fun `malformed import blob is ignored`() {
        val f = flow("import-2")
        f.importStoredTokensJson("not-json{{{")
        assertNull(f.exportStoredTokensJson())
        assertFalse(f.isAuthenticated())
    }
}
