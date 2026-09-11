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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 请求目标未加载成功时不展示旧对象，旧编辑内容仍由会话保留。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CardOpeningPage(error:String?,busy:Boolean,onRetry:()->Unit,onPrevious:(()->Unit)?,onExit:()->Unit) {
    BackHandler {if(!busy)onExit()}
    Scaffold(containerColor=NovexColors.Canvas,topBar={NovexPageTopBar(title=if(error==null)"打开作品" else "作品未打开",onBack={if(!busy)onExit()})}) {padding->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp)) {
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(error?:"正在读取作品")
            if(error!=null) {
                Button(enabled=!busy,onClick=onRetry){Text("重新读取")}
                onPrevious?.let {TextButton(enabled=!busy,onClick=it){Text("返回原作品与编辑")}}
            }
        }
    }
}
