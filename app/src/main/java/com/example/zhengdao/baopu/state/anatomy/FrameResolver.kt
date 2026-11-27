package com.example.zhengdao.baopu.state.anatomy

import com.example.zhengdao.baopu.state.geometry.Rot3
import com.example.zhengdao.baopu.state.geometry.Vec3

internal data class AnatomicalFrame(
    val id: String,
    val origin: Vec3,
    val orientation: Rot3,
) {
    init {
        require(id.isNotBlank()) { "anatomicalFrame.id 不许为空" }
    }
}

internal data class FrameResolution(
    val regionFrameId: String,
    val targetFrameId: String,
    val points: List<Vec3>,
)

internal interface FrameResolver {
    fun resolve(
        regionFrame: AnatomicalFrame,
        targetFrame: AnatomicalFrame,
        points: List<Vec3>,
    ): Resolution<FrameResolution>
}
