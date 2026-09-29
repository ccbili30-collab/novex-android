package novex.android.thinking

import com.openminis.app.data.db.ProviderThinkingContractEntity

/**
 * 自定义规则 ↔ Room 行的往返（P3.2 真重写版）。
 *
 * 形态与回放合同的编解码已内聚到各类型自身（[ThinkingWireFormat.toStorage] /
 * [fromStorage]、[ReasoningEchoPolicy.toStorage] / [fromStorage]）；本件只剩
 * DB 视角的组装：scope 拆成 kind/pattern 两列，形态与回放各存一个 JSON blob。
 *
 * 防御口径不变：任何一端解码失败（损坏 blob、新版本写入的词汇）都退到
 * 「无意见」（null wireFormat），解析层安全落空，不在请求中途抛异常。
 */
object ThinkingContractCoding {

    // ---- 形态 / 回放（委托给类型自带的编码） ----

    fun encodeWireFormat(fmt: ThinkingWireFormat?): String? = fmt?.toStorage()?.toString()

    fun decodeWireFormat(json: String?): ThinkingWireFormat? = ThinkingWireFormat.fromStorage(json)

    fun encodeEcho(echo: ReasoningEchoPolicy?): String? = echo?.toStorage()?.toString()

    fun decodeEcho(json: String?): ReasoningEchoPolicy? = ReasoningEchoPolicy.fromStorage(json)

    // ---- 规则 ↔ Entity ----

    fun toEntity(rule: ThinkingContract, id: String, instanceId: String, sortOrder: Int): ProviderThinkingContractEntity =
        ProviderThinkingContractEntity(
            id = id,
            providerInstanceId = instanceId,
            label = rule.label,
            scopeKind = rule.scope.columnKind(),
            scopePattern = (rule.scope as? ThinkingContract.Scope.ModelPattern)?.pattern,
            wireFormatJson = encodeWireFormat(rule.wireFormat),
            reasoningEchoJson = encodeEcho(rule.reasoningEcho),
            sortOrder = sortOrder,
        )

    fun toRule(row: ProviderThinkingContractEntity): ThinkingContract =
        ThinkingContract(
            kind = ThinkingContract.Kind.CUSTOM,
            scope = row.scopeColumn(),
            wireFormat = decodeWireFormat(row.wireFormatJson),
            reasoningEcho = decodeEcho(row.reasoningEchoJson),
            label = row.label,
        )

    private fun ThinkingContract.Scope.columnKind(): String =
        if (this is ThinkingContract.Scope.ModelPattern) "modelPattern" else "allModels"

    private fun ProviderThinkingContractEntity.scopeColumn(): ThinkingContract.Scope =
        if (scopeKind == "modelPattern") {
            ThinkingContract.Scope.ModelPattern(scopePattern ?: "*")
        } else {
            ThinkingContract.Scope.AllModels
        }
}
