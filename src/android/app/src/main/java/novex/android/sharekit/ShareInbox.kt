package novex.android.sharekit

import android.content.Context
import com.openminis.app.share.PendingShare
import novex.android.sharekit.ShareWire.toJson
import novex.android.logkit.RunLog
import org.json.JSONObject
import java.io.File

/**
 * 分享内容的盘上收件箱（P3.5c 自 share/SharedShareStore 真重写）。
 *
 * 生产者（分享接收 Activity，另一个进程内的短命实例）把 [PendingShare]
 * 写进 prefs，消费者（主界面内的聊天面）之后取走。二进制附件先落到
 * `filesDir/share_extension/` 暂存目录——生产者一 finish，ContentResolver
 * 流就没了，先拷成文件消费者才读得到。
 *
 * 键与合并窗（冻结面）：prefs 文件 `share_prefs`、键 `pending_share`、
 * 暂存目录名 `share_extension`；300s 内的新分享并入未消费的旧记录而非
 * 整体替换（此前整替会把“连发两张截图只到一张”变成必然），合并上限
 * 50 条且保最新——用户正在操作的是最新那份。附件文件名带 UUID 后缀，
 * 跨次追加在盘上无碰撞。
 */
object ShareInbox {
    private const val CATEGORY = "ShareInbox"
    private const val PREFS_NAME = "share_prefs"
    private const val KEY_PENDING = "pending_share"
    private const val STAGING_DIR = "share_extension"

    /** 新分享并入未消费记录的窗口（毫秒），与 iOS a51e7255 对齐。 */
    private const val MERGE_WINDOW_MS = 300_000L

    /** 合并条目上限：用户或捣乱的发端在窗口内反复分享，清单不能无界长。 */
    private const val MERGE_MAX_ITEMS = 50

    fun sharedFileDirectory(context: Context): File =
        File(context.filesDir, STAGING_DIR).apply { mkdirs() }

    private fun prefsOf(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 持久化一份分享：窗口内有未消费记录则按 (kind, value) 去重后合并
     * （双投递的 intent 不会把同一附件注两遍），超限保最新；窗口外的
     * 旧记录视作被遗弃，整体替换。合并后时间戳续满一新窗。
     */
    fun savePendingShare(context: Context, share: PendingShare) {
        val now = System.currentTimeMillis()
        val stored = mergeIfRecent(loadPendingShare(context), share, now) ?: share
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PENDING, stored.toJson().toString()).apply()
        RunLog.info(CATEGORY, "inbox now holds ${stored.items.size} share item(s)")
    }

    /** 窗口内的旧记录与新增合并（按 (kind,value) 去重、超限保最新、时间戳续满新窗）；
     *  窗口外（=旧记录被遗弃）返回 null 由调用方整体替换。 */
    private fun mergeIfRecent(
        existing: PendingShare?,
        incoming: PendingShare,
        now: Long,
    ): PendingShare? {
        if (existing == null || now - existing.timestampMs > MERGE_WINDOW_MS) return null
        val deduped = (existing.items + incoming.items).associateBy { it.kind to it.value }
        val all = deduped.values.toList()
        val capped = all.takeLast(MERGE_MAX_ITEMS)
        RunLog.info(
            CATEGORY,
            "share merge: ${existing.items.size}+${incoming.items.size} -> ${capped.size} item(s)" +
                if (capped.size < all.size) " (kept newest ${all.size})" else "",
        )
        return PendingShare(capped, now)
    }

    fun loadPendingShare(context: Context): PendingShare? {
        val raw = prefsOf(context).getString(KEY_PENDING, null) ?: return null
        return runCatching { ShareWire.pendingShareFromJson(JSONObject(raw)) }
            .onFailure { RunLog.warning(CATEGORY, "pending share undecodable: ${it.message}") }
            .getOrNull()
    }

    fun clearPendingShare(context: Context) {
        prefsOf(context).edit().remove(KEY_PENDING).apply()
    }

    /**
     * 清暂存附件：消费完或记录过期时调。[keep] 列出必须幸存的文件名——
     * 内存缓冲 TTL 提到 300s 后，存在“缓冲 A 还攥着文件引用、新到的
     * 陈旧 prefs 记录走了丢弃路径”的窗口，全目录无差别清会把 A 的附件
     * 从脚下抽走，留下坏附件；知道内存里有活缓冲的调用方据此排除。
     */
    fun cleanSharedFiles(context: Context, keep: Set<String> = emptySet()) {
        sharedFileDirectory(context).listFiles().orEmpty()
            .filterNot { it.name in keep }
            .forEach { file -> runCatching { file.delete() } }
    }
}
