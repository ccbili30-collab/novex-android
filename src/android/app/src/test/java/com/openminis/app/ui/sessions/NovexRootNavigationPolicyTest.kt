package com.openminis.app.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexRootNavigationPolicyTest {
    @Test
    fun selectingAnyDestinationExpandsTheDockAndKeepsTheNewSelection() {
        val result = NovexRootNavigationState().select(NovexRootSpace.WORLDS)

        assertEquals(NovexRootSpace.WORLDS, result.selected)
        assertTrue(result.expanded)
    }

    @Test
    fun outsideTapCollapsesWithoutChangingTheSelectedDestination() {
        val result = NovexRootNavigationState(
            selected = NovexRootSpace.CHARACTERS,
            expanded = true,
        ).collapse()

        assertEquals(NovexRootSpace.CHARACTERS, result.selected)
        assertFalse(result.expanded)
    }

    @Test
    fun horizontalMovementStopsAtTheFirstAndLastDestination() {
        assertEquals(
            NovexRootSpace.CONVERSATIONS,
            NovexRootNavigationState().move(-1).selected,
        )
        assertEquals(
            NovexRootSpace.CHARACTERS,
            NovexRootNavigationState(NovexRootSpace.CHARACTERS).move(1).selected,
        )
        assertEquals(
            NovexRootSpace.WORLDS,
            NovexRootNavigationState().move(1).selected,
        )
    }

    @Test
    fun rootDockIsHiddenUntilTheInitialSetupIsComplete() {
        assertFalse(
            shouldShowNovexRootDock(
                configLoaded = false,
                hasUsableModel = false,
                homeReady = false,
            ),
        )
        assertFalse(
            shouldShowNovexRootDock(
                configLoaded = true,
                hasUsableModel = true,
                homeReady = false,
            ),
        )
        assertTrue(
            shouldShowNovexRootDock(
                configLoaded = true,
                hasUsableModel = true,
                homeReady = true,
            ),
        )
    }

    @Test
    fun configuredEmptyLibraryStillShowsAllThreeRootEntrances() {
        assertTrue(
            shouldShowNovexRootDock(
                configLoaded = true,
                hasUsableModel = true,
                homeReady = true,
            ),
        )
    }

    @Test
    fun dockDragMapsTheWholeWidthToThreeDestinations() {
        assertEquals(NovexRootSpace.CONVERSATIONS, novexRootSpaceAtOffset(-20f, 240f))
        assertEquals(NovexRootSpace.CONVERSATIONS, novexRootSpaceAtOffset(20f, 240f))
        assertEquals(NovexRootSpace.WORLDS, novexRootSpaceAtOffset(120f, 240f))
        assertEquals(NovexRootSpace.CHARACTERS, novexRootSpaceAtOffset(220f, 240f))
        assertEquals(NovexRootSpace.CHARACTERS, novexRootSpaceAtOffset(280f, 240f))
    }

    @Test
    fun dockDragUsesTheFingerEndPositionWithinTheCenteredDock() {
        assertEquals(
            NovexRootSpace.CONVERSATIONS,
            novexRootSpaceAtPageX(x = 260f, pageWidth = 900f, dockWidth = 600f),
        )
        assertEquals(
            NovexRootSpace.WORLDS,
            novexRootSpaceAtPageX(x = 450f, pageWidth = 900f, dockWidth = 600f),
        )
        assertEquals(
            NovexRootSpace.CHARACTERS,
            novexRootSpaceAtPageX(x = 680f, pageWidth = 900f, dockWidth = 600f),
        )
    }

    @Test
    fun onlyTheActualBottomDockBoundsOwnDockDrags() {
        assertTrue(isNovexRootDockHit(450f, 850f, 900f, 900f, 300f, 100f))
        assertFalse(isNovexRootDockHit(100f, 850f, 900f, 900f, 300f, 100f))
        assertFalse(isNovexRootDockHit(450f, 700f, 900f, 900f, 300f, 100f))
    }

    @Test
    fun unlockedDockDoesNotDisappearDuringHomeRecreation() {
        assertFalse(nextNovexRootDockUnlocked(wasUnlocked = false, homeReady = false))
        assertTrue(nextNovexRootDockUnlocked(wasUnlocked = false, homeReady = true))
        assertTrue(nextNovexRootDockUnlocked(wasUnlocked = true, homeReady = false))
    }
}
