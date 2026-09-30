package com.openminis.app.service

import kotlinx.coroutines.flow.StateFlow
import novex.android.runtime.StreamSlotLimiter

/**
 * 会话并发闸门门面（P3.5b 重写）。租约计数、FIFO 排队与取消竞态
 * 回收在 [novex.android.runtime.StreamSlotLimiter]；ChatViewModel 一族
 * 以 import 引用本对象，成员原名转发、调用点零改动。
 */
object SessionConcurrencyManager {

    const val MAX_CONCURRENT = StreamSlotLimiter.MAX_CONCURRENT

    val runningSessions: StateFlow<Set<String>> get() = StreamSlotLimiter.leaseHolders

    val suspendedSessions: StateFlow<List<String>> get() = StreamSlotLimiter.queueOrder

    suspend fun acquireSlot(sessionId: String) = StreamSlotLimiter.awaitSlot(sessionId)

    fun releaseSlot(sessionId: String) = StreamSlotLimiter.releaseSlot(sessionId)

    fun isSuspended(sessionId: String): Boolean = StreamSlotLimiter.isQueued(sessionId)
}
