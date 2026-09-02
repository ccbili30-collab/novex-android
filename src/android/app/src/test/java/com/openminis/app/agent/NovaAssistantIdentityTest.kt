package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaAssistantIdentityTest {
    @Test
    fun `default general assistant is Nova while app remains Novex`() {
        assertEquals("Nova", SoulMetadata.DEFAULT.name)
        assertTrue(SoulStore.DEFAULT_CONTENT.contains("name: \"Nova\""))
    }

    @Test
    fun `legacy default assistant names migrate to Nova`() {
        assertTrue(migrateLegacyAssistantName("name: \"Novex\"").contains("name: \"Nova\""))
        assertTrue(migrateLegacyAssistantName("name: \"Minis\"").contains("name: \"Nova\""))
        assertEquals("name: \"自定义角色\"", migrateLegacyAssistantName("name: \"自定义角色\""))
    }
}
