package novex.android.netwatch

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

/**
 * 连通性监视与连接池清扫（P3.5c 自 network/NetworkMonitor 真重写）。
 *
 * 两件事：
 *  - 用 ConnectivityManager.NetworkCallback 盯 NET_CAPABILITY_INTERNET，
 *    把通/断 publish 到 [status]；
 *  - 网络切换时把 OkHttp 连接池里的空闲连接全部逐出——经本地 VPN /
 *    代理（clash 之类打 127.0.0.1）时，到 localhost 的 TCP socket 能扛过
 *    网络抖动，池子会把死掉的 h2 隧道反复递给重试方，请求写进去后
 *    永远等不到响应头。逐出 [sharedLLMConnectionPool]（各长存 LLM 客户端
 *    共享的池）加 [start] 登记的客户端，才能真扫到供应方连接。
 */
class LinkMonitor {

    enum class NetworkStatus { CONNECTED, DISCONNECTED }

    companion object {
        private const val TAG = "LinkMonitor"

        /** 供应方长存客户端共用的连接池（5 连接 / 5 分钟空闲）。 */
        val sharedLLMConnectionPool = okhttp3.ConnectionPool(
            5, 5, java.util.concurrent.TimeUnit.MINUTES,
        )
    }

    private val _status = MutableStateFlow(NetworkStatus.DISCONNECTED)
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private var connectivityManager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var registeredClient: OkHttpClient? = null

    /**
     * 登记网络回调并置初值。[client] 是可选的共享 OkHttp 客户端，其连接
     * 池会随网络切换一并逐出。
     */
    fun start(context: Context, client: OkHttpClient? = null) {
        registeredClient = client
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        connectivityManager = cm
        if (cm == null) {
            Log.e(TAG, "ConnectivityManager not available")
            return
        }

        publishInitial(cm)

        val watcher = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (_status.value == NetworkStatus.DISCONNECTED) {
                    Log.d(TAG, "transition: DISCONNECTED -> CONNECTED")
                    _status.value = NetworkStatus.CONNECTED
                    evictPools()
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "transition: CONNECTED -> DISCONNECTED")
                _status.value = NetworkStatus.DISCONNECTED
                evictPools()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val next = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    NetworkStatus.CONNECTED
                } else {
                    NetworkStatus.DISCONNECTED
                }
                if (next != _status.value) {
                    Log.d(TAG, "capabilities changed: ${_status.value} -> $next")
                    _status.value = next
                    evictPools()
                }
            }
        }
        callback = watcher
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            watcher,
        )
        Log.d(TAG, "monitoring started")
    }

    /** 注销回调；清理阶段调用。 */
    fun stop() {
        callback?.let { registered ->
            try {
                connectivityManager?.unregisterNetworkCallback(registered)
                Log.d(TAG, "monitoring stopped")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "callback was not registered: ${e.message}")
            }
        }
        callback = null
        connectivityManager = null
        registeredClient = null
    }

    // ── 内部 ───────────────────────────────────────────────────────────

    private fun publishInitial(cm: ConnectivityManager) {
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        _status.value = if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
            NetworkStatus.CONNECTED
        } else {
            NetworkStatus.DISCONNECTED
        }
        Log.d(TAG, "initial network status: ${_status.value}")
    }

    /** 共享池 + 登记客户端的池子一起逐出。 */
    private fun evictPools() {
        sharedLLMConnectionPool.evictAll()
        registeredClient?.connectionPool?.evictAll()
        Log.d(TAG, "OkHttp connection pools evicted (shared + registered client)")
    }
}
