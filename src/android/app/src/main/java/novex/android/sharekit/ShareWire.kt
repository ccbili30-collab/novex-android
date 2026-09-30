package novex.android.sharekit

import com.openminis.app.share.PendingShare
import org.json.JSONArray
import org.json.JSONObject

/**
 * PendingShare 的 JSON 编解码（P3.5c 真重写）。线上形态是 iOS 分享扩展
 * 的同一份契约：`{items: [{kind, value}], timestamp}`，详见记录类文档
 * （com.openminis.app.share.PendingShare——嵌套类型被 UI 钉形，正典在
 * 旧路径，此处只挂编解码扩展）。
 */
object ShareWire {

    fun PendingShare.toJson(): JSONObject {
        val arr = JSONArray().apply { items.forEach { put(JSONObject().put("kind", it.kind.wire).put("value", it.value)) } }
        return JSONObject().put("items", arr).put("timestamp", timestampMs)
    }

    /** 缺 kind、未知 kind、空 value 的条目整条丢弃；全军覆没返回 null。
     *  timestamp 缺失按“现在”补——新鲜度判定宁可宽松。 */
    fun pendingShareFromJson(json: JSONObject): PendingShare? {
        val arr = json.optJSONArray("items") ?: return null
        fun entry(i: Int) = arr.getJSONObject(i)
        val items = (0 until arr.length()).mapNotNull { i ->
            val wire = entry(i).optString("kind", "")
            val kind = PendingShare.Item.Kind.entries.firstOrNull { it.wire == wire } ?: return@mapNotNull null
            entry(i).optString("value", "").takeIf { it.isNotEmpty() }?.let { PendingShare.Item(kind, it) }
        }
        return items.takeIf { it.isNotEmpty() }?.let { PendingShare(it, json.optLong("timestamp", System.currentTimeMillis())) }
    }
}
