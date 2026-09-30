package novex.android.authkit

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * 把系统路由来的 OAuth 回调重定向投递回本机接收器（P3.5c 自
 * auth/OAuthRedirectActivity 的转发逻辑真重写）。
 *
 * 没有这层时，供应商 302 到 localhost 会让 Android 弹“选择应用”对话框；
 * Manifest 里钉在旧路径 Activity 上的 intent-filter 先一步接住重定向，
 * 这里再把 code/state 原样打给正在监听的 [LoopbackReceiver]。兜底端口
 * 54545（Claude 线）对应 URI 里读不出端口的畸形重定向。
 */
object LoopbackRedirectRelay {

    private const val TAG = "LoopbackRedirectRelay"
    private const val FALLBACK_PORT = 54545

    /**
     * 起后台线程把 `http://127.0.0.1:<port><path>?<query>` GET 一遍——
     * LoopbackReceiver 收到后即抽码自停。网络侧失败只记日志。
     */
    fun forward(uri: android.net.Uri?) {
        if (uri == null) return
        Log.i(TAG, "OAuth redirect received: $uri")

        val code = uri.getQueryParameter("code")
        if (code == null) {
            Log.w(TAG, "no 'code' parameter in redirect URI")
            return
        }

        Thread {
            try {
                val port = uri.port.takeIf { it > 0 } ?: FALLBACK_PORT
                val target = "http://127.0.0.1:$port${uri.path}?${uri.query}"
                Log.d(TAG, "forwarding to local receiver: $target")
                val conn = URL(target).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"
                Log.i(TAG, "local receiver responded: ${conn.responseCode}")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "forwarding to local receiver failed", e)
            }
        }.start()
    }
}
