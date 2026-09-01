package com.openminis.app.deeplink

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionDeepLinkInstrumentedTest {
    @Test
    fun notificationSessionLinkKeepsItsExactTarget() {
        assertEquals(
            DeepLinkAction.OpenSession("session-42"),
            DeepLinkHandler.parse(Uri.parse("minis://session/session-42")),
        )
    }
}
