package com.example.zhengdao.baopu.state.anatomy

internal data class ConstraintDescriptor(
    val id: String,
    val description: String,
) {
    init {
        require(id.isNotBlank()) { "constraint.id 不许为空" }
        require(description.isNotBlank()) { "constraint.description 不许为空" }
    }
}

internal data class ConstraintInput(
    val shapes: List<ShapePrimitive>,
    val frame: FrameResolution,
    val topology: TopologyRelations,
)

internal data class ConstraintFinding(
    val constraintId: String,
    val level: ValidationLevel,
    val detail: String,
)

internal interface ConstraintEngine {
    fun evaluate(input: ConstraintInput): Resolution<List<ConstraintFinding>>
}
