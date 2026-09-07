package com.openminis.app.ui.novex

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.novex.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun NovexCardCopySection(root: NovexCardCopyKey) {
    var open by remember(root) { mutableStateOf(false) }
    var message by remember(root) { mutableStateOf<String?>(null) }
    NovexContentSection("另存卡片") {
        NovexSummaryRow("复制为独立卡片", "选择是否保留或连带复制外部引用", onClick = { open = true })
    }
    if(open) NovexCardCopyDialog(root, { open = false }) { result ->
        open = false; message = "副本已保存，可返回对应卡片库查看。新编号：${result.root.id}"
    }
    message?.let { NovexNoticeDialog("复制完成", it) { message = null } }
}

@Composable
internal fun NovexCardCopyDialog(root: NovexCardCopyKey, onDismiss: () -> Unit, onCopied: (NovexCardCopyResult) -> Unit) {
    val workspace = rememberNovexWorkspace()
    val scope = rememberCoroutineScope()
    var policy by remember(root) { mutableStateOf(NovexCardCopyPolicy.DETACH) }
    var plan by remember(root) { mutableStateOf<NovexCardCopyPlan?>(null) }
    var choosePolicy by remember(root) { mutableStateOf(false) }
    var refresh by remember(root) { mutableIntStateOf(0) }
    var busy by remember(root) { mutableStateOf(false) }
    var loading by remember(root) { mutableStateOf(true) }
    var error by remember(root) { mutableStateOf<String?>(null) }
    LaunchedEffect(root, policy, refresh) {
        loading = true; plan = null
        try { plan = workspace.prepareCardCopy(root, policy) }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(failure: Exception) { error = failure.message ?: "无法准备复制" }
        finally { loading = false }
    }
    NovexContentDialog("复制预览", onDismiss = { if(!busy) onDismiss() }, confirmButton = {
        TextButton(enabled = plan != null && !busy && !loading, onClick = {
            val selected = plan ?: return@TextButton
            busy = true
            scope.launch {
                try { onCopied((workspace.apply(NovexCommand.CopyCard(selected)) as NovexChange.CardsCopied).result) }
                catch(cancelled: CancellationException) { throw cancelled }
                catch(failure: Exception) { error = failure.message ?: "复制失败"; plan = null }
                finally { busy = false }
            }
        }) { Text(if(busy) "正在保存" else "确认复制") }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } }) {
        TextButton(enabled = !busy, onClick = { choosePolicy = true }) { Text("引用处理：${policy.label}") }
        Text(policy.description)
        if(loading) Text("正在核对来源和依赖…")
        plan?.let { value ->
            Text("${value.items.size} 张独立卡片 · ${value.items.sumOf { it.versionCount }} 个角色版本 · ${value.mediaCount} 份图片")
            Text("范围外引用：${value.externalReferenceCount}；缺失提示：${value.missingTargets.size}")
            Text("复制不会改变当前对话身份或采用内容。角色卡在此复制其全部版本。", color = NovexColors.SecondaryText)
            Text((value.items.map { "${it.name} · ${it.moduleCount} 个模块\n${it.key.id}" } + value.missingTargets).joinToString("\n\n"),
                modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()))
        }
        if(!busy && !loading) TextButton(onClick = { refresh++ }) { Text("重新核对预览") }
    }
    if(choosePolicy) NovexSearchableSelectionSheet("引用处理", NovexCardCopyPolicy.entries.map { option ->
        NovexSelectionAction(option.label, description = option.description) { policy = option; choosePolicy = false }
    }, "搜索复制方式", onDismissRequest = { choosePolicy = false })
    error?.let { NovexNoticeDialog("复制未完成", it) { error = null } }
}
