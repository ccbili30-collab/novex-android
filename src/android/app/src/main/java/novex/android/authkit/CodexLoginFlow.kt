package novex.android.authkit

import android.content.Context
import android.net.Uri
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import novex.android.logkit.RunLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.ProxySelector
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * DNS / 代理连通失败时抛出：UI 捕获后给本地化的“检查网络或代理”提示，
 * 而不是裸露 OkHttp 报文。
 */
class OAuthNetworkUnreachableException(cause: Throwable) : Exception(cause.message, cause)

/**
 * OpenAI Codex OAuth 登录流（P3.5c 自 auth/OpenAIOAuthManager 真重写）。
 *
 * 与标准线的差异（冻结面）：
 *  - code 换 token 用 application/json 体；
 *  - 授权 URL 追加 codex_cli_simplified_flow / originator=codex_cli_rs /
 *    id_token_add_organizations 三个 CLI 指纹参数；
 *  - state 校验失败仅告警放行（沿用既有行为），不中断交换；
 *  - 令牌 POST 走“系统代理感知”专用客户端并最多重试 3 次（1s×轮次退避）
 *    ——Custom Tab 走系统网络栈能完成登录，而默认 OkHttp 用运营商
 *    resolver 解析 auth.openai.com，在 VPN 式 fake-IP 代理（Clash 等）
 *    下会 UnknownHostException。
 */
class CodexLoginFlow(appContext: Context, instanceId: String) :
    VendorLoginFlow(appContext, instanceId) {

    companion object {
        private const val CATEGORY = "CodexLoginFlow"

        /** 整链登录并把 access token 镜像成实例 API key。失败抛出。 */
        suspend fun login(
            context: Context,
            instanceId: String,
            providerRepository: ProviderRepository,
        ): String {
            val flow = CodexLoginFlow(context, instanceId)
            val token = flow.runLogin(context)
            providerRepository.saveApiKey(instanceId, token)
            return token
        }
    }

    override val authorizeEndpoint = "https://auth.openai.com/oauth/authorize"
    override val tokenEndpoint = "https://auth.openai.com/oauth/token"
    override val clientIdValue = "app_EMoamEEZ73f0CkXaXp7hrann"
    override val clientSecretValue: String? = null
    override val loopbackPort = 1455
    override val redirectTail = "/auth/callback"
    override val scopeValue = "openid profile email offline_access"

    /** 本轮授权的期望 state（[authorizationUrl] 生成时记下）。 */
    private var expectedState: String? = null

    override fun nextPkce(): Pair<String, String> =
        super.nextPkce().also { (verifier, _) -> vault.writeAux("verifier", verifier) }

    override fun authorizationUrl(): String {
        val url = super.authorizationUrl()
        expectedState = pendingState
        RunLog.debug(CATEGORY, "PKCE verifierLen=${pendingVerifier?.length ?: 0} stateLen=${pendingState?.length ?: 0}")
        return url + "&codex_cli_simplified_flow=true&originator=codex_cli_rs&id_token_add_organizations=true"
    }

    /** id_token（JWT）里的 ChatGPT 账号与套餐档位，供 UI 展示。 */
    var accountId: String?
        get() = vault.readAux("account_id")
        private set(value) { value?.let { vault.writeAux("account_id", it) } }

    var planType: String?
        get() = vault.readAux("plan_type")
        private set(value) { value?.let { vault.writeAux("plan_type", it) } }

    // ── 整链编排 ───────────────────────────────────────────────────────

    suspend fun runLogin(context: Context): String {
        RunLog.info(CATEGORY, "=== OAuth login started (instance=$instanceId) ===")

        val authUrl = authorizationUrl()
        // 授权 URL 进日志前抹掉 code_challenge，其余保留供诊断核对。
        val sanitized = authUrl.replace(Regex("code_challenge=[^&]+"), "code_challenge=<redacted>")
        RunLog.info(CATEGORY, "authorize URL: $sanitized")
        RunLog.info(CATEGORY, "redirect_uri=$redirectUri loopbackPort=$loopbackPort")

        val accessToken = withContext(Dispatchers.IO) {
            val (code, state) = awaitCallbackCode(context, authUrl)

            val matches = expectedState != null && state == expectedState
            RunLog.info(
                CATEGORY,
                "callback: codeLen=${code.length} stateMatch=$matches expected=${expectedState != null}",
            )
            if (!matches) {
                RunLog.warning(CATEGORY, "state mismatch — proceeding anyway to mirror prior behaviour")
            }

            exchangeJsonWithRetry(code)
        }

        RunLog.info(CATEGORY, "=== OAuth login complete (instance=$instanceId tokenLen=${accessToken.length}) ===")
        return accessToken
    }

    private suspend inline fun awaitCallbackCode(context: Context, authUrl: String): Pair<String, String?> =
        suspendCancellableCoroutine { cont ->
            val server = LoopbackReceiver(loopbackPort) { code, state ->
                if (cont.isActive) cont.resume(code to state)
            }
            cont.invokeOnCancellation { server.stop() }
            server.start()
            RunLog.info(CATEGORY, "callback receiver up on port $loopbackPort path=$redirectTail")

            // 不加 FLAG_ACTIVITY_NEW_TASK（MainActivity 为 singleTask，新任务
            // 拉起会在授权页聚焦输入框时顶掉 IME）。
            androidx.browser.customtabs.CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(context, Uri.parse(authUrl))
            RunLog.info(CATEGORY, "Custom Tab launched")
        }

    // ── JSON 交换（代理感知客户端 + 重试）──────────────────────────────

    /**
     * 每次登录重建的客户端：接系统 [ProxySelector]（默认选择器读
     * Settings.Global.HTTP_PROXY、ConnectivityManager 链路代理与 VPN 路由），
     * 并在网络拦截器里记下实际路由的代理与 socket 地址。懒加载保证每次
     * 登录读到的是当前代理状态（冷启动到点登录之间网络可能切换）。
     */
    private val proxyAwareClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .proxySelector(ProxySelector.getDefault())
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                val route = chain.connection()?.route()
                RunLog.info(
                    CATEGORY,
                    "okhttp connect: url=${req.url} via proxy=${route?.proxy} socket=${route?.socketAddress}",
                )
                chain.proceed(req)
            }
            .build()
    }

    private fun exchangeJsonWithRetry(code: String): String {
        val body = JSONObject().apply {
            put("grant_type", "authorization_code")
            put("client_id", clientIdValue)
            put("code", code)
            put("redirect_uri", redirectUri)
            put("code_verifier", vault.readAux("verifier") ?: "")
        }
        RunLog.info(CATEGORY, "token exchange POST $tokenEndpoint bodyLen=${body.toString().length}")
        logNetworkEnvironment(tokenEndpoint)

        val request = okhttp3.Request.Builder()
            .url(tokenEndpoint)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        // DNS 在 Custom Tab 关闭后可能短暂失败——最多 3 轮，线性退避。
        var lastFailure: Exception? = null
        var response: okhttp3.Response? = null
        for (attempt in 1..3) {
            try {
                response = proxyAwareClient.newCall(request).execute()
                lastFailure = null
                break
            } catch (e: Exception) {
                RunLog.warning(
                    CATEGORY,
                    "token exchange attempt $attempt failed: ${e.javaClass.simpleName}: ${e.message}",
                )
                lastFailure = e
                if (attempt < 3) Thread.sleep(1000L * attempt)
            }
        }
        response ?: run {
            RunLog.error(
                CATEGORY,
                "token exchange failed after 3 attempts: ${lastFailure?.javaClass?.simpleName}: ${lastFailure?.message}",
            )
            throw lastFailure?.let { asNetworkErrorIfApplicable(it) }
                ?: IllegalStateException("Token exchange failed")
        }

        response.use { resp ->
            val responseBody = resp.body?.string() ?: ""
            RunLog.info(
                CATEGORY,
                "token exchange response: ${resp.code} bodyLen=${responseBody.length} body[0..500]=${responseBody.take(500)}",
            )
            if (resp.code !in 200..299) {
                RunLog.error(CATEGORY, "token exchange non-2xx: ${resp.code}")
                throw IllegalStateException("Token exchange failed (${resp.code}): $responseBody")
            }

            val json = JSONObject(responseBody)
            val accessToken = json.optString("access_token", "")
            if (accessToken.isEmpty()) {
                RunLog.error(CATEGORY, "no access_token field in response")
                throw IllegalStateException("No access_token in response")
            }

            vault.writeTokens(json)
            json.optString("id_token", "").takeIf { it.isNotEmpty() }?.let(::parseIdToken)
            RunLog.info(
                CATEGORY,
                "token exchange OK accessTokenLen=${accessToken.length} " +
                    "expiresIn=${json.optLong("expires_in", 0)}s hasRefresh=${json.has("refresh_token")}",
            )
            return accessToken
        }
    }

    private fun asNetworkErrorIfApplicable(e: Throwable): Throwable =
        if (e is UnknownHostException || e is SocketTimeoutException) {
            OAuthNetworkUnreachableException(e)
        } else {
            e
        }

    /** 记 host 的解析 IP 与系统选中的代理链，定位“浏览器通、OkHttp 不通”。 */
    private fun logNetworkEnvironment(url: String) {
        val host = runCatching { URI(url).host }.getOrNull() ?: return
        try {
            RunLog.info(CATEGORY, "dns: $host → ${InetAddress.getByName(host).hostAddress}")
        } catch (e: Exception) {
            RunLog.warning(CATEGORY, "dns lookup FAILED for $host: ${e.javaClass.simpleName}: ${e.message}")
        }
        try {
            RunLog.info(CATEGORY, "proxy chain for $host: ${ProxySelector.getDefault().select(URI(url))}")
        } catch (e: Exception) {
            RunLog.warning(CATEGORY, "proxy lookup failed: ${e.message}")
        }
    }

    /** 取 JWT 中段的 chatgpt_account_id / chatgpt_plan_type。 */
    private fun parseIdToken(token: String) {
        try {
            val segments = token.split(".")
            if (segments.size < 2) return
            val claims = JSONObject(String(Base64.getUrlDecoder().decode(segments[1])))
            accountId = claims.optString("chatgpt_account_id").ifEmpty { null }
            planType = claims.optString("chatgpt_plan_type").ifEmpty { null }
        } catch (e: Exception) {
            RunLog.warning(CATEGORY, "id_token parse failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
