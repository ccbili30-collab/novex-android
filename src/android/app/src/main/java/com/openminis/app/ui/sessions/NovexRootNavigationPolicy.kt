package com.openminis.app.ui.sessions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class NovexRootSpace {
    CONVERSATIONS,
    WORLDS,
    CHARACTERS,
}

internal enum class NovexLibraryBackAction {
    CLOSE_SEARCH,
    LEAVE_ROOT,
}

internal fun novexLibraryBackAction(searching: Boolean): NovexLibraryBackAction =
    if (searching) NovexLibraryBackAction.CLOSE_SEARCH else NovexLibraryBackAction.LEAVE_ROOT

internal class NovexLibrarySearchState(
    private val scope: CoroutineScope,
    private val debounceMs: Long = 300L,
) {
    private val mutableInput = MutableStateFlow("")
    private val mutableApplied = MutableStateFlow("")
    private var applyJob: Job? = null

    val input = mutableInput.asStateFlow()
    val applied = mutableApplied.asStateFlow()

    fun update(value: String) {
        mutableInput.value = value
        applyJob?.cancel()
        applyJob = scope.launch {
            delay(debounceMs)
            mutableApplied.value = value
        }
    }

    fun clear() {
        applyJob?.cancel()
        applyJob = null
        mutableInput.value = ""
        mutableApplied.value = ""
    }
}

internal fun shouldShowNovexRootDock(
    configLoaded: Boolean,
    hasUsableModel: Boolean,
    homeReady: Boolean,
    hasRootContent: Boolean,
): Boolean = configLoaded && hasUsableModel && homeReady && hasRootContent

internal fun novexRootSpaceAtOffset(offset: Float, width: Float): NovexRootSpace {
    if (width <= 0f) return NovexRootSpace.CONVERSATIONS
    val fraction = (offset / width).coerceIn(0f, 0.9999f)
    return NovexRootSpace.entries[(fraction * NovexRootSpace.entries.size).toInt()]
}

internal fun novexRootSpaceAtPageX(
    x: Float,
    pageWidth: Float,
    dockWidth: Float,
): NovexRootSpace {
    val dockLeft = (pageWidth - dockWidth) / 2f
    return novexRootSpaceAtOffset(x - dockLeft, dockWidth)
}

internal fun isNovexRootDockHit(
    x: Float,
    y: Float,
    pageWidth: Float,
    pageHeight: Float,
    dockWidth: Float,
    dockHeight: Float,
): Boolean {
    val left = (pageWidth - dockWidth) / 2f
    val right = left + dockWidth
    return x in left..right && y >= pageHeight - dockHeight
}

internal fun nextNovexRootDockVisibility(reportedVisible: Boolean): Boolean = reportedVisible

internal data class NovexRootNavigationState(
    val selected: NovexRootSpace = NovexRootSpace.CONVERSATIONS,
    val expanded: Boolean = false,
) {
    fun select(destination: NovexRootSpace): NovexRootNavigationState = copy(
        selected = destination,
        expanded = true,
    )

    fun collapse(): NovexRootNavigationState = copy(expanded = false)

    fun move(delta: Int): NovexRootNavigationState {
        val destinations = NovexRootSpace.entries
        val index = (destinations.indexOf(selected) + delta).coerceIn(destinations.indices)
        return select(destinations[index])
    }
}
