package novex.android.authkit

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 刷新并发编排（P3.5c 真重写抽出的公共件）：Claude / Gemini / Kimi 三家
 * 的令牌刷新共用同一套纪律，原先三份拷贝各自为政。
 *
 *  - [serialized]：按实例的互斥单飞——并行请求同时发现临期时，只放
 *    一个去打网络，其余排队等锁；
 *  - [fresherArrived]：排队回来先看盘上 expire_at 是否已被别人刷新，
 *    是则本轮直接判成功，省一次网络。
 */
internal object RefreshGate {

    private val gates = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> serialized(instanceId: String, block: suspend () -> T): T =
        gates.getOrPut(instanceId) { Mutex() }.withLock { block() }

    /** 锁内复看：进锁前记下的旧 expire_at 已被并发刷新超越且新值未过期。 */
    fun fresherArrived(vault: CredentialVault, priorExpireAt: Long): Boolean {
        val latest = vault.readTokens()?.optLong("expire_at", 0) ?: 0
        return latest > priorExpireAt && latest > System.currentTimeMillis()
    }
}
