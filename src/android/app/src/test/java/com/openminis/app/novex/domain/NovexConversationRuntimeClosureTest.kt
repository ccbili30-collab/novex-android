package com.openminis.app.novex.domain

import android.app.Application
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NovexConversationRuntimeClosureTest {
    @Test fun `opening a deleting or deleted session cannot start another runtime and failure permits reopening`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val id = "closure-${java.util.UUID.randomUUID()}"
        try {
            val old = Job()
            ChatViewModelStore.registerRuntime(id, old)
            ChatViewModelStore.stopAndJoin(id)
            assertTrue(old.isCompleted)
            ChatViewModelStore.ownerFor(id)
            val late = Job()
            ChatViewModelStore.registerRuntime(id, late)
            assertTrue(late.isCancelled)
            ChatViewModelStore.finishDeletion(id, false)
            val reopened = Job()
            ChatViewModelStore.registerRuntime(id, reopened)
            assertTrue(reopened.isActive)
            ChatViewModelStore.stopAndJoin(id)
            ChatViewModelStore.finishDeletion(id, true)
            val afterDeletion = Job()
            ChatViewModelStore.registerRuntime(id, afterDeletion)
            assertTrue(afterDeletion.isCancelled)
        } finally { Dispatchers.resetMain() }
    }
}
