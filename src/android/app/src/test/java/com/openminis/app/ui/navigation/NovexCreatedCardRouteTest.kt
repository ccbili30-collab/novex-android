package com.openminis.app.ui.navigation

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NovexCreatedCardRouteTest {
    @Test fun createdVersionOpensTheExistingDetailAndKeepsItsExactVersion() {
        val route = Routes.characterDetail("root-id", "parallel-version")
        val uri = Uri.parse(route)
        assertEquals("characters/card/root-id", uri.path)
        assertEquals("parallel-version", uri.getQueryParameter("versionId"))
        assertFalse(route.contains("edit"))
    }
    @Test fun existingLibraryLinksRemainValidWithoutAnExplicitVersion() {
        assertEquals("characters/card/root-id", Routes.characterDetail("root-id"))
    }
}
