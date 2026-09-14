package novex.android

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import novex.content.ContentModule

@Composable internal fun ModuleList(modules:List<ContentModule>,enabled:Boolean,onOpen:(String)->Unit,onMove:(String,String?)->Unit,footer:LazyListScope.()->Unit) {
    ReorderList(modules.map {OrderEntry(it.id,it.name)},enabled,onOpen,onMove,
        description={id->Text("${modules.single {it.id==id}.blocks.size} 个内容块")},operationLabel="模块操作",footer=footer)
}
