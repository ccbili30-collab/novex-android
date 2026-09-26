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
import novex.content.ModuleRouting
import novex.content.ModuleTemporality
import novex.content.ModuleUse

@Composable internal fun ModuleOptionsDialog(module:ContentModule,busy:Boolean=false,error:String?=null,onDismiss:()->Unit,
                                             onSave:(List<String>,ModuleUse?,ModuleRouting?,ModuleTemporality?)->Unit) {
    var kind by rememberSaveable(module.id){mutableStateOf(when(module.use){ModuleUse.Always->"always";ModuleUse.Manual->"manual";is ModuleUse.Keywords->"keywords";null->"unset"})}
    // [T-stage1-tags] 路由/时间性标签；缺省（null）在 UI 即"默认/常量"——
    // 与存量卡零迁移语义一致，chip 直接以语义值呈现。
    var routing by rememberSaveable(module.id){mutableStateOf((module.routing?:ModuleRouting.DEFAULT).name)}
    var temporality by rememberSaveable(module.id){mutableStateOf((module.temporality?:ModuleTemporality.CONSTANT).name)}
    val original=module.use as? ModuleUse.Keywords
    var words by rememberSaveable(module.id){mutableStateOf(original?.words?.joinToString("\n").orEmpty())}
    var tags by rememberSaveable(module.id){mutableStateOf(module.tags.joinToString("\n"))}
    var all by rememberSaveable(module.id){mutableStateOf(original?.requireAll?:false)}
    var sensitive by rememberSaveable(module.id){mutableStateOf(original?.caseSensitive?:false)}
    fun entries(value:String)=value.lines().map {it.trim()}.filter {it.isNotEmpty()}.distinct()
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text("携带与标签")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("模块去向",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(ModuleRouting.DEFAULT.name to "默认（进开局资料包）",ModuleRouting.PER_TURN.name to "每轮注入",
            ModuleRouting.STYLE.name to "文风",ModuleRouting.STANDBY.name to "待命（按触发规则）").forEach {(value,label)->
            FilterChip(enabled=!busy,selected=routing==value,onClick={routing=value},label={Text(label)})
        }
        if(routing==ModuleRouting.STANDBY.name) {
            Text("触发规则",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            listOf("unset" to "AI（人工智能）选择","always" to "始终携带","keywords" to "条件携带","manual" to "手动携带").forEach {(value,label)->
                FilterChip(enabled=!busy,selected=kind==value,onClick={kind=value},label={Text(label)})
            }
            if(kind=="keywords") {
                OutlinedTextField(enabled=!busy,value=words,onValueChange={words=it},label={Text("关键词，每行一个")})
                Row {Checkbox(enabled=!busy,checked=all,onCheckedChange={all=it});Text("匹配全部关键词")}
                Row {Checkbox(enabled=!busy,checked=sensitive,onCheckedChange={sensitive=it});Text("区分英文大小写")}
            }
        }
        Text("时间性",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(ModuleTemporality.CONSTANT.name to "常量（游玩不变，压缩后可重注入）",
            ModuleTemporality.SNAPSHOT.name to "快照（开局状态，不重注入）").forEach {(value,label)->
            FilterChip(enabled=!busy,selected=temporality==value,onClick={temporality=value},label={Text(label)})
        }
        OutlinedTextField(enabled=!busy,value=tags,onValueChange={tags=it},label={Text("标签，每行一个")})
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(enabled=!busy && (routing!=ModuleRouting.STANDBY.name || kind!="keywords" || entries(words).isNotEmpty()),onClick={
        val use=when(kind){"always"->ModuleUse.Always;"manual"->ModuleUse.Manual;"keywords"->ModuleUse.Keywords(entries(words),sensitive,all);else->null}
        onSave(entries(tags),use,ModuleRouting.valueOf(routing),ModuleTemporality.valueOf(temporality))
    }){Text("应用")}},dismissButton={TextButton(enabled=!busy,onClick=onDismiss){Text("取消")}})
}
