package com.openminis.app.ui.sessions
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import novex.android.ui.DropdownMenu
import novex.android.ui.DropdownMenuItem
import com.openminis.app.ui.noven.NovenSessionRow
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import novex.android.data.chat.SessionRow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionItemContent(
    session: SessionRow,
    isSelecting: Boolean,
    selectedIds: Set<String>,
    onSessionClick: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    // [T-android-sessionlist-longpress-select] Context-menu Select: enters
    // selection mode with this row selected (distinct from onToggleSelect,
    // which only flips set membership while ALREADY selecting).
    onEnterSelect: (String) -> Unit,
    onPinToggle: (String) -> Unit,
    onEditRequest: (SessionRow) -> Unit,
    onExportRequest: (SessionRow, String) -> Unit,
    onRegenerateTitle: (String) -> Unit,
    /** 轻量启动面（无 provider 运行时）隐藏「重新生成标题」菜单项。 */
    canRegenerateTitle: Boolean = true,
    onDuplicate: (String) -> Unit,
    onDeleteRequest: (String) -> Unit,
    /** [T-android-session-grouping] Opens the group picker for this session. */
    onMoveToGroup: (String) -> Unit,
    /**
     * [T-android-session-grouping] True only when this session belongs to a group
     * that ACTUALLY EXISTS locally — not merely `folderId != null`.
     *
     * A dangling folder_id renders as ungrouped (see partitionByFolder), so
     * deciding the wording from the raw id alone made the row and its menu
     * disagree: the session sat in the date buckets while its menu offered
     * "更换分组". The caller resolves membership the same way the list does.
     */
    isFiled: Boolean,
    isRegenerating: Boolean = false,
    searchQuery: String = "",
    searchSnippet: String? = null,
    /**
     * [T-android-folder-card-ios-parity] Overrides the row's own surface
     * background. Folder members pass Transparent so the group container's
     * welded fill shows through; null keeps the default surface.
     */
    rowBackground: Color? = null,
    /** home-v2：解析好的卡片标签与 primary 缩略图。 */
    cardFace: com.openminis.app.ui.noven.SessionCardFace? = null,
    cardReader: novex.android.CardSessionModel? = null,
) {
    if (isSelecting) {
        val isSelected = session.id in selectedIds
        NovenSessionRow(
            session = session,
            onClick = { onToggleSelect(session.id) },
            onLongClick = null,
            searchQuery = searchQuery,
            searchSnippet = searchSnippet,
            rowBackground = rowBackground,
            cardFace = cardFace,
            cardReader = cardReader,
            leadingIcon = {
                Icon(
                    imageVector = if (isSelected) novex.android.ui.NovexIcons.CheckCircle else novex.android.ui.NovexIcons.Circle,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            },
        )
    } else {
        var showContextMenu by remember { mutableStateOf(false) }
        var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
        // [T-android-menu-press-side] Which HALF of the row the finger was on.
        // Pressing on the right used to left-anchor the menu at the finger,
        // overflow the window, and get clamped left — so the popup (and its
        // top-LEFT-origin scale animation) visually appeared to the left of
        // the finger. Right-half presses now anchor the menu's RIGHT edge at
        // the press point with a matching top-right animation origin, so the
        // menu hangs off the finger naturally on both sides.
        var menuAlignEnd by remember { mutableStateOf(false) }
        var rowWidthPx by remember { mutableFloatStateOf(0f) }
        val density = LocalDensity.current
        val isPinned = session.pinnedAt != null

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowWidthPx = it.width.toFloat() },
        ) {
            NovenSessionRow(
                session = session,
                onClick = { onSessionClick(session.id) },
                searchQuery = searchQuery,
                searchSnippet = searchSnippet,
                rowBackground = rowBackground,
                cardFace = cardFace,
                cardReader = cardReader,
                onLongClick = { offsetPx ->
                    pressOffset = with(density) {
                        DpOffset(offsetPx.x.toDp(), offsetPx.y.toDp())
                    }
                    menuAlignEnd = rowWidthPx > 0f && offsetPx.x > rowWidthPx / 2f
                    showContextMenu = true
                },
                // [T-android-sessionrow-overflow] 可见的 ⋮ 入口（用户决策 2026-09-14：
                // "做成三点菜单，点开就是置顶或者删除"）。置顶此前只藏在长按菜单里，
                // 没有可见入口，测试者以为功能没了。长按完整菜单保留不动。
                trailing = {
                    var rowMenuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { rowMenuOpen = true },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                novex.android.ui.NovexIcons.MoreVert,
                                contentDescription = stringResource(R.string.sessionlist_row_actions),
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        MinisMenu(
                            expanded = rowMenuOpen,
                            onDismissRequest = { rowMenuOpen = false },
                            alignEnd = true,
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (isPinned) R.string.sessionlist_unpin else R.string.sessionlist_pin)) },
                                onClick = { rowMenuOpen = false; onPinToggle(session.id) },
                                leadingIcon = {
                                    Icon(
                                        if (isPinned) novex.android.ui.NovexIcons.Close else novex.android.ui.NovexIcons.PushPin,
                                        contentDescription = null,
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                onClick = { rowMenuOpen = false; onDeleteRequest(session.id) },
                                leadingIcon = {
                                    Icon(
                                        novex.android.ui.NovexIcons.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                            )
                        }
                    }
                },
            )
            // Loading overlay when regenerating title
            if (isRegenerating) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            stringResource(R.string.sessionlist_regenerating_title),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            // Invisible zero-size anchor at the press position — DropdownMenu
            // will open from here so it follows the touch point.
            Box(
                modifier = Modifier
                    .offset(x = pressOffset.x, y = pressOffset.y)
                    .size(1.dp),
            ) {
                MinisMenu(
                    expanded = showContextMenu,
                    onDismissRequest = { showContextMenu = false },
                    alignEnd = menuAlignEnd,
                ) {
                // Pin / Unpin
                DropdownMenuItem(
                    text = { Text(stringResource(if (isPinned) R.string.sessionlist_unpin else R.string.sessionlist_pin)) },
                    onClick = {
                        showContextMenu = false
                        onPinToggle(session.id)
                    },
                    leadingIcon = {
                        Icon(
                            if (isPinned) novex.android.ui.NovexIcons.Close else novex.android.ui.NovexIcons.PushPin,
                            contentDescription = null,
                        )
                    },
                )
                // Export submenu (JSON / Plain Text)
                var showExportSub by remember { mutableStateOf(false) }
                DropdownMenuItem(
                    text = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.sessionlist_export))
                            Icon(novex.android.ui.NovexIcons.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    },
                    onClick = { showExportSub = !showExportSub },
                    leadingIcon = {
                        Icon(novex.android.ui.NovexIcons.Share, contentDescription = null)
                    },
                )
                if (showExportSub) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessionlist_export_json), modifier = Modifier.padding(start = 24.dp)) },
                        onClick = {
                            showContextMenu = false
                            onExportRequest(session, "json")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessionlist_export_plain), modifier = Modifier.padding(start = 24.dp)) },
                        onClick = {
                            showContextMenu = false
                            onExportRequest(session, "text")
                        },
                    )
                }
                // Edit Title & Category
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_edit_title_category)) },
                    onClick = {
                        showContextMenu = false
                        onEditRequest(session)
                    },
                    leadingIcon = {
                        Icon(novex.android.ui.NovexIcons.Edit, contentDescription = null)
                    },
                )
                // Regenerate Title —— 需要 provider 运行时（sub model/主模型
                // 调 LLM）。轻量启动面隐藏入口而不是放一个点了没反应的死按钮。
                if (canRegenerateTitle) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessionlist_regenerate_title)) },
                        onClick = {
                            showContextMenu = false
                            onRegenerateTitle(session.id)
                        },
                        leadingIcon = {
                            Icon(novex.android.ui.NovexIcons.Refresh, contentDescription = null)
                        },
                    )
                }
                // Duplicate
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_duplicate)) },
                    onClick = {
                        showContextMenu = false
                        onDuplicate(session.id)
                    },
                    leadingIcon = {
                        Icon(novex.android.ui.NovexIcons.ContentCopy, contentDescription = null)
                    },
                )
                // Move to / Change Group
                // [T-android-session-grouping] The wording follows membership:
                // a session already in a group is being MOVED BETWEEN groups,
                // not filed for the first time. Same idiom as Pin/Unpin.
                //
                // A single item opening a sheet, deliberately NOT an inline
                // submenu of group names — the menu body would then cost
                // O(groups) to compose on every open, and the group data would
                // have to be captured into the menu closure.
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (isFiled) R.string.group_change
                                else R.string.group_move_to,
                            ),
                        )
                    },
                    onClick = {
                        showContextMenu = false
                        onMoveToGroup(session.id)
                    },
                    leadingIcon = {
                        Icon(
                            if (isFiled) novex.android.ui.NovexIcons.DriveFileMove
                            else novex.android.ui.NovexIcons.Folder,
                            contentDescription = null,
                        )
                    },
                )
                // Select
                // [T-android-sessionlist-longpress-select] Must ENTER
                // selection mode, not just toggle the hidden set —
                // onToggleSelect alone never set isSelecting, so nothing
                // visibly happened and the id sat invisibly pre-selected.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_select_action)) },
                    onClick = {
                        showContextMenu = false
                        onEnterSelect(session.id)
                    },
                    leadingIcon = {
                        Icon(novex.android.ui.NovexIcons.ChecklistRtl, contentDescription = null)
                    },
                )
                MinisMenuDivider()
                // Delete
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showContextMenu = false
                        onDeleteRequest(session.id)
                    },
                    leadingIcon = {
                        Icon(
                            novex.android.ui.NovexIcons.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                )
                }
            }
        }
    }
}

/**
 * [T-android-session-grouping] The card that heads a group's block.
 *
 * Tapping it collapses/expands (accordion — opening one closes the others).
 * Long-pressing opens the management menu: pin, rename, dissolve. Dissolve is
 * deliberately NOT tinted destructive — it moves sessions back to the main list
 * and deletes nothing, and tinting it red would train the eye to read it as the
 * dangerous item.
 */
