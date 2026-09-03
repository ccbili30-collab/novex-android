package com.openminis.app.ui.sessions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

internal data class NovexRootHeaderConfig(
    val searching: Boolean,
    val searchDescription: String,
    val onSettings: () -> Unit,
    val onSearchToggle: () -> Unit,
    val createItems: List<NovexCreateMenuItem>,
)

/** Keeps the root toolbar outside the horizontally moving page while each page owns its actions. */
internal class NovexRootHeaderHost {
    private val configurations = mutableMapOf<NovexRootSpace, NovexRootHeaderConfig>()
    private var revision by mutableIntStateOf(0)

    fun register(space: NovexRootSpace, config: NovexRootHeaderConfig) {
        val previous = configurations[space]
        configurations[space] = config
        if (
            previous?.searching != config.searching ||
            previous?.searchDescription != config.searchDescription ||
            previous?.createItems?.map { it.label } != config.createItems.map { it.label }
        ) revision++
    }

    fun current(space: NovexRootSpace): NovexRootHeaderConfig? {
        @Suppress("UNUSED_VARIABLE") val observedRevision = revision
        return configurations[space]
    }
}

internal val LocalNovexRootHeaderHost = compositionLocalOf<NovexRootHeaderHost?> { null }

@Composable
internal fun RegisterNovexRootHeader(space: NovexRootSpace, config: NovexRootHeaderConfig) {
    val host = LocalNovexRootHeaderHost.current ?: return
    SideEffect { host.register(space, config) }
}
