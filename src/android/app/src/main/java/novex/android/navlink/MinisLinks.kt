package novex.android.navlink

import android.net.Uri
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.ui.navigation.Routes

/**
 * minis:// 深链解析器（P3.5c 自 deeplink/DeepLinkHandler 真重写）。
 *
 * 只管“换顶层屏幕”的导航目标；资源型 URI（workspace/ skills/ …）指向
 * 盘上文件，走聊天层的 ChatLinkResolver，不在此列。
 *
 * URI 表（冻结面）：
 *   minis://share                              打开分享流
 *   minis://session/<id>                       打开指定会话（多段路径不收）
 *   minis://action/new_chat                    图标长按快捷：新草稿
 *   minis://action/camera_chat                 新草稿 + 首次合成自动开相机
 *   minis://settings[/<path>[/<arg>]]          设置子页
 *
 * settings 路径别名：model-groups|model_groups、usage|usage-stats|
 * usage_stats、shared-folders|shared_folders。logs 认 ?tab= 参数（压给
 * [DeepLinkCoordinator] 的挂起页签）。未知 settings 路径回落设置首页而
 * 不是 Unknown——LLM 生成的链接不能把用户晾在死胡同；已退役路由
 * （open_terminal、mounts/mirrors/rootfs/environments/permissions、
 * views/alarm、session/<id>/<path>、voice_chat）同样落设置首页/Unknown。
 */
object LinkParser {

    fun parse(uri: Uri?): DeepLinkAction {
        if (uri == null || uri.scheme != "minis") return DeepLinkAction.Unknown
        val host = uri.host ?: return DeepLinkAction.Unknown
        return when (host) {
            "share" -> DeepLinkAction.OpenShare
            "action" -> parseQuickAction(uri.path.orEmpty())
            "settings" -> parseSettings(uri)
            "session" -> parseSession(uri.path.orEmpty())
            else -> DeepLinkAction.Unknown
        }
    }

    private fun parseQuickAction(path: String): DeepLinkAction = when (path.removePrefix("/")) {
        "new_chat" -> DeepLinkAction.NewChat
        "camera_chat" -> DeepLinkAction.NewCameraChat
        else -> DeepLinkAction.Unknown
    }

    /** 单段 <sessionId> 才有效；多段（原 HTML 预览捷径）按 Unknown。 */
    private fun parseSession(path: String): DeepLinkAction {
        val segments = path.removePrefix("/").split('/').filter { it.isNotEmpty() }
        val sessionId = segments.firstOrNull() ?: return DeepLinkAction.Unknown
        if (sessionId.isBlank() || segments.size != 1) return DeepLinkAction.Unknown
        return DeepLinkAction.OpenSession(sessionId)
    }

    /** 无参 settings 子页：别名 → 路由（别名全是历史拼写与 iOS 对齐项）。 */
    private val flatSettings = mapOf(
        "usage" to Routes.USAGE_STATS, "usage-stats" to Routes.USAGE_STATS, "usage_stats" to Routes.USAGE_STATS,
        "skills" to Routes.SKILLS, "memory" to Routes.MEMORY, "storage" to Routes.STORAGE,
        "shared-folders" to Routes.SHARED_FOLDERS, "shared_folders" to Routes.SHARED_FOLDERS,
        "logs" to Routes.LOGS, "appearance" to Routes.APPEARANCE,
        "background" to Routes.BACKGROUND, "about" to Routes.ABOUT,
    )

    /** 带参 settings 子页：别名 → (列表路由, 详情路由工厂)。 */
    private val argSettings = mapOf(
        "providers" to (Routes.PROVIDER_LIST to Routes::providerDetail),
        "model-groups" to (Routes.MODEL_GROUPS to Routes::modelGroupDetail),
        "model_groups" to (Routes.MODEL_GROUPS to Routes::modelGroupDetail),
    )

    /**
     * settings 子路径 → NavHost 路由。空路径/未知头段都落设置首页
     * （iOS 同款“尽力落到合理位置”）。logs 是唯一带副作用的路径：
     * 可选 ?tab= 压给协调器，页面到达时读走并清空。
     */
    private fun parseSettings(uri: Uri): DeepLinkAction {
        val segments = uri.path.orEmpty().trimStart('/').split('/').filter { it.isNotEmpty() }
        val head = segments.firstOrNull() ?: return DeepLinkAction.OpenSettingsScreen(Routes.SETTINGS)
        val arg = segments.getOrNull(1)?.takeIf { it.isNotBlank() }

        argSettings[head]?.let { (listRoute, detailRoute) ->
            return screen(if (arg == null) listRoute else detailRoute(arg))
        }
        flatSettings[head]?.let { route ->
            if (head == "logs") DeepLinkCoordinator.setPendingLogsTab(uri.getQueryParameter("tab"))
            return screen(route)
        }
        return screen(Routes.SETTINGS)
    }

    private fun screen(route: String) = DeepLinkAction.OpenSettingsScreen(route)
}
