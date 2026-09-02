package com.openminis.app.ui.settings

import com.openminis.app.data.character.ContentModuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WorldPageModulePolicyTest {
    @Test
    fun overviewIsFixedAndAllOtherWorldSectionsAreExplicitModules() {
        assertEquals(
            listOf(
                ContentModuleType.TIMELINE,
                ContentModuleType.ERA_EVENT,
                ContentModuleType.MAP,
                ContentModuleType.REGION,
                ContentModuleType.FACTION,
                ContentModuleType.RACE,
                ContentModuleType.CUSTOM,
            ),
            WORLD_PAGE_MODULE_TYPES,
        )
        assertFalse(WORLD_PAGE_MODULE_TYPES.any { worldModuleDisplayName(it) == "世界观概述" })
    }

    @Test
    fun moduleBodyRoundTripsAsStructuredContent() {
        val original = "第一纪元\n雾港建立"
        assertEquals(original, decodeWorldModuleText(encodeWorldModuleText(original)))
    }

    @Test
    fun moduleSummarySupportsSparseAndRichContentWithoutRenderingTheWholeBody() {
        assertEquals("尚未填写内容", moduleListSummary("  \n "))
        assertEquals(
            "雾港在第一纪元建立，随后成为沿海势力争夺的中心…",
            moduleListSummary("雾港在第一纪元建立，随后成为沿海势力争夺的中心。\n这一行不应在列表展开。", 24),
        )
    }
}
