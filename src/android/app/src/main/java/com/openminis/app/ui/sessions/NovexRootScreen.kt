package com.openminis.app.ui.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.openminis.app.data.NovexBulletinMonitor
import com.openminis.app.data.NovexUpdateMonitor
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.ui.noven.NovenBackAction
import com.openminis.app.ui.noven.NovenBottomBar
import com.openminis.app.ui.noven.NovenColors
import com.openminis.app.ui.noven.NovenCreateScreen
import com.openminis.app.ui.noven.NovenHomeScreen
import com.openminis.app.ui.noven.NovenMeScreen
import com.openminis.app.ui.noven.NovenMessagesScreen
import com.openminis.app.ui.noven.NovenTab
import com.openminis.app.ui.noven.novenBackAction
import com.openminis.app.ui.settings.NovexUpdateHost
import com.openminis.app.ui.settings.NovexUpdateHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * home-v2 根页面：五个 tab（首页/会话/创作/消息/我的）+ 固定底栏。
 * 无左右滑动切换；每个 tab 的状态由 rememberSaveableStateHolder 保留。
 * 公告宿主常驻组合中，叠卡弹窗在任何 tab 都能出来。
 */
@Composable
internal fun NovexRootScreen(
    initialTab: NovenTab = NovenTab.HOME,
    initialTabReady: Boolean = true,
    conversationContent: @Composable (
        onShowHome: () -> Unit,
        onRootNavigationVisibilityChange: (Boolean) -> Unit,
    ) -> Unit,
    // 回调一律必填：默认空实现会让未来调用点静默失效。
    onOpenCard: (String, String) -> Unit,
    onChat: (String) -> Unit,
    onCreateWorld: () -> Unit,
    onCreateCharacter: () -> Unit,
    onImportCard: (android.net.Uri, Boolean) -> Unit,
    onResumeImportDraft: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCreativeLibrary: () -> Unit,
    chatRepository: ChatRepository? = null,
) {
    val stateHolder = rememberSaveableStateHolder()
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(initialTabReady, initialTab) {
        if (selectedName == null && initialTabReady) selectedName = initialTab.name
    }

    // 旧卡迁移只做一次、放根页面：用户先进「我的」或「创作」也不漏。
    // 完成后三个 tab 各自 library.refresh()。
    val app = LocalContext.current.applicationContext as com.openminis.app.MinisApp
    var libraryReady by remember { mutableStateOf(false) }
    var migrationError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) { com.openminis.app.cards.LegacyCards(app).migrate() }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            migrationError = failure.message
        }
        libraryReady = true
    }

    // 平台按 displayCutout/状态栏拟合内容，Compose 够不到的那一条画的是
    // decor 的 windowBackground（浅色主题是白）。把窗口底色刷成 Canvas：
    // 状态栏区视觉与页面一致，两条启动路径同样生效。不能直接删 —— app 主题
    // 可被用户单独覆盖（与系统 uiMode 不一致时 windowBackground 反而错），
    // 所以保留写入，并在组合移除时按 uiMode 恢复主题色，避免染色留给
    // 之后复用同一窗口的页面。
    val canvasColor = NovenColors.Canvas
    val context = LocalContext.current
    DisposableEffect(canvasColor) {
        var c = context
        while (c is android.content.ContextWrapper && c !is android.app.Activity) {
            c = c.baseContext
        }
        val decor = (c as? android.app.Activity)?.window?.decorView
        decor?.setBackgroundColor(canvasColor.toArgb())
        onDispose {
            val night = c.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            decor?.setBackgroundColor(
                if (night) android.graphics.Color.rgb(15, 17, 18)
                else android.graphics.Color.rgb(244, 245, 244)
            )
        }
    }
    val selected = selectedName
        ?.let { runCatching { NovenTab.valueOf(it) }.getOrNull() }
        ?: NovenTab.HOME
    var homeQuery by rememberSaveable { mutableStateOf("") }
    var navVisible by remember { mutableStateOf(true) }
    // 兜底：隐藏底栏的唯一来源是会话 tab（多选）。任何情况下离开会话 tab
    // 都强制恢复，避免返回键被 GoHome 抢走时底栏永久消失。
    LaunchedEffect(selected) {
        if (selected != NovenTab.SESSIONS) navVisible = true
    }
    val updateHub = remember { NovexUpdateHub() }
    val bulletin by NovexBulletinMonitor.state.collectAsState()
    val detectedUpdate by NovexUpdateMonitor.available.collectAsState()
    val meBadged = bulletin.hasBadge || detectedUpdate != null

    val backAction = novenBackAction(selected, homeQuery)
    BackHandler(enabled = backAction == NovenBackAction.ClearHomeSearch) { homeQuery = "" }
    BackHandler(enabled = backAction == NovenBackAction.GoHome) { selectedName = NovenTab.HOME.name }

    Column(Modifier.fillMaxSize().background(NovenColors.Canvas)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (selectedName != null) {
                stateHolder.SaveableStateProvider(selected.name) {
                    Box(Modifier.fillMaxSize().statusBarsPadding()) {
                        when (selected) {
                            NovenTab.HOME -> NovenHomeScreen(
                                query = homeQuery,
                                onQueryChange = { homeQuery = it },
                                onOpenCard = onOpenCard,
                                onChat = onChat,
                                onCreateWorld = onCreateWorld,
                                onImportCard = onImportCard,
                                libraryReady = libraryReady,
                                migrationError = migrationError,
                            )
                            NovenTab.SESSIONS -> conversationContent(
                                { selectedName = NovenTab.HOME.name },
                                { visible -> navVisible = visible },
                            )
                            NovenTab.CREATE -> NovenCreateScreen(
                                onChat = onChat,
                                onCreateWorld = onCreateWorld,
                                onCreateCharacter = onCreateCharacter,
                                onImportCard = onImportCard,
                                onOpenCard = onOpenCard,
                                onResumeImportDraft = onResumeImportDraft,
                                libraryReady = libraryReady,
                            )
                            NovenTab.MESSAGES -> NovenMessagesScreen()
                            NovenTab.ME -> NovenMeScreen(
                                libraryReady = libraryReady,
                                updateHub = updateHub,
                                chatRepository = chatRepository,
                                onOpenCard = onOpenCard,
                                onCreateWorld = onCreateWorld,
                                onCreateCharacter = onCreateCharacter,
                                onImportCard = onImportCard,
                                onOpenSettings = onOpenSettings,
                                onOpenCreativeLibrary = onOpenCreativeLibrary,
                            )
                        }
                    }
                }
            }
            // 公告宿主：冷启动叠卡弹窗/公告中心/更新对话框，任何 tab 生效。
            NovexUpdateHost(updateHub)
        }
        if (navVisible) {
            NovenBottomBar(
                selected = selected,
                onSelect = { selectedName = it.name },
                meBadged = meBadged,
            )
        }
    }
}
