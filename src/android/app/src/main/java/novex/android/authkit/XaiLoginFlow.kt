package novex.android.authkit

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import novex.android.data.model.ProviderInstance
import org.json.JSONObject
import java.util.Base64
import kotlin.coroutines.resume

/**
 * xAI（Grok）OAuth 登录流（P3.5c 自 auth/XAIOAuthManager 真重写）。
 *
 * 四点xai特有约定（冻结面）：
 *  - 授权 / 令牌端点不是常量：首用从 OIDC 发现文档解析并缓存进加密
 *    prefs；两处端点都必须落在 `*.x.ai` 之下才被采信（开放重定向护栏）。
 *  - redirect_uri 用 127.0.0.1 拼法（服务端白名单按全串精确匹配，
 *    localhost 拼法会被拒）。
 *  - PKCE 十六进制形态，且换 token 时要求回显 `code_challenge` 与
 *    `code_challenge_method`——非标准 PKCE 行为。
 *  - 授权 URL 额外带 OIDC 必需的 `nonce`，以及 `plan=generic`、
 *    `referrer=minis` 归因参数。
 *
 * 刷新对 HTTP 403 特判：订阅档位不含 API 权限时刷新会回 403，不是令牌
 * 坏——保住令牌包，等用户升档或改手工 key，不强制重登。
 *
 * 重入防护：同一实例的登录编排一次只放一个（双击/重组双发会各生成一套
 * PKCE+state 抢绑端口，回调码落进错误的 continuation 表现为诡异的
 * “state mismatch”）；并发到的那次等在飞流程结束后直接读结果。
 */
class XaiLoginFlow(appContext: Context, instanceId: String) :
    VendorLoginFlow(appContext, instanceId) {

    companion object {
        private const val TAG = "XaiLoginFlow"

        const val OAUTH_CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
        const val DISCOVERY_URL = "https://auth.x.ai/.well-known/openid-configuration"
        const val CALLBACK_PORT = 56121
        const val CALLBACK_PATH = "/callback"

        /** Custom Tab 关闭后回调迟到race的宽限窗。 */
        private const val DISMISS_GRACE_MS = 1500L

        /** 整链登录并把 access token 镜像成实例 API key。失败抛出。 */
        suspend fun login(
            context: Context,
            instanceId: String,
            providerRepository: com.openminis.app.data.repository.ProviderRepository,
        ): String {
            val flow = XaiLoginFlow(context, instanceId)
            val token = flow.runLogin(context)
            providerRepository.saveApiKey(instanceId, token)
            return token
        }
    }

    // 抽象面占位：两端点实际由 OIDC 发现解析，授权/交换/刷新全部绕开
    // 这两个常量走 [resolveAuthorizeEndpoint] / [resolveTokenEndpoint]。
    override val authorizeEndpoint = "https://auth.x.ai/oauth/authorize"
    override val tokenEndpoint = "https://auth.x.ai/oauth/token"
    override val clientIdValue = OAUTH_CLIENT_ID
    override val clientSecretValue: String? = null
    override val loopbackPort = CALLBACK_PORT
    override val redirectTail = CALLBACK_PATH
    override val scopeValue =
        "openid profile email offline_access grok-cli:access api:access"

    override val redirectUri: String get() = "http://127.0.0.1:$loopbackPort$redirectTail"

    /** ID-token 解出的账号信息，随令牌包一起落盘。 */
    var accountId: String?
        get() = vault.readAux("account_id")
        private set(value) { value?.let { vault.writeAux("account_id", it) } }

    var email: String?
        get() = vault.readAux("email")
        private set(value) { value?.let { vault.writeAux("email", it) } }

    var displayName: String?
        get() = vault.readAux("display_name")
        private set(value) { value?.let { vault.writeAux("display_name", it) } }

    @Volatile private var loginInFlight = false
    private var loginReceiver: LoopbackReceiver? = null
    private var dismissalObserver: DefaultLifecycleObserver? = null

    // ── PKCE（hex 形态，verifier 与 challenge 都落盘）─────────────────

    override fun nextPkce(): Pair<String, String> =
        PkceMaterial.hexPair().also { (verifier, challenge) ->
            vault.writeAux("verifier", verifier)
            vault.writeAux("challenge", challenge)
        }

    // ── OIDC 发现 ─────────────────────────────────────────────────────

    /**
     * 拉发现文档、校验双端点域、缓存进加密 prefs。任一端点不在 `*.x.ai`
     * 之下即抛——防发现文档被指向第三方端点。
     */
    private suspend fun discoverEndpoints(): Pair<String, String> = withContext(Dispatchers.IO) {
        val (status, body) = OAuthWire.get(DISCOVERY_URL)
        check(status in 200..299 && body.isNotEmpty()) {
            "xAI OIDC discovery failed: HTTP $status"
        }
        val json = JSONObject(body)
        val authorize = json.optString("authorization_endpoint", "")
        val token = json.optString("token_endpoint", "")
        check(authorize.isNotEmpty() && token.isNotEmpty()) {
            "xAI OIDC discovery missing endpoints"
        }
        require(Uri.parse(authorize).host?.endsWith(".x.ai") == true) {
            "xAI OAuth discovery returned untrusted authorization endpoint: $authorize"
        }
        require(Uri.parse(token).host?.endsWith(".x.ai") == true) {
            "xAI OAuth discovery returned untrusted token endpoint: $token"
        }
        vault.writeAux("auth_endpoint", authorize)
        vault.writeAux("token_endpoint", token)
        authorize to token
    }

    private suspend fun resolveAuthorizeEndpoint(): String =
        vault.readAux("auth_endpoint")?.takeIf { it.isNotEmpty() }
            ?: discoverEndpoints().first

    private suspend fun resolveTokenEndpoint(): String =
        vault.readAux("token_endpoint")?.takeIf { it.isNotEmpty() }
            ?: discoverEndpoints().second

    // ── 授权 URL（带 nonce 与归因参数）────────────────────────────────

    private suspend fun buildAuthorizeRequest(): String {
        val (_, challenge) = nextPkce()
        val state = PkceMaterial.opaqueToken().also { vault.writeAux("state", it) }
        val query = mapOf(
            "response_type" to "code", "client_id" to clientIdValue,
            "redirect_uri" to Uri.encode(redirectUri), "scope" to Uri.encode(scopeValue),
            "state" to state, "nonce" to PkceMaterial.opaqueToken(),
            "code_challenge" to challenge, "code_challenge_method" to "S256",
            "plan" to "generic", "referrer" to "minis",
        ).entries.joinToString("&") { "${it.key}=${it.value}" }
        return "${resolveAuthorizeEndpoint()}?$query"
    }

    // ── 换 token（回显 challenge 对）───────────────────────────────────

    override fun exchangeForm(code: String, verifier: String): Map<String, String> = mapOf(
        "grant_type" to "authorization_code", "code" to code, "redirect_uri" to redirectUri,
        "code_verifier" to (vault.readAux("verifier") ?: verifier), "client_id" to clientIdValue,
        // xAI 特有：令牌端点要求 challenge 对随交换回显，不只 verifier。
        "code_challenge" to (vault.readAux("challenge") ?: ""), "code_challenge_method" to "S256",
    )

    /**
     * 自带交换：POST 到发现出的令牌端点（不走基类的常量端点），成功后
     * 落令牌包并解析 id_token。
     */
    private suspend fun exchangeDiscovered(code: String): String = withContext(Dispatchers.IO) {
        val (status, body) = OAuthWire.postForm(resolveTokenEndpoint(), exchangeForm(code, ""))
        if (status !in 200..299) {
            Log.e(TAG, "xAI exchange rejected: $status ${OAuthWire.redactForLog(body)}")
            throw IllegalStateException("xAI token exchange failed ($status): $body")
        }
        val json = JSONObject(body)
        val accessToken = json.optString("access_token", "")
        check(accessToken.isNotEmpty()) { "xAI token exchange: no access_token in response" }

        vault.writeTokens(json)
        afterTokensPersisted(json)
        Log.i(
            TAG,
            "xAI exchange ok, expires in ${json.optLong("expires_in", 0)}s, " +
                "has refresh: ${json.has("refresh_token")}",
        )
        accessToken
    }

    override suspend fun afterTokensPersisted(json: JSONObject) {
        json.optString("id_token", "").takeIf { it.isNotEmpty() }?.let(::parseIdToken)
        // 顺带把发现的令牌端点缓存好，刷新不必重跑发现。
        runCatching { resolveTokenEndpoint() }
    }

    /** 解 JWT 中段的 email / name / sub / account_id（全部尽力而为）。 */
    private fun parseIdToken(idToken: String) {
        try {
            val segments = idToken.split(".")
            if (segments.size < 2) return
            val claims = JSONObject(String(Base64.getUrlDecoder().decode(segments[1])))
            claims.optString("email").takeIf { it.isNotEmpty() }?.let { email = it }
            claims.optString("name").takeIf { it.isNotEmpty() }?.let { displayName = it }
            // sub 是用户 id；account_id 是工作区/组织——有谁记谁，最坏
            // 情况 UI 也有个可显示的标识。
            (claims.optString("account_id").takeIf { it.isNotEmpty() }
                ?: claims.optString("sub").takeIf { it.isNotEmpty() })?.let { accountId = it }
        } catch (e: Exception) {
            Log.w(TAG, "id_token parse failed: ${e.message}")
        }
    }

    // ── 刷新 ───────────────────────────────────────────────────────────

    override suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
        val rotating = vault.readTokens()?.optString("refresh_token").orEmpty()
        if (rotating.isEmpty()) return@withContext false

        try {
            val (status, body) = OAuthWire.postForm(
                resolveTokenEndpoint(),
                mapOf("grant_type" to "refresh_token", "refresh_token" to rotating, "client_id" to clientIdValue),
            )
            when (status) {
                403 -> { // 档位不含 API 权限：保令牌包，给升档/手工 key 留后路。
                    Log.w(TAG, "xAI refresh got 403 — preserving token (subscription tier issue)"); false
                }
                in 200..299 -> {
                    val json = JSONObject(body)
                    vault.writeTokens(json.put("refresh_token", json.optString("refresh_token", rotating)))
                    true
                }
                else -> { Log.e(TAG, "xAI refresh failed: $status"); false }
            }
        } catch (e: Exception) {
            Log.e(TAG, "xAI refresh error", e); false
        }
    }

    // ── 整链编排 ───────────────────────────────────────────────────────

    /**
     * 起回调服务 → Custom Tab 开授权页（等发现解析出端点后才拼 URL）→
     * 等码 → 校验 state → 交换。重入时等在飞流程收尾后回读其结果。
     */
    suspend fun runLogin(context: Context): String {
        if (loginInFlight) {
            Log.w(TAG, "xAI login already in progress — coalescing re-entrant call (instance: $instanceId)")
            while (loginInFlight) delay(100)
            return vault.readTokens()
                ?.optString("access_token", "")
                ?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("xAI login: prior re-entrant call finished without a token")
        }
        loginInFlight = true
        try {
            Log.i(TAG, "=== xAI OAuth login started (instance: $instanceId) ===")

            loginReceiver?.stop()
            loginReceiver = null

            val authUrl = buildAuthorizeRequest()
            val expectedState = vault.readAux("state") ?: ""

            val accessToken = withContext(Dispatchers.IO) {
                val (code, state) = awaitCallbackCode(context, authUrl)

                loginReceiver?.stop()
                loginReceiver = null
                Log.i(TAG, "xAI callback received — code length: ${code.length}")

                if (expectedState.isNotEmpty() && state != null && state != expectedState) {
                    throw IllegalStateException("xAI OAuth state mismatch — possible CSRF, refusing to exchange")
                }

                exchangeDiscovered(code)
            }

            Log.i(TAG, "=== xAI OAuth login complete (instance: $instanceId) ===")
            return accessToken
        } finally {
            loginInFlight = false
            disarmDismissalDetector()
        }
    }

    private suspend inline fun awaitCallbackCode(context: Context, authUrl: String): Pair<String, String?> =
        suspendCancellableCoroutine { cont ->
            val server = LoopbackReceiver(loopbackPort) { code, state ->
                if (cont.isActive) {
                    // 成功回调先摘外部中止钩子，避免紧随其后的服务器自停
                    // 把已经 resume 的 continuation 又取消一遍。
                    loginReceiver?.abortHook = null
                    cont.resume(code to state)
                }
            }
            // 用户提前关掉 Custom Tab 时：stop() 经此钩子把 continuation
            // 取消掉，外层 try/finally 立即复位重入闸，不必干等下一次
            // 入站连接才唤醒 accept 循环。
            server.abortHook = {
                if (cont.isActive) cont.cancel(CancellationException("xAI OAuth callback cancelled"))
            }
            cont.invokeOnCancellation {
                server.stop()
                loginReceiver = null
            }
            loginReceiver = server
            server.start()
            Log.i(TAG, "callback receiver up on port $loopbackPort")

            // 与 Codex 线同款：不加 FLAG_ACTIVITY_NEW_TASK，保 IME。
            CustomTabsIntent.Builder().setShowTitle(true).build()
                .launchUrl(context, Uri.parse(authUrl))
            Log.i(TAG, "Custom Tab opened for xAI authorization")

            armDismissalDetector()
        }

    // ── Custom Tab 关闭探测 ────────────────────────────────────────────

    /**
     * Android Custom Tabs 没有可靠的“用户关闭”回调，借
     * ProcessLifecycleOwner 探测：装好观察器后等一次 ON_RESUME——回调
     * 成功时服务器早已自停（[loginReceiver] 为 null），仍在监听即视为
     * 用户中途退出，宽限窗后停服触发中止。
     */
    private fun armDismissalDetector() {
        disarmDismissalDetector()
        val observer = object : DefaultLifecycleObserver {
            @Volatile var leftForeground = false

            override fun onPause(owner: LifecycleOwner) { leftForeground = true }
            override fun onStop(owner: LifecycleOwner) { leftForeground = true }

            override fun onResume(owner: LifecycleOwner) {
                if (!leftForeground) return
                GlobalScope.launch {
                    delay(DISMISS_GRACE_MS)
                    val server = loginReceiver ?: return@launch
                    Log.w(TAG, "app resumed without OAuth callback — treating as user dismissal")
                    server.stop()
                }
            }
        }
        dismissalObserver = observer
        // ProcessLifecycleOwner 的观察器操作只允许主线程。
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
            }
        } catch (e: Exception) {
            Log.w(TAG, "dismissal detector attach failed: ${e.message}")
        }
    }

    private fun disarmDismissalDetector() {
        val observer = dismissalObserver ?: return
        dismissalObserver = null
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
            }
        } catch (_: Exception) {
        }
    }
}
