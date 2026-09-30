package novex.android.runtime

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.openminis.app.service.SessionBadgeStore
import com.openminis.app.service.SessionBadgeStore.SessionBadgeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 会话角标队列的内存态与落盘实现（旧路径 [SessionBadgeStore] 门面转
 * 发到这里；枚举类型钉在门面上，见其嵌套声明）。
 *
 * 模型：每个会话一条**有序**状态队列，队头即列表 cell 当前渲染的角
 * 标。推入新状态时插队到头（并去重——同一状态若已在中段就提到头，
 * 不堆叠副本）；用户回到会话即摘除对应状态，队列后段自然浮出。
 *
 * 持久化是一条 SharedPreferences 字符串，编码为
 * `id1=PAUSED,ICLOUD_SYNCING;id2=PAUSED`——整个字符串一次原子写，
 * 条目量级（几百会话 × 短枚举名）远够用，没必要为它引入 JSON。
 */
internal object SessionBadges {

    private const val TAG = "SessionBadges"
    private const val PREFS = "session_badge_store"
    private const val KEY = "badge_state_by_session"

    private val queues = MutableStateFlow<Map<String, List<SessionBadgeState>>>(emptyMap())
    val byId: StateFlow<Map<String, List<SessionBadgeState>>> = queues.asStateFlow()

    @Volatile
    private var prefs: SharedPreferences? = null

    /** 进程启动时恢复磁盘态。重复调用是幂等的。 */
    fun attach(context: Context) {
        if (prefs != null) return
        val loaded = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also { prefs = it }
        queues.value = decode(loaded)
        Log.d(TAG, "restored badges for ${queues.value.size} session(s)")
    }

    /** 队列队头；空队列返回 null。 */
    fun headOf(sessionId: String): SessionBadgeState? =
        queues.value[sessionId]?.firstOrNull()

    /** 把 [state] 插到队头（已在队头则不动；在中段则提前，不叠副本）。 */
    fun promote(sessionId: String, state: SessionBadgeState) {
        rewrite { current ->
            val line = current[sessionId].orEmpty()
            if (line.firstOrNull() == state) {
                current
            } else {
                current + (sessionId to (listOf(state) + line.filter { it != state }))
            }
        }
    }

    /** 摘除 [state]；队列清空后连 key 一起删。 */
    fun drop(sessionId: String, state: SessionBadgeState) {
        rewrite { current ->
            val line = current[sessionId] ?: return@rewrite current
            val remaining = line.filter { it != state }
            if (remaining.isEmpty()) current - sessionId else current + (sessionId to remaining)
        }
    }

    /** 会话被删时整条丢弃。 */
    fun wipe(sessionId: String) {
        rewrite { it - sessionId }
    }

    /**
     * 以权威的中断集合为准对账 [SessionBadgeState.PAUSED]：
     * 该有而没有的补上（进程被杀的会话走不到生命周期回调，只有靠
     * 启动时对账补角标）；不该有还挂着的摘掉。其余状态不动。
     */
    fun reconcileAgainst(interrupted: Set<String>) {
        rewrite { current ->
            val merged = current.toMutableMap()
            interrupted.forEach { sid ->
                val line = merged[sid].orEmpty()
                if (SessionBadgeState.PAUSED !in line) {
                    merged[sid] = listOf(SessionBadgeState.PAUSED) + line
                }
            }
            merged.keys.toList().forEach { sid ->
                val line = merged[sid] ?: return@forEach
                if (SessionBadgeState.PAUSED in line && sid !in interrupted) {
                    val remaining = line.filter { it != SessionBadgeState.PAUSED }
                    if (remaining.isEmpty()) merged.remove(sid) else merged[sid] = remaining
                }
            }
            merged
        }
        Log.d(TAG, "reconcileAgainst: ${interrupted.size} interrupted session(s)")
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    /**
     * 单一写入口：在监视器里算出新表、发布、落盘三步一气呵成，读方
     * 不会看到"流已更新、盘还没写"的中间态。
     */
    private fun rewrite(block: (Map<String, List<SessionBadgeState>>) -> Map<String, List<SessionBadgeState>>) {
        synchronized(this) {
            val next = block(queues.value)
            if (next === queues.value) return
            queues.value = next
            prefs?.let { store -> encodeInto(store, next) }
        }
    }

    private fun encodeInto(store: SharedPreferences, map: Map<String, List<SessionBadgeState>>) {
        val blob = map.entries.joinToString(";") { (id, line) ->
            "$id=${line.joinToString(",") { it.name }}"
        }
        runCatching { store.edit().putString(KEY, blob).apply() }
            .onFailure { Log.w(TAG, "persist failed: ${it.message}") }
    }

    private fun decode(store: SharedPreferences): Map<String, List<SessionBadgeState>> {
        val blob = runCatching { store.getString(KEY, null) }.getOrNull()
        if (blob.isNullOrBlank()) return emptyMap()
        return blob.split(';').mapNotNull { entry ->
            val sep = entry.indexOf('=')
            if (sep <= 0 || sep == entry.lastIndex) return@mapNotNull null
            val id = entry.substring(0, sep)
            val line = entry.substring(sep + 1).split(',')
                .mapNotNull { name -> runCatching { SessionBadgeState.valueOf(name) }.getOrNull() }
            if (line.isEmpty()) null else id to line
        }.toMap()
    }
}
