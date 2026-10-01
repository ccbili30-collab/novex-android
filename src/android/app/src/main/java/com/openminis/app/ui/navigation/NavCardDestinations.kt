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

internal fun NavGraphBuilder.registerCardDestinations(deps: NavDeps) {
    val navController = deps.navController
    val context = deps.context
    val returnFromCard = deps.returnFromCard
    val openCreationWorkspace = deps.openCreationWorkspace
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
                onHelpCreate = { openCreationWorkspace(it, false) },
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
}
