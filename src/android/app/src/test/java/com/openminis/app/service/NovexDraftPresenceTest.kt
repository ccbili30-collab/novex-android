package com.openminis.app.service

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NovexDraftPresenceTest {
    @Test fun openingAnEmptyDraftDoesNotRequestAnUnneededForegroundService() {
        val app = RuntimeEnvironment.getApplication()
        val host = object : android.content.ContextWrapper(app) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getResources(): android.content.res.Resources = object : android.content.res.Resources(app.assets, app.resources.displayMetrics, app.resources.configuration) {
                override fun getText(id: Int): CharSequence = "在对话中"
            }
        }
        SessionActivityTracker.init(host)
        SessionActivityTracker.clearPresence()
        while (shadowOf(app).nextStartedService != null) { }
        try {
            repeat(3) {
                SessionActivityTracker.setPresent("__new__draft-$it")
                assertNull("Opening a blank conversation must not start a foreground task", shadowOf(app).nextStartedService)
                SessionActivityTracker.setAbsent("__new__draft-$it")
            }
        } finally { SessionActivityTracker.clearPresence() }
    }
}
