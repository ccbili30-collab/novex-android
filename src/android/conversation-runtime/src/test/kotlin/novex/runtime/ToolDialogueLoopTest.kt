package novex.runtime

import com.sun.net.httpserver.HttpServer
import novex.content.*
import novex.storage.*
import novex.model.*
import novex.conversation.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI
import java.net.InetSocketAddress

class ToolDialogueLoopTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun setup():Pair<CardStore,CardToolRound> {
        val store=CardStore(temporary.newFolder().toPath())
        store.save(ContentDocument("card",CardKind.CHARACTER,"角色"),null,ChangeSource.HUMAN,"initial")
        return store to CardToolRound(CardToolCoordinator(store,TurnJournal(temporary.newFolder().toPath())),CardToolReader(store))
    }
    private val policy=CardToolPolicy(setOf(ManagementTarget("card")))
    private val request=TextRequest("local-model",listOf(WireMessage("user","把角色改名为新名称")),512)
    private fun measure(text:String)=TokenMeasurement(text.toByteArray().size.toLong(),"测试字节估算",true)
    private fun tool(id:String,name:String,args:JSONObject)=JSONObject().put("content","").put("tool_calls",JSONArray().put(JSONObject().put("id",id).put("type","function").put("function",JSONObject().put("name",name).put("arguments",args.toString()))))
    private fun base()=JSONObject().put("root_id","card").put("target_id","card")
    private fun withServer(reply:(JSONObject,Int)->JSONObject,block:(()->ChatCompletionCall,()->Int)->Unit) {
        var count=0;val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/"){exchange ->
            val input=JSONObject(exchange.requestBody.bufferedReader().use { it.readText() });count++
            val message=reply(input,count)
            val body=JSONObject().put("choices",JSONArray().put(JSONObject().put("message",message).put("finish_reason",if(message.has("tool_calls"))"tool_calls" else "stop"))).toString().toByteArray()
            exchange.sendResponseHeaders(200,body.size.toLong());exchange.responseBody.use{it.write(body)}
        };server.start()
        try{block({ChatCompletionCall(ModelEndpoint(URI("http://127.0.0.1:${server.address.port}/"),null))},{count})}finally{server.stop(0)}
    }
    @Test fun `一个运行自动读取真实版本改名保存并取得最终回复`() {
        val(store,rounds)=setup()
        withServer({input,n->when(n){
            1->tool("read","read_card",base())
            2->{val history=input.getJSONArray("messages");val read=JSONObject(history.getJSONObject(history.length()-1).getString("content"));tool("write","rename_card",base().put("draft_version",read.getString("draft_version")).put("name","新名称"))}
            else->{val history=input.getJSONArray("messages");assertEquals("saved",JSONObject(history.getJSONObject(history.length()-1).getString("content")).getString("status"));JSONObject().put("content","已保存新名称。")}
        }}){factory,count->
            val result=ToolDialogueLoop(rounds,factory).run("chat","turn",request,ModelCapacity(128000),128000,5,{policy},::measure) as ToolLoopResult.Finished
            assertEquals(ModelResult.Reply("已保存新名称。",null,null),result.result)
            assertEquals(3,count());assertEquals(2,result.outcomes.size)
            assertEquals("新名称",store.open("card")!!.content.name)
            assertEquals(store.open("card")!!.revision,(result.outcomes.last().result as CardToolResult.Saved).revision)
        }
    }
    @Test fun `批准暂停不发后续请求预算结束保留已保存事实`() {
        val(store,rounds)=setup()
        withServer({_,_->tool("write","rename_card",base().put("draft_version","saved:initial").put("name","新名称"))}){factory,count->
            val waiting=ToolDialogueLoop(rounds,factory).run("chat","approval",request,ModelCapacity(128000),128000,5,{policy.copy(permission=ToolPermission.APPROVAL)},::measure)
            assertTrue(waiting is ToolLoopResult.Waiting);assertEquals(1,count());assertEquals("角色",store.open("card")!!.content.name)
            val limited=ToolDialogueLoop(rounds,factory).run("chat","free",request,ModelCapacity(128000),128000,1,{policy},::measure) as ToolLoopResult.BudgetReached
            assertEquals("新名称",store.open("card")!!.content.name);assertTrue(limited.outcomes.single().result is CardToolResult.Saved)
            assertEquals("tool",limited.next.messages.last().role);assertEquals(2,count())
        }
    }
    @Test fun `回复期间撤销权限会拒绝修改且下一请求没有工具接口`() {
        val(store,rounds)=setup();var current=policy
        withServer({input,n->if(n==1){current=policy.copy(permission=ToolPermission.READ_ONLY);tool("write","rename_card",base().put("draft_version","saved:initial").put("name","不应保存"))}
            else {assertFalse(input.has("tools"));JSONObject().put("content","当前不允许编辑。")}}){factory,count->
            val result=ToolDialogueLoop(rounds,factory).run("chat","turn",request,ModelCapacity(128000),128000,3,{current},::measure) as ToolLoopResult.Finished
            assertEquals(CardToolResult.Denied,result.outcomes.single().result);assertEquals("角色",store.open("card")!!.content.name);assertEquals(2,count())
        }
    }
    @Test fun `停止或容量不足不向服务发送且不会编造成功回复`() {
        val(_,rounds)=setup()
        withServer({_,_->error("不应收到请求")}){factory,count->
            val stop=DialogueStop();stop.stop()
            val cancelled=ToolDialogueLoop(rounds,factory).run("chat","stopped",request,ModelCapacity(128000),128000,2,{policy},::measure,stop) as ToolLoopResult.Finished
            assertEquals(ModelResult.Cancelled,cancelled.result)
            val blocked=ToolDialogueLoop(rounds,factory).run("chat","blocked",request,ModelCapacity(null),128000,2,{policy},::measure) as ToolLoopResult.Finished
            assertTrue(blocked.result is ModelResult.NotSent);assertEquals(0,count());assertEquals(0,blocked.requests)
        }
    }
    @Test fun `预算暂停重新打开继续只使用剩余累计预算`() {
        val(store,rounds)=setup();val directory=temporary.newFolder().toPath()
        withServer({_,n->if(n==1)tool("write","rename_card",base().put("draft_version","saved:initial").put("name","新名称")) else JSONObject().put("content","已完成")}){factory,count->
            val first=ToolDialogueLoop(rounds,factory).run("chat","turn",request,ModelCapacity(128000),128000,1,{policy},::measure) as ToolLoopResult.BudgetReached
            PausedLoopStore(directory).save("chat","turn",null,LoopBudget(ModelCapacity(128000),128000,1),first)
            val paused=PausedLoopStore(directory).load("chat","turn")!!
            val same=ToolDialogueLoop(rounds,factory).resume(paused,{policy},::measure) as ToolLoopResult.BudgetReached
            assertEquals(1,same.requests);assertEquals(1,count())
            val revision=store.open("card")!!.revision
            val finished=ToolDialogueLoop(rounds,factory).resume(paused,{policy},::measure,maximumRequests=2) as ToolLoopResult.Finished
            assertEquals(2,finished.requests);assertEquals(2,count());assertEquals(first.outcomes,finished.outcomes)
            assertEquals(revision,store.open("card")!!.revision)
            assertEquals(ModelResult.Reply("已完成",null,null),finished.result)
        }
    }
    @Test fun `批准恢复不重复累计结果且撤权时不执行卡片操作`() {
        val(store,rounds)=setup();val directory=temporary.newFolder().toPath()
        val approval=policy.copy(permission=ToolPermission.APPROVAL)
        withServer({input,n->if(n==1)tool("write","rename_card",base().put("draft_version","saved:initial").put("name","不应修改")) else {
            assertFalse(input.has("tools"));assertEquals("denied",JSONObject(input.getJSONArray("messages").getJSONObject(2).getString("content")).getString("status"))
            JSONObject().put("content","权限已撤销")
        }}){factory,count->
            val first=ToolDialogueLoop(rounds,factory).run("chat","turn",request,ModelCapacity(128000),128000,3,{approval},::measure) as ToolLoopResult.Waiting
            PausedLoopStore(directory).save("chat","turn",null,LoopBudget(ModelCapacity(128000),128000,3),first)
            val paused=PausedLoopStore(directory).load("chat","turn")!!
            val still=ToolDialogueLoop(rounds,factory).resume(paused,{approval},::measure) as ToolLoopResult.Waiting
            assertEquals(1,count());assertEquals(1,still.requests);assertEquals(1,still.outcomes.size)
            val finished=ToolDialogueLoop(rounds,factory).resume(paused,{policy.copy(permission=ToolPermission.READ_ONLY)},::measure) as ToolLoopResult.Finished
            assertEquals(2,count());assertEquals(2,finished.requests)
            assertEquals(listOf(CardToolResult.Denied),finished.outcomes.map { it.result })
            assertEquals("角色",store.open("card")!!.content.name)
        }
    }

    @Test fun `重新打开批准确认后自动回送保存结果取得最终回复`() {
        val cards=temporary.newFolder().toPath();val journals=temporary.newFolder().toPath();val checkpoints=temporary.newFolder().toPath()
        val store=CardStore(cards)
        store.save(ContentDocument("card",CardKind.CHARACTER,"角色"),null,ChangeSource.HUMAN,"initial")
        val approval=policy.copy(permission=ToolPermission.APPROVAL)
        val rounds=CardToolRound(CardToolCoordinator(store,TurnJournal(journals)),CardToolReader(store))
        withServer({input,n->if(n==1)tool("write","rename_card",base().put("draft_version","saved:initial").put("name","确认名称")) else {
            assertEquals("saved",JSONObject(input.getJSONArray("messages").getJSONObject(2).getString("content")).getString("status"))
            JSONObject().put("content","已保存")
        }}){factory,count->
            val first=ToolDialogueLoop(rounds,factory).run("chat","turn",request,ModelCapacity(128000),128000,3,{approval},::measure) as ToolLoopResult.Waiting
            PausedLoopStore(checkpoints).save("chat","turn",null,LoopBudget(ModelCapacity(128000),128000,3),first)
            val reopened=CardStore(cards);val coordinator=CardToolCoordinator(reopened,TurnJournal(journals))
            val paused=PausedLoopStore(checkpoints).load("chat","turn")!!
            val pending=(paused.state as ToolLoopResult.Waiting).pending
            val command=CardToolProtocol.parse("4:chat4:turn",pending.response.calls.single())
            coordinator.select(command,true)
            val confirmed=coordinator.confirm(command,approval) as CardToolResult.Saved
            val finished=ToolDialogueLoop(CardToolRound(coordinator,CardToolReader(reopened)),factory).resume(paused,{approval},::measure) as ToolLoopResult.Finished
            assertEquals(2,count());assertEquals(2,finished.requests)
            assertEquals(listOf(confirmed),finished.outcomes.map { it.result })
            assertEquals(confirmed.revision,reopened.open("card")!!.revision)
            assertEquals(ModelResult.Reply("已保存",null,null),finished.result)
        }
    }

    @Test fun `持续恢复完成登记重开同一暂停点不重复请求`() {
        val(_,rounds)=setup();val checkpoints=temporary.newFolder().toPath();val runs=temporary.newFolder().toPath()
        val pause=PausedLoopStore(checkpoints).save("chat","turn",null,LoopBudget(ModelCapacity(128000),128000,1),ToolLoopResult.BudgetReached(request,1,emptyList()))
        withServer({_,_->JSONObject().put("content","恢复完成")}){factory,count->
            fun runner()=PersistentToolDialogue(PausedLoopStore(checkpoints),TurnJournal(runs)){ToolDialogueLoop(rounds,factory)}
            val first=runner().resume("chat","turn",pause.version,2,{policy},::measure)
            assertEquals(TurnState.FINISHED,first.state)
            val result=JSONObject(TurnJournal(runs).text(first.outcome!!))
            assertEquals("finished",result.getString("kind"));assertEquals(2,result.getInt("requests"))
            assertEquals("恢复完成",result.getJSONObject("result").getString("text"))
            assertEquals(first,runner().resume("chat","turn",pause.version,2,{policy},::measure))
            assertEquals(1,count())
        }
    }
    @Test fun `领取后异常重开保留未确认且下一暂停自动登记`() {
        val(_,rounds)=setup();val checkpoints=temporary.newFolder().toPath();val runs=temporary.newFolder().toPath()
        val pause=PausedLoopStore(checkpoints).save("chat","turn",null,LoopBudget(ModelCapacity(128000),128000,1),ToolLoopResult.BudgetReached(request,1,emptyList()))
        var calls=0
        fun runner()=PersistentToolDialogue(PausedLoopStore(checkpoints),TurnJournal(runs)){calls++;error("模拟领取后中断")}
        assertThrows(IllegalStateException::class.java){runner().resume("chat","turn",pause.version,2,{policy},::measure)}
        val reopened=runner().resume("chat","turn",pause.version,2,{policy},::measure)
        assertEquals(TurnState.RUNNING,reopened.state);assertNull(reopened.outcome);assertEquals(1,calls)
        val other=PausedLoopStore(checkpoints).save("other","turn",null,LoopBudget(ModelCapacity(128000),128000,1),ToolLoopResult.BudgetReached(request,1,emptyList()))
        val finished=PersistentToolDialogue(PausedLoopStore(checkpoints),TurnJournal(runs)){ToolDialogueLoop(rounds){error("预算未增加不应发请求")}}
            .resume("other","turn",other.version,1,{policy},::measure)
        val outcome=JSONObject(TurnJournal(runs).text(finished.outcome!!))
        assertEquals("paused",outcome.getString("kind"))
        assertNotEquals(other.version,outcome.getString("version"))
        assertEquals(outcome.getString("version"),PausedLoopStore(checkpoints).load("other","turn")!!.version)
    }

    @Test fun `两个恢复入口竞争同一暂停点只有一个执行网络`() {
        val(_,rounds)=setup();val checkpoints=temporary.newFolder().toPath();val runs=temporary.newFolder().toPath()
        val pause=PausedLoopStore(checkpoints).save("chat","turn",null,LoopBudget(ModelCapacity(128000),128000,1),ToolLoopResult.BudgetReached(request,1,emptyList()))
        val claimed=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            withServer({_,_->JSONObject().put("content","只有一次")}){factory,count->
                val first=worker.submit<StoredTurn> {
                    PersistentToolDialogue(PausedLoopStore(checkpoints),TurnJournal(runs)){
                        claimed.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS));ToolDialogueLoop(rounds,factory)
                    }.resume("chat","turn",pause.version,2,{policy},::measure)
                }
                try {
                    assertTrue(claimed.await(5,java.util.concurrent.TimeUnit.SECONDS))
                    val competing=PersistentToolDialogue(PausedLoopStore(checkpoints),TurnJournal(runs)){error("不得二次领取")}
                        .resume("chat","turn",pause.version,2,{policy},::measure)
                    assertEquals(TurnState.RUNNING,competing.state);assertEquals(0,count())
                } finally {release.countDown()}
                assertEquals(TurnState.FINISHED,first.get(5,java.util.concurrent.TimeUnit.SECONDS).state)
                assertEquals(1,count())
            }
        } finally {release.countDown();worker.shutdownNow()}
    }

    @Test fun `持久化首次读取编辑暂停重开继续形成完整工具回合`() {
        val(store,rounds)=setup();val checkpoints=temporary.newFolder().toPath();val runs=temporary.newFolder().toPath()
        val budget=LoopBudget(ModelCapacity(128000),128000,2)
        withServer({input,n->when(n){
            1->tool("read","read_card",base())
            2->{val messages=input.getJSONArray("messages");val latest=JSONObject(messages.getJSONObject(messages.length()-1).getString("content"));tool("write","rename_card",base().put("draft_version",latest.getString("draft_version")).put("name","连续完成"))}
            else->{val messages=input.getJSONArray("messages");assertEquals("saved",JSONObject(messages.getJSONObject(messages.length()-1).getString("content")).getString("status"));JSONObject().put("content","名称已保存")}
        }}){factory,count->
            fun host()=PersistentToolDialogue(PausedLoopStore(checkpoints),TurnJournal(runs)){ToolDialogueLoop(rounds,factory)}
            val first=host().start("chat","turn",request,budget,{policy},::measure)
            assertEquals(TurnState.FINISHED,first.state)
            assertEquals("连续完成",store.open("card")!!.content.name)
            val journal=TurnJournal(runs)
            assertEquals("把角色改名为新名称",JSONObject(journal.text(first.input)).getJSONObject("request").getJSONArray("messages").getJSONObject(0).getString("content"))
            val paused=JSONObject(journal.text(first.outcome!!));assertEquals("paused",paused.getString("kind"))
            assertEquals(first,host().start("chat","turn",request,budget,{policy},::measure));assertEquals(2,count())
            val revision=store.open("card")!!.revision
            val resumed=host().resume("chat","turn",paused.getString("version"),3,{policy},::measure)
            val finished=JSONObject(journal.text(resumed.outcome!!))
            assertEquals("finished",finished.getString("kind"));assertEquals(3,finished.getInt("requests"))
            assertEquals("名称已保存",finished.getJSONObject("result").getString("text"));assertEquals(2,finished.getJSONArray("outcomes").length())
            assertEquals(revision,store.open("card")!!.revision);assertEquals(3,count())
            assertThrows(IllegalArgumentException::class.java){host().start("chat","turn",request.copy(messages=listOf(WireMessage("user","另一个输入"))),budget,{policy},::measure)}
            assertEquals(3,count())
        }
    }
    @Test fun `首次领取后异常原请求仍可找回且不会自动重发`() {
        val checkpoints=temporary.newFolder().toPath();val runs=temporary.newFolder().toPath();var starts=0
        fun host()=PersistentToolDialogue(PausedLoopStore(checkpoints),TurnJournal(runs)){starts++;error("模拟运行中断")}
        val budget=LoopBudget(ModelCapacity(128000),128000,2)
        assertThrows(IllegalStateException::class.java){host().start("chat","turn",request,budget,{policy},::measure)}
        val stored=host().start("chat","turn",request,budget,{policy},::measure)
        assertEquals(TurnState.RUNNING,stored.state);assertNull(stored.outcome);assertEquals(1,starts)
        assertEquals("把角色改名为新名称",JSONObject(TurnJournal(runs).text(stored.input)).getJSONObject("request").getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test fun `普通对话每轮采用资料进入持续宿主且管理对象不自动注入`() {
        val(store,rounds)=setup();val checkpoints=temporary.newFolder().toPath();val runs=temporary.newFolder().toPath()
        fun text(value:String):ContentRef {val ref=store.contents.allocator()();store.contents.receive(listOf(ContentTransfer(ContentRef("raw"),ref))){java.io.ByteArrayInputStream(value.toByteArray())};return ref}
        store.save(ContentDocument("world",CardKind.WORLD,"港口世界",listOf(ContentModule("context","背景",listOf(ContentBlock.Text("lighthouse",text("港口独有的蓝色灯塔"))),use=ModuleUse.Keywords(listOf("港口"),false,false)))),null,ChangeSource.HUMAN,"world-version")
        store.save(store.open("card")!!.content.copy(modules=listOf(ContentModule("private","管理内容",listOf(ContentBlock.Text("secret",text("仅供管理的秘密文本"))),use=ModuleUse.Always))),"initial",ChangeSource.HUMAN,"private-version")
        val settings=TurnSettings("local-model",ModelCapacity(128000),128000,512,"叙述世界中的人物与场景",listOf(SourceSelection("world")),setOf("card"),TriggerWindow(1,setOf(MessageRole.USER)))
        val builder=DialogueRequestBuilder(RequestMaterials(store));val journal=TurnJournal(runs)
        withServer({input,n->
            val wire=input.toString()
            assertFalse(wire.contains("仅供管理的秘密文本"))
            if(n==1)assertTrue(wire.contains("港口独有的蓝色灯塔")) else assertFalse(wire.contains("港口独有的蓝色灯塔"))
            JSONObject().put("content","场景回复")
        }){factory,count->
            fun host()=PersistentToolDialogue(PausedLoopStore(checkpoints),TurnJournal(runs)){ToolDialogueLoop(rounds,factory)}
            val first=host().startConversation("chat","one","去港口",emptyList(),settings,builder,2,{policy},::measure)
            val trace=JSONObject(journal.text(first.trace!!)).getJSONArray("modules").getJSONObject(0)
            assertEquals("world-version",trace.getString("revision"));assertTrue(trace.getBoolean("selected"))
            assertEquals(first,host().startConversation("chat","one","去港口",emptyList(),settings,builder,2,{policy},::measure));assertEquals(1,count())
            val second=host().startConversation("chat","two","休息",listOf(WireMessage("user","去港口"),WireMessage("assistant","场景回复")),settings,builder,2,{policy},::measure)
            val decision=JSONObject(journal.text(second.trace!!)).getJSONArray("modules").getJSONObject(0)
            assertFalse(decision.getBoolean("selected"));assertEquals("CONDITION_MISSED",decision.getString("reason"));assertEquals(2,count())
            val failed=host().startConversation("chat","missing","保留我的输入",emptyList(),settings.copy(selections=listOf(SourceSelection("missing"))),builder,2,{policy},::measure)
            assertEquals("保留我的输入",JSONObject(journal.text(failed.input)).getString("input"))
            assertEquals("preparation_failed",JSONObject(journal.text(failed.outcome!!)).getString("kind"));assertEquals(2,count())
        }
    }

    @Test fun `模型注册菜单取得真实回执动作经普通对话入口发送`() {
        val store=CardStore(temporary.newFolder().toPath());val menuRoot=temporary.newFolder().toPath()
        val menuJournal=temporary.newFolder().toPath();val cardJournal=temporary.newFolder().toPath()
        val runRoot=temporary.newFolder().toPath();val checkpointRoot=temporary.newFolder().toPath()
        val policy=CardToolPolicy(emptySet())
        fun rounds()=CardToolRound(CardToolCoordinator(store,TurnJournal(cardJournal)),CardToolReader(store),ConversationControlTools(ConversationControlStore(menuRoot),TurnJournal(menuJournal),"chat","reply"))
        val settings=TurnSettings("local-model",ModelCapacity(128000),128000,512,"自然互动",emptyList(),emptySet(),TriggerWindow(1,setOf(MessageRole.USER)))
        val builder=DialogueRequestBuilder(RequestMaterials(store))
        withServer({input,n->when(n) {
            1->{assertTrue(input.getJSONArray("tools").toString().contains("register_controls"));tool("menu","register_controls",JSONObject().put("controls","""[{"actionKey":"go","label":"去港口","behavior":"action","prompt":"去港口看看灯塔"}]"""))}
            2->{val messages=input.getJSONArray("messages");val result=JSONObject(messages.getJSONObject(messages.length()-1).getString("content"));assertEquals("registered",result.getString("status"));assertEquals(1,ConversationControlStore(menuRoot).read("chat").controls.visible(listOf("reply")).size);JSONObject().put("content","快捷操作已添加")}
            else->{val messages=input.getJSONArray("messages");assertEquals("去港口看看灯塔",messages.getJSONObject(messages.length()-1).getString("content"));JSONObject().put("content","你来到港口")}
        }}){factory,count->
            fun host()=PersistentToolDialogue(PausedLoopStore(checkpointRoot),TurnJournal(runRoot)){ToolDialogueLoop(rounds(),factory)}
            val first=host().startConversation("chat","setup","添加前往港口的操作",emptyList(),settings,builder,1,{policy},::measure)
            val pausedRecord=JSONObject(TurnJournal(runRoot).text(first.outcome!!))
            assertEquals("paused",pausedRecord.getString("kind"));assertEquals(1,count())
            val completed=host().resume("chat","setup",pausedRecord.getString("version"),2,{policy},::measure)
            val record=JSONObject(TurnJournal(runRoot).text(completed.outcome!!))
            assertEquals("registered",record.getJSONArray("outcomes").getJSONObject(0).getJSONObject("result").getString("status"));assertEquals(2,count())
            val menu=ConversationControlStore(menuRoot);val handle=menu.read("chat").controls.visible(listOf("reply")).single().handle
            val instruction=menu.invoke(handle,listOf("reply")) as ControlInvocation.SendInstruction
            val next=host().startConversation("chat","action",instruction.text,emptyList(),settings,builder,3,{policy},::measure)
            assertEquals("你来到港口",JSONObject(TurnJournal(runRoot).text(next.outcome!!)).getJSONObject("result").getString("text"));assertEquals(3,count())
            assertTrue(store.list().isEmpty())
        }
    }

}
