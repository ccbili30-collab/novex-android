package novex.android.authkit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * OpenRouter 换长期 API key 的 OAuth-PKCE 流（P3.5c 自
 * auth/OpenRouterOAuthManager 真重写）。
 *
 * 与别家不同：换到的不是 access/refresh 对，而是一枚永久 `key`，直接
 * 经 [ProviderRepository.saveApiKey] 落库，此后按普通 API-key 供应商走。
 *
 * 冻结面：
 *  - 授权页 `https://openrouter.ai/auth?callback_url=…&code_challenge=…&
 *    code_challenge_method=S256&_nc=<毫秒时间戳>`（_nc 防缓存）；
 *  - 换 key POST `https://openrouter.ai/api/v1/auth/keys`（JSON 体：
 *    code / code_verifier / code_challenge_method），头带
 *    HTTP-Referer=https://github.com/ccbili30-collab/novex-android 与
 *    X-Title="Minis App"；
 *  - PKCE 96 字节，standard base64 → 手工替换成 URL-safe（去填充，
 *    + → -、/ → _），与 iOS Data.base64URLEncodedOpenRouter 逐位一致；
 *  - 回调端口 3000，占用时降 3001、3002。
 *
 * 本流不落 OAuth 令牌包——isAuthenticated / logout 直接对着仓库里的
 * API key 做。
 */
object OpenRouterKeyFlow {

    private const val TAG = "OpenRouterKeyFlow"
    private const val AUTH_ENTRY = "https://openrouter.ai/auth"
    private const val KEYS_ENDPOINT = "https://openrouter.ai/api/v1/auth/keys"
    private const val PRIMARY_PORT = 3000
    private val BACKUP_PORTS = listOf(3001, 3002)

    private var receiver: LoopbackReceiver? = null

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 起回调服务开 Custom Tab 走完授权，换出的 key 落库并返回。失败抛。 */
    suspend fun login(context: Context, instanceId: String, providerRepository: ProviderRepository): String {
        Log.i(TAG, "=== OpenRouter OAuth login started (instance: $instanceId) ===")

        receiver?.stop()
        receiver = null

        val (verifier, challenge) = freshPkce()
        Log.i(TAG, "PKCE ready — verifier length: ${verifier.length}")

        val apiKey = withContext(Dispatchers.IO) {
            val code = suspendCancellableCoroutine { cont ->
                val server = LoopbackReceiver(PRIMARY_PORT, BACKUP_PORTS) { received, _ ->
                    if (cont.isActive) cont.resume(received)
                }
                receiver = server
                server.start()
                Log.i(TAG, "callback receiver up on port ${server.boundPort}")

                cont.invokeOnCancellation {
                    server.stop()
                    receiver = null
                }

                val callbackUrl = "http://localhost:${server.boundPort}/callback"
                val authUrl = Uri.parse(AUTH_ENTRY).buildUpon()
                    .appendQueryParameter("callback_url", callbackUrl)
                    .appendQueryParameter("code_challenge", challenge)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .appendQueryParameter("_nc", System.currentTimeMillis().toString())
                    .build()
                Log.d(TAG, "auth URL: $authUrl")

                val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
                tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                tab.launchUrl(context, authUrl)
                Log.i(TAG, "Custom Tab opened for OpenRouter authorization")
            }

            receiver?.stop()
            receiver = null
            Log.i(TAG, "callback received — code length: ${code.length}")

            swapCodeForKey(code, verifier)
        }

        providerRepository.saveApiKey(instanceId, apiKey)
        Log.i(TAG, "=== OpenRouter OAuth login complete (instance: $instanceId) ===")
        return apiKey
    }

    fun isAuthenticated(instanceId: String, providerRepository: ProviderRepository): Boolean =
        providerRepository.loadApiKey(instanceId) != null

    fun logout(instanceId: String, providerRepository: ProviderRepository) {
        providerRepository.deleteApiKey(instanceId)
        Log.i(TAG, "logout — cleared API key (instance: $instanceId)")
    }

    // ── 内部 ───────────────────────────────────────────────────────────

    private fun freshPkce(): Pair<String, String> {
        val bytes = ByteArray(96).also { SecureRandom().nextBytes(it) }
        val verifier = urlSafe(bytes)
        val challenge = urlSafe(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8)))
        Log.d(TAG, "PKCE verifier (${verifier.length} chars): ${verifier.take(20)}...")
        Log.d(TAG, "PKCE challenge (${challenge.length} chars): $challenge")
        return verifier to challenge
    }

    /** standard base64（不换行）→ + 替 -、/ 替 _、去掉 = 填充。 */
    private fun urlSafe(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            .replace('+', '-')
            .replace('/', '_')
            .replace("=", "")

    private fun swapCodeForKey(code: String, verifier: String): String {
        val body = JSONObject().apply {
            put("code", code)
            put("code_verifier", verifier)
            put("code_challenge_method", "S256")
        }
        Log.d(TAG, "exchange body: $body")
        Log.d(TAG, "code: $code / verifier (${verifier.length} chars): ${verifier.take(20)}...${verifier.takeLast(10)}")

        val request = Request.Builder()
            .url(KEYS_ENDPOINT)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://github.com/ccbili30-collab/novex-android")
            .header("X-Title", "Minis App")
            .build()

        http.newCall(request).execute().use { resp ->
            val responseBody = resp.body?.string() ?: ""
            Log.i(TAG, "response status: ${resp.code}")
            // 成功体带的是活的 API key——只记长度，绝不记内容。
            Log.d(TAG, "response body received (len=${responseBody.length})")

            if (!resp.isSuccessful) {
                throw IllegalStateException("OpenRouter key exchange failed (${resp.code}): $responseBody")
            }
            return JSONObject(responseBody).getString("key")
        }
    }
}
