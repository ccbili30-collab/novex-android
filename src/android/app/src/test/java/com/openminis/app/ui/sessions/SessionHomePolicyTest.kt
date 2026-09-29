package com.openminis.app.ui.sessions

import com.openminis.app.data.db.ChatSessionEntity
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHomePolicyTest {
    private val timeZone = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun normalizedAndLegacyWorldSessionsAreBothClassifiedAsWorldConversations() {
        assertTrue(session("normalized", worldId = "world-a").isWorldConversation())
        assertTrue(session("legacy", worldSnapshotJson = "{\"id\":\"world-b\"}").isWorldConversation())
        assertFalse(session("general").isWorldConversation())
    }

    @Test
    fun recencyUsesOnlyTodayAndEarlierAtTheLocalDayBoundary() {
        val now = localTime(2026, Calendar.SEPTEMBER, 2, 9, 30)
        val today = localTime(2026, Calendar.SEPTEMBER, 2, 0, 1)
        val yesterday = localTime(2026, Calendar.SEPTEMBER, 1, 23, 59)

        assertEquals(SessionHomeRecency.TODAY, sessionHomeRecency(today, now, timeZone))
        assertEquals(SessionHomeRecency.EARLIER, sessionHomeRecency(yesterday, now, timeZone))
    }

    private fun localTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(timeZone).apply {
            clear()
            set(year, month, day, hour, minute)
        }.timeInMillis

    private fun session(
        id: String,
        worldId: String? = null,
        worldSnapshotJson: String? = null,
        characterVersionId: String? = null,
        updatedAt: Long = 0,
    ) = ChatSessionEntity(
        id = id,
        modelId = "model",
        createdAt = updatedAt,
        updatedAt = updatedAt,
        worldId = worldId,
        worldSnapshotJson = worldSnapshotJson,
        characterVersionId = characterVersionId,
    )
}
