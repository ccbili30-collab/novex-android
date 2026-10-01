package com.openminis.app.ui.chat

// 移动会话选择面板：列出当前会话之外的全部会话供挑选；还可一键
// 「新建会话再移动」。会话流经 ChatViewModelStore.pendingTransfer 暂存
// （输入 + 附件），跳转后由目标会话回收。
// 类别图标与相对时间直接吃 ui/noven/NovenSessionRow 的共享表——这文件
// 以前带着一份复制粘贴的本地副本，删了。

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import novex.android.data.chat.SessionRow
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.ui.noven.categoryStyle
import com.openminis.app.ui.noven.relativeDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveToSessionSheet(
    currentSessionId: String,
    chatRepository: ChatRepository,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onImportCard: ((Boolean)->Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<SessionRow>>(emptyList()) }
    var currentSession by remember { mutableStateOf<SessionRow?>(null) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            chatRepository.sessionById(currentSessionId) to
                chatRepository.dao.primarySessions().filter { it.id != currentSessionId }
        }
        currentSession = loaded.first
        sessions = loaded.second
    }

    novex.android.ui.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            onImportCard?.let { import ->
                novex.android.ui.TextButton(onClick = { import(true) }) { Text("导入为世界") }
                novex.android.ui.TextButton(onClick = { import(false) }) { Text("导入为角色") }
            }
            Text(
                stringResource(R.string.move_to_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            NewSessionTargetRow(
                enabled = currentSession != null,
                onPick = {
                    val source = currentSession ?: return@NewSessionTargetRow
                    scope.launch {
                        val target = withContext(Dispatchers.IO) {
                            chatRepository.createSession(
                                modelId = source.modelId,
                                title = null,
                                memoryEnabled = false,
                            )
                        }
                        onSelect(target.id)
                    }
                },
            )
            if (sessions.isEmpty()) {
                Text(
                    stringResource(R.string.move_to_sheet_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(16.dp),
                        ),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        MoveToPickerRow(session = session, onClick = { onSelect(session.id) })
                    }
                }
            }
        }
    }
}
