package novex.runtime

import novex.conversation.TokenMeasurement
import org.json.JSONObject

/** 无服务专用计数器时的估计。图片编码是传输字节，不能直接当成文字令牌。 */
object RequestEstimates {
    fun approximate(payload:String):TokenMeasurement {
        val value=JSONObject(payload)
        var pictures=0L
        val messages=value.optJSONArray("messages")
        if(messages!=null)for(index in 0 until messages.length()) {
            val content=messages.getJSONObject(index).optJSONArray("content")?:continue
            for(part in 0 until content.length()) {
                val item=content.getJSONObject(part)
                if(item.optString("type")=="image_url") {
                    pictures++
                    item.put("image_url",JSONObject().put("url","[image]"))
                }
            }
        }
        val text=value.toString().toByteArray(Charsets.UTF_8).size.toLong()
        // 可替换的通用预算，不宣称等于服务实际图像消耗；服务仍可能拒绝。
        return TokenMeasurement(Math.addExact(text,Math.multiplyExact(pictures,8192L)),
            if(pictures==0L)"保守文字字节估计" else "文字字节估计＋每图预留 8192 令牌；图像实际消耗由服务决定",true)
    }
}
