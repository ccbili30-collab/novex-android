package novex.storage

import novex.content.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class ModuleUseStorageTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun `携带设置与自定义标签保存复制及导出还原保持完整`() {
        val store=CardStore(temporary.newFolder().toPath())
        val modules=listOf(ContentModule("a","身份",emptyList(),listOf("主要人物"),ModuleUse.Always),
            ContentModule("b","港口",emptyList(),listOf("地理","自定义"),ModuleUse.Keywords(listOf("港口","渡船"),false,true)),
            ContentModule("c","手动资料",emptyList(),use=ModuleUse.Manual))
        store.save(ContentDocument("role",CardKind.CHARACTER,"守塔人",modules),null,ChangeSource.HUMAN,"first")
        assertEquals(modules,store.open("role")!!.content.modules)
        val drafts=CardDrafts(store)
        val world=drafts.create(ContentDocument("world",CardKind.WORLD,"小镇"))
        val copied=CardCharacters(store).copy("world",world.version,"role")
        drafts.commit("world",copied.version)
        val zip=temporary.root.toPath().resolve("world.zip");CardFiles(store).export("world",zip)
        val isolated=CardStore(temporary.newFolder().toPath())
        val imported=CardFiles(isolated).prepare(Files.newInputStream(zip),"ignored",CardKind.CHARACTER)
        val restored=imported.content.internalCharacters.single().modules
        assertEquals(modules.map { it.use },restored.map { it.use })
        assertEquals(modules.map { it.tags },restored.map { it.tags })
        assertNotEquals(modules.map { it.id },restored.map { it.id })
    }
    @Test fun `无新字段的旧实验模块仍可读取且不推断携带未知规则拒绝`() {
        val source=ContentDocument("role",CardKind.CHARACTER,"旧卡",listOf(ContentModule("m","模块",emptyList())))
        val encoded=CardStructureCodec.encode(source)
        val module=encoded.getJSONArray("modules").getJSONObject(0)
        assertFalse(module.has("tags"));assertFalse(module.has("use"))
        assertEquals(source,CardStructureCodec.decode(encoded))
        module.put("tags","不能静默丢弃的标签")
        assertThrows(org.json.JSONException::class.java){CardStructureCodec.decode(encoded)}
        module.remove("tags")
        module.put("use",org.json.JSONObject().put("kind","unsupported"))
        assertThrows(IllegalStateException::class.java){CardStructureCodec.decode(encoded)}
    }
}
