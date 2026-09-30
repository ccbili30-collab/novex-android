package novex.android.authkit

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE / state / nonce 随机材料生成（P3.5c 自 auth/ 真重写）。
 *
 * 三种出参形态对应三家约定，均为冻结面：
 *  - [base64UrlPair]：N 个随机字节 → base64url 无填充 verifier，SHA-256 后
 *    同样编码为 challenge。N 的缺省 64；Anthropic 要求 96（与 iOS 端一致）。
 *  - [hexPair]：32 字节 → 64 个十六进制字符 verifier（xAI / Gemini 约定）。
 *  - [opaqueToken]：32 字节 base64url，用作 state 或 OIDC nonce。
 *
 * verifier 由调用方决定存活方式（内存字段或落加密 prefs），本对象不持有状态，
 * 每次调用都用新的 [SecureRandom] 实例。
 */
internal object PkceMaterial {

    private fun sha256Base64Url(ascii: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(ascii.toByteArray(Charsets.US_ASCII)),
        )

    /** base64url 形态的 (verifier, challenge=S256(verifier))。 */
    fun base64UrlPair(byteCount: Int = 64): Pair<String, String> {
        val bytes = ByteArray(byteCount).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return verifier to sha256Base64Url(verifier)
    }

    /** 十六进制形态的 (verifier, challenge)。 */
    fun hexPair(): Pair<String, String> {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = bytes.joinToString("") { "%02x".format(it) }
        return verifier to sha256Base64Url(verifier)
    }

    /** 32 字节随机 base64url 串：OAuth state 或 OIDC nonce 通用。 */
    fun opaqueToken(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
