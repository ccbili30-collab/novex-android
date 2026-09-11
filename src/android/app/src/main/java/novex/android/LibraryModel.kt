package novex.android

import android.app.Application
import android.os.FileObserver
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import novex.content.*
import novex.storage.*
import java.util.UUID

data class LibraryState(val cards:List<CardSummary> = emptyList(),val imports:List<CardSummary> = emptyList(),val busy:Boolean=false,val error:String?=null,val createdId:String?=null)

/** 列表观察正式摘要的发布；人工、AI 与导入保存都从同一目录进入库。 */
class LibraryModel(application:Application):AndroidViewModel(application) {
    private val location=application.filesDir.toPath().resolve("rewrite-content")
    private var store:CardStore?=null
    private var observer:FileObserver?=null
    private var refreshPending=false
    var state by mutableStateOf(LibraryState())
        private set
    init {refresh()}

    fun refresh() {
        if(state.busy){refreshPending=true;return}
        work { storage ->
            val listing=withContext(Dispatchers.IO) {
                storage.list() to CardDrafts(storage).list()
                    .filter {it.baseRevision==null && it.source==ChangeSource.IMPORT}
                    .map {CardSummary(it.content.id,it.content.name,it.content.kind,it.version)}
            }
            // 在主线程合并，避免覆盖读取期间已消费的创建结果。
            state=state.copy(cards=listing.first,imports=listing.second,error=null)
        }
    }
    fun create(name:String,kind:CardKind) {
        if(name.isBlank() || state.busy)return
        val id=UUID.randomUUID().toString()
        work { storage ->
            withContext(Dispatchers.IO) {
                CardCreation(storage).create(id,name.trim(),kind,ChangeSource.HUMAN,UUID.randomUUID().toString())
            }
            // 保存回执不依赖后续列表读取；列表失败也不能诱使用户重复创建。
            state=state.copy(createdId=id)
            refreshPending=true
        }
    }
    fun delete(card:CardSummary,onDeleted:()->Unit={}) {
        work { storage ->
            withContext(Dispatchers.IO){storage.delete(card.id,card.revision)}
            state=state.copy(cards=state.cards.filterNot {it.id==card.id})
            refreshPending=true
            onDeleted()
        }
    }
    fun acknowledgeCreation(){state=state.copy(createdId=null)}

    private suspend fun storage():CardStore {
        store?.let {return it}
        val initialized=withContext(Dispatchers.IO){CardStore(location)}
        store=initialized
        // 正式摘要通过原子替换发布；监听移入，也覆盖直接写入和移除。
        observer=object:FileObserver(location.resolve("heads").toString(),MOVED_TO or CLOSE_WRITE or DELETE or MOVED_FROM) {
            override fun onEvent(event:Int,path:String?) {
                if(path?.matches(Regex("[0-9a-f]{64}"))==true)
                    viewModelScope.launch {refresh()}
            }
        }.also {it.startWatching()}
        return initialized
    }
    private fun work(action:suspend (CardStore)->Unit) {
        if(state.busy)return
        state=state.copy(busy=true,error=null)
        viewModelScope.launch {
            try {action(storage())}
            catch(failure:Exception){
                if(failure is kotlinx.coroutines.CancellationException)throw failure
                state=state.copy(error=failure.message?:"作品读取或保存未完成")
            } finally {
                state=state.copy(busy=false)
                if(refreshPending){refreshPending=false;refresh()}
            }
        }
    }
    override fun onCleared(){observer?.stopWatching();observer=null;super.onCleared()}
}
