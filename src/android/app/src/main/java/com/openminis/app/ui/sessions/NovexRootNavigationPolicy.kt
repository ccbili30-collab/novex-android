package com.openminis.app.ui.sessions

internal enum class NovexRootSpace {
    CONVERSATIONS,
    WORLDS,
    CHARACTERS,
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
