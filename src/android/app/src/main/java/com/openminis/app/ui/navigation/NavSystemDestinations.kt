package com.openminis.app.ui.navigation

import kotlinx.coroutines.launch

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkCoordinator
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import novex.core.NovexContentAddress
import com.openminis.app.ui.chat.ChatScreen
import com.openminis.app.ui.chat.ConversationSettingsScreen
import com.openminis.app.ui.creative.CreativeLibraryScreen
import com.openminis.app.ui.sessions.NovexRootScreen
import com.openminis.app.ui.sessions.SessionListScreen
import com.openminis.app.ui.settings.AboutScreen
import com.openminis.app.ui.settings.AddAgentLoopGroupsScreen
import com.openminis.app.ui.settings.AddAgentLoopModelsScreen
import com.openminis.app.ui.settings.AddCustomModelScreen
import com.openminis.app.ui.settings.BackgroundSettingsScreen
import com.openminis.app.ui.settings.AddModelsToGroupScreen
import com.openminis.app.ui.settings.NovexProviderSetupScreen
import com.openminis.app.ui.settings.ImageGenerationSettingsScreen
import com.openminis.app.ui.settings.ImageGenerationSourceScreen
import com.openminis.app.ui.settings.ModelEntryDetailScreen
import com.openminis.app.ui.settings.ModelGroupDetailScreen
import com.openminis.app.ui.settings.ModelGroupsScreen
import com.openminis.app.ui.settings.ProviderDetailScreen
import com.openminis.app.ui.settings.ProviderListScreen
import com.openminis.app.ui.sandbox.FileBrowserScreen
import com.openminis.app.ui.sandbox.FileBrowserViewModel
import com.openminis.app.ui.sandbox.FileItem
import com.openminis.app.ui.sandbox.FilePreviewScreen
import com.openminis.app.ui.settings.AppearanceScreen
import com.openminis.app.ui.settings.ThemeColorScreen
import com.openminis.app.ui.settings.SettingsScreen
import com.openminis.app.ui.settings.SessionStorageDetailScreen
import com.openminis.app.ui.settings.SkillDetailScreen
import com.openminis.app.ui.settings.StorageManagementScreen
import com.openminis.app.ui.settings.SkillFileViewerScreen
import com.openminis.app.ui.settings.UsageStatsScreen
import com.openminis.app.ui.settings.SharedFolderDetailScreen
import com.openminis.app.ui.settings.SharedFoldersScreen
import com.openminis.app.ui.settings.SkillsManagementScreen
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.ui.settings.LogDetailScreen
import com.openminis.app.ui.settings.LogManagementScreen
import com.openminis.app.ui.settings.MemoryFileEditScreen
import com.openminis.app.ui.settings.MemoryManagementScreen
import com.openminis.app.ui.settings.NovexFeedbackScreen
import com.openminis.app.ui.onboarding.OnboardingModelSelectionScreen

import androidx.navigation.NavGraphBuilder

internal fun NavGraphBuilder.registerSystemDestinations(deps: NavDeps) {
    val navController = deps.navController
    val chatRepository = deps.chatRepository
    val providerRepository = deps.providerRepository
    val skillRepository = deps.skillRepository
    val memoryRepository = deps.memoryRepository
        composable(Routes.NOVEX_FEEDBACK) {
            NovexFeedbackScreen(onBack = { navController.safePopBackStack() })
        }

        composable(Routes.SHARED_FOLDERS) {
            SharedFoldersScreen(
                onBack = { navController.safePopBackStack() },
                onFolderClick = { folderId ->
                    navController.safeNavigate(Routes.sharedFoldersDetail(folderId))
                },
            )
        }

        composable(
            route = Routes.SHARED_FOLDERS_DETAIL,
            arguments = listOf(navArgument("folderId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val folderId = backStackEntry.arguments?.getString("folderId") ?: return@composable
            val ctx = androidx.compose.ui.platform.LocalContext.current
            SharedFolderDetailScreen(
                folderId = folderId,
                onBack = { navController.safePopBackStack() },
                onBrowseFiles = {
                    val hostPath = novex.android.ContentPaths.resolveHostPath("/var/minis/$folderId")
                        ?: java.io.File(ctx.applicationContext.filesDir, "minis-global/$folderId")
                    val label = when (folderId) {
                        "shared" -> ctx.getString(com.openminis.app.R.string.shared_folder_name_shared)
                        "skills" -> ctx.getString(com.openminis.app.R.string.shared_folder_name_skills)
                        "memory" -> ctx.getString(com.openminis.app.R.string.shared_folder_name_memory)
                        else -> folderId
                    }
                    FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                        rootPath = hostPath,
                        rootLabel = label,
                        // Route reads through the content bind mounts so the
                        // host dirs that back /var/minis/{shared,skills,memory}
                        // resolve, matching how chat-files browse works.
                        linuxRootPath = "/var/minis/$folderId",
                        appContext = ctx.applicationContext,
                    )
                    navController.safeNavigate(Routes.FILE_BROWSER)
                },
            )
        }

        composable("settings/component-gallery") {
            novex.android.ui.NovexComponentGallery { navController.safePopBackStack() }
        }
        composable(Routes.STORAGE) {
            StorageManagementScreen(
                chatDao = chatRepository.dao,
                onBack = { navController.safePopBackStack() },
                onSessionClick = { sessionId ->
                    navController.safeNavigate(Routes.sessionStorageDetail(sessionId))
                },
            )
        }

        composable(
            route = Routes.SESSION_STORAGE_DETAIL,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            SessionStorageDetailScreen(
                sessionId = sessionId,
                chatDao = chatRepository.dao,
                onBack = { navController.safePopBackStack() },
                onBrowseFiles = { rootPath ->
                    // [T-android-copy-abs-path-fullpath] This browser is rooted at
                    // the per-session host dir (filesDir/minis-sessions/<sid>),
                    // whose immediate children (workspace/ attachments/ offloads/
                    // browser/) are exactly the PRoot /var/minis/* subdirs. The
                    // host listing already resolves correctly so we keep rootPath
                    // host-based (no linuxRootPath re-routing — that would redirect
                    // /var/minis to the global/empty placeholder dir). We only pass
                    // displayLinuxPrefix = "/var/minis" so "Copy Absolute Path"
                    // emits the agent-visible /var/minis/workspace/foo.py instead of
                    // the opaque /data/user/0/.../minis-sessions/<sid>/... host path.
                    FilePreviewHolder.fileBrowserViewModel = FileBrowserViewModel(
                        rootPath = java.io.File(rootPath),
                        rootLabel = "Session Files",
                        displayLinuxPrefix = "/var/minis",
                    )
                    navController.safeNavigate(Routes.FILE_BROWSER)
                },
            )
        }

        composable(Routes.FILE_BROWSER) {
            val vm = FilePreviewHolder.fileBrowserViewModel ?: return@composable
            FileBrowserScreen(
                viewModel = vm,
                onBack = { navController.safePopBackStack() },
                onPreviewFile = { item ->
                    FilePreviewHolder.currentItem = item
                    navController.safeNavigate(Routes.FILE_PREVIEW)
                },
            )
        }

        // Rendered as a Dialog destination (not a `composable`) so the
        // underlying screen — typically ChatScreen — stays in the composition
        // while preview is open. With `composable()`, NavHost unmounts the
        // previous entry, which detaches the chat LazyColumn from layout;
        // when the user pops back, LazyListState re-anchors to (0, 0) and
        // the user loses their scroll position (plus a white-flash on the
        // first frame before the list remeasures). `dialog()` keeps the
        // back entry's composition alive — listState retains both
        // firstVisibleItemIndex/Offset and its layoutInfo cache, so the
        // chat paints its previous viewport on the first frame after pop.
        // `usePlatformDefaultWidth=false` lets the dialog fill the screen
        // edge-to-edge, matching the prior full-screen composable feel;
        // `decorFitsSystemWindows=false` lets FilePreviewScreen handle its
        // own insets exactly like before.
        dialog(
            route = Routes.FILE_PREVIEW,
            dialogProperties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            val item = FilePreviewHolder.currentItem ?: return@dialog
            FilePreviewScreen(
                item = item,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.SKILLS) {
            if (skillRepository != null) {
                SkillsManagementScreen(
                    skillRepository = skillRepository,
                    onBack = { navController.safePopBackStack() },
                    onSkillClick = { skillId -> navController.safeNavigate(Routes.skillDetail(skillId)) },
                )
            }
        }

        composable(
            route = Routes.SKILL_DETAIL,
            arguments = listOf(navArgument("skillId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val skillId = backStackEntry.arguments?.getString("skillId") ?: return@composable
            if (skillRepository != null) {
                SkillDetailScreen(
                    skillId = skillId,
                    skillRepository = skillRepository,
                    onBack = { navController.safePopBackStack() },
                    onFileClick = { id, relativePath ->
                        navController.safeNavigate(Routes.skillFile(id, relativePath))
                    },
                )
            }
        }

        composable(
            route = Routes.SKILL_FILE,
            arguments = listOf(
                navArgument("skillId") { type = NavType.StringType },
                navArgument("relativePath") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val skillId = backStackEntry.arguments?.getString("skillId") ?: return@composable
            val rawPath = backStackEntry.arguments?.getString("relativePath") ?: "SKILL.md"
            val relativePath = java.net.URLDecoder.decode(rawPath, "UTF-8")
            if (skillRepository != null) {
                SkillFileViewerScreen(
                    skillId = skillId,
                    relativePath = relativePath,
                    skillRepository = skillRepository,
                    onBack = { navController.safePopBackStack() },
                )
            }
        }

        composable(Routes.MEMORY) {
            if (memoryRepository != null) {
                MemoryManagementScreen(
                    memoryRepository = memoryRepository,
                    onBack = { navController.safePopBackStack() },
                    onFileClick = { fileName, isGlobal ->
                        navController.safeNavigate(Routes.memoryFileEdit(fileName, isGlobal))
                    },
                )
            }
        }

        // [P3.3 裁军] MCP Integrations 管理页路由随 MCP 集成面退役删除
        // （MCPRepository 只服务已下架的 minis-mcp-cli 幽灵提示，无运行时
        // 消费方）。

        // [T-soul-md] SOUL.md editor.
        composable(Routes.SOUL) {
            com.openminis.app.ui.settings.SoulSettingsScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(
            route = Routes.MEMORY_FILE_EDIT,
            arguments = listOf(
                navArgument("fileName") { type = NavType.StringType },
                navArgument("isGlobal") { type = NavType.BoolType },
            ),
        ) { backStackEntry ->
            val fileName = backStackEntry.arguments?.getString("fileName") ?: return@composable
            val isGlobal = backStackEntry.arguments?.getBoolean("isGlobal") ?: false
            if (memoryRepository != null) {
                MemoryFileEditScreen(
                    fileName = fileName,
                    isGlobal = isGlobal,
                    memoryRepository = memoryRepository,
                    onBack = { navController.safePopBackStack() },
                )
            }
        }

        // [P3.3 裁军] PERMISSIONS（OffloadPermissionScreen）/ SHIZUKU
        // （ShizukuPermissionScreen）/ SYSTEM_PERMISSIONS
        // （SystemPermissionsScreen）三个目的地随 offload 与语音纠正区块
        // 退役删除。

        composable(Routes.USAGE_STATS) {
            UsageStatsScreen(
                chatDao = chatRepository.dao,
                providerConfig = providerRepository.config.value,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.APPEARANCE) {
            AppearanceScreen(
                onBack = { navController.safePopBackStack() },
                onColorThemeClick = { navController.safeNavigate(Routes.THEME_COLORS) },
            )
        }

        composable(Routes.THEME_COLORS) {
            ThemeColorScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.BACKGROUND) {
            BackgroundSettingsScreen(
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.safePopBackStack() })
        }

        composable(Routes.LOGS) {
            LogManagementScreen(
                onBack = { navController.safePopBackStack() },
                onLogFileClick = { fileName ->
                    navController.safeNavigate(Routes.logDetail(fileName))
                },
            )
        }

        composable(
            route = Routes.LOG_DETAIL,
            arguments = listOf(navArgument("fileName") { type = NavType.StringType }),
        ) { backStackEntry ->
            val fileName = backStackEntry.arguments?.getString("fileName") ?: return@composable
            LogDetailScreen(
                fileName = fileName,
                onBack = { navController.safePopBackStack() },
            )
        }
}
