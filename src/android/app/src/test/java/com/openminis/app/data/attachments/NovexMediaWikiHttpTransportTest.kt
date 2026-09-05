package com.openminis.app.data.attachments

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexMediaWikiHttpTransportTest {
    @Test
    fun sendsFixedIdentifyingUserAgentAndPreservesRetryAfter() = runTest {
        var captured: Request? = null
        val transport = NovexMediaWikiHttpTransport(
            NovexMediaWikiCallExecutor { request ->
                captured = request
                response(
                    request = request,
                    code = 429,
                    body = "{}".toResponseBody(),
                    headers = mapOf("Retry-After" to "30"),
                )
            },
        )

        val result = transport.get("https://example.org/w/rest.php/v1/search/page")

        assertEquals(result.headers[NovexMediaWikiHttpTransport.DIAGNOSTIC_ERROR_HEADER], 429, result.statusCode)
        assertEquals("30", result.headers["Retry-After"])
        assertEquals(NovexMediaWikiHttpTransport.USER_AGENT, captured?.header("User-Agent"))
        assertEquals("application/json", captured?.header("Accept"))
    }

    @Test
    fun rejectsOversizedResponsesBeforeTheyReachTheDocumentParser() = runTest {
        val oversizedBody = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = NovexMediaWikiHttpTransport.MAX_RESPONSE_BYTES + 1
            override fun source() = Buffer().writeUtf8("{}")
        }
        val transport = NovexMediaWikiHttpTransport(
            NovexMediaWikiCallExecutor { request ->
                response(request, code = 200, body = oversizedBody)
            },
        )

        val result = transport.get("https://example.org/large")

        assertEquals(NovexMediaWikiHttpTransport.RESPONSE_TOO_LARGE, result.statusCode)
        assertTrue(result.body.length <= 2)
    }

    private fun response(
        request: Request,
        code: Int,
        body: ResponseBody,
        headers: Map<String, String> = emptyMap(),
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("fixture")
        .body(body)
        .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
        .build()
}
