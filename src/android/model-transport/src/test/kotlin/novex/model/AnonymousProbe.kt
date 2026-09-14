package novex.model

import novex.conversation.*
import java.net.URI

/** 手动触发的一次外部诊断；不属于自动测试，不读取用户配置。 */
object AnonymousProbe {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==3)
        val request=TextRequest(args[0],listOf(WireMessage("user","这是连接测试。请只回复：连接成功。")),256)
        val result=ChatCompletionCall(ModelEndpoint(URI("https://opencode.ai/zen/v1/chat/completions"),"public"),25_000)
            .execute(request,ModelCapacity(args[1].toLong(),args[2].toLong()),args[1].toLong()) {
                TokenMeasurement(it.toByteArray(Charsets.UTF_8).size.toLong(),"完整序列化请求的字节估算，非模型专用分词",true)
            }
        when(result) {
            is ModelResult.Reply -> println("正常回复；输入用量=${result.inputTokens}；输出用量=${result.outputTokens}；文字=${result.text}")
            is ModelResult.Rejected -> println("服务拒绝；状态=${result.status}；类别=${result.category}；重试时点=${result.retryAfter}")
            is ModelResult.Partial -> println("部分回复；原因=${result.reason}；文字长度=${result.text.length}")
            else -> println("未取得正常回复；结果=${result.javaClass.simpleName}")
        }
    }
}
