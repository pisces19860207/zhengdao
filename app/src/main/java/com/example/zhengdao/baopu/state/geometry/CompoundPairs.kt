// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.state.geometry

internal data class CompoundPairReading(
    val distanceCm: Double,
    val depthCm: Double,
    val intersecting: Boolean,
    val iterations: Int,
    val chunkCount: Int,
    val nearestChunkId: String,
)

internal object CompoundPairs {

    private data class Query(val hit: Boolean, val nearest: CompoundDistance)

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
        return CompoundPairReading(
            distanceCm = result.nearest.distanceCm,
            depthCm = if (result.hit) maxDepth(compound, other, compoundFirst) else 0.0,
            intersecting = result.hit,
            iterations = result.nearest.iterations,
            chunkCount = compound.chunks.size,
            nearestChunkId = result.nearest.nearestChunkId,
        )
    }

    private fun readBoth(a: CompoundProxy, b: CompoundProxy): CompoundPairReading {
        val queries = a.chunks.map { query(b, world(it)) }
        val hit = queries.any { it.hit }
        val nearest = queries.minBy { it.nearest.distanceCm }
        return CompoundPairReading(
            distanceCm = nearest.nearest.distanceCm,
            depthCm = if (hit) maxPairDepth(a, b) else 0.0,
            intersecting = hit,
            iterations = queries.sumOf { it.nearest.iterations },
            chunkCount = a.chunks.size * b.chunks.size,
            nearestChunkId = nearest.nearest.nearestChunkId,
        )
    }

    private fun query(compound: CompoundProxy, other: ConvexShape): Query {
        val hit = compound.anyHit(other)
        return Query(hit, compound.minDistance(other))
    }

    private fun maxDepth(compound: CompoundProxy, other: ConvexShape, compoundFirst: Boolean): Double =
        compound.chunks.maxOf { chunk ->
            val transformed = world(chunk)
            val depth = if (compoundFirst) {
                Epa.penetration(transformed, other)
            } else {
                Epa.penetration(other, transformed)
            }
            depth?.depthCm ?: 0.0
        }

    private fun maxPairDepth(a: CompoundProxy, b: CompoundProxy): Double =
        a.chunks.maxOf { left -> b.chunks.maxOf { right -> Epa.penetration(world(left), world(right))?.depthCm ?: 0.0 } }

    private fun world(chunk: ProxyChunk): ConvexShape = TransformedChunk(chunk.shape, chunk.transform)

    private class TransformedChunk(
        private val base: ConvexShape,
        private val tf: LocalTransform,
    ) : ConvexShape {
        override fun support(dir: Vec3): Vec3 =
            tf.worldPoint(base.support(tf.rotation.transposeTimes(dir)))
    }
}
