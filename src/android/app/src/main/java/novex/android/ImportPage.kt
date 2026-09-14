package novex.android

import com.openminis.app.ui.novex.NovexPageTopBar
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexIcons
import com.openminis.app.ui.novex.NovexSearchField

import com.openminis.app.ui.novex.Button
import com.openminis.app.ui.novex.TextButton
import com.openminis.app.ui.novex.Scaffold

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import novex.content.ContentTargets
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
            Button(enabled=!state.busy && state.draft!=null,onClick={files.confirm()},modifier=Modifier.weight(1f)){Text("确认导入")}
        }}){padding->Column(Modifier.fillMaxSize().padding(padding)) {
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(20.dp))}
        state.draft?.let {draft->
            val shown=ContentTargets.find(draft.content,target?:draft.content.id)
            CardReading(shown,reader,onCharacter={target=it})
        }
    }}
}
