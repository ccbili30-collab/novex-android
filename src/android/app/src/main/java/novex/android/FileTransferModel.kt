package novex.android

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import novex.content.CardKind
import novex.storage.*
import java.nio.file.Files
import java.util.UUID

interface CardImportProvider {
    fun prepareCardImport(store:CardStore,input:java.io.InputStream,name:String,kind:CardKind):CardDraft
}

data class FileTransferState(val visible:Boolean=false,val draft:CardDraft?=null,val busy:Boolean=false,
    val error:String?=null,val message:String?=null,val importedId:String?=null,val exportTarget:String?=null)

/** 系统文件接口只负责流的取得和交付；格式、草稿、保存归独立核心。 */
class FileTransferModel(application:Application):AndroidViewModel(application) {
    private val resolver=application.contentResolver
    private val store by lazy {CardStore(application.filesDir.toPath().resolve("rewrite-content"))}
    var state by mutableStateOf(FileTransferState())
        private set
    fun prepare(uri:Uri,kind:CardKind) {
        if(state.busy)return
        state=FileTransferState(visible=true,busy=true)
        launch {
            val draft=withContext(Dispatchers.IO) {
                val name=runCatching {resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {cursor->
                    if(cursor.moveToFirst())cursor.getString(0) else null
                }}.getOrNull()?.takeIf {it.isNotBlank()}?:uri.lastPathSegment?.substringAfterLast('/')?:"导入作品"
                val input=requireNotNull(resolver.openInputStream(uri)){"无法读取所选文件"}
                (getApplication<Application>() as? CardImportProvider)?.prepareCardImport(store,input,name,kind)?:CardFiles(store).prepare(input,name,kind)
            }
            state=state.copy(draft=draft)
        }
    }
    fun resume(id:String) {
        if(state.busy)return
        state=FileTransferState(visible=true,busy=true)
        launch {
            val recovered=withContext(Dispatchers.IO){CardDrafts(store).read(id) to store.open(id)}
            state=if(recovered.first!=null)state.copy(draft=recovered.first)
                else if(recovered.second!=null)FileTransferState(importedId=id,busy=true)
                else throw IllegalArgumentException("导入草稿不存在")
        }
    }
    fun confirm()=work {
        val draft=requireNotNull(state.draft)
        val saved=withContext(Dispatchers.IO){CardDrafts(store).commit(draft.content.id,draft.version)}
        state=FileTransferState(importedId=saved.content.id,busy=true)
    }
    fun discard()=work {
        state.draft?.let {draft->withContext(Dispatchers.IO){CardDrafts(store).discard(draft.content.id,draft.version)}}
        state=FileTransferState(busy=true)
    }
    fun close(){if(!state.busy)state=state.copy(visible=false)}
    fun acknowledge(){state=state.copy(importedId=null)}
    fun export(id:String,uri:Uri,targetId:String=id)=work {
        state=state.copy(exportTarget=id)
        withContext(Dispatchers.IO) {
            val temporary=getApplication<Application>().cacheDir.toPath().resolve("export-${UUID.randomUUID()}.zip")
            try {
                CardFiles(store).export(id,temporary,targetId)
                val output=requireNotNull(resolver.openOutputStream(uri,"w")){"无法写入所选位置"}
                output.use {target->Files.newInputStream(temporary).use {it.copyTo(target,65536)}}
            } finally {Files.deleteIfExists(temporary)}
        }
        state=state.copy(message="已导出")
    }
    private fun work(action:suspend ()->Unit) {
        if(state.busy)return
        state=state.copy(busy=true,error=null,message=null)
        launch(action)
    }
    private fun launch(action:suspend ()->Unit) {
        viewModelScope.launch {
            try{action()}
            catch(cancelled:CancellationException){throw cancelled}
            catch(failure:Exception){state=state.copy(error=failure.message?:"文件处理未完成")}
            finally{state=state.copy(busy=false)}
        }
    }
}
