package novex.android

import novex.android.ui.NovexPageTopBar
import novex.android.ui.NovexColors
import novex.android.ui.NovexIcons
import novex.android.ui.NovexSearchField

import novex.android.ui.Button
import novex.android.ui.TextButton
import novex.android.ui.Scaffold

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import novex.content.ContentTargets
import novex.content.CardKind
import novex.content.flattenModules
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ImportPage(files:FileTransferModel,reader:CardSessionModel,onClose:()->Unit) {
    val state=files.state
    var target by rememberSaveable(state.draft?.content?.id) {mutableStateOf<String?>(null)}
    val back:()->Unit={if(target!=null)target=null else {files.close();onClose()}}
    BackHandler {if(!state.busy)back()}
    Scaffold(containerColor=NovexColors.Canvas,topBar={NovexPageTopBar(title="导入预览",onBack={if(!state.busy)back()})},
        bottomBar={Row(Modifier.navigationBarsPadding().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            TextButton(enabled=!state.busy,onClick={files.discard()}){Text("取消导入")}
            Button(enabled=!state.busy && state.draft!=null,onClick={files.confirm()},modifier=Modifier.weight(1f),
                shape=RoundedCornerShape(14.dp),
                colors=ButtonDefaults.buttonColors(
                    containerColor=com.openminis.app.ui.noven.NovenColors.Mint,
                    contentColor=com.openminis.app.ui.noven.NovenColors.OnMint)){Text("确认导入")}
        }}){padding->Column(Modifier.fillMaxSize().padding(padding)) {
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(20.dp))}
        state.draft?.let {draft->
            val shown=ContentTargets.find(draft.content,target?:draft.content.id)
            CardReading(shown,reader,onCharacter={target=it},byline={
                // [A4] 导入态横幅：让读的人知道这是"即将入库的内容"而不是
                // 已有卡片；规模统计始终按整包（世界连带内部角色）计算。
                val moduleCount=draft.content.modules.flattenModules().size
                val roleCount=draft.content.internalCharacters.size
                val summary=buildString {
                    append(if(draft.content.kind==CardKind.WORLD)"世界" else "角色")
                    if(moduleCount>0)append(" · $moduleCount 个模块")
                    if(roleCount>0)append(" · $roleCount 个内部角色")
                }
                Row(Modifier.fillMaxWidth()
                    .background(NovexColors.SurfaceMuted,RoundedCornerShape(10.dp))
                    .padding(horizontal=12.dp,vertical=10.dp),
                    verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Icon(NovexIcons.Info,null,Modifier.size(16.dp),tint=NovexColors.SecondaryText)
                    Column {
                        Text("导入预览 · 确认后加入你的卡片库",style=androidx.compose.material3.MaterialTheme.typography.labelLarge,color=NovexColors.Text)
                        Text(summary,style=androidx.compose.material3.MaterialTheme.typography.labelSmall,color=NovexColors.SecondaryText)
                    }
                }
            })
        }
    }}
}
