package com.openminis.app.novex.domain

data class NovexReferenceTraversalResult(
    val targets: List<NovexReferenceTarget>,
    val references: List<NovexCardReference>,
    val cycleReferenceIds: Set<String>,
    val missingTargets: Set<NovexReferenceTarget>,
    val truncated: Boolean,
)

/** The supplied reader enforces content scope; purpose filtering happens before target reads. */
object NovexReferenceTraversal {
    suspend fun collect(
        root: NovexReferenceTarget,
        purposes: Set<NovexReferencePurpose>,
        maxTargets: Int = 1_000,
        maxDepth: Int = 64,
        read: suspend (NovexReferenceTarget) -> List<NovexCardReference>?,
    ): NovexReferenceTraversalResult {
        require(maxTargets > 0 && maxDepth >= 0) { "引用展开限制无效" }
        val targets = linkedSetOf<NovexReferenceTarget>()
        val references = linkedMapOf<String, NovexCardReference>()
        val active = mutableSetOf<NovexReferenceTarget>()
        val visited = mutableSetOf<NovexReferenceTarget>()
        val cycles = linkedSetOf<String>()
        val missing = linkedSetOf<NovexReferenceTarget>()
        var truncated = false

        suspend fun visit(target: NovexReferenceTarget, depth: Int) {
            if (target in visited) return
            if (depth > maxDepth || visited.size >= maxTargets) {
                truncated = true
                return
            }
            visited += target
            val links = read(target)
            if (links == null) {
                missing += target
                return
            }
            targets += target
            active += target
            links.filter { reference ->
                reference.source == target.subject && reference.purpose in purposes &&
                    (target.moduleId == null || reference.sourceModuleId == target.moduleId)
            }.forEach { reference ->
                references[reference.id] = reference
                if (reference.target in active) cycles += reference.id
                else visit(reference.target, depth + 1)
            }
            active -= target
        }

        visit(root, 0)
        return NovexReferenceTraversalResult(targets.toList(), references.values.toList(), cycles, missing, truncated)
    }
}
