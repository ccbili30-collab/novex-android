package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.logging.AppLogger
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * 「已下载、未安装」APK 的跨重建/跨进程死亡持久（血统清剿 P3.7 就地真
 * 重写；prefs 名/键、JSON 字段集与日志行为契约冻结面）。
 *
 * 为什么要有它：原始更新流程把下载好的 [File] 引用放在 Composable 的
 * `remember{}` 槽里。用户一点「去设置」授予「安装未知应用」权限，系统把
 * 应用压后台；回来时 Activity 十有八九重建、槽位清零，UI 无声地请用户
 * **再下载一次** APK。
 *
 * 存储：SharedPreferences 单键存一小段 JSON。刻意不用 DataStore——本件
 * 每次更新流程至多碰几下，阻塞访问无所谓，SharedPreferences 别处已初始化。
 *
 * 新鲜度：超过 [MAX_AGE_MS]（24 小时）的 [PendingUpdate] 读时即弃——一周
 * 后冷启动自动装上陈旧 APK（GitHub release 已被重滚）是不允许的。
 *
 * 完整性：[setPending] 拿到文件字节就顺带算并存 sha256；没有字节时
 * [verify] 回落到（尺寸 == 记录尺寸）。
 */
object PendingUpdateStore {

    private const val TAG = "PendingUpdateStore"
    private const val PREFS = "pending_update"
    private const val KEY = "pending"
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    data class PendingUpdate(
        val targetVersionName: String,
        val apkPath: String,
        val apkSize: Long,
        val sha256: String?,
        val downloadedAtMs: Long,
        val channel: String = com.openminis.app.BuildConfig.UPDATE_CHANNEL,
    )

    private var store: SharedPreferences? = null

    /** 幂等；MinisApp.onCreate 调用安全。 */
    fun init(context: Context) {
        if (store != null) return
        store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun requirePrefs(context: Context): SharedPreferences {
        store?.let { return it }
        init(context)
        return store!!
    }

    fun setPending(context: Context, pending: PendingUpdate) {
        val blob = JSONObject().apply {
            put("targetVersionName", pending.targetVersionName)
            put("apkPath", pending.apkPath)
            put("apkSize", pending.apkSize)
            put("sha256", pending.sha256 ?: JSONObject.NULL)
            put("downloadedAtMs", pending.downloadedAtMs)
            put("channel", pending.channel)
        }
        requirePrefs(context).edit().putString(KEY, blob.toString()).apply()
        AppLogger.info(
            TAG,
            "setPending version=${pending.targetVersionName} size=${pending.apkSize} sha256=${pending.sha256 != null}",
        )
    }

    /**
     * 取回挂起的更新；以下情形返回 null：
     *  - 什么都没存；
     *  - JSON 畸形（当作没有，顺手清除）；
     *  - 超过 [MAX_AGE_MS]（清除）；
     *  - 渠道与当前构建不一致（清除）。
     */
    fun getPending(context: Context): PendingUpdate? {
        val prefs = requirePrefs(context)
        val raw = prefs.getString(KEY, null) ?: return null
        val blob = runCatching { JSONObject(raw) }.getOrNull()
        if (blob == null) {
            AppLogger.warning(TAG, "stored JSON malformed, discarding")
            prefs.edit().remove(KEY).apply()
            return null
        }
        val pending = PendingUpdate(
            targetVersionName = blob.optString("targetVersionName"),
            apkPath = blob.optString("apkPath"),
            apkSize = blob.optLong("apkSize"),
            sha256 = blob.optString("sha256", "").takeIf { it.isNotEmpty() && it != "null" },
            downloadedAtMs = blob.optLong("downloadedAtMs"),
            channel = blob.optString("channel", ""),
        )
        if (pending.channel != com.openminis.app.BuildConfig.UPDATE_CHANNEL) {
            clearPending(context)
            return null
        }
        val ageMs = System.currentTimeMillis() - pending.downloadedAtMs
        if (ageMs > MAX_AGE_MS) {
            AppLogger.info(TAG, "pending update expired age=${ageMs}ms; clearing")
            clearPending(context)
            return null
        }
        return pending
    }

    fun clearPending(context: Context) {
        requirePrefs(context).edit().remove(KEY).apply()
        AppLogger.info(TAG, "clearPending")
    }

    /**
     * 恢复时开火安装意图前的严格完整性核查：
     *  - 文件在；
     *  - 长度与记录尺寸一致；
     *  - 记了 sha256 的，重算须一致。
     *
     * 有效返回 File；损坏/缺失返回 null（调用方应清记录重下）。
     */
    fun verify(pending: PendingUpdate): File? {
        val apk = File(pending.apkPath)
        if (!apk.exists()) {
            AppLogger.warning(TAG, "verify: file missing ${pending.apkPath}")
            return null
        }
        if (apk.length() != pending.apkSize) {
            AppLogger.warning(TAG, "verify: size mismatch expected=${pending.apkSize} actual=${apk.length()}")
            return null
        }
        val expected = pending.sha256 ?: return apk
        val actual = runCatching { sha256(apk) }.getOrNull()
        if (actual == null || !actual.equals(expected, ignoreCase = true)) {
            AppLogger.warning(TAG, "verify: sha256 mismatch expected=$expected actual=$actual")
            return null
        }
        return apk
    }

    /** 流式分块喂摘要的 SHA-256 十六进制输出。 */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val got = input.read(chunk)
                if (got <= 0) break
                digest.update(chunk, 0, got)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
