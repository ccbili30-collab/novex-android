package com.openminis.app.ui.noven

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.app.R
import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.ui.novex.AlertDialog
import com.openminis.app.ui.novex.TextButton
import novex.android.LibraryModel
import java.util.UUID

/**
 * 创作 tab（03.png）：AI 创作输入 + 三个入口 + 未完成草稿列表。
 * 发送动作生成新草稿会话并预填输入（DeepLinkCoordinator.pendingChatInput，
 * ChatScreen 只预填不自动发送）。「未完成」= 导入草稿（续走导入确认）+
 * 编辑草稿（打开卡片页，CardSessionModel 自动恢复 CardDrafts）。
 */
@Composable
internal fun NovenCreateScreen(
    onChat: (String) -> Unit,
    onCreateWorld: () -> Unit,
    onCreateCharacter: () -> Unit,
    onImportCard: (Uri, Boolean) -> Unit,
    onOpenCard: (String, String) -> Unit,
    onResumeImportDraft: (String) -> Unit,
    libraryReady: Boolean,
) {
    val library: LibraryModel = viewModel(key = "noven-create-library")
    LaunchedEffect(libraryReady) {
        if (libraryReady) library.refresh()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, library) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) library.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var aiInput by rememberSaveable { mutableStateOf("") }
    var importChoiceOpen by remember { mutableStateOf(false) }
    val importPicker = rememberNovenImportPicker { uri, world -> onImportCard(uri, world) }
    val unfinished = library.state.imports + library.state.edits
    val importIds = remember(library.state.imports) { library.state.imports.map { it.id }.toSet() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NovenDimens.PageHorizontal),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 14.dp),
        ) {
            Text(
                "创作",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = NovenColors.Text,
            )
            Text(
                "把脑子里的世界做出来",
                fontSize = 13.sp,
                color = NovenColors.Secondary,
                modifier = Modifier.padding(start = 10.dp, top = 4.dp),
            )
        }

        // 「和 AI 一起创作」卡片
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(NovenDimens.CardRadius))
                .background(NovenColors.Surface)
                .border(NovenDimens.Hairline, NovenColors.Divider, RoundedCornerShape(NovenDimens.CardRadius))
                .padding(14.dp),
        ) {
            Text(
                "和 AI 一起创作",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = NovenColors.Text,
            )
            Text(
                "说一个开头，AI 边聊边帮你整理成世界和角色",
                fontSize = 13.sp,
                color = NovenColors.Secondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(NovenColors.Muted)
                    .padding(start = 14.dp, end = 4.dp),
            ) {
                BasicTextField(
                    value = aiInput,
                    onValueChange = { aiInput = it },
                    singleLine = true,
                    cursorBrush = SolidColor(NovenColors.Mint),
                    textStyle = TextStyle(fontSize = 14.sp, color = NovenColors.Text),
                    decorationBox = { inner ->
                        Box {
                            if (aiInput.isEmpty()) {
                                Text("比如：一座每天漂移一公里的岛", fontSize = 14.sp, color = NovenColors.Secondary)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                val canSend = aiInput.isNotBlank()
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (canSend) NovenColors.Mint else NovenColors.Mint.copy(alpha = 0.35f))
                        .clickable(enabled = canSend) {
                            val draftId = "__new__${UUID.randomUUID()}"
                            DeepLinkCoordinator.setPendingChatInput(draftId, aiInput.trim())
                            aiInput = ""
                            onChat(draftId)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_phosphor_arrow_up),
                        contentDescription = "发送",
                        tint = NovenColors.OnMint,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 三个等宽入口
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NovenCreateTile("新建世界", R.drawable.ic_phosphor_globe, Modifier.weight(1f), onCreateWorld)
            NovenCreateTile("新建角色", R.drawable.ic_phosphor_user, Modifier.weight(1f), onCreateCharacter)
            NovenCreateTile("导入卡片", R.drawable.ic_phosphor_download_simple, Modifier.weight(1f)) {
                importChoiceOpen = true
            }
        }

        library.state.error?.let { error ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(error, fontSize = 12.sp, color = NovenColors.Secondary)
                TextButton(onClick = library::refresh) { Text("重新读取") }
            }
        }

        // 「未完成」：列表为空时整个区块隐藏
        if (unfinished.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                "未完成",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = NovenColors.Text,
            )
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(NovenDimens.CardRadius))
                    .background(NovenColors.Surface)
                    .border(NovenDimens.Hairline, NovenColors.Divider, RoundedCornerShape(NovenDimens.CardRadius)),
            ) {
                unfinished.forEachIndexed { index, draft ->
                    if (index > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(NovenDimens.Hairline)
                                .background(NovenColors.Divider),
                        )
                    }
                    val isImport = draft.id in importIds
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isImport) onResumeImportDraft(draft.id)
                                else onOpenCard(draft.id, draft.id)
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                draft.name.ifBlank { "未命名" },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = NovenColors.Text,
                            )
                            Text(
                                if (isImport) "导入草稿 · 待确认" else "有未保存的修改",
                                fontSize = 12.sp,
                                color = NovenColors.Secondary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Text(
                            if (isImport) "确认" else "继续",
                            fontSize = 13.sp,
                            color = NovenColors.Mint,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (importChoiceOpen) {
        AlertDialog(
            onDismissRequest = { importChoiceOpen = false },
            title = { Text("导入卡片") },
            confirmButton = {
                TextButton(onClick = { importChoiceOpen = false; importPicker(true) }) {
                    Text("导入世界卡")
                }
            },
            dismissButton = {
                TextButton(onClick = { importChoiceOpen = false; importPicker(false) }) {
                    Text("导入角色卡")
                }
            },
        )
    }
}

@Composable
private fun NovenCreateTile(label: String, icon: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(NovenDimens.CardRadius))
            .background(NovenColors.Surface)
            .border(NovenDimens.Hairline, NovenColors.Divider, RoundedCornerShape(NovenDimens.CardRadius))
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = NovenColors.Text,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 13.sp, color = NovenColors.Text)
    }
}
