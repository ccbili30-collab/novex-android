package novex.storage

import java.nio.file.Files
import java.nio.file.Path

/** 使用安卓最低支持版本已有的缓冲读写接口，保持 UTF-8 文字及严格解码。 */
object Utf8Files {
    fun read(path:Path):String=Files.newBufferedReader(path,Charsets.UTF_8).use { it.readText() }
    fun write(path:Path,text:String) {Files.newBufferedWriter(path,Charsets.UTF_8).use { it.write(text) }}
}
