package com.openminis.app.data.attachments

import com.openminis.app.novex.domain.NovexMediaWikiHttpResponse
import com.openminis.app.novex.domain.NovexMediaWikiTransport
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

internal fun interface NovexMediaWikiCallExecutor {
    fun execute(request: Request): Response
}

/** Android-only HTTP adapter. Domain discovery, confirmation and parsing remain in novex-core. */
class NovexMediaWikiHttpTransport internal constructor(
    private val callExecutor: NovexMediaWikiCallExecutor,
) : NovexMediaWikiTransport {
    constructor(client: OkHttpClient = defaultClient()) : this(
        NovexMediaWikiCallExecutor { request -> client.newCall(request).execute() },
    )

    override suspend fun get(url: String): NovexMediaWikiHttpResponse = try {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("Accept-Encoding", "gzip")
            .header("User-Agent", USER_AGENT)
            .build()
        callExecutor.execute(request).use { response ->
            val body = response.body
            val declaredLength = body?.contentLength() ?: 0L
            if (declaredLength > MAX_RESPONSE_BYTES) {
                return@use NovexMediaWikiHttpResponse(
                    statusCode = RESPONSE_TOO_LARGE,
                    body = "{}",
                )
            }
            val bytes = body?.source()?.let { source ->
                val buffer = Buffer()
                var remaining = MAX_RESPONSE_BYTES + 1
                while (remaining > 0) {
                    val read = source.read(buffer, minOf(READ_CHUNK_BYTES, remaining))
                    if (read == -1L) break
                    remaining -= read
                }
                buffer.readByteArray()
            } ?: ByteArray(0)
            if (bytes.size > MAX_RESPONSE_BYTES) {
                return@use NovexMediaWikiHttpResponse(
                    statusCode = RESPONSE_TOO_LARGE,
                    body = "{}",
                )
            }
            NovexMediaWikiHttpResponse(
                statusCode = response.code,
                body = bytes.toString(Charsets.UTF_8),
                headers = response.headers.names().associateWith { name ->
                    response.header(name).orEmpty()
                },
            )
        }
    } catch (failure: IOException) {
        NovexMediaWikiHttpResponse(
            statusCode = NETWORK_UNAVAILABLE,
            body = "{}",
            headers = mapOf(DIAGNOSTIC_ERROR_HEADER to failure.toString()),
        )
    }

    companion object {
        internal const val USER_AGENT =
            "Novex-Android/0.2 (https://github.com/ccbili30-collab/novex-android; read-only research client)"
        internal const val MAX_RESPONSE_BYTES = 8L * 1024L * 1024L
        internal const val RESPONSE_TOO_LARGE = 413
        internal const val NETWORK_UNAVAILABLE = 599
        internal const val DIAGNOSTIC_ERROR_HEADER = "X-Novex-Network-Error"
        private const val READ_CHUNK_BYTES = 8L * 1024L

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
