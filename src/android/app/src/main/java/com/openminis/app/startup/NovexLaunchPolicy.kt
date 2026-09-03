package com.openminis.app.startup

internal enum class NovexLaunchDestination {
    WAIT,
    HOME,
    LEGACY,
}

/** Pure routing rule kept outside the Activity so every ingress is testable. */
internal fun novexLaunchDestination(
    state: NovexStartupState,
    safeMode: Boolean,
    plainLauncherStart: Boolean,
): NovexLaunchDestination = when {
    safeMode -> NovexLaunchDestination.LEGACY
    state.phase == NovexStartupPhase.FAILED -> NovexLaunchDestination.LEGACY
    state.minimumAvailable && plainLauncherStart -> NovexLaunchDestination.HOME
    state.minimumAvailable -> NovexLaunchDestination.LEGACY
    else -> NovexLaunchDestination.WAIT
}
