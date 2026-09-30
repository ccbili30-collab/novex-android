package novex.android.authkit

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.openminis.app.auth.KimiDeviceFlow
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Kimi Code 登录流（P3.5c 自 auth/KimiOAuthManager 真重写）：
 * RFC 8628 设备授权Grant——不开浏览器、不占回调端口，用户在
 * auth.kimi.com 输短码，客户端轮询令牌端点。
 *
 * 继承自 [VendorLoginFlow] 只为复用凭据保险箱与登出；PKCE / 回调 /
 * 浏览器那套抽象面在此全是惰性占位（callbackPort=0、空 scope——Kimi
 * 拒绝任何显式 scope）。
 *
 * client id 可经 AndroidManifest meta-data `KimiOAuthClientID` 覆写；
 * 缺省借官方 CLI 的第一方身份（用户明示批准的合规决定：轮换 / 限流 /
 * 吊销风险已知悉）。显式置空则关闭登录入口，但缺省不空——iOS 的教训
 * 是空占位让登录键变成静默哑键。
 *
 * 刷新带并发合并与“先比对再删除”：invalid_grant 只有在盘上 refresh
 * token 仍是失败请求发出的那枚时才清库（并发轮换后的陈旧失败不认）。
 */
class KimiLoginFlow(appContext: Context, instanceId: String) :
    VendorLoginFlow(appContext, instanceId) {

    companion object {
        private const val TAG = "KimiLoginFlow"

        /** 官方 Kimi Code CLI 的 OAuth client id（CLIProxyAPI 公开源码所见）。 */
        const val OFFICIAL_CLIENT_ID = "17e5f671-d194-4dfb-9706-5516cb48c098"

        private const val REFRESH_LEEWAY_MS = 5L * 60 * 1000

        /**
         * 整链设备码登录：挂起到用户批准或终态失败。[onDeviceCode] 在
         * 短码下发后即回调（此时登录未完成），UI 借它展示码与验证 URL。
         * 返回 access token 并镜像成实例 API key。
         */
        suspend fun login(
            context: Context,
            instanceId: String,
            providerRepository: ProviderRepository,
            onDeviceCode: (KimiDeviceFlow.DeviceAuthorization) -> Unit,
        ): String {
            val flow = KimiLoginFlow(context, instanceId)
            val token = flow.runDeviceLogin(onDeviceCode)
            providerRepository.saveApiKey(instanceId, token)
            return token
        }
    }

    enum class RefreshOutcome { SUCCESS, INVALID_GRANT, TRANSIENT, NO_TOKEN }

    // 抽象面占位：设备流只用 tokenEndpoint 与 clientId，其余不生效。
    override val authorizeEndpoint = KimiDeviceFlow.AUTH_HOST
    override val tokenEndpoint = KimiDeviceFlow.TOKEN_URL
    override val clientIdValue: String
        get() {
            val override = try {
                appContext.packageManager
                    .getApplicationInfo(appContext.packageName, PackageManager.GET_META_DATA)
                    .metaData?.getString("KimiOAuthClientID")
            } catch (_: Exception) {
                null
            }
            // 显式空覆写 = 关闭登录；缺省回落官方值。
            return override ?: OFFICIAL_CLIENT_ID
        }
    override val clientSecretValue: String? = null
    override val loopbackPort = 0
    override val redirectTail = ""
    override val scopeValue = "" // Kimi 拒绝任何显式 scope——永不发送。

    val isLoginAvailable: Boolean get() = clientIdValue.isNotEmpty()

    // ── 设备码流 ───────────────────────────────────────────────────────

    /** auth.kimi.com 只认表单体（JSON 换来 `client_id is required`）。 */
    private fun postForm(url: String, params: Map<String, String>): Pair<Int, JSONObject> {
        val (status, body) = OAuthWire.postForm(url, params)
        val json = try {
            JSONObject(body)
        } catch (_: Exception) {
            JSONObject()
        }
        return status to json
    }

    /** 第一步：申请设备码。只发 client_id，不带 scope（带上即被拒）。 */
    suspend fun requestDeviceAuthorization(): KimiDeviceFlow.DeviceAuthorization =
        withContext(Dispatchers.IO) {
            check(isLoginAvailable) { "Kimi login unavailable — client id override is empty" }
            Log.i(TAG, "device-authorization round started for $instanceId")

            val (status, json) = postForm(
                KimiDeviceFlow.DEVICE_AUTHORIZATION_URL,
                mapOf("client_id" to clientIdValue),
            )
            if (status !in 200..299) {
                val reason = json.optString("error_description", "")
                    .ifEmpty { json.optString("error", "device authorization failed") }
                Log.e(TAG, "device_authorization failed: $reason")
                throw IllegalStateException("Kimi device authorization failed: $reason")
            }
            KimiDeviceFlow.parseDeviceAuthorization(json)
                ?: throw IllegalStateException("Kimi device authorization response missing required fields")
                    .also { Log.e(TAG, "device_authorization parse failure: ${OAuthWire.redactForLog(json.toString())}") }
        }

    /** 第二步：轮询令牌端点直到批准 / 终态失败（先睡后问，RFC 8628 §3.4）。 */
    suspend fun pollForToken(auth: KimiDeviceFlow.DeviceAuthorization): String =
        withContext(Dispatchers.IO) {
            val pollBody = mapOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "device_code" to auth.deviceCode, "client_id" to clientIdValue,
            )
            val deadline = System.currentTimeMillis() + auth.expiresInSeconds * 1000
            var interval = auth.intervalSeconds
            while (System.currentTimeMillis() < deadline) {
                delay(interval * 1000)
                val (status, json) = postForm(tokenEndpoint, pollBody)
                when (val result = KimiDeviceFlow.classifyPoll(json, status in 200..299)) {
                    is KimiDeviceFlow.PollResult.Success -> {
                        persistLoginSuccess(result)
                        Log.i(TAG, "device login landed for $instanceId")
                        return@withContext result.accessToken
                    }
                    KimiDeviceFlow.PollResult.Pending -> Unit
                    KimiDeviceFlow.PollResult.SlowDown ->
                        interval = KimiDeviceFlow.bumpedInterval(interval)
                            .also { Log.d(TAG, "slow_down — interval now ${it}s") }
                    is KimiDeviceFlow.PollResult.Denied -> fail("denied", result.description)
                    is KimiDeviceFlow.PollResult.Expired -> fail("code expired", result.description)
                    is KimiDeviceFlow.PollResult.Fatal -> fail("failed", result.description)
                }
            }
            throw IllegalStateException("Kimi login timed out")
        }

    private fun fail(what: String, why: String): Nothing =
        throw IllegalStateException("Kimi login $what: $why")

    /** 新登录生成设备身份；刷新路径另有来源。 */
    private fun storedDeviceIdentity(): String = UUID.randomUUID().toString()

    /** 两步串接；[onDeviceCode] 切主线程回调把码/URL 交给 UI。 */
    suspend fun runDeviceLogin(
        onDeviceCode: (KimiDeviceFlow.DeviceAuthorization) -> Unit,
    ): String {
        val auth = requestDeviceAuthorization()
        Log.i(
            TAG,
            "device code issued — user_code=${auth.userCode} " +
                "interval=${auth.intervalSeconds}s expires=${auth.expiresInSeconds}s",
        )
        withContext(Dispatchers.Main) { onDeviceCode(auth) }
        return pollForToken(auth)
    }

    private fun persistLoginSuccess(result: KimiDeviceFlow.PollResult.Success) {
        val now = System.currentTimeMillis()
        val bundle = JSONObject().put("access_token", result.accessToken)
        // 可选字段与设备身份（绝不进请求体）逐个补齐。
        if (!result.refreshToken.isNullOrEmpty()) bundle.put("refresh_token", result.refreshToken)
        result.expiresInSeconds?.let { bundle.put("expire_at", now + it * 1000) }
        bundle.put("device_id", storedDeviceIdentity()).put("last_refresh", now)
        vault.writeTokens(bundle)
    }

    // ── 刷新（单飞 + 先比对再删除）────────────────────────────────────

    suspend fun classifiedRefresh(): RefreshOutcome = RefreshGate.serialized(instanceId) {
        val stored = vault.readTokens() ?: return@serialized RefreshOutcome.NO_TOKEN
        val rotating = stored.optString("refresh_token", "")
        if (rotating.isEmpty()) return@serialized RefreshOutcome.NO_TOKEN
        if (RefreshGate.fresherArrived(vault, stored.optLong("expire_at", 0))) {
            Log.d(TAG, "refresh coalesced — fresher token already stored")
            return@serialized RefreshOutcome.SUCCESS
        }

        try {
            val (status, json) = postForm(
                tokenEndpoint,
                mapOf("grant_type" to "refresh_token", "refresh_token" to rotating, "client_id" to clientIdValue),
            )
            if (status in 200..299 && json.optString("access_token", "").isNotEmpty()) {
                vault.writeTokens(restamp(json, rotating, stored))
                Log.i(TAG, "refresh ok (expires in ${json.optLong("expires_in", 0)}s)")
                return@serialized RefreshOutcome.SUCCESS
            }

            val lower = json.toString().lowercase()
            val fatal = status in 400..403 || "invalid_grant" in lower || "refresh_token_reused" in lower
            if (fatal) {
                // 先比对再删除：盘上已被并发轮换成新值时，这枚失败是
                // 陈旧的——保新凭据不删。
                val onDisk = vault.readTokens()?.optString("refresh_token", "")
                if (!KimiDeviceFlow.shouldDeleteAfterInvalidGrant(rotating, onDisk)) {
                    Log.w(TAG, "stale invalid_grant ignored — token rotated concurrently; keeping new credentials")
                    return@serialized RefreshOutcome.SUCCESS
                }
                Log.e(TAG, "refresh token rejected — wiping stored credentials")
                logout()
                return@serialized RefreshOutcome.INVALID_GRANT
            }
            Log.w(TAG, "refresh transient failure — keeping token")
            RefreshOutcome.TRANSIENT
        } catch (e: Exception) {
            Log.w(TAG, "refresh transient error — keeping token: ${e.message}")
            RefreshOutcome.TRANSIENT
        }
    }

    /** 刷新应答落库前的整备：补回被省略的 refresh_token、续盖设备身份与时间戳。 */
    private fun restamp(json: JSONObject, rotating: String, prior: JSONObject): JSONObject {
        if (!json.has("refresh_token") || json.optString("refresh_token").isEmpty()) {
            json.put("refresh_token", rotating) // 不轮换的服务端会省略——保旧值
        }
        return json
            .put("device_id", prior.optString("device_id", UUID.randomUUID().toString()))
            .put("last_refresh", System.currentTimeMillis())
    }

    override suspend fun refreshToken(): Boolean = classifiedRefresh() == RefreshOutcome.SUCCESS

    override fun refreshLeewayMs(): Long = REFRESH_LEEWAY_MS

    /** 手工 bearer 优先；有 refresh token 且进 5 分钟窗才刷新。 */
    override suspend fun validAccessToken(): String? = withContext(Dispatchers.IO) {
        vault.loadManualBearer()?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        val stored = vault.readTokens() ?: return@withContext null
        val token = stored.optString("access_token", "").ifEmpty { return@withContext null }
        val expireAt = stored.optLong("expire_at", 0)
        val nearExpiry = stored.optString("refresh_token", "").isNotEmpty() &&
            expireAt > 0 && expireAt - System.currentTimeMillis() <= REFRESH_LEEWAY_MS
        if (!nearExpiry) return@withContext token

        when (classifiedRefresh()) {
            RefreshOutcome.SUCCESS -> vault.readTokens()?.optString("access_token", "")?.ifEmpty { null }
            RefreshOutcome.INVALID_GRANT -> null // classifiedRefresh 内已 logout()
            else -> if (expireAt in 1..System.currentTimeMillis()) null else token
        }
    }
}
