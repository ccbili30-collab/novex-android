package com.openminis.app.novex.domain

import android.app.Application
import com.openminis.app.provider.ToolJsonRepair
import com.openminis.app.tools.NovexManagementTools
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NovexEmptyToolArgumentsTest {
    @Test fun `valid empty inspection arguments never produce a truncation warning`() {
        val args = JSONObject()
        val tags = ToolJsonRepair.repair(NovexManagementTools.INSPECT, args, "{}", NovexManagementTools.definitions())
        assertTrue(tags.isEmpty())
        assertEquals(0, args.length())
    }
    @Test fun `actually incomplete arguments keep the repair warning`() {
        val args = JSONObject()
        val tags = ToolJsonRepair.repair(NovexManagementTools.INSPECT, args,
            """{"subject_kind":"world""", NovexManagementTools.definitions())
        assertTrue(tags.any { it.startsWith("truncation+") })
        assertEquals("world", args.getString("subject_kind"))
    }
}
