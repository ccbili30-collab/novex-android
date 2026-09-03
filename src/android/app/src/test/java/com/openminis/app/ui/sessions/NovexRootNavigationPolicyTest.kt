package com.openminis.app.ui.sessions

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NovexRootNavigationPolicyTest {
    @Test
    fun librarySearchKeepsEveryRapidKeystrokeButAppliesOnlyTheSettledQuery() = runTest {
        val search = NovexLibrarySearchState(backgroundScope, debounceMs = 300L)

        "abcdefghijklmnopqrstuvwxyz".forEach { search.update(search.input.value + it) }

        assertEquals("abcdefghijklmnopqrstuvwxyz", search.input.value)
        assertEquals("", search.applied.value)
        advanceTimeBy(299L)
        runCurrent()
        assertEquals("", search.applied.value)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals("abcdefghijklmnopqrstuvwxyz", search.applied.value)
    }

    @Test
    fun closingSearchCancelsAFilterThatHasNotRunYet() = runTest {
        val search = NovexLibrarySearchState(backgroundScope, debounceMs = 300L)

        search.update("不会执行")
        advanceTimeBy(299L)
        search.clear()
        advanceTimeBy(1L)
        runCurrent()

        assertEquals("", search.input.value)
        assertEquals("", search.applied.value)
    }

    @Test
    fun libraryRootConsumesBackToCloseSearchBeforeLeavingTheApplication() {
        assertEquals(
            NovexLibraryBackAction.CLOSE_SEARCH,
            novexLibraryBackAction(searching = true),
        )
        assertEquals(
            NovexLibraryBackAction.LEAVE_ROOT,
            novexLibraryBackAction(searching = false),
        )
    }

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
                hasRootContent = false,
            ),
        )
        assertFalse(
            shouldShowNovexRootDock(
                configLoaded = true,
                hasUsableModel = true,
                homeReady = false,
                hasRootContent = true,
            ),
        )
        assertTrue(
            shouldShowNovexRootDock(
                configLoaded = true,
                hasUsableModel = true,
                homeReady = true,
                hasRootContent = true,
            ),
        )
    }

    @Test
    fun configuredEmptyOnboardingKeepsItsOriginalBottomLinksUnobstructed() {
        assertFalse(
            shouldShowNovexRootDock(
                configLoaded = true,
                hasUsableModel = true,
                homeReady = true,
                hasRootContent = false,
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
    fun returningToAnEmptyOnboardingHidesAPreviouslyUnlockedDock() {
        assertFalse(nextNovexRootDockVisibility(reportedVisible = false))
        assertTrue(nextNovexRootDockVisibility(reportedVisible = true))
    }
}
