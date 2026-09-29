package novex.model

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * OpenAI 兼容 Images API（`POST {base}/images/generations` 与 multipart 的
 * `/images/edits`），自有实现（HttpURLConnection，模块零上游依赖）。行为面对齐
 * 被替换的上游生图路径：请求体键（model/prompt/n/size?/quality?/response_format:
 * b64_json）、b64_json 自动探测（400 且错误体提及 response_format/b64_json 时去掉
 * 该键重试一次）、`data[].b64_json` 与 `data[].url`（无鉴权 GET 下载）两种条目、
 * `mime_type` 提示与魔数兜底、代理误路由到 chat completions 的 404 语义。
 *
 * 错误不本地分类——[ImagesResult.HttpError] 只携带状态与按 OpenAI 错误形态抽取的
 * message，映射矩阵归调用方（与聊天线共用同一套分类）。
 */
class ImagesClient(
    /** 已规范化的基址（形如 https://host/v1）；images 路径拼在其后。 */
    private val base: URI,
    /** Bearer 令牌；null/空白=不带鉴权头（无 key 中继是受支持配置）。 */
    private val bearerToken: String?,
    /** 出站 User-Agent（调用方决定默认品牌 UA 或实例自定 UA）。 */
    private val userAgent: String,
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) {
    init { require(timeoutMillis > 0) }

    /** 生成式生图：JSON 请求体。同步阻塞，调用方自备 IO 调度。 */
    fun generate(model: String, prompt: String, n: Int, size: String?, quality: String?): ImagesResult {
        var withoutResponseFormat = false
        while (true) {
            val body = JSONObject()
                .put("model", model)
                .put("prompt", prompt)
                .put("n", n)
            if (size != null) body.put("size", size)
            if (quality != null) body.put("quality", quality)
            if (!withoutResponseFormat) body.put("response_format", "b64_json")
            val outcome = post(body.toString().toByteArray(Charsets.UTF_8), "application/json; charset=utf-8", "/images/generations")
            if (outcome is ImagesResult.HttpError && outcome.status == 400 && !withoutResponseFormat &&
                (outcome.message.contains("response_format") || outcome.message.contains("b64_json"))
            ) { withoutResponseFormat = true; continue }
            return outcome
        }
        @Suppress("UNREACHABLE_CODE")
        return ImagesResult.InvalidResponse("images/generations: unreachable")
    }

    /**
     * 图生图 / 编辑：multipart/form-data。首图进 `image`、其余进 `image[]`
     * （上游同款字段名，多图端点自行拒绝超量）。响应形状与生成式一致，共用解析。
     */
    fun edit(model: String, prompt: String, images: List<ImageInput>, n: Int, size: String?, quality: String?): ImagesResult {
        if (images.isEmpty()) return ImagesResult.InvalidResponse("images/edits requires at least one input image")
        var withoutResponseFormat = false
        while (true) {
            val boundary = "minis-${System.nanoTime()}"
            val body = multipartBody(boundary, model, prompt, n, size, quality, withoutResponseFormat, images)
            val outcome = post(body, "multipart/form-data; boundary=$boundary", "/images/edits")
            if (outcome is ImagesResult.HttpError && outcome.status == 400 && !withoutResponseFormat &&
                (outcome.message.contains("response_format") || outcome.message.contains("b64_json"))
            ) { withoutResponseFormat = true; continue }
            return outcome
        }
        @Suppress("UNREACHABLE_CODE")
        return ImagesResult.InvalidResponse("images/edits: unreachable")
    }

    private fun multipartBody(boundary: String, model: String, prompt: String, n: Int, size: String?,
                              quality: String?, withoutResponseFormat: Boolean, images: List<ImageInput>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun field(name: String, value: String) {
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
            out.write(value.toByteArray()); out.write("\r\n".toByteArray())
        }
        field("model", model); field("prompt", prompt); field("n", n.toString())
        if (size != null) field("size", size)
        if (quality != null) field("quality", quality)
        if (!withoutResponseFormat) field("response_format", "b64_json")
        for ((index, image) in images.withIndex()) {
            val fieldName = if (index == 0) "image" else "image[]"
            val extension = image.mimeType.substringAfterLast('/', "").ifEmpty { "png" }
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"image$index.$extension\"\r\n".toByteArray())
            out.write("Content-Type: ${image.mimeType}\r\n\r\n".toByteArray())
            out.write(image.data); out.write("\r\n".toByteArray())
        }
        out.write("--$boundary--\r\n".toByteArray())
        return out.toByteArray()
    }

    private fun post(body: ByteArray, contentType: String, path: String): ImagesResult {
        val url = base.toString().trimEnd('/') + path
        var connection: HttpURLConnection? = null
        return try {
            val candidate = URI(url)
            // 端点契约与聊天线同源（https 或本机回环、头整洁）；令牌不进 URL。
            val endpoint = ModelEndpoint(candidate, bearerToken?.takeIf { it.isNotBlank() },
                mapOf("User-Agent" to userAgent))
            connection = candidate.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = timeoutMillis
            connection.setRequestProperty("Content-Type", contentType)
            endpoint.authorize(connection)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) return ImagesResult.HttpError(status, httpErrorMessage(status, text))
            parseGenerations(text, path)
        } catch (_: java.net.SocketTimeoutException) {
            ImagesResult.TimedOut
        } catch (_: IOException) {
            ImagesResult.NetworkFailure
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * `/images` 两端点响应解析：`data[]` 条目按 b64_json（解码，坏条目跳过）或 url
     * （无鉴权 GET 下载，失败跳过）取回字节；mime 依次取条目 `mime_type` 提示、
     * 下载响应 Content-Type、魔数兜底。`revised_prompt` 按序拼接为文本。代理把
     * 请求误路由到 chat completions 时（无 data 有 choices）给出可判别的 404 语义，
     * 调用方据此回落 chat 路线。
     */
    private fun parseGenerations(body: String, path: String): ImagesResult {
        val root = try { JSONObject(body) } catch (_: Exception) {
            return ImagesResult.InvalidResponse("$path returned non-JSON body")
        }
        val data = root.optJSONArray("data")
        if (data == null) {
            if (root.has("choices")) return ImagesResult.HttpError(404,
                "[404] $path not supported (got chat completions response)")
            return ImagesResult.Success(emptyList(), "")
        }
        val images = mutableListOf<GeneratedImage>()
        val revised = mutableListOf<String>()
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val mimeHint = if (item.isNull("mime_type")) null else item.optString("mime_type").takeIf { it.isNotEmpty() } // xAI 扩展提示
            val b64 = if (item.isNull("b64_json")) "" else item.optString("b64_json", "")
            if (b64.isNotEmpty()) {
                val bytes = try { java.util.Base64.getDecoder().decode(b64) } catch (_: IllegalArgumentException) { continue }
                images += GeneratedImage(mimeHint ?: detectImageMime(bytes), bytes)
            } else {
                val url = if (item.isNull("url")) "" else item.optString("url", "")
                if (url.isNotEmpty()) {
                    val downloaded = download(url)
                    if (downloaded != null) {
                        images += GeneratedImage(mimeHint ?: downloaded.first ?: detectImageMime(downloaded.second), downloaded.second)
                    }
                }
            }
            if (!item.isNull("revised_prompt")) item.optString("revised_prompt", "").takeIf { it.isNotEmpty() }?.let { revised += it }
        }
        return ImagesResult.Success(images, revised.joinToString("\n"))
    }

    /** url 条目下载：无鉴权 GET（与被替换实现一致——URL 本身即下载凭证）。失败返回 null 跳过。 */
    private fun download(url: String): Pair<String?, ByteArray>? {
        return try {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = timeoutMillis
                val status = connection.responseCode
                if (status !in 200..299) return null
                val bytes = connection.inputStream?.use { it.readBytes() } ?: return null
                if (bytes.isEmpty()) null else (connection.getHeaderField("Content-Type")?.substringBefore(';')?.takeIf { it.isNotEmpty() } to bytes)
            } finally { connection.disconnect() }
        } catch (_: Exception) { null }
    }

    /** OpenAI 错误形态抽取（error.message + request_id），非 JSON 体退回原文片段。 */
    private fun httpErrorMessage(status: Int, body: String): String = try {
        val root = JSONObject(body)
        val error = root.optJSONObject("error")
        val message = if (error?.isNull("message") == false) error.optString("message") else body
        val requestId = (if (error?.isNull("request_id") == false) error.optString("request_id") else null)
            ?: (if (root.isNull("request_id")) null else root.optString("request_id"))
        buildString {
            append("HTTP $status: ").append(message.take(1_200))
            if (!requestId.isNullOrEmpty() && requestId !in message) append("; request_id=").append(requestId.take(200))
        }
    } catch (_: Exception) {
        "HTTP $status: ${body.take(1_500)}"
    }

    private fun detectImageMime(data: ByteArray): String {
        if (data.size < 4) return "image/png"
        return when {
            data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() -> "image/png"
            data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> "image/jpeg"
            data[0] == 0x52.toByte() && data[1] == 0x49.toByte() && data[2] == 0x46.toByte() && data[3] == 0x46.toByte() -> "image/webp"
            data[0] == 0x47.toByte() && data[1] == 0x49.toByte() && data[2] == 0x46.toByte() -> "image/gif"
            else -> "image/png"
        }
    }

    companion object {
        /** 生图可远慢于聊天（gpt-image 系整图渲染）：读超时对齐被替换实现的 600s。 */
        const val DEFAULT_TIMEOUT_MILLIS = 600_000
        private const val CONNECT_TIMEOUT_MILLIS = 30_000
    }
}

/** 编辑接口的输入图（mime + 原始字节）。 */
data class ImageInput(val mimeType: String, val data: ByteArray)

/** 单张产出图：mime（提示/响应头/魔数兜底后）与解码字节。 */
data class GeneratedImage(val mimeType: String, val data: ByteArray)

/** Images 调用结论。失败不自动重试；HTTP 分类矩阵归调用方。 */
sealed interface ImagesResult {
    data class Success(val images: List<GeneratedImage>, val revisedPromptText: String) : ImagesResult
    data class HttpError(val status: Int, val message: String) : ImagesResult
    data class InvalidResponse(val reason: String) : ImagesResult
    /** 读超时（生图慢是常态，调用方可与网络异常区分提示）。 */
    data object TimedOut : ImagesResult
    data object NetworkFailure : ImagesResult
}
