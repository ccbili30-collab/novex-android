package novex.runtime

import novex.content.ContentBlock
import novex.content.ContentModule
import novex.content.ContentRef
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-bulk-tools] 宏工具的 JSON 树（2026-09-16 用户批γ：像 3D 建模跑宏代码，
 * 模型一次写一整棵模块树塞进去）。原则：**先整包校验、再原子落库**——任何
 * 节点不合法时一个字都不写，错误信息精确到 JSON 路径（modules[2].children[0].name），
 * 模型改一处重发即可，不出现"建一半再调整"。
 */
data class BulkModuleNode(
    val name: String,
    val text: String?,
    val children: List<BulkModuleNode>,
)

object CardBulk {
    const val MAX_MODULES = 200
    const val MAX_DEPTH = 4
    const val MAX_TEXT_CHARS = 60_000
    const val MAX_TOTAL_TEXT_CHARS = 240_000

    /** 递归解析并整包校验；任何违规抛 IllegalArgumentException，消息带 JSON 路径。 */
    fun parseTree(value: JSONObject, key: String): List<BulkModuleNode> {
        val array = value.optJSONArray(key) ?: throw IllegalArgumentException("$key 缺失（模块数组）")
        val nodes = parseArray(array, key)
        val totalText = nodes.sumOf { n -> (n.text?.length ?: 0) + n.childrenSum() }
        require(totalText <= MAX_TOTAL_TEXT_CHARS) { "$key 正文总量 ${totalText} 超过上限 $MAX_TOTAL_TEXT_CHARS，请拆成多次 add_module_bulk" }
        return nodes
    }

    private fun BulkModuleNode.childrenSum(): Int =
        (text?.length ?: 0) + children.sumOf { it.childrenSum() }

    private fun parseArray(array: JSONArray, path: String): List<BulkModuleNode> {
        require(array.length() > 0) { "$path 至少要有一个模块" }
        val out = mutableListOf<BulkModuleNode>()
        var count = 0
        for (i in 0 until array.length()) {
            val node = array.optJSONObject(i) ?: throw IllegalArgumentException("$path[$i] 不是对象")
            val name = node.optString("name").trim()
            require(name.isNotBlank()) { "$path[$i].name 不能为空" }
            require(name.length <= 120) { "$path[$i].name 超过 120 字" }
            val text = node.optString("text").trim().takeIf { it.isNotEmpty() }
            if (text != null) require(text.length <= MAX_TEXT_CHARS) { "$path[$i].text 超过 $MAX_TEXT_CHARS 字，请拆分模块" }
            count++
            val children = if (node.has("children") && !node.isNull("children")) parseArray(node.getJSONArray("children"), "$path[$i].children").also { count += countNodes(it) } else emptyList()
            out += BulkModuleNode(name, text, children)
        }
        return out.also { require(count <= MAX_MODULES) { "模块总数 $count 超过上限 $MAX_MODULES" } }
    }

    private fun countNodes(nodes: List<BulkModuleNode>): Int =
        nodes.sumOf { 1 + countNodes(it.children) }

    private fun depthOf(nodes: List<BulkModuleNode>, depth: Int = 1): Int =
        nodes.maxOfOrNull { depthOf(it.children, depth + 1) } ?: depth

    /**
     * 编号生成：bulk- 前缀 + 卡片段 + 每次调用的随机盐——同卡多次宏操作
     * （先追加再插入）编号不撞（对话包实测教训：确定性编号在第二次插入
     * 时与首次产物冲突，"同一内容树中编号重复"）。
     */
    fun buildModules(
        nodes: List<BulkModuleNode>,
        seed: String,
        textRef: (String) -> ContentRef,
        counter: IntArray = intArrayOf(0),
        depth: Int = 1,
    ): List<ContentModule> {
        require(depth <= MAX_DEPTH) { "模块嵌套超过 $MAX_DEPTH 层（第 $depth 层）" }
        val salt = java.util.UUID.randomUUID().toString().take(4)
        fun id(kind: String, index: Int) = "bulk-$kind$seed-$salt-$index"
        return nodes.map { node ->
            val index = counter[0]++
            val block = node.text?.let { ContentBlock.Text(id("b", index), textRef(it)) }
            ContentModule(
                id = id("m", index),
                name = node.name,
                blocks = listOfNotNull(block),
                children = if (node.children.isEmpty()) emptyList() else buildModules(node.children, seed, textRef, counter, depth + 1),
            )
        }
    }

    /** 递归 JSON Schema（depth 限层），供工具定义复用。 */
    fun moduleSchema(depth: Int = 1): JSONObject {
        val properties = JSONObject()
            .put("name", JSONObject().put("type", "string").put("description", "模块名称（必填）"))
            .put("text", JSONObject().put("type", "string").put("description", "该模块的正文文字，保存后成为一个文字块；纯分组模块可省略"))
        if (depth < MAX_DEPTH) {
            properties.put("children", JSONObject().put("type", "array")
                .put("description", "嵌套子模块，数组顺序即排序")
                .put("items", moduleSchema(depth + 1)))
        }
        return JSONObject().put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(listOf("name")))
    }

    /** 日志编码（重放不回读请求体，仅存档）。 */
    fun encodeTree(nodes: List<BulkModuleNode>): JSONArray {
        val array = JSONArray()
        fun write(node: BulkModuleNode): JSONObject = JSONObject()
            .put("name", node.name)
            .put("text", node.text ?: JSONObject.NULL)
            .put("children", if (node.children.isEmpty()) JSONObject.NULL else JSONArray(node.children.map(::write)))
        nodes.forEach { array.put(write(it)) }
        return array
    }

    fun decodeTree(array: JSONArray): List<BulkModuleNode> =
        parseArray(array, "modules")
}
