package com.openminis.app.ui.sessions
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.size
import com.openminis.app.ui.noven.NovenSessionRow
import com.openminis.app.ui.noven.relativeDate
import androidx.compose.material3.ExperimentalMaterial3Api
import novex.android.data.chat.SessionRow
import novex.android.data.chat.SessionFolderRow


// FAB color — use shared theme values
// 16-category styles, relativeDate, the session row and its badge/palette
// helpers moved to ui/noven/NovenSessionRow.kt — shared with the launcher
// home surface (installNovexHomeSurface 也 host 本屏).

// Date period for section grouping (matching iOS)
internal enum class DatePeriod {
    PINNED,
    TODAY,
    EARLIER,
}

/** Map a session to the preview home's intentionally compact Today/Earlier split. */
internal fun datePeriod(timestamp: Long): DatePeriod = when (sessionHomeRecency(timestamp)) {
    SessionHomeRecency.TODAY -> DatePeriod.TODAY
    SessionHomeRecency.EARLIER -> DatePeriod.EARLIER
}

/**
 * [T-android-session-grouping] One rendered group block: a user-created group
 * plus the sessions filed into it.
 *
 * Holds session IDS, not session objects — the list differ re-evaluates this on
 * every emission, so the value must stay cheap to compare. (iOS learned the
 * same lesson as `SidebarGroup`; a `List<SessionRow>` here deep-compares
 * long message strings on every tick.)
 *
 * `ids` is EMPTY while collapsed, but [totalCount] keeps the real number so the
 * card can still say "5 chats".
 */
data class FolderGroupBlock(
    val folder: SessionFolderRow,
    val ids: List<String>,
    val totalCount: Int,
    val isCollapsed: Boolean,
    val latestUpdatedAt: Long,
    /** Newest member's title — iOS folderSectionHeader's "N chats · title" summary line. */
    val summaryTitle: String? = null,
    /** Newest member's category — tints the composed folder icon like iOS FolderComposedIcon. */
    val firstCategory: String? = null,
)

/**
 * [T-android-session-grouping] Partition sessions into group blocks + the
 * ungrouped remainder.
 *
 * Ordering rules, ported from iOS `computeGroupedSessionIDs`:
 *  - Input arrives `updated_at DESC`, so first-encounter order over the filed
 *    sessions IS the groups' activity order — no separate sort needed.
 *  - Pinned groups float above unpinned as a STABLE PARTITION, not a re-sort,
 *    so activity order survives inside each half.
 *  - **Group membership outranks pin for PLACEMENT**: a pinned session that is
 *    also filed renders inside its group, not in the Pinned bucket. Otherwise
 *    filing a pinned session looks like a no-op — the write lands but the row
 *    never moves. The pin itself is untouched: pinned members sort first inside
 *    the group and keep their pin glyph.
 *  - A `folder_id` pointing at a group we don't have renders as UNGROUPED
 *    rather than vanishing. There is no FK, so this is a normal state.
 *  - Empty groups still render — a group that disappears when its last session
 *    moves out reads as data loss.
 */
internal fun partitionByFolder(
    sessions: List<SessionRow>,
    folders: List<SessionFolderRow>,
    collapsedIds: Set<String>,
): Pair<List<FolderGroupBlock>, List<SessionRow>> {
    if (folders.isEmpty()) return emptyList<FolderGroupBlock>() to sessions

    val byId = folders.associateBy { it.id }
    val members = LinkedHashMap<String, MutableList<SessionRow>>()
    val ungrouped = mutableListOf<SessionRow>()

    for (s in sessions) {
        val fid = s.folderId
        // Presence check against the loaded map — never a DB constraint.
        if (fid != null && byId.containsKey(fid)) {
            members.getOrPut(fid) { mutableListOf() }.add(s)
        } else {
            ungrouped.add(s)
        }
    }

    // First-encounter order = activity order. Groups with no members are
    // appended afterwards so they still render.
    val ordered = members.keys.toMutableList()
    for (f in folders) if (f.id !in members) ordered.add(f.id)

    val blocks = ordered.mapNotNull { fid ->
        val folder = byId[fid] ?: return@mapNotNull null
        val m = members[fid].orEmpty()
        val collapsed = fid in collapsedIds
        // Pinned members first, stable partition — the pin is a display
        // affordance inside the group, not a reason to leave it.
        val displayOrdered = m.filter { it.pinnedAt != null } + m.filter { it.pinnedAt == null }
        FolderGroupBlock(
            folder = folder,
            ids = if (collapsed) emptyList() else displayOrdered.map { it.id },
            totalCount = m.size,
            isCollapsed = collapsed,
            // Recency order (not display order) — this means "newest activity".
            latestUpdatedAt = m.firstOrNull()?.updatedAt ?: folder.updatedAt,
            summaryTitle = m.firstOrNull()?.title,
            firstCategory = m.firstOrNull()?.category,
        )
    }

    val pinnedFirst = blocks.filter { it.folder.isPinned } + blocks.filter { !it.folder.isPinned }
    return pinnedFirst to ungrouped
}

internal fun groupSessionsByDate(sessions: List<SessionRow>): List<Pair<DatePeriod, List<SessionRow>>> {
    val pinned = sessions.filter { it.pinnedAt != null }.sortedByDescending { it.pinnedAt }
    val unpinned = sessions.filter { it.pinnedAt == null }
    val grouped = unpinned.groupBy { datePeriod(it.updatedAt) }
    val result = mutableListOf<Pair<DatePeriod, List<SessionRow>>>()
    if (pinned.isNotEmpty()) {
        result.add(DatePeriod.PINNED to pinned)
    }
    for (period in DatePeriod.entries) {
        if (period == DatePeriod.PINNED) continue
        grouped[period]?.let { result.add(period to it) }
    }
    return result
}
