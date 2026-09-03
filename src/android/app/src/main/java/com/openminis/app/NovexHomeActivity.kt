package com.openminis.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.openminis.app.startup.NovexStartupMetrics
import com.openminis.app.ui.sessions.NovexConversationRoot
import com.openminis.app.ui.sessions.NovexRootScreen
import com.openminis.app.ui.theme.MinisTheme
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal const val EXTRA_NOVEX_START_ROUTE = "novex.start_route"

/**
 * Lightweight Novex host used for the three root spaces. It never requests
 * provider, model, tool, browser, voice or sandbox objects while the user is
 * browsing the home, world library or character library.
 */
class NovexHomeActivity : ComponentActivity() {
    private var runtimeRequest: Job? = null
    private var preparingRuntime by mutableStateOf(false)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.openminis.app.i18n.LocaleWrap.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as? MinisApp
        if (app == null || !app.startupCoordinator.state.value.minimumAvailable) {
            startActivity(Intent().setClassName(this, "com.openminis.app.NovexLaunchActivity").apply {
                action = Intent.ACTION_MAIN
            })
            finish()
            return
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            MinisTheme(darkTheme = dark) {
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
                            onCreationPlaceholder = {
                                Toast.makeText(this, "创作模式将在后续版本开放", Toast.LENGTH_SHORT).show()
                            },
                            onInteractive = {
                                NovexStartupMetrics.reportHomeInteractive()
                                app.startPostHomeMaintenance()
                            },
                            onRootNavigationVisibilityChange = onRootNavigationVisibilityChange,
                        )
                    },
                    onOpenWorld = { id -> openLegacy("characters/world/${Uri.encode(id)}") },
                    onCreateWorld = { openLegacy("characters/world/edit") },
                    onOpenCharacter = { id -> openLegacy("characters/card/${Uri.encode(id)}") },
                    onCreateCharacter = { openLegacy("characters/catalog/edit?createVariant=false") },
                )
            }
        }
    }

    private fun openLegacy(route: String) {
        if (runtimeRequest?.isActive == true) return
        val app = application as? MinisApp ?: return
        preparingRuntime = true
        runtimeRequest = lifecycleScope.launch {
            val result = app.startupCoordinator.ensureRuntime()
            preparingRuntime = false
            result.fold(
                onSuccess = {
                    startActivity(
                        Intent().setClassName(this@NovexHomeActivity, "com.openminis.app.MainActivity")
                            .putExtra(EXTRA_NOVEX_START_ROUTE, route),
                    )
                },
                onFailure = ::showRuntimeFailure,
            )
        }
    }

    private fun showRuntimeFailure(error: Throwable) {
        AlertDialog.Builder(this)
            .setTitle("对话能力准备失败")
            .setMessage(error.message ?: "模型或工具运行时无法初始化，世界与角色资料仍可继续浏览。")
            .setPositiveButton("关闭后重试") { _, _ -> finishAffinity() }
            .setNegativeButton("留在首页", null)
            .show()
    }
}
