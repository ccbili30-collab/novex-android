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

// T342: Material 3 motion easing curves. Compose-Material3 (1.3.x) ships
// `MotionScheme` only in 1.4-alpha; mirror the spec values directly so we
// don't take a dependency-bump tax just for two CubicBezierEasing instances.
// Source: m3.material.io/styles/motion/easing-and-duration/tokens-specs
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

object Routes {
    const val SESSION_LIST = "sessions"
    const val CHAT = "chat/{sessionId}"
    const val CONVERSATION_SETTINGS = "conversation_settings/{sessionId}"
    const val SETTINGS = "settings"
    const val NOVEX_FEEDBACK = "novex_feedback"
    const val PROVIDER_LIST = "providers"
    const val ADD_PROVIDER = "add_provider"
    const val IMAGE_GENERATION_SETTINGS = "image_generation_settings"
    const val IMAGE_GENERATION_SOURCE = "image_generation_source/{sourceId}"
    fun imageGenerationSource(sourceId: String) = "image_generation_source/$sourceId"
    const val PROVIDER_DETAIL = "provider/{instanceId}"
    // [P3.3 裁军] SHADOW_VOICE_DETAIL（只读语音服务影子页路由）随语音全家退役。
    const val MODEL_GROUPS = "model_groups"
    const val MODEL_GROUP_DETAIL = "model_group/{groupId}"
    const val ADD_MODELS_TO_GROUP = "add_models_to_group/{groupId}"
    /** T185: picker that adds model *entries* to the agent-loop set. */
    const val ADD_MODELS_TO_AGENT_LOOP = "add_models_to_agent_loop"
    /** T185: picker that adds model *groups* to the agent-loop set. */
    const val ADD_GROUPS_TO_AGENT_LOOP = "add_groups_to_agent_loop"
    /** T171→T182: AGENT_LOOP_MODELS deprecated (the screen lived inside
     *  Settings, now the picker is a section inside ModelGroupsScreen).
     *  Route declared so any back-compat deep-link string from preview
     *  builds pops back instead of crashing. */
    const val AGENT_LOOP_MODELS = "agent_loop_models"
    const val MODEL_ENTRY_DETAIL = "model_entry/{instanceId}/{entryId}"
    const val ADD_CUSTOM_MODEL = "add_custom_model/{instanceId}"
    const val STORAGE = "storage"
    const val SESSION_STORAGE_DETAIL = "session_storage/{sessionId}"
    const val FILE_BROWSER = "file_browser"
    const val FILE_PREVIEW = "file_preview"
    const val SKILLS = "skills"
    const val SKILL_DETAIL = "skill/{skillId}"
    const val SKILL_FILE = "skill_file/{skillId}/{relativePath}"

    fun skillDetail(skillId: String) = "skill/$skillId"
    fun skillFile(skillId: String, relativePath: String = "SKILL.md"): String {
        // Path may contain `/`, which the nav library treats as a route
        // separator. URL-encode so subdirectory paths survive a round-trip.
        val encoded = java.net.URLEncoder.encode(relativePath, "UTF-8").replace("+", "%20")
        return "skill_file/$skillId/$encoded"
    }
    const val CREATIVE_LIBRARY = "creative_library?sessionId={sessionId}"
    fun creativeLibrary(sessionId: String? = null): String =
        if (sessionId == null) "creative_library" else "creative_library?sessionId=${android.net.Uri.encode(sessionId)}"
    const val MEMORY = "memory"
    // [P3.3 裁军] MCP 路由随 MCP 集成面退役删除。
    /** [T-soul-md] SOUL.md editor. */
    const val SOUL = "soul"
    const val STORY_WORLD = "characters/world/{worldId}"
    const val STORY_WORLD_EDIT = "characters/world/edit?worldId={worldId}"
    const val CHARACTER_DETAIL = "characters/card/{characterId}?versionId={versionId}"
    const val CONTENT_MODULE_DETAIL = "characters/module/{moduleId}"
    const val CHARACTER_EDIT = "characters/edit?worldId={worldId}&characterId={characterId}"
    const val PERSONA_EDIT = "characters/persona/edit?worldId={worldId}&personaId={personaId}"
    const val CHARACTER_START = "characters/start/{characterId}"
    const val CHARACTER_CATALOG_EDIT =
        "characters/catalog/edit?characterId={characterId}&versionId={versionId}&worldId={worldId}&createVariant={createVariant}"
    const val INTERACTIVE_FICTION_DETAIL = "interactive-fiction/card/{projectId}"
    const val INTERACTIVE_FICTION_EDIT = "interactive-fiction/edit?projectId={projectId}"
    const val MEMORY_FILE_EDIT = "memory_file/{fileName}/{isGlobal}"
    // [P3.3 裁军] PERMISSIONS / SHIZUKU / SYSTEM_PERMISSIONS 三条路由随
    // offload（Shizuku/特权后端）与语音纠正区块（SystemPermissions 唯一
    // 内容）退役删除。
    const val USAGE_STATS = "usage_stats"
    const val LOGS = "logs"
    const val LOG_DETAIL = "log_detail/{fileName}"
    const val APPEARANCE = "appearance"
    const val THEME_COLORS = "appearance/theme_colors"
    const val BACKGROUND = "background"
    const val ABOUT = "about"
    const val ONBOARDING_MODELS = "onboarding_models"
    /** T235: Shared folders (Shared / Skills / Memory) — fixed list. */
    const val SHARED_FOLDERS = "shared_folders"
    const val SHARED_FOLDERS_DETAIL = "shared_folders_detail/{folderId}"
    fun sharedFoldersDetail(folderId: String) = "shared_folders_detail/$folderId"
    // [P3.3 裁军] SCHEDULED_TASKS / SCHEDULED_TASK_EDIT / SCHEDULED_TASK_RUNS
    // 三条路由随定时任务（scheduled/ + ui/scheduled/）退役删除。

    fun logDetail(fileName: String) = "log_detail/$fileName"
    fun sessionStorageDetail(sessionId: String) = "session_storage/$sessionId"
    fun memoryFileEdit(fileName: String, isGlobal: Boolean) = "memory_file/$fileName/$isGlobal"
    fun storyWorld(worldId: String) = "characters/world/${android.net.Uri.encode(worldId)}"
    fun storyWorldEdit(worldId: String? = null) =
        if (worldId == null) "characters/world/edit" else "characters/world/edit?worldId=${android.net.Uri.encode(worldId)}"
    fun characterDetail(characterId: String, versionId: String? = null) = buildString {
        append("characters/card/${android.net.Uri.encode(characterId)}")
        versionId?.let { append("?versionId=${android.net.Uri.encode(it)}") }
    }
    fun contentModuleDetail(moduleId: String) = "characters/module/${android.net.Uri.encode(moduleId)}"
    fun characterEdit(worldId: String, characterId: String? = null) = buildString {
        append("characters/edit?worldId=").append(android.net.Uri.encode(worldId))
        characterId?.let { append("&characterId=").append(android.net.Uri.encode(it)) }
    }
    fun personaEdit(worldId: String, personaId: String? = null) = buildString {
        append("characters/persona/edit?worldId=").append(android.net.Uri.encode(worldId))
        personaId?.let { append("&personaId=").append(android.net.Uri.encode(it)) }
    }
    fun characterStart(characterId: String) = "characters/start/${android.net.Uri.encode(characterId)}"
    fun characterCatalogEdit(
        characterId: String? = null,
        versionId: String? = null,
        worldId: String? = null,
        createVariant: Boolean = false,
    ) = buildString {
        append("characters/catalog/edit?createVariant=").append(createVariant)
        characterId?.let { append("&characterId=").append(android.net.Uri.encode(it)) }
        versionId?.let { append("&versionId=").append(android.net.Uri.encode(it)) }
        worldId?.let { append("&worldId=").append(android.net.Uri.encode(it)) }
    }
    fun interactiveFiction(projectId: String) =
        "interactive-fiction/card/${android.net.Uri.encode(projectId)}"
    fun interactiveFictionEdit(projectId: String? = null) =
        if (projectId == null) {
            "interactive-fiction/edit"
        } else {
            "interactive-fiction/edit?projectId=${android.net.Uri.encode(projectId)}"
        }
    fun chat(sessionId: String) = "chat/$sessionId"
    fun conversationSettings(sessionId: String) =
        "conversation_settings/${android.net.Uri.encode(sessionId)}"
    fun providerDetail(instanceId: String) = "provider/$instanceId"
    fun modelGroupDetail(groupId: String) = "model_group/$groupId"
    fun addModelsToGroup(groupId: String) = "add_models_to_group/$groupId"
    // [T-android-model-entry-route-slash-crash] entryId is a composite key
    // "<instanceId>/<modelId>" (entryCompositeId) — it CONTAINS a '/'. Left
    // raw, that slash splits the route into an extra path segment, so the
    // built route no longer matches the registered MODEL_ENTRY_DETAIL pattern
    // (model_entry/{instanceId}/{entryId}) and navigate() throws
    // IllegalArgumentException "destination … cannot be found" — a guaranteed
    // crash on tapping any model whose id carries a '/'. URL-encode it so the
    // slash becomes %2F (one segment); the receiver decodes it back.
    fun modelEntryDetail(instanceId: String, entryId: String) =
        "model_entry/${android.net.Uri.encode(instanceId)}/${android.net.Uri.encode(entryId)}"
    fun addCustomModel(instanceId: String) = "add_custom_model/$instanceId"
}

/** Holder for file preview navigation state (not serializable via nav args). */
internal object FilePreviewHolder {
    var currentItem: FileItem? = null
    var fileBrowserViewModel: FileBrowserViewModel? = null
}

@Composable
fun AppNavigation(
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    envVarRepository: EnvVarRepository? = null,
    skillRepository: SkillRepository? = null,
    memoryRepository: MemoryRepository? = null,
    navController: NavHostController = rememberNavController(),
    initialDeepLink: DeepLinkAction? = null,
    initialRoute: String? = null,
) {
    val context = LocalContext.current
    fun returnFromCard() {
        if(navController.previousBackStackEntry==null) (context as? android.app.Activity)?.finish()
        else navController.safePopBackStack()
    }
    val cardNavigationScope = androidx.compose.runtime.rememberCoroutineScope()

    fun openCreationWorkspace(subject: NovexContentAddress, organizeImported: Boolean = false) {
        DeepLinkCoordinator.setPendingChatAction(if (organizeImported) DeepLinkCoordinator.ChatAction.ORGANIZE_IMPORTED_CARD
            else DeepLinkCoordinator.ChatAction.OPEN_CREATION_TOOL)
        val draftId = buildChatDraftId(
            draftId = java.util.UUID.randomUUID().toString(),
            context = ChatDraftContext(managedSubjects = listOf(subject)),
        )
        navController.safeNavigate(Routes.chat(draftId)) {
            popUpTo(Routes.SESSION_LIST) { inclusive = false }
        }
    }

    // Handle initial deep link after composition
    LaunchedEffect(initialDeepLink) {
        when (initialDeepLink) {
            is DeepLinkAction.OpenSession -> {
                // T-double-chat-fix: on process-death recreation NavController
                // auto-restores [SESSION_LIST, chat/<id>] AND MainActivity
                // synthesizes an OpenSession deep-link from saved state. A
                // bare navigate() pushed a SECOND chat entry, forcing the
                // user to press back twice. launchSingleTop collapses the
                // duplicate; popUpTo(SESSION_LIST, saveState=true) +
                // restoreState=true preserves chat-screen state across the
                // hop. When the synthesized deep-link is a no-op (chat
                // already on top) launchSingleTop short-circuits.
                navController.safeNavigate(Routes.chat(initialDeepLink.sessionId)) {
                    popUpTo(Routes.SESSION_LIST) {
                        inclusive = false
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            // T183: any settings screen reachable by route string. The
            // parser already resolved the path → route mapping so we
            // just navigate.
            is DeepLinkAction.OpenSettingsScreen -> {
                navController.safeNavigate(initialDeepLink.route)
            }
            // [P3.3 裁军] OpenPermissionSettings / OpenHtmlPreview 动作随对应
            // 功能退役（解析端已删，此分支自然死路）。
            // App-icon quick actions: both open a fresh draft chat. Camera
            // additionally seeds DeepLinkCoordinator.pendingChatAction (done
            // up-front in startDestination block below so the seed lands
            // before ChatScreen's first compose).
            is DeepLinkAction.NewChat,
            is DeepLinkAction.NewCameraChat -> {
                // Navigation handled by startDestination = chat/<__new__…>
                // when the launch intent carries one of these actions.
                // Nothing to do here — see startDestination block below.
            }
            else -> {}
        }
    }

    // A launcher-icon cold start always remains on the session list. Shares
    // are the sole no-deep-link exception because their payload must land in
    // a composer. Notification and shortcut deep links are handled above.
    LaunchedEffect(Unit) {
        val hasDeepLink = initialDeepLink != null && initialDeepLink !is DeepLinkAction.Unknown
        if (hasDeepLink) return@LaunchedEffect
        val hasPendingShare =
            com.openminis.app.share.ShareCoordinator.bufferVersion.value > 0
        if (hasPendingShare) {
            navController.navigate(Routes.chat("__new__${java.util.UUID.randomUUID()}")) {
                popUpTo(Routes.SESSION_LIST) { inclusive = false }
            }
        }
    }

    // T185: warm-share fallback — if a share lands while the user is sitting
    // on the session list (cold-start with mode 3 that raced past the launch
    // resolver, or onNewIntent re-fires processPendingShare), route into a
    // fresh chat so ChatScreen's existing LaunchedEffect(shareBufferVersion)
    // can drain it. The drain itself is idempotent: consumeBuffer is one-shot,
    // so a ChatScreen already in the backstack won't double-inject.
    val shareBufferVersion by com.openminis.app.share.ShareCoordinator.bufferVersion.collectAsState()
    LaunchedEffect(shareBufferVersion) {
        if (shareBufferVersion == 0) return@LaunchedEffect
        val current = navController.currentDestination?.route ?: return@LaunchedEffect
        if (current == Routes.SESSION_LIST) {
            navController.safeNavigate(Routes.chat("__new__${java.util.UUID.randomUUID()}")) {
                popUpTo(Routes.SESSION_LIST) { inclusive = false }
            }
        }
    }

    // [P3.3 裁军] htmlShortcut（HTML 预览固定捷径冷启动）与 NewVoiceChat
    // 分支随对应功能退役删除。
    // App-icon quick action cold start: mount NavHost directly at a fresh
    // draft chat, seeding the pending action so ChatScreen consumes it on
    // its first LaunchedEffect tick — avoids a sessions-list flash and a
    // duplicate back-stack entry.
    val quickActionStart: String? = when (initialDeepLink) {
        is DeepLinkAction.NewCameraChat -> {
            DeepLinkCoordinator.setPendingChatAction(
                DeepLinkCoordinator.ChatAction.OPEN_CAMERA,
            )
            Routes.chat("__new__${java.util.UUID.randomUUID()}")
        }
        is DeepLinkAction.NewChat -> Routes.chat("__new__${java.util.UUID.randomUUID()}")
        else -> null
    }
    val startDestination = when {
        quickActionStart != null -> quickActionStart
        initialRoute != null -> initialRoute
        else -> Routes.SESSION_LIST
    }
    NavHost(
        navController = navController,
        startDestination = startDestination,
        // T153: paint the in-app theme color underneath every transition
        // frame. Without this the NavHost's transition surface is
        // transparent and the window background bleeds through during
        // enter/exit animations — on dark mode the base Light window
        // background flashes white between screens. Tying the host to
        // the active Material colorScheme also keeps the first frame
        // correct on cold start.
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        // T342: Material 3 motion — shared-axis X transition.
        // Spec (m3.material.io/styles/motion/transitions):
        //   - enter uses EmphasizedDecelerate (cubic-bezier 0.05, 0.7, 0.1, 1.0)
        //     so the new destination eases in confidently
        //   - exit uses EmphasizedAccelerate (cubic-bezier 0.3, 0.0, 0.8, 0.15)
        //     so the leaving destination clears out fast
        //   - both legs together feel like a single 300ms motion (200ms
        //     exit overlapping 300ms enter), short enough that a rapid second
        //     tap still lands on the next destination once safeNavigate's
        //     RESUMED guard releases
        //   - the small slide distance (~SlideDirection default ≈ container
        //     width ÷ N — Compose's slideIntoContainer already picks a
        //     subtle distance) plus fade reads as a single coordinated
        //     motion rather than a hard cut, matching Settings/Files in
        //     Material You system apps.
        // RESUMED guard via safeNavigate + the colorScheme.background
        // modifier above (T333) still defend against rapid-tap white
        // flashes; the slightly longer enter spec (300ms vs 220ms) is
        // covered by the same guard.
        enterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(300, easing = EmphasizedDecelerate),
            ) + fadeIn(animationSpec = tween(300, easing = EmphasizedDecelerate))
        },
        exitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(200, easing = EmphasizedAccelerate),
            ) + fadeOut(animationSpec = tween(200, easing = EmphasizedAccelerate))
        },
        popEnterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(300, easing = EmphasizedDecelerate),
            ) + fadeIn(animationSpec = tween(300, easing = EmphasizedDecelerate))
        },
        popExitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(200, easing = EmphasizedAccelerate),
            ) + fadeOut(animationSpec = tween(200, easing = EmphasizedAccelerate))
        },
    ) {
        composable("integrated-import?uri={uri}&world={world}",arguments=listOf(
            navArgument("uri"){type=NavType.StringType},navArgument("world"){type=NavType.BoolType;defaultValue=false})) {entry->
            com.openminis.app.cards.IntegratedCardHost(kind=if(entry.arguments?.getBoolean("world")==true)novex.content.CardKind.WORLD else novex.content.CardKind.CHARACTER,
                importUri=entry.arguments?.getString("uri"),onChat={navController.safeNavigate(Routes.chat(it))},onBack={returnFromCard()})
        }
        composable("integrated-card-settings/{chat}?mode={mode}",arguments=listOf(
            navArgument("chat"){type=NavType.StringType},
            navArgument("mode"){type=NavType.StringType;defaultValue="identity"})) {entry->
            com.openminis.app.cards.IntegratedCardSettings(
                requireNotNull(entry.arguments?.getString("chat")),
                initialMode=entry.arguments?.getString("mode")?:"identity",
                onBack={navController.safePopBackStack()})
        }
        composable("integrated-card?root={root}&target={target}",arguments=listOf(navArgument("root"){type=NavType.StringType;defaultValue=""},navArgument("target"){type=NavType.StringType;defaultValue=""})) {entry->
            com.openminis.app.cards.IntegratedCardHost(root=entry.arguments?.getString("root"),target=entry.arguments?.getString("target"),onChat={navController.safeNavigate(Routes.chat(it))},onBack={returnFromCard()})
        }
        // 「未完成」导入草稿续接：复用 IntegratedCardHost 的导入确认页。
        composable("integrated-import-resume?draft={draft}",arguments=listOf(
            navArgument("draft"){type=NavType.StringType})) {entry->
            com.openminis.app.cards.IntegratedCardHost(resumeDraftId=entry.arguments?.getString("draft"),
                onChat={navController.safeNavigate(Routes.chat(it))},onBack={returnFromCard()})
        }
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
                            navController.safeNavigate(Routes.ADD_PROVIDER)
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

        composable(
            route = Routes.CREATIVE_LIBRARY,
            arguments = listOf(
                navArgument("sessionId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val app = LocalContext.current.applicationContext as com.openminis.app.MinisApp
            val libraryScope = androidx.compose.runtime.rememberCoroutineScope()
            CreativeLibraryScreen(
                repository = app.creativeArtifactRepository,
                deviceDirectory = app.creativeArtifactDeviceDirectory,
                workspace = app.novexWorkspace,
                conversationId = entry.arguments?.getString("sessionId"),
                onConfigureConversation = { navController.safeNavigate(Routes.conversationSettings(it)) },
                onOpenCard = { address -> libraryScope.launch {
                    val root=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.openminis.app.cards.IntegratedCatalog.root(address,com.openminis.app.cards.IntegratedCards(app).store)
                    }
                    root?.let {navController.safeNavigate("integrated-card?root=${android.net.Uri.encode(it)}&target=${android.net.Uri.encode(it)}")}

                } },
                onBack = { navController.safePopBackStack() },
                onOpenArtifact = { record, file ->
                    FilePreviewHolder.currentItem = FileItem(
                        file = file,
                        name = novex.core.NovexDisplayName.file(record.artifact.title),
                        isDirectory = false,
                        isSymlink = false,
                        size = file.length(),
                        modifiedMs = record.artifact.updatedAt,
                    )
                    navController.safeNavigate(Routes.FILE_PREVIEW)
                },
            )
        }

        composable(
            route = Routes.STORY_WORLD,
            arguments = listOf(navArgument("worldId") { type = NavType.StringType }),
        ) { entry ->
            com.openminis.app.cards.LegacyCardEntry("world",entry.arguments?.getString("worldId"),
                onChat={navController.safeNavigate(Routes.chat(it))},onBack={returnFromCard()})

        }

        composable(
            route = Routes.STORY_WORLD_EDIT,
            arguments = listOf(navArgument("worldId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }),
        ) { entry ->
            com.openminis.app.cards.LegacyCardEntry("world",entry.arguments?.getString("worldId"),
                onChat={navController.safeNavigate(Routes.chat(it))},onBack={returnFromCard()})

        }

        composable(
            route = Routes.CHARACTER_DETAIL,
            arguments = listOf(
                navArgument("characterId") { type = NavType.StringType },
                navArgument("versionId") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            com.openminis.app.cards.LegacyCardEntry("character",entry.arguments?.getString("characterId"),version=entry.arguments?.getString("versionId"),
                onChat={navController.safeNavigate(Routes.chat(it))},onBack={returnFromCard()})

        }

        composable(
            route = Routes.CONTENT_MODULE_DETAIL,
            arguments = listOf(navArgument("moduleId") { type = NavType.StringType }),
        ) { entry ->
            val moduleId = entry.arguments?.getString("moduleId") ?: return@composable
            com.openminis.app.ui.settings.CatalogContentModuleDetailScreen(
                moduleId = moduleId,
                onBack = { navController.safePopBackStack() },
                onHelpCreate = { openCreationWorkspace(it) },
            )
        }

        composable(
            route = Routes.CHARACTER_CATALOG_EDIT,
            arguments = listOf(
                navArgument("characterId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("versionId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("worldId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("createVariant") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            com.openminis.app.cards.LegacyCardEntry("character",entry.arguments?.getString("characterId"),version=entry.arguments?.getString("versionId"),
                onChat={navController.safeNavigate(Routes.chat(it))},onBack={returnFromCard()})

        }

        composable(
            route = Routes.INTERACTIVE_FICTION_DETAIL,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) { entry ->
            com.openminis.app.cards.LegacyCardEntry("game",entry.arguments?.getString("projectId"),
                onChat={navController.safeNavigate(Routes.chat(it))},onBack={returnFromCard()})

        }

        composable(
            route = Routes.INTERACTIVE_FICTION_EDIT,
            arguments = listOf(
                navArgument("projectId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            com.openminis.app.cards.LegacyCardEntry("game",entry.arguments?.getString("projectId"),
                onChat={navController.safeNavigate(Routes.chat(it))},onBack={returnFromCard()})

        }

        composable(
            route = Routes.CHARACTER_EDIT,
            arguments = listOf(
                navArgument("worldId") { type = NavType.StringType },
                navArgument("characterId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val worldId = entry.arguments?.getString("worldId") ?: return@composable
            com.openminis.app.ui.settings.Card3CharacterEditorScreen(
                worldId = worldId,
                cardId = entry.arguments?.getString("characterId"),
                onBack = { navController.safePopBackStack() },
                onSaved = { characterId ->
                    navController.safeNavigate(Routes.characterDetail(characterId)) {
                        popUpTo(Routes.storyWorld(worldId)) { inclusive = false }
                    }
                },
            )
        }

        composable(
            route = Routes.PERSONA_EDIT,
            arguments = listOf(
                navArgument("worldId") { type = NavType.StringType },
                navArgument("personaId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val worldId = entry.arguments?.getString("worldId") ?: return@composable
            com.openminis.app.ui.settings.Card3PersonaEditorScreen(
                worldId = worldId,
                personaId = entry.arguments?.getString("personaId"),
                onBack = { navController.safePopBackStack() },
                onSaved = { navController.safePopBackStack() },
            )
        }

        composable(
            route = Routes.CHARACTER_START,
            arguments = listOf(navArgument("characterId") { type = NavType.StringType }),
        ) { entry ->
            val characterId = entry.arguments?.getString("characterId") ?: return@composable
            val context = androidx.compose.ui.platform.LocalContext.current
            com.openminis.app.ui.settings.StartCharacterChatScreen(
                characterId = characterId,
                onBack = { navController.safePopBackStack() },
                onCreatePersona = {
                    val card = com.openminis.app.data.character.CharacterCardStore.character(context, characterId)
                    card?.let { navController.safeNavigate(Routes.personaEdit(it.worldId)) }
                },
                onCreateDraft = { draftId ->
                    navController.safeNavigate(Routes.chat(draftId)) {
                        popUpTo(Routes.SESSION_LIST) { inclusive = false }
                    }
                },
            )
        }

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

        composable(Routes.PROVIDER_LIST) {
            ProviderListScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onAddProvider = { navController.safeNavigate(Routes.ADD_PROVIDER) },
                onProviderClick = { instanceId ->
                    navController.safeNavigate(Routes.providerDetail(instanceId))
                },
                // [P3.3 裁军] onVoiceServiceClick + SHADOW_VOICE_DETAIL 目的地
                // 随语音服务影子页退役删除。
            )
        }

        composable(Routes.ADD_PROVIDER) {
            NovexProviderSetupScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onSaved = { navController.safePopBackStack() },
            )
        }

        composable(Routes.IMAGE_GENERATION_SETTINGS) {
            ImageGenerationSettingsScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onAddSource = { navController.safeNavigate(Routes.imageGenerationSource("new")) },
                onSourceClick = { sourceId -> navController.safeNavigate(Routes.imageGenerationSource(sourceId)) },
            )
        }

        composable(
            route = Routes.IMAGE_GENERATION_SOURCE,
            arguments = listOf(navArgument("sourceId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val raw = backStackEntry.arguments?.getString("sourceId") ?: return@composable
            ImageGenerationSourceScreen(
                sourceId = raw.takeUnless { it == "new" },
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(
            route = Routes.PROVIDER_DETAIL,
            arguments = listOf(navArgument("instanceId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val instanceId = backStackEntry.arguments?.getString("instanceId") ?: return@composable
            ProviderDetailScreen(
                instanceId = instanceId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onModelEntryClick = { entryId ->
                    navController.safeNavigate(Routes.modelEntryDetail(instanceId, entryId))
                },
                onAddCustomModel = {
                    navController.safeNavigate(Routes.addCustomModel(instanceId))
                },
                // [P3.3 裁军] onVoiceServiceClick 随语音服务影子页退役摘除。
            )
        }

        composable(Routes.MODEL_GROUPS) {
            ModelGroupsScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onGroupClick = { groupId ->
                    navController.safeNavigate(Routes.modelGroupDetail(groupId))
                },
                onAddAgentLoopModels = {
                    navController.safeNavigate(Routes.ADD_MODELS_TO_AGENT_LOOP)
                },
                onAddAgentLoopGroups = {
                    navController.safeNavigate(Routes.ADD_GROUPS_TO_AGENT_LOOP)
                },
            )
        }

        composable(
            route = Routes.MODEL_GROUP_DETAIL,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            ModelGroupDetailScreen(
                groupId = groupId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
                onAddModels = {
                    navController.safeNavigate(Routes.addModelsToGroup(groupId))
                },
            )
        }

        composable(
            route = Routes.ADD_MODELS_TO_GROUP,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            AddModelsToGroupScreen(
                groupId = groupId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        // T185: full-screen picker for adding entries to the agent-loop
        // usable set (replaces the T182 ModalBottomSheet so the visual
        // matches AddModelsToGroupScreen — same shared
        // modelEntryPickerItems composable in ui/components/).
        composable(Routes.ADD_MODELS_TO_AGENT_LOOP) {
            AddAgentLoopModelsScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        // T185: companion picker for adding model groups to the agent-loop
        // set. Simpler layout (no per-provider sectioning) but same
        // selection/confirm semantics as the entries picker.
        composable(Routes.ADD_GROUPS_TO_AGENT_LOOP) {
            AddAgentLoopGroupsScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        // T182: AgentLoopModels is no longer a standalone screen. The
        // picker now lives as the last section inside ModelGroupsScreen
        // (mirrors iOS Views/Providers/ModelGroupsView.swift L77-79's
        // `AgentLoopModelsSection`). Routes.AGENT_LOOP_MODELS is left
        // declared for back-compat with any deep-link string we may
        // have shipped to early users; safePopBackStack lands them on
        // the prior screen if it ever fires.
        composable(Routes.AGENT_LOOP_MODELS) {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                navController.safePopBackStack()
            }
        }

        composable(
            route = Routes.MODEL_ENTRY_DETAIL,
            arguments = listOf(
                navArgument("instanceId") { type = NavType.StringType },
                navArgument("entryId") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            // [T-android-model-entry-route-slash-crash] Decode back the
            // %2F-encoded composite ids so ModelEntryDetailScreen can match
            // entry.id == "<instanceId>/<modelId>" (which carries a literal '/')
            // against the repo. Mirrors the encode in Routes.modelEntryDetail.
            val instanceId = backStackEntry.arguments?.getString("instanceId")
                ?.let { android.net.Uri.decode(it) } ?: return@composable
            val entryId = backStackEntry.arguments?.getString("entryId")
                ?.let { android.net.Uri.decode(it) } ?: return@composable
            ModelEntryDetailScreen(
                instanceId = instanceId,
                entryId = entryId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        composable(
            route = Routes.ADD_CUSTOM_MODEL,
            arguments = listOf(navArgument("instanceId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val instanceId = backStackEntry.arguments?.getString("instanceId") ?: return@composable
            AddCustomModelScreen(
                instanceId = instanceId,
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
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

        composable(Routes.ONBOARDING_MODELS) {
            OnboardingModelSelectionScreen(
                providerRepository = providerRepository,
                onBack = { navController.safePopBackStack() },
            )
        }

        // [P3.3 裁军] 定时任务三个目的地（列表/编辑/运行记录）随
        // scheduled/ + ui/scheduled/ 退役删除。
    }
}
