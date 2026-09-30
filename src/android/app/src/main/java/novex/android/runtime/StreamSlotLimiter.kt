package novex.android.runtime

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.ArrayDeque
import kotlin.coroutines.resume

/**
 * 会话并发闸门：同一时刻最多 [MAX_CONCURRENT] 个会话循环真正在跑，
 * 超出的挂起排队（先来先得），有槽位释放时按队头唤醒。
 *
 * 设计要点：
 *  - 计数按"租约"而非按会话 id——同一会话的重试可能叠着申两把槽，
 *    只记 id 会把第二把误当成已持有而拒绝回收。
 *  - 申槽（查容量 + 入队）与放槽（回收 + 唤醒队头）各自在同一个
 *    监视器临界区内完成，释放不会从中间插队把申请人晾在门外。
 *  - 取消竞态：挂起协程的取消可能赶在 resume 之后才到达，此时租约
 *    已经发出但调用方永远不会用——在取消回调里把这类"已发出却无人
 *    认领"的租约收回，避免五个全局槽被永久吃掉一个。
 */
object StreamSlotLimiter {

    /** 全局并发上限（旧路径门面以常量形式对外，值冻结）。 */
    const val MAX_CONCURRENT = 5

    /** 持有租约的会话集合（每个 id 至少一把租约在身）。 */
    private val _leaseHolders = MutableStateFlow<Set<String>>(emptySet())
    val leaseHolders: StateFlow<Set<String>> = _leaseHolders.asStateFlow()

    /** 排队等待中的会话，按入队先后排列。 */
    private val _queueOrder = MutableStateFlow<List<String>>(emptyList())
    val queueOrder: StateFlow<List<String>> = _queueOrder.asStateFlow()

    private class AwaitingSlot(
        val sessionId: String,
        val continuation: CancellableContinuation<Unit>,
    ) {
        var fulfilled = false
    }

    private val monitor = Any()
    private val queue = ArrayDeque<AwaitingSlot>()
    private val leases = mutableMapOf<String, Int>()

    /** 申请一个槽位；无空槽时挂起入队，直到别人释放或自身被取消。 */
    suspend fun awaitSlot(sessionId: String): Unit = suspendCancellableCoroutine { cont ->
        var grantedRightAway = false
        val applicant = AwaitingSlot(sessionId, cont)

        synchronized(monitor) {
            if (leases.values.sum() < MAX_CONCURRENT) {
                if (cont.isActive) {
                    grantLocked(sessionId)
                    grantedRightAway = true
                    cont.resume(Unit)
                }
                // 容量够但协程已死：不授租也不入队，等取消回调收尾。
            } else {
                queue.addLast(applicant)
                _queueOrder.value = _queueOrder.value + sessionId
            }
        }

        cont.invokeOnCancellation {
            synchronized(monitor) {
                val wasQueued = queue.remove(applicant)
                if (wasQueued) {
                    forgetQueuedLocked(sessionId)
                } else if (applicant.fulfilled || grantedRightAway) {
                    // 取消晚于 resume：租约已记在名下但永远无人使用，退回。
                    surrenderLocked(sessionId)
                }
            }
        }
    }

    /** 归还一个槽位；若有人在排队则顺势唤醒队头。 */
    fun releaseSlot(sessionId: String) {
        synchronized(monitor) { surrenderLocked(sessionId) }
    }

    fun isQueued(sessionId: String): Boolean = sessionId in _queueOrder.value

    // ── 监视器内私有例程（调用方必须已持有 [monitor]） ──────────────

    private fun grantLocked(sessionId: String) {
        leases[sessionId] = (leases[sessionId] ?: 0) + 1
        _leaseHolders.value = leases.keys.toSet()
    }

    private fun surrenderLocked(sessionId: String) {
        val held = leases[sessionId] ?: return
        if (held > 1) {
            // 同会话还叠着别的租约，只减计数不动集合。
            leases[sessionId] = held - 1
            return
        }
        leases.remove(sessionId)
        _leaseHolders.value = leases.keys.toSet()
        handSlotToNextWaiter()
    }

    private fun handSlotToNextWaiter() {
        while (true) {
            val next = queue.pollFirst() ?: return
            forgetQueuedLocked(next.sessionId)
            if (!next.continuation.isActive) continue // 已取消的申请人直接跳过
            next.fulfilled = true
            grantLocked(next.sessionId)
            next.continuation.resume(Unit)
            return
        }
    }

    private fun forgetQueuedLocked(sessionId: String) {
        _queueOrder.value = _queueOrder.value.toMutableList().also { it.remove(sessionId) }
    }
}
