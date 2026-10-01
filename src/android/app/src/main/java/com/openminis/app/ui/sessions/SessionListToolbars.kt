package com.openminis.app.ui.sessions
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import kotlinx.coroutines.flow.StateFlow
import com.openminis.app.ui.components.MinisTextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

// ─── Selection Toolbar (matching iOS selectionToolbar) ──────────────────────

@Composable
internal fun SelectionToolbar(
    selectedCount: Int,
    onExport: () -> Unit,
    /** [T-android-session-grouping] Bulk-file the selection into a group. */
    onMove: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        // Export button (matching iOS)
        MinisTextButton(
            onClick = onExport,
            enabled = selectedCount > 0,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    novex.android.ui.NovexIcons.Share,
                    contentDescription = stringResource(R.string.sessionlist_export),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.sessionlist_export), fontSize = 11.sp)
            }
        }

        // Move to Group button
        MinisTextButton(
            onClick = onMove,
            enabled = selectedCount > 0,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    novex.android.ui.NovexIcons.Folder,
                    contentDescription = stringResource(R.string.group_move_action),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.group_move_action), fontSize = 11.sp)
            }
        }

        // Delete button (matching iOS)
        MinisTextButton(
            onClick = onDelete,
            enabled = selectedCount > 0,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    novex.android.ui.NovexIcons.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = if (selectedCount > 0) MaterialTheme.colorScheme.error else Color.Gray,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.delete),
                    fontSize = 11.sp,
                    color = if (selectedCount > 0) MaterialTheme.colorScheme.error else Color.Gray,
                )
            }
        }
    }
}


@Composable
internal fun SessionInlineSearchField(
    valueFlow: StateFlow<String>,
    searchingFlow: StateFlow<Boolean>,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val value by valueFlow.collectAsState()
    val searching by searchingFlow.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = 12.dp, end = 4.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_phosphor_search),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            if (value.isBlank()) {
                Text(
                    stringResource(R.string.search_chats_placeholder),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
        if (searching) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.padding(end = 11.dp).size(17.dp),
                strokeWidth = 2.dp,
            )
        } else {
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(novex.android.ui.NovexIcons.Close, contentDescription = stringResource(R.string.sessionlist_dismiss))
            }
        }
    }
}


@Composable
internal fun SectionHeader(title: String) {
    // T172: title may now be a localized string, so compare against the
    // localized "Pinned" rather than the hardcoded enum label.
    val isPinned = title == stringResource(R.string.sessionlist_section_pinned)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp, bottom = 8.dp),
    ) {
        if (isPinned) {
            Icon(
                imageVector = novex.android.ui.NovexIcons.PushPin,
                contentDescription = null,
                tint = com.openminis.app.ui.noven.NovenColors.Secondary,
                modifier = Modifier
                    .size(13.dp)
                    .padding(end = 0.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = com.openminis.app.ui.noven.NovenColors.Secondary,
        )
    }
}

// ─── Session Item (context menu replaces swipe-to-delete, matching iOS) ─────

