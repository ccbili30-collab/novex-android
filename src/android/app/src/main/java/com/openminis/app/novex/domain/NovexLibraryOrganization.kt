package com.openminis.app.novex.domain

import org.json.JSONArray
import org.json.JSONObject

/** Folder hierarchy is organization, not ownership, context adoption or tool permission. */
object NovexLibraryOrganization {
    fun decode(id: String, name: String, members: Set<NovexContentAddress>, json: String): NovexWorkGroup {
        val data = JSONObject(json)
        val rows = data.optJSONArray("folders") ?: JSONArray()
        val folders = (0 until rows.length()).map { index -> rows.getJSONObject(index).let {
            NovexLibraryFolder(it.getString("id"), it.getString("name"), it.optString("parent").takeIf(String::isNotBlank))
        } }
        val locations = data.optJSONArray("locations") ?: JSONArray()
        val map = (0 until locations.length()).associate { index -> locations.getJSONObject(index).let {
            NovexContentAddress(NovexContentKind.valueOf(it.getString("kind")), it.getString("id")) to it.getString("folder")
        } }.filterKeys { it in members }
        return NovexWorkGroup(id, name, members, folders, map).also(::validate)
    }

    fun encode(group: NovexWorkGroup): String {
        validate(group)
        return JSONObject().put("folders", JSONArray(group.folders.map {
            JSONObject().put("id", it.id).put("name", it.name).put("parent", it.parentId ?: "")
        })).put("locations", JSONArray(group.locations.map { (address, folder) ->
            JSONObject().put("kind", address.kind.name).put("id", address.id).put("folder", folder)
        })).toString()
    }

    fun validate(group: NovexWorkGroup) {
        val ids = group.folders.map { it.id }.toSet()
        require(ids.size == group.folders.size && ids.none(String::isBlank)) { "文件夹编号重复或无效" }
        require(group.folders.all { it.name.isNotBlank() && (it.parentId == null || it.parentId in ids) }) { "文件夹名称或上级无效" }
        require(group.folders.map { it.parentId to it.name }.distinct().size == group.folders.size) { "同一位置已有同名文件夹" }
        group.folders.forEach { folder ->
            val seen = mutableSetOf(folder.id)
            var parent = folder.parentId
            while (parent != null) {
                require(seen.add(parent)) { "文件夹不能循环嵌套" }
                parent = group.folders.first { it.id == parent }.parentId
            }
        }
        require(group.locations.all { it.key in group.members && it.value in ids }) { "内容所在文件夹已不存在" }
    }
}
