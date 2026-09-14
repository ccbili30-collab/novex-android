package com.openminis.app.cards

import com.openminis.app.ui.novex.TextButton

import com.openminis.app.ui.novex.Button

import com.openminis.app.ui.novex.AlertDialog

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import novex.content.CardKind
import novex.android.IntegratedCardLibrary
import com.openminis.app.MinisApp

@Composable fun IntegratedCardHost(kind:CardKind?=null,root:String?=null,target:String?=null,onChat:(String)->Unit,onBack:()->Unit={},onSurface:(Boolean)->Unit={},importUri:String?=null,embedded:Boolean=false,createOnly:Boolean=false,onOpenCard:((String)->Unit)?=null) {
    val app=LocalContext.current.applicationContext as MinisApp
    val scope=rememberCoroutineScope()
    var error by remember {mutableStateOf<String?>(null)}
    var existing by remember {mutableStateOf<Triple<String,String,Boolean>?>(null)}
    var starting by remember {mutableStateOf(false)}
    var migrationFailures by remember {mutableStateOf<List<String>>(emptyList())}
    var ready by remember {mutableStateOf(false)}
    var retry by remember {mutableIntStateOf(0)}
    LaunchedEffect(retry) {
        error=null
        try {migrationFailures=withContext(Dispatchers.IO){LegacyCards(app).migrate(force=retry>0)};ready=true}
        catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){error="旧卡副本准备未完成，原数据保留：${failure.message}"}
    }
    if(!ready) {
        androidx.compose.foundation.layout.Column {
            Text(error?:"正在准备卡片资料，原数据保留")
            if(error!=null)TextButton(onClick={retry++}){Text("重试")}
            TextButton(onClick=onBack){Text("返回")}
        }
        return
    }
    androidx.compose.foundation.layout.Column {
    if(migrationFailures.isNotEmpty())TextButton(onClick={error=migrationFailures.joinToString("\n")}){Text("${migrationFailures.size} 项旧资料未迁入 · 查看原因")}
    IntegratedCardLibrary(kind,root?.takeIf {it.isNotBlank()},target?.takeIf {it.isNotBlank()},initialImportUri=importUri,showBack=!embedded,createOnly=createOnly,onOpenCard=onOpenCard,onExisting={id,target,manage->existing=Triple(id,target,manage)},onUse={id,objectId,manage->
        if(!starting){starting=true;scope.launch {
            try {
                val chat=withContext(Dispatchers.IO) {
                    val cards=IntegratedCards(app)
                    val name=novex.content.ContentTargets.find(requireNotNull(cards.store.open(id)).content,objectId).name
                    val binding=if(manage)CardBinding(managed=setOf(novex.runtime.ManagementTarget(id,objectId))) else CardBinding(primary=novex.runtime.SourceSelection(id,objectId))
                    IntegratedCardEntry.draftId(app,name,binding)
                }
                onChat(chat)
            }catch(cancelled:CancellationException){throw cancelled}catch(failure:Exception){error=failure.message?:"对话未创建"}finally{starting=false}
        }}
    },onBack=onBack,onSurface=onSurface)
    }
    existing?.let {request->ExistingCardConversation(app,novex.runtime.SourceSelection(request.first,request.second),request.third,{existing=null},{id->existing=null;onChat(id)})}
    error?.let {AlertDialog(onDismissRequest={error=null},title={Text("操作未完成")},text={androidx.compose.foundation.layout.Column {Text(it);if(migrationFailures.isNotEmpty())TextButton(onClick={error=null;ready=false;retry++}){Text("重试迁入")}}},confirmButton={TextButton(onClick={error=null}){Text("知道了")}})}
}
