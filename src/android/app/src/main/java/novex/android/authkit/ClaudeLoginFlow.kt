package novex.android.authkit

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.openminis.app.BuildConfig
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Anthropic（Claude Code）OAuth 登录流（P3.5c 自 auth/ClaudeOAuthManager
 * 真重写）。
 *
 * 与标准线的三点差异（均为冻结面）：
 *  - code 换 token 用 application/json 体，且 body 里带 `state`；
 *  - PKCE verifier 取 96 字节（与 iOS 端一致，Anthropic 收 43–128 字符）；
 *  - 提前刷新窗收紧到 5 分钟（基类 4 小时太宽，白烧刷新轮次）。
 *
 * 刷新结果的四分类与并发合并：并行请求同时发现临期时，按实例的
 * [Mutex] 只放一个去打网络，其余等锁回来直接读新令牌；HTTP 400/401/403
 * 或体里点名 invalid_grant / refresh_token 才判定凭据作废并清库，5xx 与
 * 网络错误保凭据留给重试。
 */
class ClaudeLoginFlow(appContext: Context, instanceId: String) :
    VendorLoginFlow(appContext, instanceId) {

    companion object {
        private const val TAG = "ClaudeLoginFlow"
        private const val REFRESH_LEEWAY_MS = 5L * 60 * 1000

        /**
         * Claude Code OAuth 凭据必须随请求携带的系统提示前缀。
         * 值不硬编码：构建期经 BuildConfig.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
         * 注入，来源是私仓的 provider-customization.properties（公开镜像只带
         * 空值示例）。未配置时读到即抛——首个 OAuth (Claude Code) 请求需要
         * 它的时刻才炸，而不是构建期。
         */
        val ANTHROPIC_OAUTH_IDENTIFIER_PROMPT: String
            get() = BuildConfig.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT.ifEmpty {
                throw IllegalStateException(
                    "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT is not configured. Copy " +
                        "app/provider-customization.properties.example to " +
                        "app/provider-customization.properties and set " +
                        "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT before using Claude Code OAuth.",
                )
            }

        private val refreshGates = ConcurrentHashMap<String, Mutex>()
        private fun gateFor(instanceId: String): Mutex = refreshGates.getOrPut(instanceId) { Mutex() }

        /** 整链登录并把 access token 镜像成实例 API key。失败抛出。 */
        suspend fun login(
            context: Context,
            instanceId: String,
            providerRepository: ProviderRepository,
        ): String {
            val flow = ClaudeLoginFlow(context, instanceId)
            val token = flow.runLogin(context)
            providerRepository.saveApiKey(instanceId, token)
            return token
        }
    }

    /** 刷新结局四分类：UI 按它决定“重试请求”还是“请用户重登”。 */
    enum class RefreshOutcome { SUCCESS, INVALID_GRANT, TRANSIENT, NO_TOKEN }

    override val authorizeEndpoint = "https://claude.ai/oauth/authorize"
    override val tokenEndpoint = "https://console.anthropic.com/v1/oauth/token"
    override val clientIdValue = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    override val clientSecretValue: String? = null
    override val loopbackPort = 54545
    override val redirectTail = "/callback"
    override val scopeValue = "org:create_api_key user:profile user:inference"

    /** 96 字节 PKCE，verifier 与 state 落加密 prefs 供 JSON 交换取用。 */
    override fun nextPkce(): Pair<String, String> =
        PkceMaterial.base64UrlPair(byteCount = 96).also { (verifier, _) ->
            vault.writeAux("verifier", verifier)
        }

    override fun nextState(): String =
        super.nextState().also { vault.writeAux("state", it) }

    // ── 整链编排 ───────────────────────────────────────────────────────

    /**
     * 起回调服务 → Custom Tab 开授权页 → 等码 → 校验 state → JSON 换 token。
     * Custom Tab 不加 FLAG_ACTIVITY_NEW_TASK：MainActivity 是 singleTask，
     * 新任务拉起会把授权页聚焦输入框时的 IME 顶掉。
     */
    suspend fun runLogin(context: Context): String {
        Log.i(TAG, "=== Anthropic OAuth login started (instance: $instanceId) ===")

        val authUrl = authorizationUrl()

        val accessToken = withContext(Dispatchers.IO) {
            val (code, state) = awaitCallbackCode(context, authUrl)

            val savedState = vault.readAux("state")
            check(state == null || state == savedState) {
                "OAuth state mismatch — possible CSRF attack"
            }

            exchangeJson(code)
        }

        Log.i(TAG, "=== Anthropic OAuth login complete (instance: $instanceId) ===")
        return accessToken
    }

    private suspend inline fun awaitCallbackCode(context: Context, authUrl: String): Pair<String, String?> =
        suspendCancellableCoroutine { cont ->
            val server = LoopbackReceiver(loopbackPort) { code, state ->
                if (cont.isActive) cont.resume(code to state)
            }
            cont.invokeOnCancellation { server.stop() }
            server.start()
            Log.i(TAG, "callback receiver up on port $loopbackPort")

            CustomTabsIntent.Builder().setShowTitle(true).build()
                .launchUrl(context, Uri.parse(authUrl))
            Log.i(TAG, "Custom Tab opened for Anthropic authorization")
        }

    // ── JSON 交换 / 刷新 ───────────────────────────────────────────────

    private fun exchangeJson(code: String): String {
        val body = JSONObject().apply {
            put("grant_type", "authorization_code")
            put("client_id", clientIdValue)
            put("code", code)
            put("redirect_uri", redirectUri)
            put("code_verifier", vault.readAux("verifier") ?: "")
            put("state", vault.readAux("state") ?: "")
        }

        val (status, responseBody) = OAuthWire.postJson(tokenEndpoint, body.toString())
        if (status !in 200..299) {
            Log.e(TAG, "exchange rejected: $status ${OAuthWire.redactForLog(responseBody)}")
            throw IllegalStateException("Token exchange failed ($status): $responseBody")
        }

        val json = JSONObject(responseBody)
        val accessToken = json.optString("access_token", "")
        require(accessToken.isNotEmpty()) { "No access_token in response" }

        vault.writeTokens(json)
        Log.i(TAG, "exchange ok, expires in ${json.optLong("expires_in", 0)}s, has refresh: ${json.has("refresh_token")}")
        return accessToken
    }

    override suspend fun refreshToken(): Boolean = classifiedRefresh() == RefreshOutcome.SUCCESS

    suspend fun classifiedRefresh(): RefreshOutcome = withContext(Dispatchers.IO) {
        gateFor(instanceId).withLock {
            val stored = vault.readTokens() ?: return@withLock RefreshOutcome.NO_TOKEN
            val rotating = stored.optString("refresh_token", "")
            if (rotating.isEmpty()) return@withLock RefreshOutcome.NO_TOKEN

            // 等锁期间别的协程已刷过： expire_at 比进门时新且仍未过期，直接判成功。
            if (fresherTokenArrived(stored.optLong("expire_at", 0))) {
                Log.d(TAG, "refresh coalesced — fresher token already stored")
                return@withLock RefreshOutcome.SUCCESS
            }

            val body = JSONObject().apply {
                put("grant_type", "refresh_token")
                put("client_id", clientIdValue)
                put("refresh_token", rotating)
            }
            try {
                val (status, responseBody) = OAuthWire.postJson(tokenEndpoint, body.toString())
                if (status in 200..299) {
                    val json = JSONObject(responseBody)
                    if (!json.has("refresh_token")) json.put("refresh_token", rotating)
                    vault.writeTokens(json)
                    Log.i(TAG, "refresh ok, expires in ${json.optLong("expires_in", 0)}s")
                    return@withLock RefreshOutcome.SUCCESS
                }
                return@withLock classifyFailure(status, responseBody, rotating)
            } catch (e: Exception) {
                Log.w(TAG, "refresh transient error — keeping token", e)
                RefreshOutcome.TRANSIENT
            }
        }
    }

    private fun classifyFailure(status: Int, responseBody: String, rotating: String): RefreshOutcome {
        val lower = responseBody.lowercase()
        val fatal = status == 400 || status == 401 || status == 403 ||
            lower.contains("invalid_grant") ||
            lower.contains("refresh_token")
        if (!fatal) {
            Log.w(TAG, "refresh transient failure ($status) — keeping token")
            return RefreshOutcome.TRANSIENT
        }
        Log.e(TAG, "refresh token invalid ($status): ${OAuthWire.redactForLog(responseBody)} — clearing credentials")
        logout()
        return RefreshOutcome.INVALID_GRANT
    }

    private fun fresherTokenArrived(priorExpireAt: Long): Boolean {
        val now = System.currentTimeMillis()
        val latest = vault.readTokens()?.optLong("expire_at", 0) ?: 0
        return latest > priorExpireAt && latest > now
    }

    override fun refreshLeewayMs(): Long = REFRESH_LEEWAY_MS

    /**
     * 5 分钟窗内的临期刷新；INVALID_GRANT（已清库）与 NO_TOKEN 返回 null，
     * TRANSIENT 下旧令牌未实际过期就继续用。
     */
    override suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        val stored = vault.readTokens() ?: return@withContext null
        val token = stored.optString("access_token", "").ifEmpty { return@withContext null }
        val expireAt = stored.optLong("expire_at", 0)
        val now = System.currentTimeMillis()

        val nearExpiry = expireAt > 0 && (expireAt - now) <= REFRESH_LEEWAY_MS
        if (!nearExpiry) return@withContext token

        when (classifiedRefresh()) {
            RefreshOutcome.SUCCESS ->
                vault.readTokens()?.optString("access_token", "")?.ifEmpty { null }
            RefreshOutcome.INVALID_GRANT -> null // classifiedRefresh 内已 logout()
            RefreshOutcome.TRANSIENT -> if (expireAt > 0 && now >= expireAt) null else token
            RefreshOutcome.NO_TOKEN -> null
        }
    }
}
