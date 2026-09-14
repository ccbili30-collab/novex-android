package com.openminis.app.novex.domain

import org.json.JSONArray
import org.json.JSONObject

/** Persisted read scope, independent of live UI and database adapters. */
object NovexHistoryAccessScope {
    fun key(configuration: NovexConversationConfigurationSnapshot): String {
        val binding=configuration.cardBindingJson?.let(::JSONObject)
        val readable=configuration.managedSubjects.map {"${it.subject.kind}:${it.subject.id}"}.toMutableList()
        binding?.optJSONArray("managed")?.let {items->repeat(items.length()) {i->
            val target=items.getJSONObject(i)
            readable+="card:"+JSONArray().put(target.getString("root")).put(target.getString("target")).toString()
        }}
        binding?.remove("managed");binding?.remove("createdReceipts")
        return JSONObject().put("version",1)
            .put("environment",NovexFrozenContextCodec.digest(NovexConversationConfigurationCodec.encode(configuration.copy(
                playthroughStates=emptyMap(),controls=emptyList(),completedPlaythroughs=emptyList(),
                preGameAnswerIdentity=null,preGamePlayerIdentity=null,preGameAdoptedIdentity=null,
                managedSubjects=emptyList(),executionMode=NovexExecutionMode.DEFAULT,cardBindingJson=binding?.toString()))))
            .put("readableSubjects",JSONArray(readable.distinct().sorted())).toString()
    }

    /** Adding another management target does not revoke already permitted reads. Removing one does. */
    fun canReplay(recorded: String?, current: String): Boolean = runCatching {
        if (recorded.isNullOrBlank()) return false
        val previous = JSONObject(recorded)
        val now = JSONObject(current)
        if (previous.getInt("version") != 1 || now.getInt("version") != 1 ||
            previous.getString("environment") != now.getString("environment")) return false
        fun subjects(value: JSONObject): Set<String> = value.getJSONArray("readableSubjects").let { array ->
            (0 until array.length()).mapTo(mutableSetOf()) { array.getString(it) }
        }
        subjects(now).containsAll(subjects(previous))
    }.getOrDefault(false)

}
