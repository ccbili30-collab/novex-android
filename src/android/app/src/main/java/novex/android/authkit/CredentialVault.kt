package novex.android.authkit

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * OAuth 凭据保险箱（P3.5c 真重写）：一家供应商实例的令牌与辅助字段的
 * 唯一读写通道。
 *
 * 存储布局（冻结面，老设备上已有数据按这些键落盘）：
 *  - prefs 文件名 `oauth_prefs`，经 [EncryptedPrefsFactory] 自愈工厂创建
 *    （三星 One UI / Android 16 主键失效不再炸后台刷新路径）；
 *  - 结构化令牌包：`oauth_tokens_<instanceId>`，值为 JSON 文本
 *    （access_token / refresh_token / expire_at / 厂商附加字段）；
 *  - 辅助字符串：`oauth_<key>_<instanceId>`（verifier / state / email /
 *    gcp_project / auth_endpoint …各家自定）；
 *  - 手工静态 bearer：`oauth_manual_bearer_token_<instanceId>`。
 *
 * 过期语义：响应里的相对 `expires_in`（秒）在写入时折算成绝对
 * `expire_at`（epoch ms）；导入旧包时保留原绝对值不重算。
 */
class CredentialVault(private val context: Context, private val instanceId: String) {

    private companion object {
        const val MANUAL_BEARER_KEY = "manual_bearer_token"
        const val TOKENS_KEY_PREFIX = "oauth_tokens_"
        const val AUX_KEY_TEMPLATE = "oauth_%s_%s"
    }

    private val prefs: SharedPreferences by lazy {
        novex.android.vault.SelfHealingPrefs.safeCreate(context, "oauth_prefs")
    }

    // ── 结构化令牌包 ───────────────────────────────────────────────────

    fun readTokens(): JSONObject? {
        val raw = prefs.getString(TOKENS_KEY_PREFIX + instanceId, null) ?: return null
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    /** 写入令牌包；带 `expires_in` 时顺手折算绝对过期时刻。 */
    fun writeTokens(json: JSONObject) {
        val ttl = json.optLong("expires_in", 0)
        if (ttl > 0) {
            json.put("expire_at", System.currentTimeMillis() + ttl * 1000)
        }
        prefs.edit().putString(TOKENS_KEY_PREFIX + instanceId, json.toString()).apply()
    }

    // ── 辅助字符串 ─────────────────────────────────────────────────────

    fun writeAux(key: String, value: String) {
        prefs.edit().putString(auxKey(key), value).apply()
    }

    fun readAux(key: String): String? = prefs.getString(auxKey(key), null)

    private fun auxKey(key: String) = AUX_KEY_TEMPLATE.format(key, instanceId)

    // ── 手工静态 bearer ────────────────────────────────────────────────

    /**
     * 用户自填的静态 bearer：设了就原样生效、不走刷新——面向不跑标准
     * OAuth 却要 Bearer 凭据的自定义中转端（对应 iOS "manual-oauth-token"
     * 钥匙串账号）。
     */
    fun saveManualBearer(token: String) = writeAux(MANUAL_BEARER_KEY, token)

    fun loadManualBearer(): String? = readAux(MANUAL_BEARER_KEY)

    fun deleteManualBearer() {
        prefs.edit().remove(auxKey(MANUAL_BEARER_KEY)).apply()
    }

    // ── 全量登出 ───────────────────────────────────────────────────────

    fun wipe() {
        prefs.edit()
            .remove(TOKENS_KEY_PREFIX + instanceId)
            .remove(auxKey(MANUAL_BEARER_KEY))
            .apply()
    }

    // ── 跨端导入归一 ───────────────────────────────────────────────────

    /**
     * iOS 导出包用 camelCase 键（JSONEncoder 产物）；落盘前折成 snake_case。
     * `expireDate` 若是苹果参考纪元（2001-01-01 起的秒）或秒级 epoch，
     * 一并换算成 epoch ms——小于 2e9 视作参考纪元偏移，2e9..2e12 视作
     * 秒级 epoch，更大者按 ms 原样接受。
     */
    fun normalizeImported(raw: String): JSONObject? {
        val obj = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        val keyMap = mapOf(
            "accessToken" to "access_token",
            "refreshToken" to "refresh_token",
            "idToken" to "id_token",
            "expireDate" to "expire_at",
            "lastRefresh" to "last_refresh",
            "accountId" to "account_id",
            "planType" to "plan_type",
            "tokenEndpoint" to "token_endpoint",
        )
        if (keyMap.keys.none { obj.has(it) }) return obj

        val out = JSONObject()
        for (key in obj.keys()) {
            val mapped = keyMap[key] ?: key
            var value: Any = obj.get(key)
            if (mapped == "expire_at" && value is Number) {
                val v = value.toDouble()
                val appleEpochMs = 978307200000L
                value = when {
                    v < 2_000_000_000L -> (v * 1000).toLong() + appleEpochMs
                    v < 2_000_000_000_000L -> v.toLong()
                    else -> v.toLong()
                }
            }
            out.put(mapped, value)
        }
        return out
    }

    /** 导入令牌包：保留包内绝对 `expire_at`，不做 expires_in 重算。 */
    fun importTokens(raw: String) {
        val normalized = normalizeImported(raw) ?: return
        prefs.edit().putString(TOKENS_KEY_PREFIX + instanceId, normalized.toString()).apply()
    }
}
