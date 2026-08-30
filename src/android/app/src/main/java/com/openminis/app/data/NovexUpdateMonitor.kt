package com.openminis.app.data

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Process-wide, non-blocking update state shared by cold-start and the home toolbar. */
object NovexUpdateMonitor {
    private val coldCheckStarted = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _available = MutableStateFlow<UpdateChecker.CheckResult.UpdateAvailable?>(null)
    val available: StateFlow<UpdateChecker.CheckResult.UpdateAvailable?> = _available.asStateFlow()

    fun checkOnceOnColdStart() {
        if (!coldCheckStarted.compareAndSet(false, true)) return
        scope.launch { refresh() }
    }

    suspend fun refresh(): UpdateChecker.CheckResult {
        val result = UpdateChecker.check()
        _available.value = result as? UpdateChecker.CheckResult.UpdateAvailable
        return result
    }

    fun clearAvailable() {
        _available.value = null
    }
}
