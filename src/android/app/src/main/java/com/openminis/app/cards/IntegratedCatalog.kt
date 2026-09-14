package com.openminis.app.cards

import com.openminis.app.MinisApp
import com.openminis.app.novex.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import novex.content.CardKind
import novex.storage.CardSummary
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.channels.awaitClose

/** 前端目录仅投影新存储。旧地址只在此保留兼容映射，不重新读取旧卡业务。 */
class IntegratedCatalog(private val app:MinisApp) {
    suspend fun contains(address:NovexContentAddress):Boolean = withContext(Dispatchers.IO) {
        if(address.kind==NovexContentKind.CREATIVE_ARTIFACT)
            app.database.novexWorkGroupDao().targetExists(address.kind.name,address.id)
        else contains(address,IntegratedCards(app).store)
    }
    fun changes()=callbackFlow {
        val directory=java.io.File(app.filesDir,"rewrite-content/heads")
        check(directory.isDirectory || directory.mkdirs()){"卡片目录无法读取"}
        val watcher=object:android.os.FileObserver(directory.path,MOVED_TO or MOVED_FROM or CLOSE_WRITE or DELETE) {
            override fun onEvent(event:Int,path:String?){if(path?.matches(Regex("[0-9a-f]{64}"))==true)trySend(Unit)}
        }
        watcher.startWatching();trySend(Unit)
        awaitClose {watcher.stopWatching()}
    }.conflate()
    suspend fun directory():List<NovexLibraryEntry> = withContext(Dispatchers.IO) {
        LegacyCards(app).migrate()
        IntegratedCards(app).store.list().map {card->NovexLibraryEntry(address(card),card.name,if(card.kind==CardKind.WORLD)"世界" else "角色")}+
            app.creativeArtifactRepository.availableArtifacts().map {NovexLibraryEntry(it.address,NovexDisplayName.file(it.title),"文件")}
    }
    companion object {
        fun contains(address:NovexContentAddress,store:novex.storage.CardStore):Boolean {
            val id=root(address,store)?:return false
            val saved=store.open(id)?:return false
            val summary=CardSummary(saved.content.id,saved.content.name,saved.content.kind,saved.revision)
            return IntegratedCatalog.address(summary)==address
        }
        fun address(card:CardSummary):NovexContentAddress = when {
            card.id.startsWith("legacy-role:")->NovexContentAddress.characterVersion(card.id.removePrefix("legacy-role:"))
            card.id.startsWith("legacy-world:")->NovexContentAddress.world(card.id.removePrefix("legacy-world:"))
            card.id.startsWith("legacy-game:")->NovexContentAddress.interactiveFiction(card.id.removePrefix("legacy-game:"))
            card.kind==CardKind.WORLD->NovexContentAddress.world(card.id)
            else->NovexContentAddress.characterVersion(card.id)
        }
        fun root(address:NovexContentAddress,store:novex.storage.CardStore):String? {
            if(store.open(address.id)!=null)return address.id
            val id=when(address.kind){
                NovexContentKind.WORLD->LegacyCards.worldId(address.id)
                NovexContentKind.CHARACTER_VERSION->LegacyCards.roleId(address.id)
                NovexContentKind.INTERACTIVE_FICTION->LegacyCards.gameId(address.id)
                else->return null
            }
            return id.takeIf {store.open(it)!=null}
        }
    }
}
