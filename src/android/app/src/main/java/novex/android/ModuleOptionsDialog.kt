package novex.android

import com.openminis.app.ui.novex.FilterChip

import com.openminis.app.ui.novex.Checkbox

import com.openminis.app.ui.novex.AlertDialog
import com.openminis.app.ui.novex.Button
import com.openminis.app.ui.novex.TextButton
import com.openminis.app.ui.novex.OutlinedTextField

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import novex.content.ContentModule
import novex.content.ModuleUse

@Composable internal fun ModuleOptionsDialog(module:ContentModule,busy:Boolean=false,error:String?=null,onDismiss:()->Unit,onSave:(List<String>,ModuleUse?)->Unit) {
    var kind by rememberSaveable(module.id){mutableStateOf(when(module.use){ModuleUse.Always->"always";ModuleUse.Manual->"manual";is ModuleUse.Keywords->"keywords";null->"unset"})}
    val original=module.use as? ModuleUse.Keywords
    var words by rememberSaveable(module.id){mutableStateOf(original?.words?.joinToString("\n").orEmpty())}
    var tags by rememberSaveable(module.id){mutableStateOf(module.tags.joinToString("\n"))}
    var all by rememberSaveable(module.id){mutableStateOf(original?.requireAll?:false)}
    var sensitive by rememberSaveable(module.id){mutableStateOf(original?.caseSensitive?:false)}
    fun entries(value:String)=value.lines().map {it.trim()}.filter {it.isNotEmpty()}.distinct()
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text("携带与标签")},text={Column() {
        listOf("unset" to "AI（人工智能）选择","always" to "始终携带","keywords" to "条件携带","manual" to "手动携带").forEach {(value,label)->
            FilterChip(enabled=!busy,selected=kind==value,onClick={kind=value},label={Text(label)})
        }
        if(kind=="keywords") {
            OutlinedTextField(enabled=!busy,value=words,onValueChange={words=it},label={Text("关键词，每行一个")})
            Row {Checkbox(enabled=!busy,checked=all,onCheckedChange={all=it});Text("匹配全部关键词")}
            Row {Checkbox(enabled=!busy,checked=sensitive,onCheckedChange={sensitive=it});Text("区分英文大小写")}
        }
        OutlinedTextField(enabled=!busy,value=tags,onValueChange={tags=it},label={Text("标签，每行一个")})
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(enabled=!busy && (kind!="keywords" || entries(words).isNotEmpty()),onClick={
        val use=when(kind){"always"->ModuleUse.Always;"manual"->ModuleUse.Manual;"keywords"->ModuleUse.Keywords(entries(words),sensitive,all);else->null}
        onSave(entries(tags),use)
    }){Text("应用")}},dismissButton={TextButton(enabled=!busy,onClick=onDismiss){Text("取消")}})
}
