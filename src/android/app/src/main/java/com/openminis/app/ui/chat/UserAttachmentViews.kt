package com.openminis.app.ui.chat

// [T-android-split-chat] User-message rendering extracted verbatim from
// ChatScreen.kt: UserMessageBubble, UserAttachmentList, FileAttachmentTile,
// fileIconFor, ImageGalleryDialog. Full import block copied (unused=warnings).
// Externally-called ones (UserMessageBubble, UserAttachmentList) are internal.

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openminis.app.ui.theme.ChatColors
import novex.core.ContextSourceKind
import novex.android.ui.TextButton as NovexTextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.openminis.app.R

// ─── User Message (right-aligned, iOS: tertiarySystemFill bubble, 18dp radius) ─

internal fun contextSourceKindLabel(kind: novex.core.ContextSourceKind): String = when (kind) {
    novex.core.ContextSourceKind.ANSWER_IDENTITY -> "回答身份"
    novex.core.ContextSourceKind.CONVERSATION_PROMPT -> "系统提示"
    novex.core.ContextSourceKind.ACTIVE_BRANCH_MESSAGE -> "对话消息"
    novex.core.ContextSourceKind.ATTACHED_DOCUMENT -> "附件"
    novex.core.ContextSourceKind.BACKGROUND_MODULE -> "背景模块"
    novex.core.ContextSourceKind.SUMMARY -> "摘要"
    novex.core.ContextSourceKind.MEMORY -> "记忆"
    novex.core.ContextSourceKind.PLAYTHROUGH_STATE -> "游玩状态"
    novex.core.ContextSourceKind.TOOL_DEFINITION -> "工具定义"
    novex.core.ContextSourceKind.TOOL_RESULT -> "工具结果"
}

/** [feat/ui-rikkahub] 携带展示名：卡类模块去掉归属前缀，显示"卡 · 模块"；
 * 系统模块没有前缀，保持原名。 */
internal fun carriedDisplayLabel(label: String): String =
    label.removePrefix("角色 · ").removePrefix("世界 · ").removePrefix("文游 · ")

// ─── User Attachment List (iOS: UserAttachmentList — 64dp tiles, FlowRow, trailing) ─

/**
 * Mirrors iOS UserAttachmentList (AIChatView.swift:4416-4518).
 * 64dp uniform tiles in a FlowRow, right-aligned, 6dp gaps.
 * Images render as thumbnail (tap → fullscreen preview dialog).
 * Files render as icon + 2-line filename (tap → system handler).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UserAttachmentList(
    imageUris: List<Uri>,
    allFileNames: List<String>,
    nonImageUris: List<Uri> = emptyList(),
    onPreviewFile: (Uri, String) -> Unit = { _, _ -> },
) {
    var previewImageIndex by remember { mutableStateOf<Int?>(null) }

    // attachmentNames[0..imageUris.size-1] correspond to images; rest are non-image files.
    val imageCount = imageUris.size
    val fileNames = allFileNames.drop(imageCount)

    val tileSize = 64.dp

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        imageUris.forEachIndexed { idx, uri ->
            AsyncImage(
                model = uri,
                contentDescription = "Image attachment",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(tileSize)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ChatColors.secondaryBg)
                    .border(0.5.dp, ChatColors.thumbnailBorder, RoundedCornerShape(8.dp))
                    .clickable { previewImageIndex = idx },
            )
        }

        fileNames.forEachIndexed { idx, name ->
            val uri = nonImageUris.getOrNull(idx)
            FileAttachmentTile(
                fileName = name,
                tileSize = tileSize,
                onClick = {
                    // T150: route into FilePreviewScreen when we have a stable
                    // host URI for this attachment (set via mediaRef on send
                    // and on session restore). Fall back to no-op when the
                    // chip predates T150 persistence.
                    if (uri != null) onPreviewFile(uri, name)
                },
            )
        }
    }

    previewImageIndex?.let { startIdx ->
        // T-imgswipe-4f446d83: route through the shared swipe-able
        // viewer so user-bubble image attachments get the same caption
        // capsule + Copy/Share/Save chrome as composer chip / markdown
        // taps. attachmentNames[0..imageCount-1] are the image filenames
        // in display order so they line up 1:1 with imageUris.
        com.openminis.app.ui.components.ImageGalleryViewer(
            items = imageUris.mapIndexed { i, uri ->
                com.openminis.app.ui.components.ImageGalleryItem(
                    model = uri,
                    caption = allFileNames.getOrNull(i),
                )
            },
            startIndex = startIdx,
            onDismiss = { previewImageIndex = null },
        )
    }
}

@Composable
internal fun FileAttachmentTile(
    fileName: String,
    tileSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .size(tileSize)
            .clip(RoundedCornerShape(8.dp))
            .background(ChatColors.secondaryBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = fileIconFor(fileName),
            contentDescription = null,
            tint = ChatColors.secondaryText,
            modifier = Modifier
                .size(20.dp)
                .weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = fileName,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            color = ChatColors.primaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Map a filename extension to a Material icon. Mirrors iOS fileIconName(for:). */
internal fun fileIconFor(fileName: String): androidx.compose.ui.graphics.vector.ImageVector {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "pdf" -> novex.android.ui.NovexIcons.PictureAsPdf
        "doc", "docx", "pages" -> novex.android.ui.NovexIcons.Article
        "txt", "log", "csv", "md", "markdown" -> novex.android.ui.NovexIcons.Article
        "mp4", "mov", "avi", "mkv" -> novex.android.ui.NovexIcons.VideoFile
        "mp3", "wav", "m4a", "aac" -> novex.android.ui.NovexIcons.AudioFile
        "zip", "tar", "gz", "7z" -> novex.android.ui.NovexIcons.FolderZip
        else -> novex.android.ui.NovexIcons.InsertDriveFile
    }
}

/** Fullscreen image preview dialog with pager across all image attachments. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ImageGalleryDialog(
    uris: List<Uri>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val pagerState = androidx.compose.foundation.pager.rememberPagerState(
            initialPage = startIndex.coerceIn(0, (uris.size - 1).coerceAtLeast(0)),
            pageCount = { uris.size },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                ),
        ) {
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                AsyncImage(
                    model = uris[page],
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Icon(
                    novex.android.ui.NovexIcons.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                )
            }
            if (uris.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${uris.size}",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// ─── Assistant Message (left-aligned, no bubble, with "Minis" header like iOS) ─

// ─── Flattened chat items ────────────────────────────────────────────────────
// Each message is expanded into a sequence of independent LazyColumn items (header,
// text block, tool pill, etc.). This makes scroll-hovering stable during streaming:
// LazyListState anchors on a stable per-item key, so only the trailing streaming item
// changes height while earlier items remain frozen and their scroll positions
// untouched.

/** 用户气泡长按菜单：Copy / 分享 / Retry / Edit / Delete，条目按回调是否
 *  为空显隐——流式中调用方传 null 隐藏会改写当前回合的动作。 */
@Composable
internal fun UserBubbleMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShare: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    // T280 + [T-android-tool-menu-minwidth]: 收窄到 min(220dp, 屏宽)，
    // alignEnd 让菜单右缘贴气泡右缘（用户气泡右对齐）。
    val menuWidth = minOf(220, LocalConfiguration.current.screenWidthDp).dp
    com.openminis.app.ui.components.MinisMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = androidx.compose.ui.unit.DpOffset(0.dp, 6.dp),
        alignEnd = true,
        modifier = Modifier.widthIn(max = menuWidth),
        minWidth = menuWidth,
    ) {
        novex.android.ui.DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_longpress_copy)) },
            onClick = { onDismiss(); onCopy() },
            leadingIcon = { Icon(novex.android.ui.NovexIcons.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        if (onShare != null) {
            novex.android.ui.DropdownMenuItem(
                text = { Text("分享到其他文游") },
                onClick = { onDismiss(); onShare() },
                leadingIcon = { Icon(novex.android.ui.NovexIcons.Share, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        // T119: 生成中隐藏会改写当前回合的动作；调用方在流式时传 null。
        if (onRetry != null) {
            novex.android.ui.DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_longpress_retry)) },
                onClick = { onDismiss(); onRetry() },
                leadingIcon = { Icon(novex.android.ui.NovexIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        // T187: Edit 以编辑文本替换原回合；门控同 retry。
        if (onEdit != null) {
            novex.android.ui.DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_longpress_edit)) },
                onClick = { onDismiss(); onEdit() },
                leadingIcon = { Icon(novex.android.ui.NovexIcons.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        if (onDelete != null) {
            novex.android.ui.DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_longpress_delete_from_here)) },
                onClick = { onDismiss(); onDelete() },
                leadingIcon = { Icon(novex.android.ui.NovexIcons.Delete, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}
