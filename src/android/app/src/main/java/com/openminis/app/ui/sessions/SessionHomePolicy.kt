package com.openminis.app.ui.sessions

import com.openminis.app.data.db.ChatSessionEntity
import java.util.Calendar
import java.util.TimeZone

internal enum class SessionHomeFilter {
    ALL,
    WORLD,
    GENERAL,
}

internal enum class SessionHomeRecency {
    TODAY,
    EARLIER,
}

internal fun ChatSessionEntity.isWorldConversation(): Boolean =
    !worldId.isNullOrBlank() || !worldSnapshotJson.isNullOrBlank()

internal fun List<ChatSessionEntity>.forHomeFilter(filter: SessionHomeFilter): List<ChatSessionEntity> =
    when (filter) {
        SessionHomeFilter.ALL -> this
        SessionHomeFilter.WORLD -> filter(ChatSessionEntity::isWorldConversation)
        SessionHomeFilter.GENERAL -> filterNot(ChatSessionEntity::isWorldConversation)
    }

internal fun sessionHomeRecency(
    timestamp: Long,
    now: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault(),
): SessionHomeRecency {
    val sessionDay = Calendar.getInstance(timeZone).apply { timeInMillis = timestamp }
    val today = Calendar.getInstance(timeZone).apply { timeInMillis = now }
    return if (
        sessionDay.get(Calendar.ERA) == today.get(Calendar.ERA) &&
        sessionDay.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        sessionDay.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    ) {
        SessionHomeRecency.TODAY
    } else {
        SessionHomeRecency.EARLIER
    }
}
