package com.openminis.app.cards

import com.openminis.app.ui.novex.TextButton

import com.openminis.app.ui.novex.Button

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.openminis.app.MinisApp
import novex.content.CardKind

/** Old navigation addresses resolve to their new editable counterpart; old tables remain archival. */
@Composable fun LegacyCardEntry(kind:String,id:String?,version:String?=null,onChat:(String)->Unit,onBack:()->Unit) {
    val app=LocalContext.current.applicationContext as MinisApp
    var root by remember(kind,id,version){mutableStateOf<String?>(null)}
    var ready by remember(kind,id,version){mutableStateOf(false)}
    var error by remember(kind,id,version){mutableStateOf<String?>(null)}
    LaunchedEffect(kind,id,version) {
        try {
            root=withContext(Dispatchers.IO) {
                if(id==null)null
                else if(IntegratedCards(app).store.isDeleted(id))error("卡片已删除")
                else if(IntegratedCards(app).store.open(id)!=null)id
                else when(kind) {
                    "world"->LegacyCards.worldId(id)
                    "game"->LegacyCards.gameId(id)
                    else->LegacyCards.roleId(version?:requireNotNull(app.novexWorkspace.character(id)){"原角色不存在"}.character.original.id)
                }
            };ready=true
        }catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){error=failure.message?:"原卡地址未能恢复"}
    }
    if(ready)IntegratedCardHost(kind=if(kind=="character")CardKind.CHARACTER else CardKind.WORLD,root=root,createOnly=id==null,onChat=onChat,onBack=onBack)
    else androidx.compose.foundation.layout.Column {Text(error?:"正在读取原卡地址");TextButton(onClick=onBack){Text("返回")}}
}
