package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.ui.sandbox.FileItem
import com.openminis.app.ui.theme.ChatColors

internal data class ChatTranscriptRowState(
    val isStreaming: Boolean,
    val canResume: Boolean,
    val thinkingLevel: ThinkingLevel,
    val compactedHistoryExpanded: Boolean,
)

/** User actions only. Rendering has no repository, execution loop or view-model access. */
internal sealed interface ChatTranscriptAction {
    data class Share(val text: String) : ChatTranscriptAction
    data class RetryFrom(val messageId: String) : ChatTranscriptAction
    data class Edit(val messageId: String) : ChatTranscriptAction
    data class DeleteFrom(val messageId: String) : ChatTranscriptAction
    data class Withdraw(val messageId: String) : ChatTranscriptAction
    data class PreviewAttachment(val file: FileItem) : ChatTranscriptAction
    data class Prefill(val text: String) : ChatTranscriptAction
    data class OpenProcess(val process: FlatChatItem.AssistantProcess) : ChatTranscriptAction
    data class RetryLast(val navigateToLatest: Boolean) : ChatTranscriptAction
    data object Stop : ChatTranscriptAction
    data class OpenTerminal(val command: String) : ChatTranscriptAction
    data class OpenToolDetail(val id: String) : ChatTranscriptAction
    data class RerunFrom(val messageId: String, val blockId: String) : ChatTranscriptAction
    data class OpenCard(val kind: String, val id: String) : ChatTranscriptAction
    data object RevertCompact : ChatTranscriptAction
    data object ToggleCompactedHistory : ChatTranscriptAction
    data class SwitchBranch(val messageId: String, val delta: Int) : ChatTranscriptAction
}

/** A single transcript row. List keys, scroll ownership and mutation ordering stay with the host. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTranscriptRow(
    item: FlatChatItem,
    state: ChatTranscriptRowState,
    selectionController: SelectionController,
    panelExpansionState: PanelExpansionState,
    onAction: (ChatTranscriptAction) -> Unit,
) {
    val context = LocalContext.current
    when (item) {
        is FlatChatItem.UserBubble -> {
            // User bubbles intentionally don't register
            // MinisTextKit shards — long-press on a user
            // bubble shows its own action menu (Copy /
            // Retry / Edit) instead of starting text
            // selection, matching iOS UX.
            UserMessageBubble(
            message = item.message,
            // [T-android-candidate-bubble-gap] extra top
            // gap when this bubble directly follows another
            // user bubble (back-to-back candidate sends).
            precededByUser = item.precededByUser,
            onCopy = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("message", item.message.content))
            },
            onShare = {
                onAction(ChatTranscriptAction.Share(item.message.content))
            },
            // T119: pass null while a turn is in flight so
            // the long-press menu hides Retry; once the
            // stream stops (cancel or natural end) the
            // option reappears. Gating execution alone
            // wasn't enough — users still saw a tappable
            // Retry that silently no-op'd.
            onRetry = if (state.isStreaming) null else ({
                onAction(ChatTranscriptAction.RetryFrom(item.message.id))
            }),
            // T187: long-press → Edit pulls the user message
            // text into the composer; the next send truncates
            // from this turn (inclusive) before persisting
            // the edited content. Gated on isStreaming the
            // same way Retry is.
            onEdit = if (state.isStreaming || item.message.isQueued) null else ({
                onAction(ChatTranscriptAction.Edit(item.message.id))
            }),
            onDelete = if (state.isStreaming || item.message.isQueued) null else ({
                onAction(ChatTranscriptAction.DeleteFrom(item.message.id))
            }),
            onWithdraw = if (item.message.isQueued) {
                { onAction(ChatTranscriptAction.Withdraw(item.message.id)) }
            } else null,
            onPreviewFile = { uri, name ->
                // T150: turn the persisted file:// URI back
                // into a FileItem and hand off to the host
                // navigator (FilePreviewScreen). Mirrors
                // FileBrowser's onPreviewFile contract so
                // both entry points share one screen.
                val file = uri.path?.let { java.io.File(it) }
                if (file != null && file.exists()) {
                    onAction(ChatTranscriptAction.PreviewAttachment(
                        com.openminis.app.ui.sandbox.FileItem(
                            file = file,
                            name = name,
                            isDirectory = false,
                            isSymlink = false,
                            size = file.length(),
                            modifiedMs = file.lastModified(),
                        )
                    ))
                }
            },
        )
        } // close UserBubble SideEffect + UserMessageBubble block
        is FlatChatItem.AssistantHeader -> AssistantHeader()
        is FlatChatItem.AssistantText -> BoundsTrackedBlock(
            messageId = item.messageId,
            slotKey = "text:${item.block.id}",
            markdown = item.messageMarkdown,
        ) {
            CharacterAssistantBubble {
            // T-android-gc-storm-issue17: collapse oversized frozen
            // assistant text before feeding the markdown parser, which
            // is the GC-storm hotspot for legacy sessions.
            LargeContentGuard(
                content = item.block.content,
                isStreaming = item.isStreaming,
                stableKey = "text:${item.messageId}:${item.block.id}",
            ) {
                SideEffect {
                    selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                }
                StreamingMarkdownText(
                    content = item.block.content,
                    isStreaming = item.isStreaming,
                    shardId = TextShardId(
                        messageId = item.messageId,
                        shardId = "text:${item.block.id}",
                    ),
                )
            }
            }
        }
        is FlatChatItem.AssistantMarkdownBlock -> BoundsTrackedBlock(
            messageId = item.messageId,
            slotKey = "mdblock:${item.parentBlockId}:${item.blockIndex}",
            markdown = item.messageMarkdown,
        ) {
            CharacterAssistantBubble {
            LargeContentGuard(
                content = item.rawText,
                isStreaming = item.isStreaming,
                stableKey = "mdblock:${item.messageId}:${item.parentBlockId}:${item.blockIndex}",
            ) {
                SideEffect {
                    selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                }
                MarkdownBlock(
                    rawText = item.rawText,
                    isStreaming = item.isStreaming,
                    shardId = TextShardId(
                        messageId = item.messageId,
                        shardId = "mdblock:${item.parentBlockId}:${item.blockIndex}",
                    ),
                )
            }
            }
        }
        is FlatChatItem.AssistantThinking -> {
            // T300: hide Deep Thinking block when the user
            // currently has thinking turned off — even if
            // a forced-reasoning model (e.g. xAI Grok 4.x
            // via OpenRouter) still streams reasoning_-
            // content. Snapshot on the message wins so
            // toggling the level after a turn finishes
            // doesn't retro-hide an already-visible block;
            // legacy DB-restored messages (snapshot=null)
            // follow the chat's current level.
            val effectiveLevel = item.messageThinkingLevel
                ?: state.thinkingLevel
            if (effectiveLevel.isEnabled) {
                // [T-android-thinking-auto-collapse] Use
                // `isLastBlockOverall` (not `isLast` =
                // last-thinking-only) so the block flips
                // to !state.isStreaming the moment a sibling
                // text/tool_use arrives — that's the
                // edge ThinkingBlock's LaunchedEffect
                // hooks for auto-collapse, matching iOS
                // ThinkingBlockView semantics.
                ThinkingBlock(
                    block = item.block,
                    isStreaming = item.isLastBlockOverall && item.messageIsStreaming,
                    isLast = item.isLast,
                )
            }
        }
        is FlatChatItem.AssistantProcess -> NovexExecutionProcessRow(item) { onAction(ChatTranscriptAction.OpenProcess(item)) }
        is FlatChatItem.AssistantToolUse -> {
            if (item.block.toolName == "present_choices") {
                NovexChoiceButtons(item.block.toolArgs) { choice ->
                    onAction(ChatTranscriptAction.Prefill(choice))
                }
            } else if (item.block.toolName in setOf("render_panel", "panel", "present_system_panel")) {
                NovexPanel(
                    argsJson = item.block.toolArgs,
                    panelKey = "${item.messageId}:${item.block.id}",
                    expansionState = panelExpansionState,
                ) { value ->
                    onAction(ChatTranscriptAction.Prefill(value))
                }
            } else {
                ToolCallPill(
            block = item.block,
            allToolBlocks = item.allToolBlocks,
            onRetry = if (item.isLastCancelled && !state.isStreaming && !state.canResume) ({ onAction(ChatTranscriptAction.RetryLast(false)) }) else null,
            // T14: route per-card stop to the global
            // cancelStream(). The button only renders
            // when the block is RUNNING/STREAMING — see
            // ToolCallPill `isRunning && onStop != null`
            // — so passing it unconditionally is safe.
            onStop = { onAction(ChatTranscriptAction.Stop) },
            onOpenTerminalWithCommand = { onAction(ChatTranscriptAction.OpenTerminal(it)) },
            // T261: route detail open through ViewModel so
            // the sheet is hoisted out of LazyColumn item
            // scope (otherwise the sheet snaps shut when
            // the pill scrolls off-screen and Compose
            // disposes the item).
            onOpenDetail = { onAction(ChatTranscriptAction.OpenToolDetail(it)) },
            // [T-android-rerun-from-tool-block-position]
            // Re-run cuts at THIS tool_use block: keep the
            // blocks before it in the same turn, drop it +
            // everything after, then regenerate. The block
            // id (== tool_use id for a tool_use block) is
            // the stable anchor. Gated off while streaming
            // (mutating an in-flight turn corrupts agent
            // state, same rule as Retry on the user bubble).
            // safeMutate tears down the selection toolbar
            // before the truncation reshuffles the list.
            onRerunFromHere = if (!state.isStreaming) ({
                onAction(ChatTranscriptAction.RerunFrom(item.messageId, item.block.id))
            }) else null,
            onCopyDetails = {
                val text = formatToolDetailsForClipboard(item.block)
                val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("tool", text))
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.tool_longpress_copied_toast),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            },
                )
            }
        }
        is FlatChatItem.AssistantFallbackChoices -> {
            NovexChoiceButtons(item.choices) { choice ->
                onAction(ChatTranscriptAction.Prefill(choice))
            }
        }
        is FlatChatItem.AssistantInfo -> if (item.block.toolName == NovexCardCreationTask.MARKER) {
            NovexCardTaskStatusRow(item.block, !state.isStreaming, onOpenCard = { kind, id -> onAction(ChatTranscriptAction.OpenCard(kind, id)) }) {
                onAction(ChatTranscriptAction.Prefill("继续核对并完成刚才的卡片任务；先检查已有成果，不重复创建。"))
            }
        } else if (item.block.toolName == NOVEX_STORY_IMAGE) NovexStoryImage(item.block)
        else FallbackInfoBlock(
            block = item.block,
            // Only the compact-divider info block should
            // surface a "Revert Compact" button on its
            // detail sheet — other info rows (slash
            // notices, fallback notices) have nothing
            // to revert.
            onRevert = if (item.block.toolName == "compact") {
                { onAction(ChatTranscriptAction.RevertCompact) }
            } else null,
            compactedHistoryExpanded = if (item.block.toolName == "compact") {
                state.compactedHistoryExpanded
            } else null,
            onToggleCompactedHistory = if (item.block.toolName == "compact") ({
                onAction(ChatTranscriptAction.ToggleCompactedHistory)
            }) else null,
        )
        is FlatChatItem.AssistantTyping -> TypingIndicator()
        is FlatChatItem.AssistantError -> InlineErrorBanner(
            error = item.error,
            onRetry = {
                onAction(ChatTranscriptAction.RetryLast(true))
            },
        )
        is FlatChatItem.BranchSwitcher -> ConversationBranchSwitcher(
            index = item.index,
            count = item.count,
            onPrevious = {
                onAction(ChatTranscriptAction.SwitchBranch(item.messageId, -1))
            },
            onNext = {
                onAction(ChatTranscriptAction.SwitchBranch(item.messageId, 1))
            },
        )
        is FlatChatItem.AssistantLegacyContent -> BoundsTrackedBlock(
            messageId = item.messageId,
            slotKey = "legacy",
            markdown = item.messageMarkdown,
        ) {
            LargeContentGuard(
                content = item.content,
                isStreaming = item.isStreaming,
                stableKey = "legacy:${item.messageId}",
            ) {
                SideEffect {
                    selectionController.rememberMessageMarkdown(item.messageId, item.messageMarkdown)
                }
                StreamingMarkdownText(
                    content = item.content,
                    isStreaming = item.isStreaming,
                    shardId = TextShardId(
                        messageId = item.messageId,
                        shardId = "legacy",
                    ),
                )
            }
        }
    }
}

@Composable
private fun ConversationBranchSwitcher(
    index: Int,
    count: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPrevious,
            enabled = index > 1,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = com.openminis.app.ui.novex.NovexIcons.ChevronLeft,
                contentDescription = "上一分支",
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = "$index/$count",
            color = ChatColors.secondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 34.dp),
        )
        IconButton(
            onClick = onNext,
            enabled = index < count,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = com.openminis.app.ui.novex.NovexIcons.ChevronRight,
                contentDescription = "下一分支",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
