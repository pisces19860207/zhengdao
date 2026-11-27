// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.baopu.state.geometry

enum class KernelStatus { Success, Degenerate, Unsupported }

internal data class KernelBoundary(
    val status: KernelStatus,
    val reason: String? = null,
)

internal interface BoundaryAwareShape : ConvexShape {
    fun boundary(): KernelBoundary
    fun scaleLength(): Double = shapeScaleLen(this)
}

internal fun vec3IsFinite(v: Vec3): Boolean = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()

internal fun worstKernelStatus(vararg statuses: KernelStatus): KernelStatus = when {
    statuses.any { it == KernelStatus.Unsupported } -> KernelStatus.Unsupported
    statuses.any { it == KernelStatus.Degenerate } -> KernelStatus.Degenerate
    else -> KernelStatus.Success
}

internal fun mergeKernelBoundary(a: KernelBoundary, b: KernelBoundary): KernelBoundary {
    val status = worstKernelStatus(a.status, b.status)
    return KernelBoundary(
        status,
        when {
            a.status == status -> a.reason
            b.status == status -> b.reason
            else -> null
        },
    )
}

private const val AFFINE_REL = 1e-12
private const val MAX_SCALE_RATIO = 1e6

internal fun shapeBoundary(shape: ConvexShape): KernelBoundary = when (shape) {
    is BoundaryAwareShape -> shape.boundary()
    is Sphere -> sphereBoundary(shape)
    is Capsule -> capsuleBoundary(shape)
    is Ellipsoid -> ellipsoidBoundary(shape)
    is Box -> boxBoundary(shape)
    is ConvexHull -> hullBoundary(shape.vertices)
    else -> genericBoundary(shape)
}

private fun sphereBoundary(shape: Sphere): KernelBoundary = when {
    !vec3IsFinite(shape.center) || !shape.radius.isFinite() ->
        KernelBoundary(KernelStatus.Unsupported, "球心或半径非有限数")
    shape.radius == 0.0 -> KernelBoundary(KernelStatus.Degenerate, "零半径球退化为点")
    else -> KernelBoundary(KernelStatus.Success)
}

private fun capsuleBoundary(shape: Capsule): KernelBoundary = when {
    !vec3IsFinite(shape.a) || !vec3IsFinite(shape.b) || !shape.radius.isFinite() ||
        !vec3IsFinite(shape.b - shape.a) ->
        KernelBoundary(KernelStatus.Unsupported, "胶囊端点或半径非有限数")
    shape.a == shape.b && shape.radius == 0.0 ->
        KernelBoundary(KernelStatus.Degenerate, "零长零半径胶囊退化为点")
    shape.a != shape.b && shape.radius == 0.0 ->
        KernelBoundary(KernelStatus.Degenerate, "零半径胶囊退化为线段")
    else -> KernelBoundary(KernelStatus.Success)
}

private fun ellipsoidBoundary(shape: Ellipsoid): KernelBoundary = when {
    !vec3IsFinite(shape.center) || !vec3IsFinite(shape.radii) || !shape.rot.isOrthonormal() ->
        KernelBoundary(KernelStatus.Unsupported, "椭球数据或旋转非有限/非正交")
    shape.radii.x == 0.0 || shape.radii.y == 0.0 || shape.radii.z == 0.0 ->
        KernelBoundary(KernelStatus.Degenerate, "椭球至少一个半轴为零")
    else -> KernelBoundary(KernelStatus.Success)
}

private fun boxBoundary(shape: Box): KernelBoundary = when {
    !vec3IsFinite(shape.center) || !vec3IsFinite(shape.half) || !shape.rot.isOrthonormal() ->
        KernelBoundary(KernelStatus.Unsupported, "盒数据或旋转非有限/非正交")
    shape.half.x == 0.0 || shape.half.y == 0.0 || shape.half.z == 0.0 ->
        KernelBoundary(KernelStatus.Degenerate, "盒至少一个半宽为零")
    else -> KernelBoundary(KernelStatus.Success)
}

private fun hullBoundary(vertices: List<Vec3>): KernelBoundary {
    if (vertices.any { !vec3IsFinite(it) }) return KernelBoundary(KernelStatus.Unsupported, "凸包顶点非有限数")
    val p0 = vertices[0]
    var scale = 0.0
    var first = Vec3.ZERO
    for (v in vertices.drop(1)) {
        val d = v - p0
        if (!vec3IsFinite(d)) return KernelBoundary(KernelStatus.Unsupported, "凸包坐标跨度溢出")
        val n = d.norm()
        if (!n.isFinite()) return KernelBoundary(KernelStatus.Unsupported, "凸包尺度溢出")
        if (n > scale) {
            scale = n
            first = d
        }
    }
    if (scale == 0.0) return KernelBoundary(KernelStatus.Degenerate, "凸包退化为点")
    val u = first / scale
    var crossNorm = 0.0
    var second = Vec3.ZERO
    for (v in vertices.drop(1)) {
        val c = u.cross((v - p0) / scale)
        val n = c.norm()
        if (!n.isFinite()) return KernelBoundary(KernelStatus.Unsupported, "凸包仿射维数计算溢出")
        if (n > crossNorm) {
            crossNorm = n
            second = c
        }
    }
    if (crossNorm <= AFFINE_REL) return KernelBoundary(KernelStatus.Degenerate, "凸包退化为线段")
    val normal = second / crossNorm
    var height = 0.0
    for (v in vertices.drop(1)) {
        val h = kotlin.math.abs(normal.dot((v - p0) / scale))
        if (!h.isFinite()) return KernelBoundary(KernelStatus.Unsupported, "凸包仿射维数计算溢出")
        if (h > height) height = h
    }
    if (height <= AFFINE_REL) return KernelBoundary(KernelStatus.Degenerate, "凸包退化为平面")
    return KernelBoundary(KernelStatus.Success)
}

private fun genericBoundary(shape: ConvexShape): KernelBoundary {
    val probes = listOf(Vec3.UNIT_X, -Vec3.UNIT_X, Vec3.UNIT_Y, -Vec3.UNIT_Y, Vec3.UNIT_Z, -Vec3.UNIT_Z)
    if (probes.any { !vec3IsFinite(shape.support(it)) }) {
        return KernelBoundary(KernelStatus.Unsupported, "自定义凸体支撑点非有限数")
    }
    val scale = shapeScaleLen(shape)
    if (!scale.isFinite()) return KernelBoundary(KernelStatus.Unsupported, "自定义凸体尺度非有限数")
    if (scale == 0.0) return KernelBoundary(KernelStatus.Degenerate, "自定义凸体支撑集退化为点")
    return KernelBoundary(KernelStatus.Success)
}

internal fun kernelBoundary(a: ConvexShape, b: ConvexShape): KernelBoundary {
    val left = shapeBoundary(a)
    if (left.status == KernelStatus.Unsupported) return left
    val right = shapeBoundary(b)
    if (right.status == KernelStatus.Unsupported) return right
    val scaleA = boundaryScaleLen(a)
    val scaleB = boundaryScaleLen(b)
    if (!scaleA.isFinite() || !scaleB.isFinite() || scaleA < 0.0 || scaleB < 0.0) {
        return KernelBoundary(KernelStatus.Unsupported, "形体尺度非有限数")
    }
    val small = minOf(scaleA, scaleB)
    val large = maxOf(scaleA, scaleB)
    if (small > 0.0 && large / small >= MAX_SCALE_RATIO * (1.0 - 1e-12)) {
        return KernelBoundary(KernelStatus.Unsupported, "形体尺度比达到或超过 1e6")
    }
    val merged = mergeKernelBoundary(left, right)
    if (a is Sphere && b is Sphere && a.center == b.center && a.radius == b.radius) {
        return KernelBoundary(KernelStatus.Degenerate, "同心等球缺少唯一分离方向")
    }
    return merged
}

private fun hullScaleLen(vertices: List<Vec3>): Double {
    val origin = vertices[0]
    var minX = 0.0
    var minY = 0.0
    var minZ = 0.0
    var maxX = 0.0
    var maxY = 0.0
    var maxZ = 0.0
    for (vertex in vertices.drop(1)) {
        val d = vertex - origin
        if (!vec3IsFinite(d)) return Double.POSITIVE_INFINITY
        minX = minOf(minX, d.x)
        minY = minOf(minY, d.y)
        minZ = minOf(minZ, d.z)
        maxX = maxOf(maxX, d.x)
        maxY = maxOf(maxY, d.y)
        maxZ = maxOf(maxZ, d.z)
    }
    val x = maxX - minX
    val y = maxY - minY
    val z = maxZ - minZ
    return kotlin.math.sqrt(x * x + y * y + z * z)
}

internal fun boundaryScaleLen(shape: ConvexShape): Double = when (shape) {
    is BoundaryAwareShape -> shape.scaleLength()
    is Sphere -> 2.0 * kotlin.math.sqrt(3.0) * shape.radius
    is Capsule -> (shape.b - shape.a).norm() + 2.0 * kotlin.math.sqrt(3.0) * shape.radius
    is Ellipsoid -> 2.0 * kotlin.math.sqrt(
        shape.radii.x * shape.radii.x + shape.radii.y * shape.radii.y + shape.radii.z * shape.radii.z,
    )
    is Box -> 2.0 * kotlin.math.sqrt(
        shape.half.x * shape.half.x + shape.half.y * shape.half.y + shape.half.z * shape.half.z,
    )
    is ConvexHull -> hullScaleLen(shape.vertices)
    else -> shapeScaleLen(shape)
}
