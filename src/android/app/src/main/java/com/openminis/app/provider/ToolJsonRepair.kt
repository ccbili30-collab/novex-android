package com.openminis.app.provider

import novex.android.data.model.AgentToolDefinition
import org.json.JSONArray
import org.json.JSONObject

/**
 * 畸形/不完整工具调用的 JSON 修补（T-tool-json-repair b2c4f8a6；血统清剿
 * P3.7 就地真重写；策略次序、修补标签、闭合后缀表与编辑距离语义为契约
 * 冻结面）。
 *
 * 对齐 iOS 实现（AIChatViewModel.swift 的 repairToolArgs / preflight 预
 * 处理）。操作对象是流式供应商已解析出的 [JSONObject]；字典为空（截断
 * 形态）时可选用工具输入块环形缓冲里的原始流「尾巴」快照。
 *
 * 三个策略依序执行、各自只在需要时开火：
 *
 * 1. 截断修补——[args] 为空而 [rawTail] 像个刚被掐断的 JSON 对象时，追加
 *    一小组闭合后缀重试解析；
 * 2. 类型钳制——声明为 String 的必填字段，把标量值经 `toString()` 钳成串，
 *    下游的空串预检才有东西可查；
 * 3. 模糊字段名匹配——对每个缺失的必填字段，找编辑距离恰为 1 的兄弟键并
 *    改名。抓 `comand` → `command` 这类一次性手滑。
 *
 * 修补后的 [JSONObject] 在 preflight 调用点遮蔽原件；[RepairLog] 非空时
 * 调用方按 WARNING 级打标签。
 */
object ToolJsonRepair {

    /** 截断重试的闭合后缀（从空到多层收口，首个能解析者胜）。 */
    private val CLOSURE_SUFFIXES = listOf("", "\"", "\"}", "\"]}", "}", "}}", "]}", "]}}", "]", "]]")

    /**
     * 就地改写 [args]，返回开过火的修补策略标签（无变化则空）。标签非空
     * 时由调用方按 WARNING 级落日志。
     */
    fun repair(
        toolName: String,
        args: JSONObject,
        rawTail: String?,
        tools: List<AgentToolDefinition>,
    ): List<String> {
        val toolDef = tools.firstOrNull { it.name == toolName } ?: return emptyList()

        val fired = mutableListOf<String>()
        repairTruncation(args, rawTail, fired)
        coerceStringFields(args, toolDef, fired)
        rescueMisspelledFields(args, toolDef, fired)
        return fired
    }

    /**
     * 策略 1：字典为空、原始流尾巴却像个刚被掐断的 JSON 对象时开火。逐个
     * 追加闭合后缀重试，首个能解析者胜，字段拷回 [args]。一个合法的裸对象
     * （含 {}）不是截断流——恢复其解析字段不得提示模型重试，故空后缀不记
     * 标签。
     */
    private fun repairTruncation(args: JSONObject, rawTail: String?, fired: MutableList<String>) {
        if (args.length() != 0 || rawTail.isNullOrBlank()) return
        val tail = rawTail.trim()
        for (suffix in CLOSURE_SUFFIXES) {
            val parsed = parseOrNull(tail + suffix) ?: continue
            for (k in parsed.keys().asSequence().toList()) {
                args.put(k, parsed.opt(k))
            }
            if (suffix.isNotEmpty()) fired.add("truncation+" + suffix)
            return
        }
    }

    /** 策略 2：String 型必填字段的标量钳串（跳过已是串/null/容器型）。 */
    private fun coerceStringFields(args: JSONObject, toolDef: AgentToolDefinition, fired: MutableList<String>) {
        for (field in toolDef.required) {
            if (toolDef.parameters[field]?.type != "string" || !args.has(field)) continue
            val raw = args.opt(field) ?: continue
            if (raw is String || raw === JSONObject.NULL || raw is JSONObject || raw is JSONArray) continue
            val asText = raw.toString()
            if (asText.trim().isNotEmpty()) {
                args.put(field, asText)
                fired.add("type-coerce:$field")
            }
        }
    }

    /**
     * 策略 3：缺失必填字段的模糊改名。兄弟键若是 schema 认识的字段则跳过
     * ——不偷工具助手本来就会直接读走的兄弟。
     */
    private fun rescueMisspelledFields(args: JSONObject, toolDef: AgentToolDefinition, fired: MutableList<String>) {
        val schemaFields = toolDef.parameters.keys
        for (field in toolDef.required) {
            if (args.has(field)) continue
            val presentKeys = args.keys().asSequence().toList()
            val donor = presentKeys.firstOrNull { key ->
                key !in schemaFields && editDistanceExactlyOne(key, field)
            } ?: continue
            args.put(field, args.opt(donor))
            args.remove(donor)
            fired.add("fuzzy:$donor->$field")
        }
    }

    private fun parseOrNull(s: String): JSONObject? = try {
        JSONObject(s)
    } catch (_: Throwable) {
        null
    }

    /**
     * [a] 与 [b] 的 Levenshtein 编辑距离是否**恰为 1**（大小写不敏感）。
     * 短路设计——距离 > 1 不关心。等长即单字符替换；差一即单字符增删。
     */
    private fun editDistanceExactlyOne(a: String, b: String): Boolean {
        val left = a.lowercase()
        val right = b.lowercase()
        if (left == right) return false // 距离 0 = 同名键，不是修补候选
        val lengthGap = left.length - right.length
        if (lengthGap !in -1..1) return false

        if (left.length == right.length) {
            var mismatches = 0
            for (i in left.indices) {
                if (left[i] != right[i] && ++mismatches > 1) return false
            }
            return mismatches == 1
        }
        // 差一个字符：双指针走长串，允许跳过一次。
        val longer = if (left.length > right.length) left else right
        val shorter = if (left.length > right.length) right else left
        var li = 0
        var si = 0
        var skipped = false
        while (li < longer.length && si < shorter.length) {
            if (longer[li] == shorter[si]) {
                li++; si++
            } else if (!skipped) {
                li++; skipped = true
            } else {
                return false
            }
        }
        return true
    }
}
