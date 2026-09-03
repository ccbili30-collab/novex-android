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

internal fun novexRouteEntryEdge(route: String): NovexRouteEntryEdge =
    if (route == "settings") NovexRouteEntryEdge.LEFT else NovexRouteEntryEdge.RIGHT
