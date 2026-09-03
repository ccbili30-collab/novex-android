package com.openminis.app

import android.app.AlertDialog
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.openminis.app.startup.NovexStartupMetrics
import com.openminis.app.ui.sessions.NovexConversationRoot
import com.openminis.app.ui.sessions.NovexRootScreen
import com.openminis.app.ui.navigation.NovexRouteEntryEdge
import com.openminis.app.ui.navigation.novexRouteEntryEdge
import com.openminis.app.ui.theme.NovexAppTheme
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal const val EXTRA_NOVEX_START_ROUTE = "novex.start_route"

/** Installs the Compose home into an already displayed lightweight Activity. */
internal fun ComponentActivity.installNovexHomeSurface(app: MinisApp) {
    NovexStartupMetrics.reportStage("home_surface_install")
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
    )
    setContent {
        val scope = rememberCoroutineScope()
        var preparingRuntime by remember { mutableStateOf(false) }
        var runtimeRequest by remember { mutableStateOf<Job?>(null) }
        val activity = this@installNovexHomeSurface

        fun openLegacy(route: String) {
            if (runtimeRequest?.isActive == true) return
            preparingRuntime = true
            runtimeRequest = scope.launch {
                val result = app.startupCoordinator.ensureRuntime()
                preparingRuntime = false
                result.fold(
                    onSuccess = {
                        val intent = Intent().setClassName(activity, "com.openminis.app.MainActivity")
                            .putExtra(EXTRA_NOVEX_START_ROUTE, route)
                        if (novexRouteEntryEdge(route) == NovexRouteEntryEdge.LEFT) {
                            activity.startActivity(
                                intent,
                                ActivityOptions.makeCustomAnimation(
                                    activity,
                                    R.anim.novex_enter_from_left,
                                    R.anim.novex_exit_to_right,
                                ).toBundle(),
                            )
                        } else {
                            activity.startActivity(intent)
                        }
                    },
                    onFailure = activity::showNovexRuntimeFailure,
                )
            }
        }

        NovexAppTheme { appearance ->
            SideEffect {
                val style = if (appearance.darkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            NovexRootScreen(
                conversationContent = { onWorldsClick, onRootNavigationVisibilityChange ->
                    NovexConversationRoot(
                        chatRepository = app.chatRepository,
                        workspace = app.novexWorkspace,
                        preparingRuntime = preparingRuntime,
                        onOpenSession = { id -> openLegacy("chat/${Uri.encode(id)}") },
                        onNewConversation = {
                            openLegacy("chat/__new__${UUID.randomUUID()}")
                        },
                        onOpenSettings = { openLegacy("settings") },
                        onOpenWorlds = onWorldsClick,
                        onStartCreationTool = {
                            com.openminis.app.deeplink.DeepLinkCoordinator.setPendingChatAction(
                                com.openminis.app.deeplink.DeepLinkCoordinator.ChatAction.OPEN_CREATION_TOOL,
                            )
                            openLegacy("chat/__new__${UUID.randomUUID()}")
                        },
                        onInteractive = {
                            NovexStartupMetrics.reportHomeInteractive()
                            app.startPostHomeMaintenance()
                        },
                        onContentLoaded = {
                            NovexStartupMetrics.reportStage("home_content_ready")
                        },
                        onRootNavigationVisibilityChange = onRootNavigationVisibilityChange,
                    )
                },
                onOpenWorld = { id -> openLegacy("characters/world/${Uri.encode(id)}") },
                onCreateWorld = { openLegacy("characters/world/edit") },
                onOpenCharacter = { id -> openLegacy("characters/card/${Uri.encode(id)}") },
                onCreateCharacter = { openLegacy("characters/catalog/edit?createVariant=false") },
                onOpenSettings = { openLegacy("settings") },
            )
        }
    }
    NovexStartupMetrics.reportStage("home_content_set")
}

private fun ComponentActivity.showNovexRuntimeFailure(error: Throwable) {
    AlertDialog.Builder(this)
        .setTitle("对话能力准备失败")
        .setMessage(error.message ?: "模型或工具运行时无法初始化，世界与角色资料仍可继续浏览。")
        .setPositiveButton("关闭后重试") { _, _ -> finishAffinity() }
        .setNegativeButton("留在首页", null)
        .show()
}
