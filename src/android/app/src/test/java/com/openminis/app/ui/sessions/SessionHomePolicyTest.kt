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
    fun filtersSeparateRecentWorldConversationsGeneralConversationsAndCreation() {
        val sessions = listOf(
            session("world-new", worldId = "world-a", updatedAt = 30),
            session("general", updatedAt = 20),
            session("world-old", worldSnapshotJson = "{}", updatedAt = 10),
        )

        assertEquals(listOf("world-new", "world-old"), sessions.forHomeFilter(SessionHomeFilter.RECENT).map { it.id })
        assertEquals(listOf("general"), sessions.forHomeFilter(SessionHomeFilter.GENERAL).map { it.id })
        assertEquals(emptyList<String>(), sessions.forHomeFilter(SessionHomeFilter.CREATION).map { it.id })
    }

    @Test
    fun recencyUsesOnlyTodayAndEarlierAtTheLocalDayBoundary() {
        val now = localTime(2026, Calendar.SEPTEMBER, 2, 9, 30)
        val today = localTime(2026, Calendar.SEPTEMBER, 2, 0, 1)
        val yesterday = localTime(2026, Calendar.SEPTEMBER, 1, 23, 59)

        assertEquals(SessionHomeRecency.TODAY, sessionHomeRecency(today, now, timeZone))
        assertEquals(SessionHomeRecency.EARLIER, sessionHomeRecency(yesterday, now, timeZone))
    }

    @Test
    fun rootControlsAreInteractiveBeforeConversationContentFinishesLoading() {
        val cold = sessionHomeAvailability(
            sessionsLoaded = false,
            worldNamesLoaded = false,
        )
        val partial = sessionHomeAvailability(
            sessionsLoaded = true,
            worldNamesLoaded = false,
        )
        val loaded = sessionHomeAvailability(
            sessionsLoaded = true,
            worldNamesLoaded = true,
        )

        assertTrue(cold.controlsInteractive)
        assertFalse(cold.contentReady)
        assertTrue(partial.controlsInteractive)
        assertFalse(partial.contentReady)
        assertTrue(loaded.controlsInteractive)
        assertTrue(loaded.contentReady)
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
        updatedAt: Long = 0,
    ) = ChatSessionEntity(
        id = id,
        modelId = "model",
        createdAt = updatedAt,
        updatedAt = updatedAt,
        worldId = worldId,
        worldSnapshotJson = worldSnapshotJson,
    )
}
