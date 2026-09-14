package novex.runtime

import novex.content.*
import novex.storage.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CheckpointPreferencesTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `设置保持类型和选择拒绝冲突重复字段及凭据扩展`() {
        val value=CheckpointPreferences(setOf("off"),setOf("manual"),"source-version","模型",1000000)
        assertEquals(value,CheckpointPreferences.decode(value.encode()))
        val raw=JSONObject(value.encode())
        assertThrows(IllegalArgumentException::class.java){CheckpointPreferences.decode(raw.put("contextCapacity",4096.5).toString())}
        assertThrows(IllegalArgumentException::class.java){CheckpointPreferences.decode(JSONObject(value.encode()).put("apiKey","不要保存").toString())}
        assertThrows(IllegalArgumentException::class.java){CheckpointPreferences(setOf("same"),setOf("same"),null,null,64000).encode()}
        assertThrows(IllegalArgumentException::class.java){CheckpointPreferences.decode(JSONObject(value.encode()).put("manualModules",org.json.JSONArray(listOf("manual","manual"))).toString())}
    }
    @Test fun `世界内部角色资料与草稿还原分配新版本不接受旧写入版本`() {
        val cards=CardStore(temp.newFolder().toPath())
        cards.save(ContentDocument("world",CardKind.WORLD,"世界",internalCharacters=listOf(ContentDocument("role",CardKind.CHARACTER,"角色",listOf(
            ContentModule("manual","手动资料",emptyList(),use=ModuleUse.Manual),ContentModule("off","暂停资料",emptyList()))))),null,ChangeSource.HUMAN,"first")
        val session=ConversationSession("chat","对话",SourceSelection("world"),CardKind.WORLD)
        val settings=CheckpointPreferences(setOf("off"),setOf("manual"),"old-version","模型",1000000)
        val text="原始草稿🌊\n".repeat(100000)
        val archive=ConversationCheckpoints(temp.newFolder().toPath())
        ConversationCheckpointWriter(archive,cards,temp.newFolder().toPath()).save(session,CardToolPolicy(emptySet()),"save","存档"){
            listOf(CheckpointSection("settings"){settings.encode().byteInputStream()},CheckpointSection("input-draft"){text.byteInputStream()})
        }
        val sources=CheckpointSourceRestoration(temp.newFolder().toPath(),archive).prepare("chat","save")
        val target=temp.newFolder().toPath();assertTrue(CheckpointPreferenceRestoration.restore(archive,sources,target))
        val restored=ConversationInputDrafts(target.resolve("input-drafts"));val draft=restored.read("chat")
        assertEquals(text,draft.text);assertNotEquals("old-version",draft.version)
        val metadata=JSONObject(Utf8Files.read(target.resolve("preferences.json")))
        assertEquals(settings,CheckpointPreferences.decode(metadata.getJSONObject("settings").toString()))
        assertEquals(draft.version,metadata.getString("restoredInputVersion"))
        assertThrows(IllegalStateException::class.java){restored.save("chat","old-version","旧页面覆盖")}
        assertEquals(text,restored.read("chat").text)
        restored.save("chat",draft.version,"后续修改")
        assertEquals(text,archive.open("chat","save","input-draft").bufferedReader().use {it.readText()})
        val original=archive.read("chat","save")!!
        archive.save("chat","bad","缺模块",original.sections.keys.map {key->CheckpointSection(key){
            if(key=="settings")settings.copy(manual=setOf("missing")).encode().byteInputStream() else archive.open("chat","save",key)
        }})
        val badSources=CheckpointSourceRestoration(temp.newFolder().toPath(),archive).prepare("chat","bad")
        val badTarget=temp.newFolder().toPath()
        assertThrows(IllegalArgumentException::class.java){CheckpointPreferenceRestoration.restore(archive,badSources,badTarget)}
        assertNull(ConversationInputDrafts(badTarget.resolve("input-drafts")).read("chat").version)
        assertFalse(java.nio.file.Files.exists(badTarget.resolve("preferences.json")))
    }
}
