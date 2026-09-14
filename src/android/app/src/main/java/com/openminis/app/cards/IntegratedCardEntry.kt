package com.openminis.app.cards

/** A short durable route key; the original chat initializer owns defaults and persistence. */
object IntegratedCardEntry {
    private fun file(context:android.content.Context,key:String):android.util.AtomicFile {
        require(java.util.UUID.fromString(key).toString()==key){"卡片入口编号无效"}
        val directory=java.io.File(context.filesDir,"integrated-card-entries")
        check(directory.isDirectory || directory.mkdirs()){"卡片入口无法保存"}
        return android.util.AtomicFile(java.io.File(directory,"$key.json"))
    }
    fun draftId(context:android.content.Context,title:String,binding:CardBinding):String {
        val key=java.util.UUID.randomUUID().toString()
        val payload=org.json.JSONObject().put("title",title).put("binding",org.json.JSONObject(binding.encode())).toString()
        val destination=file(context,key)
        val output=destination.startWrite()
        try {output.write(payload.toByteArray(Charsets.UTF_8));destination.finishWrite(output)}
        catch(failure:Throwable){destination.failWrite(output);throw failure}
        return "__new__${java.util.UUID.randomUUID()}__card__$key"
    }
    fun read(context:android.content.Context,key:String):Pair<String,CardBinding> {
        val json=org.json.JSONObject(file(context,key).readFully().toString(Charsets.UTF_8))
        return json.getString("title") to requireNotNull(CardBinding.decode(json.getJSONObject("binding").toString()))
    }
}
