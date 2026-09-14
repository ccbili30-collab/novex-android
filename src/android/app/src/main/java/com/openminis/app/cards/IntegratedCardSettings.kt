package com.openminis.app.cards

import com.openminis.app.ui.novex.NovexPageTopBar
import com.openminis.app.ui.novex.NovexColors

import com.openminis.app.ui.novex.Scaffold

import com.openminis.app.ui.novex.RadioButton

import com.openminis.app.ui.novex.Checkbox

import com.openminis.app.ui.novex.TextButton

import com.openminis.app.ui.novex.Button

import com.openminis.app.ui.novex.AlertDialog

import novex.content.flattenModules
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import novex.runtime.*

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable fun IntegratedCardSettings(chat:String,onBack:()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val app=context.applicationContext as com.openminis.app.MinisApp
    val model:com.openminis.app.ui.chat.ChatViewModel=androidx.lifecycle.viewmodel.compose.viewModel(
        viewModelStoreOwner=com.openminis.app.ui.chat.ChatViewModelStore.ownerFor(chat),
        factory=com.openminis.app.ui.chat.ChatViewModel.factory(chat,app.chatRepository,app.providerRepository,
            appContext=app,memoryRepository=app.memoryRepository,skillRepository=app.skillRepository,mcpRepository=app.mcpRepository))
    val ready by model.conversationSettingsReady.collectAsState()
    val cards=remember {IntegratedCards(context)}
    val saver=remember {Saver<CardBinding?,String>(save={it?.encode()?:"null"},restore={if(it=="null")null else CardBinding.decode(it)})}
    var baseline by rememberSaveable(chat,stateSaver=saver) {mutableStateOf<CardBinding?>(null)}
    var draft by rememberSaveable(chat,stateSaver=saver) {mutableStateOf<CardBinding?>(null)}
    var hydrated by rememberSaveable(chat){mutableStateOf(false)}
    var discard by remember {mutableStateOf(false)}
    var choices by remember {mutableStateOf<List<Pair<SourceSelection,String>>>(emptyList())}
    var modules by remember {mutableStateOf<Map<SourceSelection,List<Pair<String,String>>>>(emptyMap())}
    var expanded by remember {mutableStateOf<SourceSelection?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(true)}
    LaunchedEffect(chat,ready) {
        if(!ready)return@LaunchedEffect
        try {
            val loaded=withContext(Dispatchers.IO){
                run {LegacyCards(app).migrate();model.integratedCardBinding()} to cards.store.list().flatMap {summary->
                    val root=requireNotNull(cards.store.open(summary.id)).content
                    listOf(SourceSelection(root.id) to root.name)+root.internalCharacters.map {SourceSelection(root.id,it.id) to "${root.name} · ${it.name}"}
                }
            };if(!hydrated){baseline=loaded.first;draft=loaded.first?:CardBinding();hydrated=true}
            val known=loaded.second.map {it.first}.toSet()
            val all=listOfNotNull(loaded.first?.primary)+loaded.first?.backgrounds.orEmpty()+loaded.first?.managed.orEmpty().map {SourceSelection(it.rootId,it.targetId)}
            choices=loaded.second+(all.distinct()-known).map {it to "作品未找到（可取消关联）"}
            modules=withContext(Dispatchers.IO){loaded.second.associate {(source,_)->
                val card=novex.content.ContentTargets.find(requireNotNull(cards.store.open(source.rootId)).content,source.targetId)
                source to (listOf(card)+card.internalCharacters).flatMap {c->c.modules.flattenModules().map {it.id to "${c.name} · ${it.name.ifBlank {"未命名模块"}}"}}
            }}
        }catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){error=failure.message}finally{busy=false}
    }
    val back:()->Unit={if(!busy){if(draft!=null && draft!=(baseline?:CardBinding()))discard=true else onBack()}}
    androidx.activity.compose.BackHandler(enabled=!WindowInsets.isImeVisible){back()}
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("放弃未保存的设置？")},
        confirmButton={TextButton(onClick={discard=false;onBack()}){Text("放弃更改")}},
        dismissButton={TextButton(onClick={discard=false}){Text("继续设置")}})
    Scaffold(containerColor=NovexColors.Canvas,topBar={NovexPageTopBar(title="卡片采用与管理",onBack=back)},bottomBar={
        Button(enabled=!busy && draft!=null,onClick={busy=true;scope.launch {
            try {model.saveIntegratedCardBinding(requireNotNull(draft),baseline);onBack()}
            catch(failure:Exception){error=failure.message}finally{busy=false}
        }},modifier=Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)){Text("保存")}
    }) {padding->LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp)) {
        error?.let {item {Text(it,color=MaterialTheme.colorScheme.error)}}
        item {Text("互动对象只选一个；背景可多选；允许管理不等于作为背景。工具权限继续使用对话设置。")}
        item {TextButton(enabled=!busy,onClick={draft=draft?.copy(primary=null)}){Text("清除主要互动对象")}}
        items(choices,key={it.first.rootId+":"+it.first.targetId}){(target,name)->
            val current=draft
            Text(name,style=MaterialTheme.typography.titleMedium)
            androidx.compose.foundation.layout.FlowRow {
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){RadioButton(selected=current?.primary==target,enabled=!busy,onClick={draft=current?.copy(primary=target)})
                Text("互动")}
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(checked=current?.backgrounds?.contains(target)==true,enabled=!busy,onCheckedChange={yes->draft=current?.copy(backgrounds=if(yes)(current.backgrounds+target).distinct() else current.backgrounds-target)})
                Text("背景")}
                val managed=ManagementTarget(target.rootId,target.targetId)
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(checked=current?.managed?.contains(managed)==true,enabled=!busy,onCheckedChange={yes->draft=current?.copy(managed=if(yes)current.managed+managed else current.managed-managed)})
                Text("管理")}
            }
            if(current?.primary==target || current?.backgrounds?.contains(target)==true) {
                TextButton(onClick={expanded=if(expanded==target)null else target}){Text("模块携带")}
                if(expanded==target)modules[target].orEmpty().forEach {(id,label)->
                    Text(label,style=MaterialTheme.typography.bodyMedium)
                    Row {
                        listOf(null to "默认",true to "必带",false to "不带").forEach {(rule,title)->
                            TextButton(enabled=!busy,onClick={draft=draft?.let {d->d.copy(overrides=if(rule==null)d.overrides-id else d.overrides+(id to rule))}}) {
                                Text((if(current?.overrides?.get(id)==rule)"✓ " else "")+title)
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
        }
    }}
}
