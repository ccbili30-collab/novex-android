package com.openminis.app.ui.settings

import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ContentModuleCatalog
import com.openminis.app.data.character.ContentModuleScope
import org.junit.Assert.assertEquals
import org.junit.Test

class CharacterPagePolicyTest {
    @Test
    fun characterVersionOffersExactlyTheConfirmedOptionalModules() {
        assertEquals(
            listOf(
                ContentModuleType.ROLE_INSTRUCTIONS,
                ContentModuleType.ROLE_PLAYER_IDENTITY,
                ContentModuleType.QUOTES,
                ContentModuleType.WORLD_EXPERIENCE,
                ContentModuleType.ATTRIBUTE_PANEL,
                ContentModuleType.EQUIPMENT,
                ContentModuleType.TALENT_SKILL,
                ContentModuleType.APPEARANCE_PERSONALITY,
                ContentModuleType.INTEREST,
                ContentModuleType.CUSTOM,
            ),
            ContentModuleCatalog.definitions(ContentModuleScope.CHARACTER_VERSION).map { it.type },
        )
    }
}
