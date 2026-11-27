// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.state.geometry

internal data class CompoundPairReading(
    val distanceCm: Double,
    val depthCm: Double,
    val intersecting: Boolean,
    val iterations: Int,
    val chunkCount: Int,
    val nearestChunkId: String,
    val status: KernelStatus = KernelStatus.Success,
    val reason: String? = null,
)

internal object CompoundPairs {

    private data class Query(val hit: CompoundHitResult, val nearest: CompoundDistance) {
        val status: KernelStatus = worstKernelStatus(hit.status, nearest.status)
        val reason: String? = when {
            hit.status == status -> hit.reason
            nearest.status == status -> nearest.reason
            else -> null
        }
    }

    private data class DepthReading(val value: Double, val boundary: KernelBoundary)

    fun read(
        a: SegmentProxy,
        b: SegmentProxy,
        compounds: Map<String, CompoundProxy>,
    ): CompoundPairReading? {
        val left = compounds[a.name]
        val right = compounds[b.name]
        return when {
            left != null && right != null -> readBoth(left, right)
            left != null -> readSingle(left, b.shape, compoundFirst = true)
            right != null -> readSingle(right, a.shape, compoundFirst = false)
            else -> null
        }
    }

    private fun readSingle(
        compound: CompoundProxy,
        other: ConvexShape,
        compoundFirst: Boolean,
    ): CompoundPairReading {
        val result = query(compound, other)
        if (result.status == KernelStatus.Unsupported) return unsupportedReading(compound.chunks.size, result.reason)
        val depth = if (result.hit.intersecting) {
            maxDepth(compound, other, compoundFirst)
        } else {
            DepthReading(0.0, KernelBoundary(result.status, result.reason))
        }
        val boundary = mergeKernelBoundary(KernelBoundary(result.status, result.reason), depth.boundary)
        return CompoundPairReading(
            distanceCm = result.nearest.distanceCm,
            depthCm = depth.value,
            intersecting = result.hit.intersecting,
            iterations = result.nearest.iterations,
            chunkCount = compound.chunks.size,
            nearestChunkId = result.nearest.nearestChunkId,
            status = boundary.status,
            reason = boundary.reason,
        )
    }

    private fun readBoth(a: CompoundProxy, b: CompoundProxy): CompoundPairReading {
        val input = mergeKernelBoundary(a.inputBoundary, b.inputBoundary)
        if (input.status == KernelStatus.Unsupported) {
            return unsupportedReading(a.chunks.size * b.chunks.size, input.reason)
        }
        val queries = a.chunks.map { query(b, world(it)) }
        var boundary = input
        for (query in queries) {
            boundary = mergeKernelBoundary(boundary, KernelBoundary(query.status, query.reason))
        }
        if (boundary.status == KernelStatus.Unsupported) {
            return unsupportedReading(a.chunks.size * b.chunks.size, boundary.reason)
        }
        val hit = queries.any { it.hit.intersecting }
        val nearest = queries.minBy { it.nearest.distanceCm }
        val depth = if (hit) maxPairDepth(a, b) else DepthReading(0.0, boundary)
        val finalBoundary = mergeKernelBoundary(boundary, depth.boundary)
        return CompoundPairReading(
            distanceCm = nearest.nearest.distanceCm,
            depthCm = depth.value,
            intersecting = hit,
            iterations = queries.sumOf { it.nearest.iterations },
            chunkCount = a.chunks.size * b.chunks.size,
            nearestChunkId = nearest.nearest.nearestChunkId,
            status = finalBoundary.status,
            reason = finalBoundary.reason,
        )
    }

    private fun query(compound: CompoundProxy, other: ConvexShape): Query {
        val hit = compound.anyHit(other)
        return Query(hit, compound.minDistance(other))
    }

    private fun maxDepth(compound: CompoundProxy, other: ConvexShape, compoundFirst: Boolean): DepthReading {
        var boundary = KernelBoundary(KernelStatus.Success)
        var max = 0.0
        for (chunk in compound.chunks) {
            val transformed = world(chunk)
            val depth = if (compoundFirst) {
                Epa.penetration(transformed, other)
            } else {
                Epa.penetration(other, transformed)
            }
            if (depth == null) continue
            boundary = mergeKernelBoundary(boundary, KernelBoundary(depth.status, depth.reason))
            if (depth.status != KernelStatus.Unsupported && depth.depthCm.isFinite()) {
                max = maxOf(max, depth.depthCm)
            }
        }
        return DepthReading(if (boundary.status == KernelStatus.Unsupported) Double.NaN else max, boundary)
    }

    private fun maxPairDepth(a: CompoundProxy, b: CompoundProxy): DepthReading {
        var boundary = KernelBoundary(KernelStatus.Success)
        var max = 0.0
        for (left in a.chunks) {
            for (right in b.chunks) {
                val depth = Epa.penetration(world(left), world(right)) ?: continue
                boundary = mergeKernelBoundary(boundary, KernelBoundary(depth.status, depth.reason))
                if (depth.status != KernelStatus.Unsupported && depth.depthCm.isFinite()) {
                    max = maxOf(max, depth.depthCm)
                }
            }
        }
        return DepthReading(if (boundary.status == KernelStatus.Unsupported) Double.NaN else max, boundary)
    }

    private fun unsupportedReading(chunkCount: Int, reason: String?): CompoundPairReading = CompoundPairReading(
        Double.POSITIVE_INFINITY,
        Double.NaN,
        false,
        0,
        chunkCount,
        "",
        status = KernelStatus.Unsupported,
        reason = reason ?: "复合对输入超出支持域",
    )

    private fun world(chunk: ProxyChunk): ConvexShape = TransformedChunk(chunk.shape, chunk.transform)

    private class TransformedChunk(
        private val base: ConvexShape,
        private val tf: LocalTransform,
    ) : BoundaryAwareShape {
        override fun support(dir: Vec3): Vec3 =
            tf.worldPoint(base.support(tf.rotation.transposeTimes(dir)))

        override fun boundary(): KernelBoundary {
            if (!vec3IsFinite(tf.offset) || !tf.rotation.isOrthonormal()) {
                return KernelBoundary(KernelStatus.Unsupported, "块刚体变换无效")
            }
            return shapeBoundary(base)
        }

        override fun scaleLength(): Double = boundaryScaleLen(base)
    }
}
