package com.example.zhengdao.baopu.state.anatomy

import com.example.zhengdao.baopu.state.geometry.ConvexShape
import com.example.zhengdao.baopu.state.geometry.LocalTransform

internal typealias Transform3 = LocalTransform

internal data class ShapePrimitive(
    val shape: ConvexShape,
    val transform: Transform3,
)

internal interface ShapeAdapter {
    fun adapt(parameters: AdmittedParameters): Resolution<List<ShapePrimitive>>
}
