package com.openminis.app.startup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NovexStartupCoordinatorTest {
    @Test
    fun repeatedMinimumRequestsAcrossActivityRecreationInitializeOnce() = runTest {
        var minimumStarts = 0
        val coordinator = NovexStartupCoordinator(
            scope = backgroundScope,
            safeMode = false,
            initializeMinimum = { minimumStarts++ },
            initializeRuntime = {},
        )

        coordinator.startMinimum()
        coordinator.startMinimum()
        coordinator.awaitMinimum()

        assertEquals(1, minimumStarts)
    }

    @Test
    fun minimumDataBecomesUsableWithoutStartingTheLegacyRuntime() = runTest {
        val minimumGate = CompletableDeferred<Unit>()
        var runtimeStarts = 0
        val coordinator = NovexStartupCoordinator(
            scope = backgroundScope,
            safeMode = false,
            initializeMinimum = { minimumGate.await() },
            initializeRuntime = { runtimeStarts++ },
        )

        coordinator.startMinimum()
        assertEquals(NovexStartupPhase.PREPARING, coordinator.state.value.phase)
        minimumGate.complete(Unit)
        runCurrent()

        assertEquals(NovexStartupPhase.MINIMUM_READY, coordinator.state.value.phase)
        assertEquals(0, runtimeStarts)
    }

    @Test
    fun concurrentRuntimeRequestsShareOneInitialization() = runTest {
        val runtimeGate = CompletableDeferred<Unit>()
        var runtimeStarts = 0
        val coordinator = NovexStartupCoordinator(
            scope = backgroundScope,
            safeMode = false,
            initializeMinimum = {},
            initializeRuntime = {
                runtimeStarts++
                runtimeGate.await()
            },
        )

        coordinator.startMinimum()
        runCurrent()
        val first = async { coordinator.ensureRuntime() }
        val second = async { coordinator.ensureRuntime() }
        runCurrent()
        assertEquals(1, runtimeStarts)
        runtimeGate.complete(Unit)
        runCurrent()

        assertTrue(first.await().isSuccess)
        assertTrue(second.await().isSuccess)
        assertEquals(NovexStartupPhase.RUNTIME_READY, coordinator.state.value.phase)
    }

    @Test
    fun runtimeFailureKeepsMinimumDataAvailableAndReturnsTheSameError() = runTest {
        val coordinator = NovexStartupCoordinator(
            scope = backgroundScope,
            safeMode = false,
            initializeMinimum = {},
            initializeRuntime = { error("工具运行时无法初始化") },
        )

        coordinator.startMinimum()
        runCurrent()
        val result = coordinator.ensureRuntime()

        assertTrue(result.isFailure)
        assertEquals(NovexStartupPhase.FAILED, coordinator.state.value.phase)
        assertEquals(NovexStartupStage.RUNTIME, coordinator.state.value.failure?.stage)
        assertTrue(coordinator.state.value.minimumAvailable)
    }

    @Test
    fun minimumFailureIsVisibleAndDoesNotStartTheRuntime() = runTest {
        var runtimeStarts = 0
        val coordinator = NovexStartupCoordinator(
            scope = backgroundScope,
            safeMode = false,
            initializeMinimum = { error("数据库无法打开") },
            initializeRuntime = { runtimeStarts++ },
        )

        coordinator.startMinimum()
        runCurrent()
        val result = coordinator.ensureRuntime()

        assertTrue(result.isFailure)
        assertEquals(NovexStartupStage.MINIMUM, coordinator.state.value.failure?.stage)
        assertFalse(coordinator.state.value.minimumAvailable)
        assertEquals(0, runtimeStarts)
    }

    @Test
    fun safeModeIsIndependentAndNeverStartsEitherInitializer() = runTest {
        var minimumStarts = 0
        var runtimeStarts = 0
        val coordinator = NovexStartupCoordinator(
            scope = backgroundScope,
            safeMode = true,
            initializeMinimum = { minimumStarts++ },
            initializeRuntime = { runtimeStarts++ },
        )

        coordinator.startMinimum()
        runCurrent()

        assertTrue(coordinator.safeMode)
        assertEquals(NovexStartupPhase.PREPARING, coordinator.state.value.phase)
        assertEquals(0, minimumStarts)
        assertEquals(0, runtimeStarts)
    }
}
