package novex.conversation

/** 能力来自已读取的模型配置；未知值保持未知，不能用常量冒充。 */
data class ModelCapacity(val contextWindow: Long?, val maximumOutput: Long? = null) {
    init { require(contextWindow == null || contextWindow > 0); require(maximumOutput == null || maximumOutput > 0) }
}
data class TokenMeasurement(val tokens: Long, val basis: String, val estimated: Boolean) {
    init { require(tokens >= 0 && basis.isNotBlank()) }
}
sealed interface CapacityDecision {
    data class Fits(val remaining: Long, val estimated: Boolean) : CapacityDecision
    data class Exceeded(val excess: Long, val estimated: Boolean) : CapacityDecision
    data class InvalidSetting(val reason: String) : CapacityDecision
    data object UnknownModelWindow : CapacityDecision
}
object RequestCapacity {
    /** 输入必须包括工具定义、消息封装、资料和历史，不只计算新消息或正文。 */
    fun check(model: ModelCapacity, selectedWindow: Long, outputReserve: Long, input: TokenMeasurement): CapacityDecision {
        if (selectedWindow <= 0 || outputReserve <= 0 || outputReserve >= selectedWindow)
            return CapacityDecision.InvalidSetting("上下文设置必须为输出保留空间")
        val maximum=model.contextWindow ?: return CapacityDecision.UnknownModelWindow
        if (selectedWindow > maximum) return CapacityDecision.InvalidSetting("当前对话设置超过已读取的模型窗口")
        if (model.maximumOutput != null && outputReserve > model.maximumOutput)
            return CapacityDecision.InvalidSetting("输出预留超过已读取的模型输出上限")
        val available=selectedWindow-outputReserve
        return if (input.tokens > available) CapacityDecision.Exceeded(input.tokens-available,input.estimated)
            else CapacityDecision.Fits(available-input.tokens,input.estimated)
    }
}
