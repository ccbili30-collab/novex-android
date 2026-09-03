package com.openminis.app.startup

import org.junit.Assert.assertEquals
import org.junit.Test

class NovexLaunchPolicyTest {
    @Test
    fun launcherWaitsUntilMinimumDataIsAvailable() {
        assertEquals(
            NovexLaunchDestination.WAIT,
            novexLaunchDestination(
                state = NovexStartupState(),
                safeMode = false,
                plainLauncherStart = true,
            ),
        )
    }

    @Test
    fun plainLauncherEntersLightweightHomeAfterMinimumData() {
        assertEquals(
            NovexLaunchDestination.HOME,
            novexLaunchDestination(
                state = NovexStartupState(
                    phase = NovexStartupPhase.MINIMUM_READY,
                    minimumAvailable = true,
                ),
                safeMode = false,
                plainLauncherStart = true,
            ),
        )
    }

    @Test
    fun deepLinksAndSafeModeEnterTheLegacyRecoveryPath() {
        val ready = NovexStartupState(
            phase = NovexStartupPhase.MINIMUM_READY,
            minimumAvailable = true,
        )
        assertEquals(
            NovexLaunchDestination.LEGACY,
            novexLaunchDestination(ready, safeMode = false, plainLauncherStart = false),
        )
        assertEquals(
            NovexLaunchDestination.LEGACY,
            novexLaunchDestination(NovexStartupState(), safeMode = true, plainLauncherStart = true),
        )
    }

    @Test
    fun initializationFailureUsesTheVisibleRecoveryPath() {
        assertEquals(
            NovexLaunchDestination.LEGACY,
            novexLaunchDestination(
                state = NovexStartupState(
                    phase = NovexStartupPhase.FAILED,
                    failure = NovexStartupFailure(NovexStartupStage.MINIMUM, "数据库无法打开"),
                ),
                safeMode = false,
                plainLauncherStart = true,
            ),
        )
    }
}
