package novex.android.authkit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import novex.android.data.model.ProviderInstance
import novex.android.data.model.ProviderType
import org.json.JSONObject

/**
 * 回环重定向型供应商登录流的公共骨架（P3.5c 自 auth/OAuthManager 真重写）。
 *
 * 覆盖走「授权页 → localhost 回调 → code 换 token」这条标准线的厂商；
 * JSON 体交换（Anthropic / OpenAI）、OIDC 发现（xAI）、设备码流（Kimi）、
 * 换长期 API key（OpenRouter）各家在自己的子类/对象里做差量。
 *
 * 公开方法名沿用旧门面（validAccessToken / refreshToken / isAuthenticated /
 * logout / …）——调用方遍布全仓且大量走全限定名引用，形状属冻结面。
 *
 * 刷新时序语义（冻结面）：
 *  - [validAccessToken] 的缺省提前刷新窗是 4 小时（供应商普遍 24h 有效期
 *    下的宽口径；Anthropic/Gemini/Kimi 子类收紧到 5 分钟）；
 *  - 手工 bearer 优先级最高——设了就不刷新、原样返回；
 *  - 刷新失败且令牌已实际过期 → 清凭据返回 null；未过期则继续用旧值。
 *
 * @param appContext 任意 Context，内部只当 Application 用
 * @param instanceId 供应商实例 id，拼进全部存储键
 */
abstract class VendorLoginFlow(
    protected val appContext: Context,
    protected val instanceId: String,
) {
    companion object {
        private const val TAG = "VendorLoginFlow"

        /** 缺省提前刷新窗（毫秒）。 */
        private const val DEFAULT_REFRESH_LEEWAY_MS = 4L * 3600 * 1000

        /** 按实例的供应商类型挑登录流；非 OAuth 型返回 null。 */
        fun forInstance(context: Context, instance: ProviderInstance): VendorLoginFlow? =
            when (instance.providerType) {
                ProviderType.anthropic -> ClaudeLoginFlow(context, instance.id)
                ProviderType.openAI -> CodexLoginFlow(context, instance.id)
                ProviderType.xAI -> XaiLoginFlow(context, instance.id)
                ProviderType.kimiCode -> KimiLoginFlow(context, instance.id)
                else -> null
            }

        /**
         * 旧门面名（冻结面）：把 HTTP 体清理成可进日志的形态——打码已知
         * 凭据字段的值并截断。等价于 [OAuthWire.redactForLog]。
         */
        fun sanitizeBody(body: String, maxLen: Int = 300): String =
            OAuthWire.redactForLog(body, maxLen)
    }

    // ── 各厂差量声明 ───────────────────────────────────────────────────

    abstract val authorizeEndpoint: String
    abstract val tokenEndpoint: String
    abstract val clientIdValue: String
    abstract val clientSecretValue: String?
    abstract val loopbackPort: Int
    abstract val redirectTail: String
    abstract val scopeValue: String

    /**
     * 发给供应商的 redirect_uri 字符串。缺省 localhost 拼法；xAI 的服务端
     * 白名单登记的是 127.0.0.1 拼法，在子类覆写（OAuth redirect_uri 是
     * 全串精确匹配，两种拼法不等价；本机回调两种都能收到）。
     */
    open val redirectUri: String get() = "http://localhost:$loopbackPort$redirectTail"

    /** 子类挂收码后的附加动作（如解析 id_token、补拉账号信息）。 */
    protected open suspend fun afterTokensPersisted(json: JSONObject) {}

    /** 子类定制换 token 的表单体（xAI 要回显 code_challenge）。 */
    protected open fun exchangeForm(code: String, verifier: String): Map<String, String> {
        val form = mutableMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientIdValue,
            "code_verifier" to verifier,
        )
        clientSecretValue?.let { form["client_secret"] = it }
        return form
    }

    /** 子类定制刷新表单体。 */
    protected open fun refreshForm(refreshToken: String): Map<String, String> {
        val form = mutableMapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to clientIdValue,
        )
        clientSecretValue?.let { form["client_secret"] = it }
        return form
    }

    // ── 状态 ───────────────────────────────────────────────────────────

    protected val vault = CredentialVault(appContext, instanceId)

    /** 当前这轮授权用的 PKCE verifier（[authorizationUrl] 生成时落位）。 */
    protected var pendingVerifier: String? = null
    protected var pendingState: String? = null
    private var receiver: LoopbackReceiver? = null

    /** 子类可换成「先落盘再取用」的 PKCE/state 存取（JSON 交换流需要）。 */
    protected open fun nextPkce(): Pair<String, String> = PkceMaterial.base64UrlPair()
    protected open fun nextState(): String = PkceMaterial.opaqueToken()

    // ── 授权 URL ───────────────────────────────────────────────────────

    open fun authorizationUrl(): String {
        val (verifier, challenge) = nextPkce()
        pendingVerifier = verifier
        pendingState = nextState()
        return "$authorizeEndpoint?" + listOf(
            "client_id=$clientIdValue",
            "redirect_uri=${Uri.encode(redirectUri)}",
            "response_type=code",
            "scope=${Uri.encode(scopeValue)}",
            "state=$pendingState",
            "code_challenge=$challenge",
            "code_challenge_method=S256",
        ).joinToString("&")
    }

    // ── 浏览器线（系统浏览器 + 广播式回调）────────────────────────────

    /**
     * 老式整链路：起回调服务 → 拉系统浏览器开授权页 → 等码 → 换 token。
     * 现行各厂商 UI 用的是子类各自的 Custom Tab 编排，此入口保留等价
     * 外部调用语义（回调 state 不匹配即失败回调）。
     */
    suspend fun startBrowserLogin(onComplete: (Boolean) -> Unit) {
        receiver?.stop()
        receiver = LoopbackReceiver(loopbackPort) { code, state ->
            if (state != null && state != pendingState) {
                Log.w(TAG, "state mismatch on callback")
                onComplete(false)
                return@LoopbackReceiver
            }
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                val ok = exchangeCode(code)
                withContext(Dispatchers.Main) { onComplete(ok) }
            }
        }.also { it.start() }

        val open = Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(open)
    }

    suspend fun exchangeCode(code: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val (status, body) = OAuthWire.postForm(
                tokenEndpoint,
                exchangeForm(code, pendingVerifier ?: ""),
            )
            if (status !in 200..299) {
                Log.e(TAG, "token exchange rejected: $status ${OAuthWire.redactForLog(body)}")
                return@withContext false
            }
            val json = JSONObject(body)
            vault.writeTokens(json)
            afterTokensPersisted(json)
            true
        } catch (e: Exception) {
            Log.e(TAG, "token exchange error", e)
            false
        } finally {
            receiver?.stop()
            receiver = null
        }
    }

    // ── 刷新与取有效令牌 ───────────────────────────────────────────────

    protected open fun refreshLeewayMs(): Long = DEFAULT_REFRESH_LEEWAY_MS

    open suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
        val stored = vault.readTokens() ?: return@withContext false
        val rotating = stored.optString("refresh_token", "").ifEmpty { return@withContext false }
        try {
            val (status, body) = OAuthWire.postForm(tokenEndpoint, refreshForm(rotating))
            if (status !in 200..299) {
                Log.e(TAG, "token refresh rejected: $status")
                return@withContext false
            }
            val json = JSONObject(body)
            // 不轮换 refresh_token 的端点会省略该字段——保住旧值。
            if (!json.has("refresh_token")) json.put("refresh_token", rotating)
            vault.writeTokens(json)
            true
        } catch (e: Exception) {
            Log.e(TAG, "token refresh error", e)
            false
        }
    }

    /**
     * 取一枚当前可用的 access token：手工 bearer 优先，其次存储令牌，
     * 临期自动刷新；刷新失败且已实际过期则清凭据返回 null。
     */
    open suspend fun validAccessToken(): String? {
        vault.loadManualBearer()?.takeIf { it.isNotEmpty() }?.let { return it }

        val stored = vault.readTokens() ?: return null
        val token = stored.optString("access_token", "").ifEmpty { return null }
        val expireAt = stored.optLong("expire_at", 0)
        val now = System.currentTimeMillis()

        val nearExpiry = expireAt > 0 && (expireAt - now) < refreshLeewayMs()
        if (nearExpiry) {
            if (refreshToken()) {
                return vault.readTokens()?.optString("access_token")
            }
            if (expireAt > 0 && now >= expireAt) {
                Log.w(TAG, "token expired and refresh failed — clearing credentials")
                logout()
                return null
            }
        }
        return token
    }

    fun isAuthenticated(): Boolean {
        if (vault.loadManualBearer()?.isNotEmpty() == true) return true
        return vault.readTokens()?.optString("access_token", "")?.isNotEmpty() == true
    }

    fun logout() = vault.wipe()

    // ── 手工 bearer / 跨端导出导入 ─────────────────────────────────────

    fun saveManualBearerToken(token: String) = vault.saveManualBearer(token)

    fun loadManualBearerToken(): String? = vault.loadManualBearer()

    fun deleteManualBearerToken() = vault.deleteManualBearer()

    /** 登录流程存的结构化令牌包原文（JSON 字符串），未登录过返回 null。 */
    fun exportStoredTokensJson(): String? = vault.readTokens()?.toString()

    /**
     * 回填一份 [exportStoredTokensJson] 产出的令牌包。逐字写入——绝对
     * expire_at 原样保留，绝不按 expires_in 重算（导入时刻重算就错了）。
     */
    fun importStoredTokensJson(json: String) = vault.importTokens(json)

    /** 读一个辅助 OAuth 字符串（如 Gemini 的 email / gcp_project）。 */
    fun exportOAuthString(key: String): String? = vault.readAux(key)

    /** 回填一个辅助 OAuth 字符串。 */
    fun importOAuthString(key: String, value: String) = vault.writeAux(key, value)
}
