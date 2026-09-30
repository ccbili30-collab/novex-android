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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.openminis.app.startup.NovexStartupMetrics
import com.openminis.app.ui.sessions.NovexRootScreen
import com.openminis.app.ui.sessions.SessionListScreen
import com.openminis.app.ui.navigation.NovexRouteEntryEdge
import com.openminis.app.ui.navigation.Routes
import com.openminis.app.ui.navigation.novexRouteEntryEdge
import com.openminis.app.ui.theme.NovexAppTheme
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
            // home-v2：与 AppNavigation 一样用 configLoaded 做门控，避免读到
            // 未加载完的配置快照。本路径 provider 运行时尚未初始化
            // （providerRepositoryOrNull == null），永远落首页；用户主动切到
            // 会话 tab 看到的是统一 SessionListScreen 的普通空态或列表，
            // provider 相关入口在那里被降级隐藏。
            val repo = app.providerRepositoryOrNull
            val configLoaded = repo?.configLoaded?.collectAsState()
            // 冷启动后的维护（公告拉取/更新检测/AppLogger/卡片目录迁移）必须在
            // 首页挂载即触发，不能等用户点进某个 tab —— 两者皆幂等：
            // reportHomeInteractive 有 AtomicBoolean，startPostHomeMaintenance
            // 内有 postHomeReady 锁。
            LaunchedEffect(Unit) {
                NovexStartupMetrics.reportHomeInteractive()
                app.startPostHomeMaintenance()
            }
            Box(Modifier.fillMaxSize()) {
            NovexRootScreen(
                initialTab = com.openminis.app.ui.noven.NovenTab.HOME,
                initialTabReady = configLoaded?.value ?: true,
                chatRepository = app.chatRepository,
                onImportCard={uri,world->openLegacy("integrated-import?uri=${Uri.encode(uri.toString())}&world=$world")},
                conversationContent = { _, onRootNavigationVisibilityChange ->
                    // 统一会话列表：与主路径（AppNavigation SESSION_LIST）同一
                    // 个 SessionListScreen 实现。providerRepository 传
                    // providerRepositoryOrNull —— 此刻运行时尚未初始化，
                    // 绝不能为了取它而触发 ensureRuntime；屏幕内部对 null 做
                    // 降级（隐藏 onboarding/AI 功能，保留全部 Room 功能）。
                    SessionListScreen(
                        chatRepository = app.chatRepository,
                        providerRepository = app.providerRepositoryOrNull,
                        onSessionClick = { id ->
                            openLegacy("chat/${Uri.encode(id)}")
                        },
                        // SessionListViewModel.createNewSession() 生成
                        // "__new__uuid" 草稿 id（含可选 __grp__/__fld__ 后缀），
                        // 与旧 NovexConversationRoot 的 __new__ 路由一致。
                        onNewChat = { id ->
                            openLegacy("chat/${Uri.encode(id)}")
                        },
                        onAddProviderClick = { openLegacy(Routes.ADD_PROVIDER) },
                        onSelectModelsClick = { openLegacy(Routes.ONBOARDING_MODELS) },
                        onScheduledTasksClick = { openLegacy(Routes.SCHEDULED_TASKS) },
                        onTerminalClick = { openLegacy(Routes.terminal()) },
                        onRootfsClick = { openLegacy(Routes.ROOTFS_MANAGEMENT) },
                        onContentLoaded = {
                            NovexStartupMetrics.reportStage("home_content_ready")
                        },
                        onRootNavigationVisibilityChange = onRootNavigationVisibilityChange,
                    )
                },
                onOpenCard = { root, target ->
                    openLegacy("integrated-card?root=${Uri.encode(root)}&target=${Uri.encode(target)}")
                },
                onChat = { id -> openLegacy("chat/${Uri.encode(id)}") },
                onCreateWorld = { openLegacy("characters/world/edit") },
                onCreateCharacter = { openLegacy("characters/catalog/edit?createVariant=false") },
                onResumeImportDraft = { id ->
                    openLegacy("integrated-import-resume?draft=${Uri.encode(id)}")
                },
                onOpenSettings = { openLegacy("settings") },
                onOpenCreativeLibrary = { openLegacy("creative_library") },
            )
            // 「正在准备对话能力…」遮罩提到根级：runtime 准备期覆盖整个表面，
            // 任意 tab 的点击都被这层吃掉并给出可见反馈（此前只在会话 tab
            // 内绘制，其他 tab 点了 openLegacy 直接 return，无任何提示）。
            if (preparingRuntime) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) {}
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(androidx.compose.ui.graphics.Color.White)
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                    ) {
                        CircularProgressIndicator(
                            color = novex.android.ui.NovexColors.Primary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp),
                        )
                        Text("正在准备对话能力…", modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            }
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
