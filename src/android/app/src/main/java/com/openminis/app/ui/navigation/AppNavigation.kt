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
    /** [T-provider-onboarding] 添加供应商的前置步：选择接入方式（智谱/深度求索/自定义）。 */
    const val PROVIDER_ONBOARDING = "provider_onboarding"
    /** 接入方式三卡选完后进入的连接页；preset 带官方预设键（zhipu/deepseek），空=手动。 */
    const val ADD_PROVIDER = "add_provider?preset={preset}"
    fun addProvider(preset: String? = null): String =
        if (preset.isNullOrBlank()) "add_provider" else "add_provider?preset=$preset"
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

/** 目的地共享的依赖袋：导航回调 + 仓库 + 宿主上下文。 */
internal class NavDeps(
    val navController: NavHostController,
    val context: android.content.Context,
    val chatRepository: ChatRepository,
    val providerRepository: ProviderRepository,
    val skillRepository: SkillRepository?,
    val memoryRepository: MemoryRepository?,
    val cardScope: kotlinx.coroutines.CoroutineScope,
    val returnFromCard: () -> Unit,
    val openCreationWorkspace: (NovexContentAddress, Boolean) -> Unit,
)

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
    val deps = NavDeps(
        navController = navController,
        context = context,
        chatRepository = chatRepository,
        providerRepository = providerRepository,
        skillRepository = skillRepository,
        memoryRepository = memoryRepository,
        cardScope = cardNavigationScope,
        returnFromCard = ::returnFromCard,
        openCreationWorkspace = ::openCreationWorkspace,
    )
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
        registerCardDestinations(deps)
        registerHomeDestinations(deps)
        registerModelDestinations(deps)
        registerSystemDestinations(deps)
    }
}
