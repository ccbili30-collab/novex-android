package novex.android.runtime

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Android 16「Live Updates」（状态栏常驻胶囊，俗称灵动岛）能力探针。
 *
 * 设备"具备灵动岛"要同时满足两件事：系统版本到 36（ProgressStyle 与
 * canPostPromotedNotifications 这些 API 才存在），且用户在系统设置里
 * 没有对本应用关闭 Live Updates——后者随时可变，所以本探针刻意不做
 * 缓存，每次现查。查询本身是一次廉价的同步调用。
 *
 * 两个判定共同决定灵动岛是否为"当前生效的状态面板"：能力具备 + 用户
 * 开了开关。生效时悬浮胶囊必须让位（互斥），前台通知改走可提升的
 * ProgressStyle 形态。
 */
object LiveUpdatesProbe {

    private const val TAG = "LiveUpdatesProbe"

    /** 设备此刻能否发布可提升（灵动岛形态）的通知。低版本直接短路为 false。 */
    fun capable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        return try {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.canPostPromotedNotifications() == true
        } catch (t: Throwable) {
            // 个别半成品的 Baklava 构建可能在这个调用上抛异常；一律按
            // "不具备"处理，退回悬浮胶囊 + 普通通知的老路。
            Log.w(TAG, "canPostPromotedNotifications() threw: ${t.message}")
            false
        }
    }

    /** 灵动岛是否应当作为当前状态面板：能力具备且用户开关为开。 */
    fun engaged(context: Context, userToggleOn: Boolean): Boolean =
        userToggleOn && capable(context)
}
