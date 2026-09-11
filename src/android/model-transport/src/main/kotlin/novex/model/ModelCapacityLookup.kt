package novex.model

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

sealed interface CapacityLookupResult {
    data class Found(val maximum:Long,val source:URI):CapacityLookupResult
    data object Unknown:CapacityLookupResult
    data class Rejected(val status:Int):CapacityLookupResult
    data object Unavailable:CapacityLookupResult
}

/** 只读取同一服务明确返回的容量；不按模型名称推测，不发送生成请求。 */
class ModelCapacityLookup(private val timeoutMillis:Int=10000,private val maxBytes:Int=4*1024*1024) {
    init {require(timeoutMillis>0 && maxBytes>0)}
    fun read(endpoint:ModelEndpoint,model:String):CapacityLookupResult {
        require(model.isNotBlank())
        val source=catalogUrl(endpoint.completionUrl)?:return CapacityLookupResult.Unknown
        var connection:HttpURLConnection?=null
        return try {
            connection=source.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects=false
            connection.requestMethod="GET"
            connection.connectTimeout=timeoutMillis;connection.readTimeout=timeoutMillis
            connection.setRequestProperty("Accept","application/json")
            endpoint.authorize(connection)
            val status=connection.responseCode
            if(status!=200)return CapacityLookupResult.Rejected(status)
            if(connection.contentLengthLong>maxBytes)return CapacityLookupResult.Unavailable
            val bytes=connection.inputStream.use {input ->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) {
                    val count=input.read(buffer);if(count<0)break
                    if(out.size().toLong()+count>maxBytes)return CapacityLookupResult.Unavailable
                    out.write(buffer,0,count)
                }
                out.toByteArray()
            }
            parse(String(bytes,Charsets.UTF_8),model)?.let {CapacityLookupResult.Found(it,source)}?:CapacityLookupResult.Unknown
        } catch(_:Exception) {CapacityLookupResult.Unavailable} finally {connection?.disconnect()}
    }
    companion object {
        /** 仅已知聊天补全路径可推导相邻目录，未知路径交还手动设置。 */
        fun catalogUrl(completion:URI):URI? {
            val suffix="/chat/completions"
            if(!completion.path.endsWith(suffix))return null
            return URI(completion.scheme,completion.authority,completion.path.removeSuffix(suffix)+"/models",null,null)
        }
        fun parse(json:String,model:String):Long? {
            val data=JSONObject(json).optJSONArray("data")?:return null
            val matches=(0 until data.length()).mapNotNull {data.optJSONObject(it)}.filter {it.opt("id")==model}
            if(matches.size!=1)return null
            val item=matches.single()
            fun positiveInteger(value:Any?):Long? {
                if(value !is Number)return null
                return value.toString().toLongOrNull()?.takeIf {it>1024}
            }
            val maximum=positiveInteger(item.opt("context_length"))?:return null
            val provider=item.optJSONObject("top_provider")
            val providerMaximum=provider?.opt("context_length")
            if(providerMaximum!=null && providerMaximum!=JSONObject.NULL) {
                return positiveInteger(providerMaximum)?.let {minOf(maximum,it)}
            }
            return maximum
        }
    }
}
