package novex.storage

import novex.content.*
import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest

class CardOccupied : IllegalStateException("目标卡正在编辑，内容未被覆盖；可查看或停止当前编辑")

/** 占用句柄只在当前执行内持有；关闭或进程退出释放系统文件锁。 */
class CardEditLease internal constructor(internal val root:Path,internal val ids:Set<String>,val owner:String,
                                         private val locks:List<Pair<FileChannel,FileLock>>) : Closeable {
    private var closed=false
    internal fun <T> useAccess(action:()->T):T=synchronized(this){check(!closed){"编辑占用已结束"};action()}
    override fun close()=synchronized(this){if(!closed){closed=true;locks.asReversed().forEach { (channel,lock)->try{lock.release()}finally{channel.close()} }}}
}

internal class CardEditAccess(directory:Path) {
    private val root=directory.resolve("edit-occupancy").toAbsolutePath().normalize()
    init { Files.createDirectories(root) }
    fun acquire(card:ContentDocument,target:String,owner:String):CardEditLease {
        require(owner.isNotBlank());ContentTargets.find(card,target)
        val ids=setOf(target);return CardEditLease(root,ids,owner,lock(ids))
    }
    fun <T> changes(old:ContentDocument?,next:ContentDocument,lease:CardEditLease?,extra:Set<String> = emptySet(),action:()->T):T {
        fun parts(card:ContentDocument?)=if(card==null)emptyMap() else (listOf(card.copy(internalCharacters=emptyList()))+card.internalCharacters).associateBy { it.id }
        val before=parts(old);val after=parts(next)
        val membership = if (old?.internalCharacters?.map { it.id } != next.internalCharacters.map { it.id }) setOf(next.id) else emptySet()
        val changed=(before.keys+after.keys).filter { before[it]!=after[it] }.toSet()+extra+membership
        return guarded(changed,lease,action)
    }
    fun <T> guarded(ids:Set<String>,lease:CardEditLease?,action:()->T):T {
        fun execute():T {
            if(lease!=null)require(lease.root==root){"编辑占用不属于当前存储"}
            val temporary=lock(ids-(lease?.ids?:emptySet()))
            try{return action()}finally{temporary.asReversed().forEach { (channel,held)->try{held.release()}finally{channel.close()} }}
        }
        return if(lease==null)execute() else lease.useAccess(::execute)
    }
    private fun lock(ids:Set<String>):List<Pair<FileChannel,FileLock>> {
        val acquired=mutableListOf<Pair<FileChannel,FileLock>>()
        try {
            ids.sorted().forEach { id ->
                val key=MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
                val channel=FileChannel.open(root.resolve(key),CREATE,WRITE)
                val held=try{channel.tryLock()}catch(_:OverlappingFileLockException){null}catch(failure:Exception){channel.close();throw failure}
                if(held==null){channel.close();throw CardOccupied()}
                acquired+=channel to held
            }
            return acquired
        } catch(failure:Exception){acquired.asReversed().forEach{(channel,held)->try{held.release()}finally{channel.close()}};throw failure}
    }
}
