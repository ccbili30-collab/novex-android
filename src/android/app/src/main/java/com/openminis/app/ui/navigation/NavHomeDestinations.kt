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

internal fun NavGraphBuilder.registerHomeDestinations(deps: NavDeps) {
    val navController = deps.navController
    val context = deps.context
    val chatRepository = deps.chatRepository
    val providerRepository = deps.providerRepository
    val skillRepository = deps.skillRepository
    val memoryRepository = deps.memoryRepository
    val cardNavigationScope = deps.cardScope
        composable(Routes.SESSION_LIST) {
            // home-v2：没有任何可用模型服务商时默认进会话 tab（新手引导在那里），
            // 否则进首页。判定沿用 SessionListScreen 的 hasProviders 写法。
            val providerConfig by providerRepository.config.collectAsState()
            val configLoaded by providerRepository.configLoaded.collectAsState()
            val initialTab = if (providerConfig.instances.any { it.isEnabled }) {
                com.openminis.app.ui.noven.NovenTab.HOME
            } else {
                com.openminis.app.ui.noven.NovenTab.SESSIONS
            }
            NovexRootScreen(
                initialTab = initialTab,
                initialTabReady = configLoaded,
                chatRepository = chatRepository,
                onImportCard={uri,world->navController.safeNavigate("integrated-import?uri=${android.net.Uri.encode(uri.toString())}&world=$world")},
                conversationContent = { _, onRootNavigationVisibilityChange ->
                    SessionListScreen(
                        chatRepository = chatRepository,
                        providerRepository = providerRepository,
                        onSessionClick = { sessionId ->
                            navController.safeNavigate(Routes.chat(sessionId))
                        },
                        onNewChat = { sessionId ->
                            navController.safeNavigate(Routes.chat(sessionId))
                        },
                        onAddProviderClick = {
                            navController.safeNavigate(Routes.PROVIDER_ONBOARDING)
                        },
                        onSelectModelsClick = {
                            navController.safeNavigate(Routes.ONBOARDING_MODELS)
                        },
                        // [P3.3 裁军] onScheduledTasksClick 随定时任务退役摘除。
                        onRootNavigationVisibilityChange = onRootNavigationVisibilityChange,
                    )
                },
                onOpenCard = { root, target ->
                    navController.safeNavigate("integrated-card?root=${android.net.Uri.encode(root)}&target=${android.net.Uri.encode(target)}")
                },
                onChat = { navController.safeNavigate(Routes.chat(it)) },
                onCreateWorld = { navController.safeNavigate(Routes.storyWorldEdit()) },
                onCreateCharacter = {
                    navController.safeNavigate(Routes.characterCatalogEdit())
                },
                onResumeImportDraft = { draftId ->
                    navController.safeNavigate("integrated-import-resume?draft=${android.net.Uri.encode(draftId)}")
                },
                onOpenSettings = {
                    navController.safeNavigate(Routes.SETTINGS)
                },
                onOpenCreativeLibrary = {
                    navController.safeNavigate(Routes.creativeLibrary())
                },
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            val configLoaded by providerRepository.configLoaded.collectAsState()
            if (!configLoaded) return@composable
            ChatScreen(
                sessionId = sessionId,
                onBackReturnsToList = navController.previousBackStackEntry?.destination?.route == Routes.SESSION_LIST,
                chatRepository = chatRepository,
                providerRepository = providerRepository,
                memoryRepository = memoryRepository,
                skillRepository = skillRepository,
                onBack = {
                    handleNovexInitialRouteBack(
                        popBackStack = { navController.safePopBackStack() },
                        finishHost = { (context as? android.app.Activity)?.finish() },
                    )
                },
                // [T-new-chat-menu-entry] Chat-menu "New Chat": same draft-id
                // funnel as the session list / NewChat deep link — a fresh
                // "__new__" route whose DB record is only created on first
                // send, so abandoning it leaves no empty session. popUpTo
                // removes the current chat from the stack (back → list) and
                // a double-fire just replaces one unpersisted draft with
                // another instead of stacking two chats.
                onNewChat = { worldId, characterId, characterVersionId, personaId ->
                    val draftId = buildChatDraftId(
                        draftId = java.util.UUID.randomUUID().toString(),
                        context = ChatDraftContext(
                            worldId = worldId,
                            characterId = characterId,
                            characterVersionId = characterVersionId,
                            personaId = personaId,
                        ),
                    )
                    navController.safeNavigate(Routes.chat(draftId)) {
                        popUpTo(Routes.SESSION_LIST) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onSettings = {
                    navController.safeNavigate(Routes.conversationSettings(sessionId))
                },
                // 侧边对话（2026-09-14 决策 12）：书签点按/新建后全屏进入该侧边
                // 会话（复用同一个 Chat 界面），返回键自然回到主线。
                onOpenSideSession = { sideId ->
                    navController.safeNavigate(Routes.chat(sideId))
                },
                onMoveToSession = { targetId ->
                    navController.safeNavigate(Routes.chat(targetId)) {
                        popUpTo(Routes.SESSION_LIST) { inclusive = false }
                    }
                },
                onBrowseChatFiles = { savedSessionId ->
                    navController.safeNavigate(Routes.creativeLibrary(savedSessionId))
                },
                onImportCard={uri,world->navController.safeNavigate("integrated-import?uri=${android.net.Uri.encode(uri.toString())}&world=$world")},
                onOpenCreatedCard = { kind, id -> cardNavigationScope.launch {
                    val workspace = (context.applicationContext as com.openminis.app.MinisApp).novexWorkspace
                    val route = when (kind) {
                        "integrated" -> {val address=org.json.JSONObject(id);"integrated-card?root=${android.net.Uri.encode(address.getString("root"))}&target=${android.net.Uri.encode(address.getString("target"))}"}
                        "world" -> Routes.storyWorld(id)
                        "character_version" -> workspace.characterForVersion(id)?.let {
                            Routes.characterDetail(it.character.character.id, versionId = id)
                        }
                        "game" -> Routes.interactiveFiction(id)
                        else -> null
                    }
                    if (route != null) navController.safeNavigate(route) { launchSingleTop = true }
                    else android.widget.Toast.makeText(context, "这张卡片已不存在，请到卡片库查看", android.widget.Toast.LENGTH_LONG).show()
                } },
                onPreviewAttachment = { item ->
                    FilePreviewHolder.currentItem = item
                    navController.safeNavigate(Routes.FILE_PREVIEW)
                },
                onModelGroupsClick = { navController.safeNavigate(Routes.MODEL_GROUPS) },
            )
        }

        composable(
            route = Routes.CONVERSATION_SETTINGS,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            ConversationSettingsScreen(
                sessionId = sessionId,
                chatRepository = chatRepository,
                providerRepository = providerRepository,
                memoryRepository = memoryRepository,
                skillRepository = skillRepository,
                onCardSettings = {mode->navController.safeNavigate("integrated-card-settings/${android.net.Uri.encode(sessionId)}?mode=${android.net.Uri.encode(mode)}")},
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = {
                    handleNovexInitialRouteBack(
                        popBackStack = { navController.safePopBackStack() },
                        finishHost = { (context as? android.app.Activity)?.finish() },
                    )
                },
                onProvidersClick = { navController.safeNavigate(Routes.PROVIDER_LIST) },
                onModelGroupsClick = { navController.safeNavigate(Routes.MODEL_GROUPS) },
                onImageGenerationClick = { navController.safeNavigate(Routes.IMAGE_GENERATION_SETTINGS) },
                onStorageClick = { navController.safeNavigate(Routes.STORAGE) },
                onSkillsClick = { navController.safeNavigate(Routes.SKILLS) },
                onMemoryClick = { navController.safeNavigate(Routes.MEMORY) },
                onSoulClick = { navController.safeNavigate(Routes.SOUL) },
                onUsageClick = { navController.safeNavigate(Routes.USAGE_STATS) },
                onAppearanceClick = { navController.safeNavigate(Routes.APPEARANCE) },
                onBackgroundClick = { navController.safeNavigate(Routes.BACKGROUND) },
                onLogsClick = { navController.safeNavigate(Routes.LOGS) },
                onAboutClick = { navController.safeNavigate(Routes.ABOUT) },
                onSharedFoldersClick = { navController.safeNavigate(Routes.SHARED_FOLDERS) },
                onFeedbackClick = { navController.safeNavigate(Routes.NOVEX_FEEDBACK) },
                onCreativeLibraryClick = { navController.safeNavigate(Routes.creativeLibrary()) },
                onComponentGalleryClick = if (com.openminis.app.BuildConfig.APPLICATION_ID.endsWith(".preview")) {
                    { navController.safeNavigate("settings/component-gallery") }
                } else null,
            )
        }
}
