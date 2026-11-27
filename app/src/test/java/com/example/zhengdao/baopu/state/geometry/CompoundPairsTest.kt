// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百九十五批 · 复合候选查询：anyHit/minDistance 与 EPA 深度语义。
package com.example.zhengdao.baopu.state.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompoundPairsTest {

    @Test
    fun 单复合对凸体_分离时距离取最近块且深度为零() {
        val proxy = spheres("a", listOf(Vec3.ZERO, Vec3(10.0, 0.0, 0.0)))
        val other = Sphere(Vec3(3.0, 0.0, 0.0), 1.0)
        val read = requireNotNull(CompoundPairs.read(segment("a", proxy.chunks.first().shape), segment("b", other), mapOf("a" to proxy)))
        val nearest = proxy.minDistance(other)

        assertEquals(1.0, read.distanceCm, 1e-12)
        assertFalse(read.intersecting)
        assertEquals(0.0, read.depthCm, 0.0)
        assertEquals("c0", read.nearestChunkId)
        assertEquals(proxy.anyHit(other).intersecting, read.intersecting)
        assertEquals(nearest.distanceCm, read.distanceCm, 0.0)
        assertEquals(nearest.iterations, read.iterations)
        println("PAIR_SINGLE distance=${read.distanceCm} depth=${read.depthCm} iterations=${read.iterations} chunks=${read.chunkCount}")
    }

    @Test
    fun 单复合对凸体_命中后深度仍取原EPA各块最大值() {
        val proxy = spheres("a", listOf(Vec3.ZERO, Vec3(10.0, 0.0, 0.0)))
        val other = Sphere(Vec3(1.0, 0.0, 0.0), 8.5)
        val read = requireNotNull(CompoundPairs.read(segment("a", proxy.chunks.first().shape), segment("b", other), mapOf("a" to proxy)))
        val depths = proxy.chunks.map { Epa.penetration(it.shape, other)?.depthCm ?: 0.0 }
        val positiveDepths = depths.filter { it > 0.0 }
        val expected = depths.max()

        assertEquals("两块都须正穿透且深度不同", 2, positiveDepths.distinct().size)
        assertTrue(read.intersecting)
        assertEquals(expected, read.depthCm, 1e-12)
        assertTrue(read.depthCm > 0.9)
        assertEquals(2, read.chunkCount)
        val reversed = CompoundProxy(proxy.id, proxy.chunks.reversed())
        val reversedRead = requireNotNull(
            CompoundPairs.read(
                segment("a", reversed.chunks.first().shape),
                segment("b", other),
                mapOf("a" to reversed),
            ),
        )
        assertEquals("反转块序仍取最大深度", expected, reversedRead.depthCm, 1e-12)
        println("PAIR_SINGLE_MAX depths=$depths max=${read.depthCm} reversed=${reversedRead.depthCm}")
    }

    @Test
    fun 单复合对凸体_变换块的EPA也在世界坐标计算() {
        val rotation = checkNotNull(Rot3.fromAxisAngle(Vec3.UNIT_Z, -Math.PI / 2.0))
        val proxy = CompoundProxy(
            "a",
            listOf(
                ProxyChunk("rotated", Capsule(Vec3.ZERO, Vec3.UNIT_Y * 8.0, 1.0), LocalTransform(Vec3(2.0, 0.0, 0.0), rotation)),
                ProxyChunk("far", Sphere(Vec3(100.0, 0.0, 0.0), 1.0), LocalTransform.IDENTITY),
            ),
        )
        val other = Sphere(Vec3(6.0, 0.0, 0.0), 1.0)
        val read = requireNotNull(CompoundPairs.read(segment("a", proxy.chunks.first().shape), segment("b", other), mapOf("a" to proxy)))
        val world = Capsule(Vec3(2.0, 0.0, 0.0), Vec3(10.0, 0.0, 0.0), 1.0)

        assertTrue(read.intersecting)
        assertTrue(read.depthCm > 0.9)
        // 旋转支撑点与直接世界胶囊的舍入路径不同，EPA 只要求数值一致。
        assertEquals(Epa.penetration(world, other)?.depthCm ?: 0.0, read.depthCm, 1e-3)
        assertEquals("rotated", read.nearestChunkId)
    }

    @Test
    fun 双侧复合_距离与命中按全块对且深度走原EPA() {
        val a = CompoundProxy(
            "a",
            listOf(
                ProxyChunk("a-left", Sphere(Vec3.ZERO, 1.0), LocalTransform.IDENTITY),
                ProxyChunk("a-right", Sphere(Vec3.ZERO, 1.0), LocalTransform(Vec3(10.0, 0.0, 0.0), Rot3.IDENTITY)),
            ),
        )
        val b = CompoundProxy(
            "b",
            listOf(
                ProxyChunk("near-a", Sphere(Vec3.ZERO, 1.0), LocalTransform(Vec3(0.2, 0.0, 0.0), Rot3.IDENTITY)),
                ProxyChunk("near-b", Sphere(Vec3.ZERO, 1.0), LocalTransform(Vec3(9.4, 0.0, 0.0), Rot3.IDENTITY)),
            ),
        )
        val read = requireNotNull(CompoundPairs.read(segment("a", a.chunks.first().shape), segment("b", b.chunks.first().shape), mapOf("a" to a, "b" to b)))
        val depths = a.chunks.flatMap { ca -> b.chunks.map { cb -> Epa.penetration(worldSphere(ca), worldSphere(cb))?.depthCm ?: 0.0 } }
        val positiveDepths = depths.filter { it > 0.0 }
        val expected = depths.max()
        val expectedIterations = a.chunks.sumOf { ca -> b.minDistance(worldSphere(ca)).iterations }

        assertTrue("双侧夹具必须有正穿透深度", positiveDepths.isNotEmpty())
        assertEquals("两个近块对深度须不同，才能证明取最大值", 2, positiveDepths.distinct().size)
        assertTrue(read.intersecting)
        assertEquals(0.0, read.distanceCm, 0.0)
        assertEquals(expected, read.depthCm, 1e-12)
        assertTrue(read.depthCm > 1.0)
        assertEquals(expectedIterations, read.iterations)
        assertEquals(4, read.chunkCount)
        println("PAIR_BOTH distance=${read.distanceCm} depth=${read.depthCm} iterations=${read.iterations} chunks=${read.chunkCount} transforms=translated")
        val reversedB = CompoundProxy(b.id, b.chunks.reversed())
        val reversed = requireNotNull(
            CompoundPairs.read(
                segment("a", a.chunks.first().shape),
                segment("b", reversedB.chunks.first().shape),
                mapOf("a" to a, "b" to reversedB),
            ),
        )
        assertTrue("只反转一侧后按索引配对必须漏命中", reversed.intersecting)
        assertEquals(0.0, reversed.distanceCm, 0.0)
        assertEquals("单侧反转仍须覆盖全块对最大值", expected, reversed.depthCm, 1e-12)
        assertEquals(read.iterations, reversed.iterations)
        assertEquals(4, reversed.chunkCount)
        println("PAIR_BOTH_REVERSED distance=${reversed.distanceCm} depth=${reversed.depthCm} iterations=${reversed.iterations} chunks=${reversed.chunkCount}")
    }

    @Test
    fun 无复合注册时查询缺席_保留原凸体路径() {
        val a = segment("a", Sphere(Vec3.ZERO, 1.0))
        val b = segment("b", Sphere(Vec3(1.0, 0.0, 0.0), 1.0))
        assertEquals(null, CompoundPairs.read(a, b, emptyMap()))
    }

    private fun segment(name: String, shape: ConvexShape) = SegmentProxy(name, shape)

    private fun worldSphere(chunk: ProxyChunk): Sphere {
        val sphere = chunk.shape as Sphere
        return Sphere(chunk.transform.worldPoint(sphere.center), sphere.radius)
    }

    private fun spheres(id: String, centers: List<Vec3>): CompoundProxy = CompoundProxy(
        id,
        centers.mapIndexed { index, center -> ProxyChunk("c$index", Sphere(center, 1.0), LocalTransform.IDENTITY) },
    )
}
