package novex.android.authkit

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google Gemini CLI OAuth 登录流（P3.5c 自 auth/GeminiOAuthManager 真重写）。
 *
 * 与标准线的差异（冻结面）：
 *  - 授权 URL 追加 `access_type=offline` 与 `prompt=consent`（确保发
 *    refresh_token）；
 *  - PKCE 用十六进制形态；
 *  - 刷新走 form 体（Google 端点不收 JSON）且必须带 client_secret；
 *  - 登录后经 oauth2/v1/userinfo 补账号邮箱，经 cloudcode-pa 的
 *    loadCodeAssist / onboardUser 两级探测把用户挂到 GCP 项目上，
 *    onboard 是长任务时轮询 operation 直到 done。
 *
 * 刷新分类与并发合并复用 [RefreshGate]；Google 对失效 refresh token 回
 * 400 + invalid_grant / invalid_token 判 INVALID_GRANT 清库，5xx 与网络
 * 错误保凭据。
 */
class GeminiLoginFlow(appContext: Context, instanceId: String) :
    VendorLoginFlow(appContext, instanceId) {

    companion object {
        private const val TAG = "GeminiLoginFlow"
        private const val USER_AGENT = "GeminiCLI/0.30.0 (android; aarch64)"
        private const val CLOUDCODE_ROOT = "https://cloudcode-pa.googleapis.com/v1internal"

        /** 提前刷新窗：Google access token 1 小时有效，5 分钟窗防尾race。 */
        private const val REFRESH_LEEWAY_MS = 5L * 60 * 1000
    }

    enum class RefreshOutcome { SUCCESS, INVALID_GRANT, TRANSIENT, NO_TOKEN }

    override val authorizeEndpoint = "https://accounts.google.com/o/oauth2/v2/auth"
    override val tokenEndpoint = "https://oauth2.googleapis.com/token"
    override val clientIdValue =
        "681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j.apps.googleusercontent.com"

    // 占位值。Google OAuth 要求 client id 配 secret；要用这套登录需从
    // Google Cloud Console 换成自己的值。API-key 供应商不受影响。
    override val clientSecretValue = "GOCSPX-xxxxxxxxxxxxxxxxxxxxxxxxxxx"
    override val loopbackPort = 8085
    override val redirectTail = "/oauth2callback"
    override val scopeValue =
        "https://www.googleapis.com/auth/cloud-platform " +
            "https://www.googleapis.com/auth/userinfo.email " +
            "https://www.googleapis.com/auth/userinfo.profile"

    /** Google 面的授权参数次序与附加项与标准线不同，整段覆写。 */
    override fun authorizationUrl(): String {
        val (_, challenge) = PkceMaterial.hexPair().also { (verifier, _) ->
            vault.writeAux("verifier", verifier)
        }
        val state = PkceMaterial.opaqueToken().also { vault.writeAux("state", it) }
        val query = mapOf(
            "client_id" to clientIdValue,
            "redirect_uri" to Uri.encode(redirectUri),
            "response_type" to "code",
            "scope" to Uri.encode(scopeValue),
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "access_type" to "offline",
            "prompt" to "consent",
        ).entries.joinToString("&") { "${it.key}=${it.value}" }
        return "$authorizeEndpoint?$query"
    }

    var email: String?
        get() = vault.readAux("email")
        private set(value) { value?.let { vault.writeAux("email", it) } }

    var gcpProjectId: String?
        get() = vault.readAux("gcp_project")
        private set(value) { value?.let { vault.writeAux("gcp_project", it) } }

    override suspend fun afterTokensPersisted(json: JSONObject) {
        fetchAccountEmail()
    }

    // ── HTTP 小助手（userinfo 与 cloudcode-pa 共用）──────────────────

    private fun authedGet(url: String, token: String): String? = runCatching {
        open(url, token).let { conn ->
            val body = conn.inputStream.bufferedReader().readText().takeIf { conn.responseCode == 200 }
            conn.disconnect(); body
        }
    }.getOrNull()

    private fun cloudcodePost(path: String, token: String, payload: String): String? = runCatching {
        (URL("$CLOUDCODE_ROOT$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            outputStream.write(payload.toByteArray())
        }.let { conn ->
            val body = conn.inputStream.bufferedReader().readText().takeIf { conn.responseCode == 200 }
            conn.disconnect(); body
        }
    }.getOrNull()

    private fun open(url: String, token: String) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", USER_AGENT)
        }

    // ── 账号资料 ───────────────────────────────────────────────────────

    private suspend fun fetchAccountEmail() {
        val token = validAccessToken() ?: return
        authedGet("https://www.googleapis.com/oauth2/v1/userinfo", token)
            ?.let { email = JSONObject(it).optString("email") }
    }

    // ── GCP 项目挂载 ───────────────────────────────────────────────────

    /**
     * 尚未解析出 GCP 项目时跑两级探测：先 loadCodeAssist 读现值，空则
     * onboardUser 建新项目（长任务时轮询 operation，最多 24 轮 ×5s）。
     */
    suspend fun discoverProjectIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        if (!gcpProjectId.isNullOrEmpty()) return@withContext true
        val token = validAccessToken() ?: return@withContext false
        val found = loadCodeAssist(token) ?: onboardUser(token) ?: return@withContext false
        gcpProjectId = found
        true
    }

    private fun loadCodeAssist(token: String): String? {
        val body = cloudcodePost(":loadCodeAssist", token, "{}") ?: run {
            Log.d(TAG, "loadCodeAssist no answer"); return null
        }
        return JSONObject(body).optString("gcpProjectId").ifEmpty { null }
    }

    private suspend fun onboardUser(token: String): String? {
        val body = cloudcodePost(
            ":onboardUser", token,
            """{"setupMetadata":{"ideType":"NEOVIM","platforms":["LINUX"]}}""",
        ) ?: run { Log.w(TAG, "onboardUser no answer"); return null }

        val json = JSONObject(body)
        val operation = json.optString("name")
        return if (operation.isNotEmpty()) pollOperation(token, operation)
        else json.optString("gcpProjectId").ifEmpty { null }
    }

    private suspend fun pollOperation(token: String, operationName: String): String? {
        repeat(24) {
            delay(5000)
            authedGet("$CLOUDCODE_ROOT/$operationName", token)?.let { body ->
                val json = JSONObject(body)
                if (json.optBoolean("done")) {
                    return json.optJSONObject("response")?.optString("gcpProjectId")?.ifEmpty { null }
                }
            }
        }
        return null
    }

    // ── 刷新（form 体 + 四分类 + 单飞合并）────────────────────────────

    override suspend fun refreshToken(): Boolean = classifiedRefresh() == RefreshOutcome.SUCCESS

    suspend fun classifiedRefresh(): RefreshOutcome = RefreshGate.serialized(instanceId) {
        val stored = vault.readTokens() ?: return@serialized RefreshOutcome.NO_TOKEN
        val rotating = stored.optString("refresh_token", "")
        if (rotating.isEmpty()) return@serialized RefreshOutcome.NO_TOKEN
        if (RefreshGate.fresherArrived(vault, stored.optLong("expire_at", 0))) {
            Log.d(TAG, "refresh coalesced — fresher token already stored")
            return@serialized RefreshOutcome.SUCCESS
        }

        try {
            val (status, body) = OAuthWire.postForm(
                tokenEndpoint,
                mapOf(
                    "grant_type" to "refresh_token", "refresh_token" to rotating,
                    "client_id" to clientIdValue, "client_secret" to (clientSecretValue ?: ""),
                ),
            )
            when {
                status in 200..299 -> {
                    val json = JSONObject(body)
                    if (!json.has("refresh_token")) json.put("refresh_token", rotating)
                    vault.writeTokens(json)
                    Log.i(TAG, "Gemini refresh ok, expires in ${json.optLong("expires_in", 0)}s")
                    RefreshOutcome.SUCCESS
                }
                else -> classifyFailure(status, body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gemini refresh transient error — keeping token", e)
            RefreshOutcome.TRANSIENT
        }
    }

    private fun classifyFailure(status: Int, body: String): RefreshOutcome {
        val lower = body.lowercase()
        val fatal = status == 400 || status == 401 || status == 403 ||
            lower.contains("invalid_grant") || lower.contains("invalid_token")
        if (!fatal) {
            Log.w(TAG, "Gemini refresh transient failure ($status) — keeping token")
            return RefreshOutcome.TRANSIENT
        }
        Log.e(TAG, "Gemini refresh token invalid ($status): ${OAuthWire.redactForLog(body)} — clearing credentials")
        logout()
        return RefreshOutcome.INVALID_GRANT
    }

    override fun refreshLeewayMs(): Long = REFRESH_LEEWAY_MS

    override suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        val stored = vault.readTokens() ?: return@withContext null
        val token = stored.optString("access_token", "").ifEmpty { return@withContext null }
        val expireAt = stored.optLong("expire_at", 0)
        if (expireAt <= 0 || expireAt - System.currentTimeMillis() > REFRESH_LEEWAY_MS) {
            return@withContext token
        }
        when (classifiedRefresh()) {
            RefreshOutcome.SUCCESS -> vault.readTokens()?.optString("access_token", "")?.ifEmpty { null }
            RefreshOutcome.INVALID_GRANT, RefreshOutcome.NO_TOKEN -> null
            RefreshOutcome.TRANSIENT -> token.takeIf { expireAt > System.currentTimeMillis() }
        }
    }
}
