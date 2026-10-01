package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/**
 * ChatViewModel 的进程级缓存，按 sessionId 分桶。
 *
 * 离开聊天页不能杀掉正在跑的 agent 回合：ViewModel 若挂在导航回栈上，
 * popBackStack 会连 viewModelScope 一起取消。每个会话一个
 * [ViewModelStore]，要丢弃时对 store 调 clear() 触发 onCleared。
 *
 * 另管三件杂事：草稿 id → 正式 id 的别名映射、删除会话时的运行中
 * Job 停等、以及「移动到…」流程的一次性转交槽。
 */
object ChatViewModelStore {

    private const val TAG = "ChatVMStore"

    /** 一个会话的全部运行态：VM 持有桶 + 正在跑的回合 Job。 */
    private class Bucket {
        var store: ViewModelStore? = null
        var job: Job? = null
    }

    private val buckets = LinkedHashMap<String, Bucket>()

    /** 已判死刑的会话键：删除流程标记，阻止新 Job 再挂上来。 */
    private val closedKeys = mutableSetOf<String>()

    /** 删除请求登记过哪些键（含别名解析展开），失败时按它回滚 closed 标记。 */
    private val deletionKeys = mutableMapOf<String, Set<String>>()

    /** 草稿 id → 落库后的正式 id；旧路由上的页面经此命中同一个桶。 */
    private val aliases = mutableMapOf<String, String>()

    private fun keyFor(sessionId: String): String = aliases[sessionId] ?: sessionId

    private fun bucket(key: String) = buckets.getOrPut(key) { Bucket() }

    // ── 运行中 Job ──────────────────────────────────────────────────────────

    @Synchronized
    fun registerRuntime(sessionId: String, job: Job) {
        val key = keyFor(sessionId)
        if (key in closedKeys || sessionId in closedKeys) job.cancel()
        else bucket(key).job = job
    }

    /** 删除会话前停掉它的运行中 Job（由会话外的协程调用）。 */
    suspend fun stopAndJoin(sessionId: String) {
        val job = withContext(Dispatchers.Main.immediate) {
            val running = synchronized(this@ChatViewModelStore) {
                val key = keyFor(sessionId)
                val keys = deletionKeys.getOrPut(sessionId) {
                    aliases.filterValues { it == key }.keys + setOf(key, sessionId)
                }
                closedKeys += keys
                buckets[key]?.job
            }
            release(sessionId)
            running?.cancel()
            running
        }
        job?.join()
    }

    /** 删除成功则保留 closed 标记；失败则放回，让会话可重新起一个干净的 runtime。 */
    suspend fun finishDeletion(sessionId: String, deleted: Boolean) = withContext(Dispatchers.Main.immediate) {
        synchronized(this@ChatViewModelStore) {
            val keys = deletionKeys.remove(sessionId).orEmpty()
            for (key in keys) {
                buckets.remove(key)?.let { bucket ->
                    bucket.job?.cancel()
                    bucket.store?.clear()
                }
            }
            if (!deleted) closedKeys.removeAll(keys)
        }
    }

    // ── Store 生命周期 ──────────────────────────────────────────────────────

    @Synchronized
    fun ownerFor(sessionId: String): ViewModelStoreOwner {
        val key = keyFor(sessionId)
        val store = bucket(key).store ?: ViewModelStore().also {
            Log.d(TAG, "allocate store for $key (total=${buckets.size})")
            bucket(key).store = it
        }
        return object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore get() = store
        }
    }

    /** 丢弃会话的 VM（onCleared 取消 viewModelScope），并清掉指向它的草稿别名。 */
    @Synchronized
    fun release(sessionId: String) {
        val key = keyFor(sessionId)
        aliases.entries.removeAll { it.value == key }
        buckets.remove(key)?.store?.let {
            it.clear()
            Log.d(TAG, "release store for $key (remaining=${buckets.size})")
        }
    }

    /**
     * 草稿 [fromSessionId] 落库成 [toSessionId]：store 挪到正式 id 名下，
     * 旧键留别名，让还开着草稿路由的页面继续看到同一个运行中的 VM。
     */
    @Synchronized
    fun rename(fromSessionId: String, toSessionId: String) {
        if (fromSessionId == toSessionId) return
        val from = buckets.remove(fromSessionId)
        if (from != null) {
            val target = bucket(toSessionId)
            target.store = from.store
            from.job?.let { job ->
                if (fromSessionId in closedKeys || toSessionId in closedKeys) job.cancel()
                else target.job = job
            }
        }
        aliases[fromSessionId] = toSessionId
        Log.d(TAG, "rename store $fromSessionId -> $toSessionId (alias kept)")
    }

    // ── 当前前台会话 ────────────────────────────────────────────────────────

    /**
     * 当前显示在屏幕上的会话 id（ChatScreen 进入时置、退出时清）。
     * `minis-config session.*` 据此读写"当前会话"；null = 没有前台聊天。
     * 读取时过别名表，草稿 id 也能映射到已落库的行。
     */
    @Volatile
    private var activeSessionIdInternal: String? = null

    val activeSessionId: String?
        get() = activeSessionIdInternal?.let(::keyFor)

    @Synchronized
    fun setActiveSession(sessionId: String?) {
        activeSessionIdInternal = sessionId
    }

    // ── 「移动到…」一次性转交槽 ─────────────────────────────────────────────

    /**
     * 「移动到…」流程的一次性暂存：源会话写入，目标会话的 ChatScreen 用
     * [consumePendingTransfer] 领走。
     */
    data class PendingTransfer(
        val inputText: String,
        val attachments: List<InputAttachment>,
        /** 内容被移动到的目标会话；只有它能领取，防止落进无关会话。 */
        val targetId: String,
        /** 暂存时刻（墙钟）；超过 [STASH_TTL_MS] 视为遗弃。 */
        val stashedAtMs: Long = System.currentTimeMillis(),
    )

    /** 暂存超过这个时长仍未被领取 → 视为遗弃丢弃，避免日后开旧会话被伏击。 */
    private const val STASH_TTL_MS = 300_000L

    @Volatile
    private var pendingTransfer: PendingTransfer? = null

    fun stashPendingTransfer(transfer: PendingTransfer) {
        pendingTransfer = transfer
        Log.d(
            TAG,
            "stashPendingTransfer: target=${transfer.targetId} " +
                "text=${transfer.inputText.length}ch attachments=${transfer.attachments.size}",
        )
    }

    /**
     * 恰好领取一次，且只有目标会话能领。会话 id 不匹配的调用留着暂存
     * 等正主来取；过期的直接丢弃。
     */
    fun consumePendingTransfer(sessionId: String): PendingTransfer? {
        val stash = pendingTransfer ?: return null
        if (System.currentTimeMillis() - stash.stashedAtMs > STASH_TTL_MS) {
            pendingTransfer = null
            Log.d(TAG, "consumePendingTransfer: dropping stale stash (target=${stash.targetId})")
            return null
        }
        // 比较走草稿→正式别名表：MoveToSessionSheet 目前只列已落库会话，
        // 但按别名解析使"移入新会话"的目标将来也不会串号。
        if (keyFor(stash.targetId) != keyFor(sessionId)) {
            Log.d(
                TAG,
                "consumePendingTransfer: session=$sessionId is not target=${stash.targetId}, leaving stash",
            )
            return null
        }
        pendingTransfer = null
        Log.d(
            TAG,
            "consumePendingTransfer: target=${stash.targetId} " +
                "text=${stash.inputText.length}ch attachments=${stash.attachments.size}",
        )
        return stash
    }
}
