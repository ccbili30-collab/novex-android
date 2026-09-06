package com.openminis.app.novex.domain

enum class NovexSourceReadMethod(val label: String) {
    READ("读取正文"), SEARCH("搜索片段"), PREVIEW("目录预览"),
}

data class NovexSourceRead(
    val sourceId: String,
    val label: String,
    val revision: String,
    val start: Int,
    val end: Int,
    val totalCharacters: Int,
    val method: NovexSourceReadMethod = NovexSourceReadMethod.READ,
) {
    init {
        require(sourceId.isNotBlank() && label.isNotBlank() && revision.isNotBlank()) { "读取记录必须包含来源、名称和修订" }
        require(start >= 0 && end >= start && totalCharacters >= end) { "读取记录的字符范围无效" }
    }
}

data class NovexSourceReadCoverage(
    val sourceId: String,
    val label: String,
    val revision: String,
    val totalCharacters: Int,
    val coveredCharacters: Int,
) {
    val complete: Boolean get() = totalCharacters > 0 && coveredCharacters == totalCharacters

    companion object {
        fun from(reads: List<NovexSourceRead>): List<NovexSourceReadCoverage> =
            reads.groupBy { it.sourceId to it.revision }.values.map { observations ->
                val first = observations.first()
                require(observations.all { it.totalCharacters == first.totalCharacters }) { "同一来源修订的长度不一致，不能合并阅读范围" }
                var end = 0
                var covered = 0
                observations.filter { it.method == NovexSourceReadMethod.READ }.sortedBy { it.start }.forEach { read ->
                    if (read.end > end) {
                        covered += read.end - maxOf(read.start, end)
                        end = read.end
                    }
                }
                NovexSourceReadCoverage(first.sourceId, observations.last().label, first.revision, first.totalCharacters, covered)
            }
    }
}
