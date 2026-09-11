package com.openminis.app.cards

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.openminis.app.MinisApp
import com.openminis.app.ui.novex.*
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.launch
import novex.runtime.SourceSelection
import novex.runtime.ManagementTarget

/** 使用既有对话设置保存器；不直接改数据库，不创建卡片副本。 */
@Composable internal fun ExistingCardConversation(app:MinisApp,source:SourceSelection,manage:Boolean,
    onDismiss:()->Unit,onOpen:(String)->Unit) {
    val sessions by app.chatRepository.observeSessions().collectAsState(initial=emptyList())
    var chosen by remember {mutableStateOf<String?>(null)}
    var query by remember {mutableStateOf("")}
    var usage by remember {mutableStateOf(if(manage)"管理" else "背景")}
    var saving by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    val id=chosen
    val model:ChatViewModel?=if(id!=null)androidx.lifecycle.viewmodel.compose.viewModel(
        viewModelStoreOwner=ChatViewModelStore.ownerFor(id),
        factory=ChatViewModel.factory(id,app.chatRepository,app.providerRepository,appContext=app,
            memoryRepository=app.memoryRepository,skillRepository=app.skillRepository,mcpRepository=app.mcpRepository)) else null
    val ready=if(model!=null)model.conversationSettingsReady.collectAsState().value else false
    NovexContentDialog("用于已有对话",onDismiss={if(!saving)onDismiss()},confirmButton={
        TextButton(enabled=ready && !saving,onClick={scope.launch {
            saving=true;error=null
            try {
                val current=requireNotNull(model).integratedCardBinding()
                val old=current?:CardBinding()
                val next=when(usage){
                    "互动"->old.copy(primary=source)
                    "管理"->old.copy(managed=old.managed+ManagementTarget(source.rootId,source.targetId))
                    else->old.copy(backgrounds=(old.backgrounds+source).distinct())
                }
                model.saveIntegratedCardBinding(next,current)
                onOpen(requireNotNull(id))
            }catch(failure:Exception){if(failure is kotlinx.coroutines.CancellationException)throw failure;error=failure.message}
            finally{saving=false}
        }}){Text(if(saving)"保存中" else "保存并打开")}
    }) {
        OutlinedTextField(value=query,onValueChange={query=it},label={Text("搜索对话")},enabled=!saving)
        Row {listOf("互动","背景","管理").forEach {value->TextButton(enabled=!saving,onClick={usage=value}){Text(if(usage==value)"✓ $value" else value)}}}
        Text(when(usage){"互动"->"替换这段对话的主要互动对象";"管理"->"允许编辑这张卡，不改变回答身份";else->"加入参考资料，不授予编辑权限"})
        LazyColumn(Modifier.heightIn(max=320.dp)) {
            items(sessions.filter {it.title.orEmpty().contains(query,true)},key={it.id}){session->
                TextButton(enabled=!saving,onClick={chosen=session.id;error=null}){Text((if(id==session.id)"✓ " else "")+(session.title?:"未命名对话"))}
            }
        }
        if(sessions.isEmpty())Text("还没有可选对话")
        error?.let {Text(it)}
    }
}
