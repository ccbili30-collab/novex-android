package com.openminis.app.agent

/** Failed reads remain failed when the model changes depth/page options or checks
 * unrelated metadata. A successful operation on the same resource resets its failures.
 * Successful pagination is never limited by total tool count. State is run-local. */
internal class FailedToolProgress {
    private data class Failure(val scope: String, val output: String)
    private val failures = ArrayDeque<Failure>()
    fun reset() = failures.clear()
    fun record(name: String, params: Map<String, Any?>, success: Boolean, output: String): String? {
        val resource = listOf("document_ref", "collection_ref", "card_id", "path", "file_path", "url")
            .firstNotNullOfOrNull { key -> params[key]?.toString()?.takeIf { it.isNotBlank() } }
        val scope = "$name:${resource.orEmpty()}"
        if (success) {
            failures.removeAll { it.scope == scope }
            return null
        }
        failures.addLast(Failure(scope, output.trim()))
        while (failures.size > 30) failures.removeFirst()
        val repeated = failures.count { it.scope == scope && it.output == output.trim() }
        if (repeated < 3) return null
        return "同一操作连续尝试仍未完成，已停止本轮自动执行。已完成结果保留。原因：${output.take(240)}"
    }
}
