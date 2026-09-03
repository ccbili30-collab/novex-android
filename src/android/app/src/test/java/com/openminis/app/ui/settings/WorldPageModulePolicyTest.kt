package com.openminis.app.ui.settings

import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ContentModuleCatalog
import com.openminis.app.data.character.ContentModuleScope
import com.openminis.app.data.character.ContentModuleTextCodec
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.ui.novex.NovexContentModuleLayout
import com.openminis.app.ui.novex.novexContentLayout
import com.openminis.app.ui.novex.novexModuleSummary
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
            ContentModuleCatalog.definitions(ContentModuleScope.WORLD).map { it.type },
        )
        assertFalse(
            ContentModuleCatalog.definitions(ContentModuleScope.WORLD)
                .any { it.displayName == "世界观概述" },
        )
    }

    @Test
    fun moduleBodyRoundTripsAsStructuredContent() {
        val original = "第一纪元\n雾港建立"
        assertEquals(original, ContentModuleTextCodec.decode(ContentModuleTextCodec.encode(original)))
    }

    @Test
    fun moduleSummarySupportsSparseAndRichContentWithoutRenderingTheWholeBody() {
        assertEquals("尚未填写内容", novexModuleSummary("  \n "))
        assertEquals(
            "雾港在第一纪元建立，随后成为沿海势力争夺的中心…",
            novexModuleSummary("雾港在第一纪元建立，随后成为沿海势力争夺的中心。\n这一行不应在列表展开。", 24),
        )
    }

    @Test
    fun everyWorldImageIsOptionalAndAvailableWhileCreatingADraft() {
        assertEquals(
            listOf(
                MediaAssetSlot.WORLD_COVER,
                MediaAssetSlot.WORLD_LOGO,
                MediaAssetSlot.WORLD_BACKGROUND,
            ),
            worldImageSlots().map { it.slot },
        )
        assertEquals(true, worldImageSlots().all { !it.required })
    }

    @Test
    fun worldModulesChooseAContentSpecificDisplayLayout() {
        assertEquals(NovexContentModuleLayout.TIMELINE, ContentModuleType.TIMELINE.novexContentLayout())
        assertEquals(NovexContentModuleLayout.TIMELINE, ContentModuleType.ERA_EVENT.novexContentLayout())
        assertEquals(NovexContentModuleLayout.MAP, ContentModuleType.MAP.novexContentLayout())
        assertEquals(NovexContentModuleLayout.GALLERY, ContentModuleType.REGION.novexContentLayout())
        assertEquals(NovexContentModuleLayout.GALLERY, ContentModuleType.FACTION.novexContentLayout())
        assertEquals(NovexContentModuleLayout.GALLERY, ContentModuleType.RACE.novexContentLayout())
        assertEquals(NovexContentModuleLayout.ARTICLE, ContentModuleType.CUSTOM.novexContentLayout())
    }
}
