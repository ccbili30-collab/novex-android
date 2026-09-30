package com.openminis.app.auth

import org.json.JSONObject

/**
 * [T-kimi-oauth] Kimi Code 的 RFC 8628 设备授权 Grant 纯逻辑（P3.5c
 * 真写；成员名被同包 KimiDeviceFlowTest 与 authkit.KimiLoginFlow 钉住，
 * 属冻结面）。只做解析与分类：零网络、零 Android 依赖（org.json 除外），
 * 与 iOS KimiDeviceFlow.swift 行为对齐。
 *
 * 线上端点实测事实（2026-07-23，iOS 侧核验）：
 *  - 请求体必须是 application/x-www-form-urlencoded；发 JSON 换来
 *    HTTP 400 + `client_id is required`；
 *  - 不许带 `scope` 字段——该 client 下任何显式 scope 都被拒，整段省略。
 */
object KimiDeviceFlow {

    const val AUTH_HOST = "https://auth.kimi.com"
    const val DEVICE_AUTHORIZATION_URL = "$AUTH_HOST/api/oauth/device_authorization"
    const val TOKEN_URL = "$AUTH_HOST/api/oauth/token"

    /** 编码 API 基址。供应层自行补 `/v1`：真实端点在 `/coding/v1/…` 下，
     *  `/coding/chat/completions` 直接 404。 */
    const val CODING_API_BASE = "https://api.kimi.com/coding"

    private const val DEFAULT_EXPIRY_SECONDS = 900L; private const val DEFAULT_INTERVAL_SECONDS = 5L
    private const val SLOW_DOWN_BUMP_SECONDS = 5L

    /** device_authorization 的解析产物。设备码剩余秒数与轮询间隔的
     *  应答缺省兜底分别是 900 与 5；openUrl 优先带预填码的完整版。 */
    data class DeviceAuthorization(
        val deviceCode: String, val userCode: String,
        val verificationUri: String, val verificationUriComplete: String?,
        val expiresInSeconds: Long, val intervalSeconds: Long,
    ) { val openUrl: String get() = verificationUriComplete ?: verificationUri }

    /** 一轮令牌端点轮询的分类。 */
    sealed class PollResult {
        data class Success(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Long?) : PollResult()
        object Pending : PollResult()   // authorization_pending：按当前间隔接着轮
        object SlowDown : PollResult()  // slow_down：接着轮，间隔 +5s（RFC 8628 §3.5）
        data class Denied(val description: String) : PollResult()
        data class Expired(val description: String) : PollResult()
        data class Fatal(val description: String) : PollResult()
    }

    private fun JSONObject.textOf(key: String) = optString(key, "").takeIf { it.isNotEmpty() }
    private fun JSONObject.firstText(vararg keys: String) = keys.firstNotNullOfOrNull { textOf(it) }
    private fun JSONObject.positiveOr(key: String, fallback: Long) = optLong(key, 0).takeIf { it > 0 } ?: fallback

    /**
     * 解析 device_authorization 应答；device_code / user_code / 验证 URI
     * （uri 与 url 两种拼法都认）任一必填缺席返回 null，由调用方报
     * 供应商错误。
     */
    fun parseDeviceAuthorization(json: JSONObject): DeviceAuthorization? {
        val required = listOf(
            json.textOf("device_code") ?: return null,
            json.textOf("user_code") ?: return null,
            json.firstText("verification_uri", "verification_url") ?: return null,
        )
        return DeviceAuthorization(
            deviceCode = required[0],
            userCode = required[1],
            verificationUri = required[2],
            verificationUriComplete = json.firstText("verification_uri_complete", "verification_url_complete"),
            expiresInSeconds = json.positiveOr("expires_in", DEFAULT_EXPIRY_SECONDS),
            intervalSeconds = json.positiveOr("interval", DEFAULT_INTERVAL_SECONDS),
        )
    }

    /** error → 终态构造器表；表外一律 Fatal。 */
    private val terminalCtors: Map<String, (String) -> PollResult> = mapOf(
        "access_denied" to PollResult::Denied, "expired_token" to PollResult::Expired,
    )

    private fun terminal(error: String, description: String): PollResult =
        terminalCtors[error]?.invoke(description) ?: PollResult.Fatal(description)

    /**
     * 分类一轮轮询应答：2xx 且带 access_token 记成功；pending / slow_down
     * 以 OAuth 错误形态（非 2xx + error 字段）到达；其余 error 值归终态。
     */
    fun classifyPoll(json: JSONObject, httpOK: Boolean): PollResult {
        if (httpOK) json.textOf("access_token")?.let { access ->
            return PollResult.Success(
                accessToken = access,
                refreshToken = json.textOf("refresh_token"),
                expiresInSeconds = json.optLong("expires_in", 0).takeIf { it > 0 },
            )
        }
        val error = json.textOf("error")?.lowercase() ?: "unknown_error"
        val description = json.textOf("error_description") ?: error
        return when (error) {
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown
            else -> terminal(error, description)
        }
    }

    /** RFC 8628 slow_down：运行间隔固定加 5 秒。 */
    fun bumpedInterval(currentSeconds: Long): Long = currentSeconds + SLOW_DOWN_BUMP_SECONDS

    /**
     * [T-oauth-refresh-race] invalid_grant 后的「先比对再删除」裁决
     * （iOS 36d506ca）：只有盘上 refresh token 仍是这轮失败请求发出的
     * 那枚时才该删——并发赢家可能已轮换它，此时删的是刚赢来的新登录
     * （“登录 45 分钟后被登出”那一类 bug）。true = 应删凭据。
     */
    fun shouldDeleteAfterInvalidGrant(staleRefreshToken: String, currentStoredRefreshToken: String?): Boolean =
        currentStoredRefreshToken?.equals(staleRefreshToken) ?: true
}
