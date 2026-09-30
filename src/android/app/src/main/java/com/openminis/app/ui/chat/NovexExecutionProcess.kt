package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import novex.android.ui.NovexContentDialog
import novex.android.ui.TextButton
import com.openminis.app.ui.theme.ChatColors
import org.json.JSONObject

private fun ToolBlockStatus?.displayLabel(): String = when (this) {
    ToolBlockStatus.STREAMING -> "准备中"
    ToolBlockStatus.PENDING -> "等待执行"
    ToolBlockStatus.RUNNING -> "执行中"
    ToolBlockStatus.SUCCESS -> "已完成"
    ToolBlockStatus.FAILED -> "未完成"
    ToolBlockStatus.CANCELLED -> "已停止"
    ToolBlockStatus.TIMEOUT -> "已超时"
        null -> "记录"
}

/**
 * [T-execution-activity-strip] 生成中钉在输入栏上方的活动条（2026-09-15 用户
 * 决策：技能调用动态要贴底滚动播报，否则用户看不到 AI 在干什么）。固定 3 行
 * 滚动显示最新的工具动态，像终端尾巴；点击打开完整工作记录弹窗；回合结束后
 * 由调用方切换成「✓ 本轮完成」样式停留 3 秒再收起。
 */
@Composable
internal fun NovexExecutionActivityStrip(
    process: FlatChatItem.AssistantProcess,
    done: Boolean,
    onOpen: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Text(
            if (done) "✓ 本轮完成 · 查看记录 ›" else "正在处理 · 最新动态 ›",
            color = if (done) ChatColors.primaryText else ChatColors.secondaryText,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
        )
        if (!done) {
            process.tools.takeLast(3).forEach { row ->
                val title = row.block.toolTitle.ifBlank {
                    buildNovexStandardToolDetailPresentation(row.block.toolName, row.block.toolArgs, row.block.content)?.title
                        ?: "查看操作详情"
                }
                Text(
                    "${row.block.toolStatus.displayLabel()} · $title",
                    color = ChatColors.secondaryText,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            }
        }
    }
}

@Composable
internal fun NovexExecutionProcessRow(process: FlatChatItem.AssistantProcess) {
    // [T-turn-single-card] 2026-09-16 用户批③：工作行只占一行——预览子行全撤
    // （回合一张卡后子行只会更长），失败/停止以角标提示，完整时间线点开看。
    // [feat/ui-rikkahub] 2026-09-27 取消浮窗：点行原地内联展开完整时间线，
    // 工具记录也在内联切换参数/结果，不再弹执行过程对话框。
    val active = process.statusLabel() == "进行中"
    // [feat/ui-rikkahub] 2026-09-27 只回答"任务完成了没"：失败后被成功挽回
    // 的步骤不算数——仅统计发生在最后一次成功**之后**的失败（真正烂尾）。
    val lastSuccessIdx = process.tools.indexOfLast { it.block.toolStatus == ToolBlockStatus.SUCCESS }
    val unresolvedFailures = process.tools.withIndex().count { (idx, row) ->
        idx > lastSuccessIdx &&
            row.block.toolStatus in setOf(ToolBlockStatus.FAILED, ToolBlockStatus.CANCELLED, ToolBlockStatus.TIMEOUT)
    }
    var expanded by remember { mutableStateOf(false) }
    // [feat/ui-rikkahub] mainstream-style collapsed header: total work time
    // (工具耗时合计，"已工作 X 分 X 秒") instead of step count; falls back
    // to a step count when no timing data exists.
    val workSeconds = (process.tools.sumOf { it.block.durationMs } / 1000L).toInt()
    val headLabel = when {
        workSeconds >= 60 -> "已工作 ${workSeconds / 60} 分 ${workSeconds % 60} 秒"
        workSeconds > 0 -> "已工作 $workSeconds 秒"
        else -> "本轮 ${process.rows.size} 步"
    }
    // [feat/ui-rikkahub] ChainOfThought timeline step: 24dp node + connector
    // stubs (both drawn — the chain continues through this row), shimmer
    // label while running. Same geometry as the thinking/tool steps.
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val shimmer = rememberInfiniteTransition(label = "processShimmer")
    val shimmerAlpha by shimmer.animateFloat(
        0.35f, 1f,
        infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "processAlpha",
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        // [feat/ui-rikkahub] Collapsed header = mainstream style: bare text + chevron
        // hugging the label, no icon, no stub segments.
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                headLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.alpha(if (active) shimmerAlpha else 1f),
            )
            Icon(
                imageVector = if (expanded) novex.android.ui.NovexIcons.KeyboardArrowUp else novex.android.ui.NovexIcons.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(14.dp),
            )
            if (unresolvedFailures > 0) {
                Text(
                    "⚠ $unresolvedFailures 步未完成",
                    color = ChatColors.secondaryText,
                    fontSize = 12.sp,
                )
            }
        }
        AnimatedVisibility(expanded) {
            // [feat/ui-rikkahub] Continuous left rail (mainstream style): a single
            // line at x=12dp behind every step node, instead of per-row stubs.
            // Only working steps live in the timeline (思考/工具) — the answer
            // prose renders outside, matching mainstream transcript logic.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawLine(
                            color = lineColor,
                            start = Offset(8.dp.toPx(), 0f),
                            end = Offset(8.dp.toPx(), size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    },
            ) {
                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                    process.rows.forEach { row -> when (row) {
                        is FlatChatItem.AssistantThinking -> ProcessStepRow(
                            icon = novex.android.ui.NovexIcons.Psychology,
                            label = "思考 · " + if (row.block.content.length >= 1000) {
                                "${row.block.content.length / 1000}K 字"
                            } else {
                                "${row.block.content.length} 字"
                            },
                            content = row.block.content,
                            copyText = row.block.content,
                        )
                        is FlatChatItem.AssistantToolUse -> ProcessStepRow(
                            icon = toolIconFor(row.block.toolName),
                            label = run {
                                val dur = if (row.block.durationMs > 0) {
                                    val s = row.block.durationMs / 1000.0
                                    " · " + if (s < 10) String.format("%.1f", s) + "s" else String.format("%.0f", s) + "s"
                                } else ""
                                "${row.block.toolStatus.displayLabel()} · " + row.block.toolTitle.ifBlank {
                                    buildNovexStandardToolDetailPresentation(
                                        row.block.toolName, row.block.toolArgs, row.block.content,
                                    )?.title ?: "工具调用"
                                } + dur
                            },
                            copyText = formatToolDetailsForClipboard(row.block),
                            errorDetail = row.block.content.takeIf {
                                it.isNotBlank() && row.block.toolStatus in setOf(
                                    ToolBlockStatus.FAILED,
                                    ToolBlockStatus.TIMEOUT,
                                    ToolBlockStatus.CANCELLED,
                                )
                            },
                        )
                        else -> Unit
                    } }
                }
            }
        }
    }
}

/**
 * [feat/ui-rikkahub] One step on the rail: typed icon + human-language label.
 * 思考 steps carry [content] and tap-expand inline (mainstream behavior); tool
 * steps stay label-only per 方案 B — payloads never render, long-press copies
 * diagnostics for bug reports.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProcessStepRow(
    icon: ImageVector,
    label: String,
    content: String? = null,
    errorDetail: String? = null,
    copyText: String? = null,
) {
    val context = LocalContext.current
    var open by remember(label) { mutableStateOf(false) }
    val expandable = content != null || errorDetail != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                when {
                    expandable -> Modifier.clickable { open = !open }
                    copyText != null -> Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = {
                            val cb = context.getSystemService(
                                android.content.Context.CLIPBOARD_SERVICE,
                            ) as android.content.ClipboardManager
                            cb.setPrimaryClip(
                                android.content.ClipData.newPlainText("step", copyText),
                            )
                            android.widget.Toast.makeText(context, "已复制诊断信息", android.widget.Toast.LENGTH_SHORT).show()
                        },
                    )
                    else -> Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 不透明节点垫：遮住身后的轨线，读作"线到节点断开、下方再续"，
        // 而不是从图标上穿过去。
        Box(
            modifier = Modifier
                .width(16.dp)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expandable) {
            Icon(
                imageVector = if (open) novex.android.ui.NovexIcons.KeyboardArrowUp else novex.android.ui.NovexIcons.KeyboardArrowDown,
                contentDescription = if (open) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(12.dp),
            )
        }
    }
    AnimatedVisibility(open) {
        Text(
            (content ?: errorDetail).orEmpty(),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier
                .padding(start = 22.dp, top = 2.dp, bottom = 4.dp)
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
internal fun NovexExecutionProcessDialog(process: FlatChatItem.AssistantProcess, onDismiss: () -> Unit, onOpenTool: (AssistantBlock) -> Unit) {
    NovexContentDialog("执行过程", onDismiss = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("返回对话") } }) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            process.rows.forEach { row -> when (row) {
                is FlatChatItem.AssistantText -> StreamingMarkdownText(
                    content = row.block.content,
                    isStreaming = false,
                    modifier = Modifier.padding(vertical = 6.dp),
                    shardId = TextShardId(row.messageId, "process:${row.block.id}"),
                )
                is FlatChatItem.AssistantMarkdownBlock -> MarkdownBlock(
                    rawText = row.rawText,
                    isStreaming = false,
                    modifier = Modifier.padding(vertical = 6.dp),
                    shardId = TextShardId(row.messageId, "process:${row.parentBlockId}:${row.blockIndex}"),
                )
                is FlatChatItem.AssistantThinking -> Text(row.block.content, modifier = Modifier.padding(vertical = 6.dp), fontSize = 13.sp, lineHeight = 19.sp)
                is FlatChatItem.AssistantToolUse -> TextButton(onClick = { onOpenTool(row.block) }) {
                    Text("${row.block.toolStatus.displayLabel()} · " + row.block.toolTitle.ifBlank { buildNovexStandardToolDetailPresentation(row.block.toolName, row.block.toolArgs, row.block.content)?.title ?: "查看操作详情" })
                }
                else -> Unit
            } }
        }
    }
}

@Composable
internal fun NovexCardTaskStatusRow(block: AssistantBlock, canContinue: Boolean, onOpenCard: (String, String) -> Unit, onContinue: () -> Unit) {
    // [feat/ui-rikkahub] 2026-09-27 成果卡 → 回执卡（chat-v1/03）：回合的
    // "附件"——缩略图 + 卡名 + 类型徽章 + 真实保存状态 + 「打开 →」。
    // 状态只来自回执 JSON：saved_verified 才算核验通过（旧数据里出现过
    // "saved"），其余一律如实标注，绝不替用户宣布成功。
    val args = runCatching { JSONObject(block.toolArgs) }.getOrNull()
    val status = args?.optString("status") ?: "incomplete"
    val label = args?.optString("label").orEmpty()
    val statusText = when (status) {
        "saved_verified", "saved" -> "已保存 · 核验通过"
        "saved_needs_review" -> "已保存 · 待核对"
        else -> "待完成"
    }
    val needsContinuation = status in setOf("incomplete", "saved_needs_review")
    val cards = args?.optJSONArray("cards")
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (cards != null && cards.length() > 0) {
            if (cards.length() > 1 && label.isNotBlank()) {
                Text(
                    label,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            for (index in 0 until cards.length()) {
                val card = cards.optJSONObject(index) ?: continue
                val kind = card.optString("kind")
                val id = card.optString("id")
                if (id.isBlank()) continue
                val kindLabel = when (kind) {
                    "integrated" -> "卡片"
                    "world" -> "世界"
                    "character_version" -> "角色"
                    "game" -> "文游"
                    else -> "卡片"
                }
                CardReceiptRow(
                    kind = kind,
                    id = id,
                    name = card.optString("name").takeIf(String::isNotBlank) ?: kindLabel,
                    kindLabel = kindLabel,
                    statusText = statusText,
                    onClick = { onOpenCard(kind, id) },
                )
            }
        } else if (label.isNotBlank()) {
            Text(
                label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        if (needsContinuation && canContinue) {
            TextButton(onClick = onContinue) { Text("继续核对与完成") }
        }
    }
}

/** Mini 卡面回执：缩略图 + 名称 + 类型徽章 + 状态行 + 「打开 →」。整卡可点。 */
@Composable
private fun CardReceiptRow(kind: String, id: String, name: String, kindLabel: String, statusText: String, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CardReceiptThumbnail(kind, id)
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        name,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        kindLabel,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 5.dp, vertical = 1.5.dp),
                    )
                }
                Text(
                    statusText,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                "打开 →",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = com.openminis.app.ui.noven.NovenColors.Mint,
            )
        }
    }
}

/** 回执缩略图：封面/头像真实数据，读不到时退回类型图标。 */
@Composable
private fun CardReceiptThumbnail(kind: String, id: String) {
    val image = rememberCardReceiptImage(kind, id)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            coil.compose.AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Icon(
                when (kind) {
                    "world", "integrated" -> novex.android.ui.NovexIcons.Book
                    "character_version" -> novex.android.ui.NovexIcons.Person
                    "game" -> novex.android.ui.NovexIcons.PlayCircleFilled
                    else -> novex.android.ui.NovexIcons.Book
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 按卡种解析封面：legacy 卡走 workspace 媒体库，integrated 卡走 CardStore
 * 内容寻址文件（与小图解码同一批真实数据，不伪造）。 */
@Composable
private fun rememberCardReceiptImage(kind: String, id: String): Any? {
    val context = LocalContext.current
    val workspace = remember(context) {
        (context.applicationContext as? com.openminis.app.MinisApp)?.novexWorkspace
    }
    val image by androidx.compose.runtime.produceState<Any?>(null, kind, id) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            when (kind) {
                "world" -> runCatching {
                    val media = workspace?.world(id)?.media
                    (media?.get(com.openminis.app.data.character.MediaAssetSlot.WORLD_COVER)
                        ?: media?.get(com.openminis.app.data.character.MediaAssetSlot.WORLD_BACKGROUND))
                        ?.managedPath?.let { java.io.File(it) }?.takeIf { it.exists() }
                }.getOrNull()
                "character_version" -> runCatching {
                    workspace?.characterForVersion(id)
                        ?.mediaByVersion?.get(id)
                        ?.get(com.openminis.app.data.character.MediaAssetSlot.CHARACTER_AVATAR)
                        ?.managedPath?.let { java.io.File(it) }?.takeIf { it.exists() }
                }.getOrNull()
                "game" -> runCatching {
                    workspace?.interactiveFiction(id)?.media
                        ?.get(com.openminis.app.data.character.MediaAssetSlot.INTERACTIVE_FICTION_COVER)
                        ?.managedPath?.let { java.io.File(it) }?.takeIf { it.exists() }
                }.getOrNull()
                "integrated" -> runCatching {
                    val address = JSONObject(id)
                    val store = novex.storage.CardStore(
                        context.filesDir.toPath().resolve("rewrite-content"),
                    )
                    val root = store.open(address.getString("root")) ?: return@runCatching null
                    val doc = novex.content.ContentTargets.find(
                        root.content, address.getString("target"),
                    )
                    val resourceId = doc.appearance.coverResourceId
                        ?: doc.appearance.avatarResourceId
                    val ref = doc.resources.firstOrNull { it.id == resourceId }?.content
                        ?: return@runCatching null
                    val maxEdge = 192
                    val bounds = android.graphics.BitmapFactory.Options()
                        .apply { inJustDecodeBounds = true }
                    store.contents.open(ref).use {
                        android.graphics.BitmapFactory.decodeStream(it, null, bounds)
                    }
                    var sample = 1
                    while ((bounds.outWidth.toLong() + sample - 1) / sample > maxEdge ||
                        (bounds.outHeight.toLong() + sample - 1) / sample > maxEdge
                    ) sample *= 2
                    store.contents.open(ref).use {
                        android.graphics.BitmapFactory.decodeStream(
                            it, null,
                            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                        )
                    }
                }.getOrNull()
                else -> null
            }
        }
    }
    return image
}
