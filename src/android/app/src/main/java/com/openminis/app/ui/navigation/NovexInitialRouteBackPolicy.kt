package com.openminis.app.ui.navigation

internal enum class NovexInitialRouteBackAction {
    POP,
    FINISH_HOST,
}

internal enum class NovexRouteEntryEdge {
    LEFT,
    RIGHT,
}

internal fun novexInitialRouteBackAction(canPop: Boolean): NovexInitialRouteBackAction =
    if (canPop) NovexInitialRouteBackAction.POP else NovexInitialRouteBackAction.FINISH_HOST

/**
 * Shared root-route back boundary used by settings, chat and future deep links.
 * A toolbar or system back request must never disappear merely because the
 * destination was launched as the navigation root.
 */
internal inline fun handleNovexInitialRouteBack(
    popBackStack: () -> Boolean,
    finishHost: () -> Unit,
) {
    if (novexInitialRouteBackAction(popBackStack()) == NovexInitialRouteBackAction.FINISH_HOST) {
        finishHost()
    }
}

internal fun novexRouteEntryEdge(route: String): NovexRouteEntryEdge =
    if (route == "settings") NovexRouteEntryEdge.LEFT else NovexRouteEntryEdge.RIGHT
