package com.openminis.app.ui.sessions

internal enum class NovexHomeBackAction {
    CLOSE_SEARCH,
    LEAVE_HOME,
}

internal fun novexHomeBackAction(searchActive: Boolean): NovexHomeBackAction =
    if (searchActive) NovexHomeBackAction.CLOSE_SEARCH else NovexHomeBackAction.LEAVE_HOME
