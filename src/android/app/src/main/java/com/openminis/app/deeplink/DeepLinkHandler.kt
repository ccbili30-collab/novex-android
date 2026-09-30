package com.openminis.app.deeplink

import android.net.Uri
import com.openminis.app.ui.navigation.Routes

/**
 * Parses minis:// deep link URIs into navigation actions.
 *
 * Supported routes (matching iOS):
 *   minis://share                              → open share flow
 *   minis://session/<id>                        → open specific session
 *   minis://settings                            → Settings home
 *   minis://settings/providers                  → Provider list
 *   minis://settings/providers/<instanceId>     → Provider detail
 *   minis://settings/model-groups               → Model Groups (incl. Agent Loop section)
 *   minis://settings/model-groups/<groupId>     → Model Group detail
 *   minis://settings/usage                      → Token usage
 *   minis://settings/skills                     → Skills management
 *   minis://settings/memory                     → Memory management
 *   minis://settings/storage                    → Storage management
 *   minis://settings/shared-folders             → Shared Folders list (T235)
 *   minis://settings/shared_folders             → alias for shared-folders
 *   minis://settings/logs                       → Log management
 *   minis://settings/appearance                 → Appearance
 *   minis://settings/background                 → Background settings
 *   minis://settings/about                      → About
 *
 * Unknown settings paths fall back to Settings home rather than
 * Unknown — matches iOS's "best-effort land somewhere reasonable"
 * behavior so an LLM-generated link can never strand the user.
 *
 * Retired routes (open_terminal, settings/{mounts,mirrors,rootfs,
 * environments,permissions}) also land on Settings home — the sandbox
 * they fronted was removed in upstream-exit R2/R3, the permissions
 * screens in P3.3.
 *
 * [P3.3 裁军] views/alarm（闹钟列表）、action/voice_chat（语音快捷）、
 * session/<id>/<resource-path>（HTML 预览固定捷径）解析随对应功能退役
 * 删除，全部落入 Unknown / Settings home。
 *
 * Resource-class URIs (`minis://workspace/...`, `minis://skills/...`,
 * etc.) are intentionally NOT handled here — they resolve to on-disk
 * files and go through `ChatLinkResolver` at the chat-view layer. This
 * parser only handles *navigation* targets that change the app's top-
 * level screen.
 */
sealed class DeepLinkAction {
    data object OpenShare : DeepLinkAction()
    data class OpenSession(val sessionId: String) : DeepLinkAction()

    /**
     * App-icon long-press quick actions (mirrors iOS QuickActionRouter).
     *
     *  - [NewChat] — plain new draft chat
     *  - [NewCameraChat] — new chat + auto-launch camera attachment on first compose
     *
     * Encoded as `minis://action/<name>` so the static shortcuts XML can
     * point to them via plain Intent.data without any custom extras —
     * matches the existing minis:// deep-link conventions.
     * [P3.3 裁军] NewVoiceChat 随语音全家退役删除。
     */
    data object NewChat : DeepLinkAction()
    data object NewCameraChat : DeepLinkAction()

    /**
     * T183: any settings screen reachable by route string. Extends the
     * sealed family so consumers don't have to learn 14 new case types
     * — they `safeNavigate(action.route)` and the route table in
     * [com.openminis.app.ui.navigation.Routes] does the rest. Routes
     * with arguments (provider detail, model-group detail) carry the
     * already-formatted route ("provider/<id>", "model_group/<id>").
     */
    data class OpenSettingsScreen(val route: String) : DeepLinkAction()

    // [P3.3 裁军] OpenHtmlPreview（minis://session/<id>/<path> 固定捷径）
    // 随内置浏览器退役删除。

    data object Unknown : DeepLinkAction()
}

object DeepLinkHandler {
    fun parse(uri: Uri?): DeepLinkAction {
        if (uri == null || uri.scheme != "minis") return DeepLinkAction.Unknown
        val host = uri.host ?: return DeepLinkAction.Unknown
        val path = uri.path.orEmpty()

        return when (host) {
            "share" -> DeepLinkAction.OpenShare
            // Quick-actions surface (app-icon long-press). Path drives which
            // pending action ChatScreen consumes on first compose. Mirrors iOS
            // QuickActionRouter.swift action ids 1:1.
            // [P3.3 裁军] voice_chat 与 views/alarm 解析随语音/闹钟退役删除。
            "action" -> when (path.removePrefix("/")) {
                "new_chat" -> DeepLinkAction.NewChat
                "camera_chat" -> DeepLinkAction.NewCameraChat
                else -> DeepLinkAction.Unknown
            }
            "settings" -> parseSettingsPath(uri)
            "session" -> {
                // minis://session/<sessionId> → OpenSession；多余的路径段
                // （原 HTML 预览固定捷径）按 Unknown 处理。
                val segments = path.removePrefix("/")
                    .split('/')
                    .filter { it.isNotEmpty() }
                val sid = segments.firstOrNull()
                when {
                    sid.isNullOrBlank() -> DeepLinkAction.Unknown
                    segments.size == 1 -> DeepLinkAction.OpenSession(sid)
                    else -> DeepLinkAction.Unknown
                }
            }
            else -> DeepLinkAction.Unknown
        }
    }

    /**
     * T183: walk a `minis://settings/<path>` URI to the right NavHost
     * route. Everything funnels through [DeepLinkAction.OpenSettingsScreen]
     * carrying a route string from [Routes]. Unknown paths land on
     * Settings home rather than [DeepLinkAction.Unknown] so an LLM-
     * generated link can never strand the user; iOS does the same.
     * [P3.3 裁军] permissions 路径随权限屏退役，落入 Settings home。
     */
    private fun parseSettingsPath(uri: Uri): DeepLinkAction {
        val path = uri.path?.trimStart('/').orEmpty()
        if (path.isEmpty()) return DeepLinkAction.OpenSettingsScreen(Routes.SETTINGS)
        val segments = path.split('/').filter { it.isNotEmpty() }
        val head = segments.firstOrNull() ?: return DeepLinkAction.OpenSettingsScreen(Routes.SETTINGS)
        val arg = segments.getOrNull(1)?.takeIf { it.isNotBlank() }

        return when (head) {
            "providers" ->
                if (arg != null) DeepLinkAction.OpenSettingsScreen(Routes.providerDetail(arg))
                else DeepLinkAction.OpenSettingsScreen(Routes.PROVIDER_LIST)
            "model-groups", "model_groups" ->
                if (arg != null) DeepLinkAction.OpenSettingsScreen(Routes.modelGroupDetail(arg))
                else DeepLinkAction.OpenSettingsScreen(Routes.MODEL_GROUPS)
            "usage", "usage-stats", "usage_stats" ->
                DeepLinkAction.OpenSettingsScreen(Routes.USAGE_STATS)
            "skills" -> DeepLinkAction.OpenSettingsScreen(Routes.SKILLS)
            "memory" -> DeepLinkAction.OpenSettingsScreen(Routes.MEMORY)
            "storage" -> DeepLinkAction.OpenSettingsScreen(Routes.STORAGE)
            "shared-folders", "shared_folders" ->
                DeepLinkAction.OpenSettingsScreen(Routes.SHARED_FOLDERS)
            "logs" -> {
                // Optional ?tab=… selects the segmented control on the
                // Logs screen. Currently recognized: "logs" (default).
                // Pushed onto DeepLinkCoordinator so the screen can read
                // it on appear and clear. Mirrors iOS
                // DeepLinkRouter.handleSettings logs handling.
                // [P3.3 裁军] config-audit 页签随 minis-config 体系退役。
                DeepLinkCoordinator.setPendingLogsTab(uri.getQueryParameter("tab"))
                DeepLinkAction.OpenSettingsScreen(Routes.LOGS)
            }
            "appearance" -> DeepLinkAction.OpenSettingsScreen(Routes.APPEARANCE)
            "background" -> DeepLinkAction.OpenSettingsScreen(Routes.BACKGROUND)
            "about" -> DeepLinkAction.OpenSettingsScreen(Routes.ABOUT)
            // Unknown path — land on Settings home rather than failing,
            // so the user can find what they wanted by browsing.
            else -> DeepLinkAction.OpenSettingsScreen(Routes.SETTINGS)
        }
    }
}
