package novex.android.authkit

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.BindException
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder

/**
 * 本机回环 OAuth 回调接收器（P3.5c 自 auth/OAuthCallbackServer 真重写）。
 *
 * 在 loopback 端口起一个极简 HTTP 线程，等供应商把浏览器 302 到
 * `http://localhost:<port><path>?code=…&state=…`，抽出参数后交给 [onCode]。
 * 端口被占时按 [fallbackPorts] 依次下探，实际绑上的端口暴露在 [boundPort]。
 *
 * 冻结面（逐字）：
 *  - CORS 预检应答：204 + Access-Control-Allow-Origin 回显可信 Origin
 *    （auth.x.ai / accounts.x.ai，否则 "null"）、GET, OPTIONS、*、600、close；
 *    xAI 的授权页会先 OPTIONS /callback 再重定向，不回预检整条流就卡死。
 *  - 成功页 HTML 与 Content-Length/Connection 头。
 *  - [abortHook] 的“消费式”语义：仅由外部 stop() 触发一次，成功路径
 *    （onCode 之后的服务器自停）不触发——调用方在拿到 code 前先把它置空。
 */
class LoopbackReceiver(
    private val port: Int,
    private val fallbackPorts: List<Int> = emptyList(),
    private val onCode: (code: String, state: String?) -> Unit,
) {
    private companion object {
        const val TAG = "LoopbackReceiver"
        const val DONE_PAGE =
            "<html><body><h1>Authorization complete</h1><p>You can close this tab.</p><script>window.close()</script></body></html>"
        val TRUSTED_PREFLIGHT_ORIGINS = listOf("auth.x.ai", "accounts.x.ai")
    }

    private var socket: ServerSocket? = null

    @Volatile private var accepting = false

    /** 实际绑定的端口（走了 fallback 时与 [port] 不同）。 */
    var boundPort: Int = port
        private set

    /**
     * 外部中止回调：用户关掉 Custom Tab、管理器想放弃等待时，由 [stop]
     * 触发一次，让挂起中的 continuation 以取消收场而不是干等。置空后
     * 才调用，防止重入 stop() 双发。
     */
    @Volatile var abortHook: (() -> Unit)? = null

    fun start() {
        accepting = true
        Thread {
            try {
                if (!bindWithFallback()) return@Thread
                Log.d(TAG, "listening on $boundPort")
                while (accepting) {
                    val conn = socket?.accept() ?: break
                    handle(conn)
                }
            } catch (e: Exception) {
                if (accepting) Log.e(TAG, "receiver crashed", e)
            }
        }.start()
    }

    fun stop() {
        val wasAccepting = accepting
        accepting = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        if (wasAccepting) {
            val hook = abortHook
            abortHook = null
            try {
                hook?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "abortHook threw: ${e.message}")
            }
        }
    }

    // ── 内部 ───────────────────────────────────────────────────────────

    private fun bindWithFallback(): Boolean {
        for (candidate in listOf(port) + fallbackPorts) {
            try {
                socket = ServerSocket(candidate)
                boundPort = candidate
                return true
            } catch (_: BindException) {
                Log.w(TAG, "port $candidate busy, trying next")
            }
        }
        Log.e(TAG, "no port available in ${listOf(port) + fallbackPorts}")
        return false
    }

    private fun handle(conn: java.net.Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(conn.getInputStream()))
            val requestLine = reader.readLine() ?: run { conn.close(); return }
            Log.d(TAG, "request: $requestLine")

            if (requestLine.startsWith("OPTIONS")) {
                answerPreflight(conn, reader)
                return
            }

            val target = requestLine.split(" ").getOrNull(1)
            val query = target?.let { URI("http://localhost$it").query }
            val params = parseQuery(query)

            val payload = DONE_PAGE.toByteArray()
            conn.getOutputStream().write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n").toByteArray() +
                    payload,
            )
            conn.close()

            val code = params["code"]
            if (code != null) {
                onCode(code, params["state"])
                stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "connection handling failed", e)
            try {
                conn.close()
            } catch (_: Exception) {
            }
        }
    }

    /** 读余下请求头找 Origin，按可信名单决定回显还是 "null"。 */
    private fun answerPreflight(conn: java.net.Socket, reader: BufferedReader) {
        var origin: String? = null
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
            if (header.lowercase().startsWith("origin:")) {
                origin = header.substringAfter(":").trim()
            }
        }
        val echoed = origin?.takeIf { o -> TRUSTED_PREFLIGHT_ORIGINS.any { o.contains(it) } } ?: "null"
        conn.getOutputStream().write(
            (
                "HTTP/1.1 204 No Content\r\n" +
                    "Access-Control-Allow-Origin: $echoed\r\n" +
                    "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: *\r\n" +
                    "Access-Control-Max-Age: 600\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        conn.close()
    }

    private fun parseQuery(query: String?): Map<String, String> {
        if (query == null) return emptyMap()
        return query.split("&").mapNotNull { kv ->
            val idx = kv.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val raw = if (kv.length > idx + 1) kv.substring(idx + 1) else ""
            kv.substring(0, idx) to URLDecoder.decode(raw, "UTF-8")
        }.toMap()
    }
}
