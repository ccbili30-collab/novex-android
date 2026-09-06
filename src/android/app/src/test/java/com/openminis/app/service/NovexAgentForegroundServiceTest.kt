package com.openminis.app.service

import android.app.Application
import android.app.Service
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises the real service against a host-side Android runtime, not a device. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NovexAgentForegroundServiceTest {
    @Test fun serviceIsAlreadyForegroundBeforeAnyStartCommand() {
        val controller = Robolectric.buildService(AgentForegroundService::class.java).create()
        try {
            val notification = shadowOf(controller.get()).lastForegroundNotification
            assertNotNull("Service creation must satisfy foreground registration before optional runtime work", notification)
            assertNotNull(notification.smallIcon)
        } finally {
            controller.destroy()
        }
    }

    @Test fun firstCommandCanStopWithoutLeavingAnUnfulfilledForegroundStart() {
        val controller = Robolectric.buildService(AgentForegroundService::class.java).create()
        try {
            val service = controller.get()
            val result = service.onStartCommand(Intent("com.openminis.app.STOP_AGENT_SERVICE"), 0, 1)
            assertEquals(Service.START_NOT_STICKY, result)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertNotNull("Stop must not bypass the initial foreground registration", shadowOf(service).lastForegroundNotification)
        } finally {
            controller.destroy()
        }
    }
}
