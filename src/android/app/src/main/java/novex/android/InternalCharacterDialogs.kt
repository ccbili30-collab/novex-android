package novex.android

import com.openminis.app.ui.novex.AlertDialog
import com.openminis.app.ui.novex.Button
import com.openminis.app.ui.novex.TextButton
import com.openminis.app.ui.novex.OutlinedTextField

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import novex.storage.CardSummary

@Composable fun NewInternalCharacterDialog(busy:Boolean=false,error:String?=null,onDismiss:()->Unit,onCreate:(String)->Unit) {
    var name by rememberSaveable {mutableStateOf("")}
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text("新建角色")},text={Column {OutlinedTextField(value=name,onValueChange={name=it},label={Text("角色名称")},singleLine=true,enabled=!busy);error?.let {Text(it,color=MaterialTheme.colorScheme.error)}}},
        confirmButton={TextButton(enabled=!busy && name.isNotBlank(),onClick={onCreate(name.trim())}){Text("创建")}},dismissButton={TextButton(enabled=!busy,onClick=onDismiss){Text("取消")}})
}
@Composable fun CopyInternalCharacterDialog(model:CardSessionModel,onDismiss:()->Unit,onCopy:(String)->Unit) {
    val busy=model.state.busy
    var query by rememberSaveable {mutableStateOf("")}
    var roles by remember {mutableStateOf<List<CardSummary>?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(model){try{roles=model.sourceCharacters()}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){error="角色库读取未完成"}}
    AlertDialog(contentScrollsItself=true,onDismissRequest={if(!busy)onDismiss()},title={Text("从角色库复制")},text={Column {
        OutlinedTextField(value=query,onValueChange={query=it},label={Text("搜索角色")},singleLine=true)
        Text("副本独立保存，之后不随来源修改。",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=8.dp))
        model.state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        val visible=roles?.filter {it.name.contains(query,ignoreCase=true)}
        if(error!=null)Text(requireNotNull(error)) else if(roles==null)LinearProgressIndicator(Modifier.fillMaxWidth())
        else if(visible.isNullOrEmpty())Text("没有匹配的角色")
        LazyColumn(Modifier.heightIn(max=360.dp)){items(visible?:emptyList(),key={it.id}){role->
            CardRow(headlineContent={Text(role.name)},modifier=Modifier.clickable(enabled=!busy){onCopy(role.id)})
        }}
    }},confirmButton={},dismissButton={TextButton(enabled=!busy,onClick=onDismiss){Text("取消")}})
}
