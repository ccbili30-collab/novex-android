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

data class LibraryState(val cards:List<CardSummary> = emptyList(),val imports:List<CardSummary> = emptyList(),val edits:List<CardSummary> = emptyList(),val busy:Boolean=false,val error:String?=null,val createdId:String?=null)

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
                val drafts=CardDrafts(storage).list()
                Triple(
                    storage.list(),
                    drafts.filter {it.baseRevision==null && it.source==ChangeSource.IMPORT}
                        .map {CardSummary(it.content.id,it.content.name,it.content.kind,it.version)},
                    // 编辑草稿只列人工来源；AI 管理会话中的草稿不当作
                    // 「有未保存的修改」让用户去点。
                    drafts.filter {it.baseRevision!=null && it.source==ChangeSource.HUMAN}
                        .map {CardSummary(it.content.id,it.content.name,it.content.kind,it.version)},
                )
            }
            // 在主线程合并，避免覆盖读取期间已消费的创建结果。
            state=state.copy(cards=listing.first,imports=listing.second,edits=listing.third,error=null)
        }
    }
    fun create(name:String,kind:CardKind) {
        if(state.busy)return
        // [§9c] 直进编辑器：空名给默认名「未命名世界/角色」，名字在编辑器里再改。
        val title=name.trim().ifBlank {if(kind==CardKind.WORLD)"未命名世界" else "未命名角色"}
        val id=UUID.randomUUID().toString()
        work { storage ->
            withContext(Dispatchers.IO) {
                CardCreation(storage).create(id,title,kind,ChangeSource.HUMAN,UUID.randomUUID().toString())
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

    /** [§9c] 空卡回收：打开仍是初始空白态（名字未改、无模块/资源/内部角色）→ 静默删除。 */
    fun deleteIfPristine(id:String,isPristine:(ContentDocument)->Boolean) {
        work { storage ->
            val saved=withContext(Dispatchers.IO){storage.open(id)}?:return@work
            if(!isPristine(saved.content))return@work
            withContext(Dispatchers.IO){storage.delete(id,saved.revision)}
            state=state.copy(cards=state.cards.filterNot {it.id==id})
            refreshPending=true
        }
    }

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
