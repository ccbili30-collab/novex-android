package novex.android.authkit

import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 供应商 OAuth 流共用的 HTTP 底座（P3.5c 自 auth/ 真重写）。
 *
 * 三件事收拢在一处，替代原先散在各 manager 里的重复样板：
 *  - 单例 OkHttp 客户端（15s 连接/读取超时，走系统代理）；
 *  - form-urlencoded 与 JSON 两种 POST 的统一入口，返回 (状态码, 响应体)；
 *  - 日志脱敏：凡是可能落到 logcat / 端内日志文件的响应体，先过
 *    [redactForLog] 再输出——某些 IdP 会在错误体里回显请求材料，
 *    畸形的“成功”体一旦走了错误路径，原始凭据就会整段进日志。
 */
internal object OAuthWire {

    /** 全部 OAuth 网络请求共用的客户端（尊重系统代理设置）。 */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val FORM_TYPE = "application/x-www-form-urlencoded".toMediaType()
    private val JSON_TYPE = "application/json".toMediaType()

    /**
     * 把响应体里已知凭据字段的“值”打码并截断到诊断可用的长度。
     * 非敏感字段（error / token_type 等）原样保留，便于排查。
     */
    fun redactForLog(body: String, maxLen: Int = 300): String {
        val masked = Regex(
            "\"(access_token|refresh_token|id_token|api_key|key|client_secret|device_code)\"\\s*:\\s*\"[^\"]*\"",
        ).replace(body) { m -> "\"${m.groupValues[1]}\":\"***\"" }
        return if (masked.length <= maxLen) masked else masked.take(maxLen) + "…(${masked.length} chars)"
    }

    /** 表单编码：k=v&…，值经 [Uri.encode] 百分号转义。 */
    fun encodeForm(params: Map<String, String>): String =
        params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }

    /** 发 form POST，挂起等待结果。调用方自行切换到 IO 线程。 */
    fun postForm(url: String, params: Map<String, String>): Pair<Int, String> =
        execute(Request.Builder().url(url).post(encodeForm(params).toRequestBody(FORM_TYPE)).build())

    /** 发 JSON POST。 */
    fun postJson(url: String, json: String): Pair<Int, String> =
        execute(Request.Builder().url(url).post(json.toRequestBody(JSON_TYPE)).build())

    /** 发 GET（OIDC 发现文档等只读端点）。 */
    fun get(url: String): Pair<Int, String> =
        execute(Request.Builder().url(url).get().build())

    private fun execute(request: Request): Pair<Int, String> =
        client.newCall(request).execute().use { resp ->
            resp.code to (resp.body?.string() ?: "")
        }
}
