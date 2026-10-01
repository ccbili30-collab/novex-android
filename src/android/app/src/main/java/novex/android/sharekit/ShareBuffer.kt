package novex.android.sharekit

import android.content.Context
import com.openminis.app.share.PendingShare
import novex.android.logkit.RunLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 分享内容的内存缓冲（P3.5c 自 share/ShareCoordinator 真重写）。
 *
 * 桥接两个活在 Activity 边界两端的角色：接收 Activity（生产者，重拉
 * 主界面前自己先销毁）与聊天面（消费者，住在主界面里）。载荷在内存里
 * 顶多活一个 [BUFFER_TTL_MS]——够启动流程落进某个会话，也不至于几周
 * 后冷不丁吓用户一跳。
 *
 * 时序常量（冻结面）：缓冲 TTL 300s；启动侧陈旧判定 5 分钟（对齐 iOS）；
 * 合并去重按 (kind, value)，时间戳取新到的那份；丢弃陈旧记录时保住
 * 活缓冲引用的附件；过期缓冲被消费时报 toast 而不是无声蒸发。
 */
object ShareBuffer {
    private const val CATEGORY = "ShareBuffer"
    private const val BUFFER_TTL_MS = 300_000L
    private const val LAUNCH_STALENESS_MS = 5L * 60 * 1000

    private data class Held(val share: PendingShare, val bufferedAtMs: Long)

    @Volatile private var held: Held? = null

    private val _bufferVersion = MutableStateFlow(0)

    /** 每缓冲一份新载荷就 +1：ChatScreen 靠 collectAsState 让温启动（用户
     *  已在某会话里）也能注入；冷启动首帧时版本已非零，同样取得到。 */
    val bufferVersion: StateFlow<Int> = _bufferVersion.asStateFlow()

    /**
     * 主界面 onCreate / onNewIntent 调：收件箱有货就搬进内存缓冲并清
     * prefs。超过 [LAUNCH_STALENESS_MS] 的直接弃（顺带清收件箱，但保住
     * 活缓冲引用的附件文件）；5 分钟内的并入未消费的缓冲而非顶掉
     * （“连发两张截图只到第二张”的修法）。
     */
    fun processPendingShare(context: Context) {
        val incoming = ShareInbox.loadPendingShare(context)
        if (incoming == null) { RunLog.info(CATEGORY, "no pending share in inbox"); return }

        ShareInbox.clearPendingShare(context)
        if (System.currentTimeMillis() - incoming.timestampMs > LAUNCH_STALENESS_MS) {
            // 丢弃的是“这份”陈旧记录，不能顺带删掉更早那份还等着注入的
            // 分享的附件——300s 缓冲窗下这个重叠完全可能发生。
            RunLog.info(CATEGORY, "launch record too old — dropped")
            ShareInbox.cleanSharedFiles(context, keep = liveBufferFileNames())
            return
        }

        val now = System.currentTimeMillis()
        val buffered = held?.takeIf { now - it.bufferedAtMs <= BUFFER_TTL_MS }
        val merged = if (buffered == null) {
            RunLog.info(CATEGORY, "holding ${incoming.items.size} share item(s)")
            incoming
        } else {
            val deduped = (buffered.share.items + incoming.items).associateBy { it.kind to it.value }
            RunLog.info(
                CATEGORY,
                "fold ${buffered.share.items.size}+${incoming.items.size} -> ${deduped.size} item(s) into buffer",
            )
            PendingShare(deduped.values.toList(), incoming.timestampMs)
        }
        // 合并后续期：合并体拿满一个新的 TTL。
        held = Held(merged, now)
        _bufferVersion.value += 1
    }

    /** 内存活缓冲引用着的附件文件名；无缓冲时为空。 */
    private fun liveBufferFileNames(): Set<String> =
        held?.share?.items.orEmpty()
            .filter { it.kind == PendingShare.Item.Kind.ATTACHMENT }
            .map(PendingShare.Item::value).toSet()

    /**
     * 一次性取走缓冲；空或已过期返回 null。过期不仅清附件，还给用户
     * 弹个 toast——之前只留一行日志，用户分享完打开应用却什么都没
     * 发生，无从得知。
     */
    fun consumeBuffer(context: Context): PendingShare? {
        val buffered = held ?: return null
        held = null
        val ageMs = System.currentTimeMillis() - buffered.bufferedAtMs
        if (ageMs > BUFFER_TTL_MS) {
            RunLog.info(CATEGORY, "buffer expired after ${ageMs}ms")
            ShareInbox.cleanSharedFiles(context)
            announceExpired(context)
            return null
        }
        RunLog.info(CATEGORY, "draining ${buffered.share.items.size} buffered item(s)")
        return buffered.share
    }

    /** 到主线程弹过期提示（消费点可能在组合/IO 上下文，Toast 要 Looper）。 */
    private fun announceExpired(context: Context) = runCatching {
        android.os.Handler(android.os.Looper.getMainLooper()).post { showExpiredToast(context) }
    }

    private fun showExpiredToast(context: Context) = runCatching {
        android.widget.Toast.makeText(
            context.applicationContext, context.getString(com.openminis.app.R.string.share_expired_toast),
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }
}
