// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十二批 · 复合代理判据：三夹具差异、块数闭集、确定性、退化契约。
package com.example.zhengdao.baopu.state.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class CompoundProxyTest {

    private data class Fixture(val name: String, val u: Vec3, val v: Vec3)

    private val fixtures = listOf(
        Fixture("腋下", Vec3.UNIT_Y, Vec3.UNIT_X),
        Fixture("胯", Vec3.UNIT_Y, -Vec3.UNIT_X),
        Fixture("颈侧", Vec3.UNIT_Y, Vec3.UNIT_Z),
    )

    private fun specOf(name: String, u: Vec3, v: Vec3): PartSpec = PartSpec(
        id = name, joint = Vec3.ZERO, spineDir = u, branchDir = v, scaleCm = 30.0,
        notchTier = 2, bodyFatTier = 3, muscleTier = 2, skeletonTier = 1,
    )

    /** 单胶囊外包近似：沿凹口平分线的一根粗胶囊，覆盖两轴张开的整个部位。 */
    private fun singleCapsule(u: Vec3, v: Vec3): Capsule {
        val bis = (u.normalized() + v.normalized()).normalized()
        return Capsule(bis * -1.5, bis * 10.5, 6.6)
    }

    /** 探针＝凹口内的小球：中心在关节沿内角平分线 0.22·标尺处。 */
    private fun notchProbe(u: Vec3, v: Vec3): Sphere {
        val bis = (u.normalized() + v.normalized()).normalized()
        return Sphere(bis * 6.6, 0.6)
    }

    @Test
    fun 凹形三夹具_单胶囊与复合代理至少一例结论不同() {
        var different = 0
        val log = StringBuilder()
        for (f in fixtures) {
            val proxy = CompoundDecompose.decompose(specOf(f.name, f.u, f.v))
            val probe = notchProbe(f.u, f.v)
            val compoundHit = proxy.anyHit(probe).intersecting
            val singleHit = Gjk.distance(singleCapsule(f.u, f.v), probe).intersecting
            val compoundCm = proxy.minDistance(probe).distanceCm
            val singleCm = Gjk.distance(singleCapsule(f.u, f.v), probe).distanceCm
            log.appendLine("${f.name}: 单胶囊 命中=$singleHit 距离=$singleCm | 复合 命中=$compoundHit 距离=$compoundCm")
            if (singleHit != compoundHit) different++
        }
        println(log.toString())
        assertTrue("三例全同即红；差异数=$different\n$log", different >= 1)
    }

    @Test
    fun 凹口内探针复合层判不相交且距离为正() {
        for (f in fixtures) {
            val proxy = CompoundDecompose.decompose(specOf(f.name, f.u, f.v))
            val read = proxy.minDistance(notchProbe(f.u, f.v))
            assertFalse("${f.name} 凹口探针不该命中", read.intersecting)
            assertTrue("${f.name} 凹口距离须为正：${read.distanceCm}", read.distanceCm > 0.0)
            assertEquals(read.intersecting, proxy.anyHit(notchProbe(f.u, f.v)).intersecting)
        }
    }

    @Test
    fun 块数恒在二到六且全档位组合覆盖() {
        var count = 0
        var maxSeen = 0
        var minSeen = Int.MAX_VALUE
        for (notch in 0..2) for (fat in 0..3) for (muscle in 0..2) for (frame in 0..2) {
            val spec = PartSpec(
                "c", Vec3.ZERO, Vec3.UNIT_Y, Vec3.UNIT_X, 30.0, notch, fat, muscle, frame,
            )
            val proxy = CompoundDecompose.decompose(spec)
            assertTrue("块数越界：${proxy.chunks.size}", proxy.chunks.size in 2..6)
            assertEquals(proxy.chunks.size, proxy.chunks.map { it.id }.distinct().size)
            maxSeen = maxOf(maxSeen, proxy.chunks.size)
            minSeen = minOf(minSeen, proxy.chunks.size)
            count++
        }
        assertEquals(108, count) // 3 凹深 × 4 体脂 × 3 肌量 × 3 骨架
        assertEquals(2, minSeen)
        assertEquals(6, maxSeen)
    }

    @Test
    fun 越界档位被夹回闭集且同输入逐位一致() {
        val probe = notchProbe(Vec3.UNIT_Y, Vec3.UNIT_X)
        val hiA = PartSpec("hi", Vec3.ZERO, Vec3.UNIT_Y, Vec3.UNIT_X, 30.0, 99, 99, 99, 99)
        val hiB = PartSpec("hi", Vec3.ZERO, Vec3.UNIT_Y, Vec3.UNIT_X, 30.0, 99, 99, 99, 99)
        val pHi1 = CompoundDecompose.decompose(hiA)
        val pHi2 = CompoundDecompose.decompose(hiB)
        assertEquals("正向越界须夹到最大块数", 6, pHi1.chunks.size)
        assertEquals("块序列须逐位一致", pHi1.chunks, pHi2.chunks)
        assertEquals("查询结果须逐位一致", pHi1.minDistance(probe), pHi2.minDistance(probe))
        assertEquals(pHi1.anyHit(probe), pHi2.anyHit(probe))
        val loA = PartSpec("lo", Vec3.ZERO, Vec3.UNIT_Y, Vec3.UNIT_X, 30.0, -5, -5, -5, -5)
        val loB = PartSpec("lo", Vec3.ZERO, Vec3.UNIT_Y, Vec3.UNIT_X, 30.0, -5, -5, -5, -5)
        val pLo1 = CompoundDecompose.decompose(loA)
        val pLo2 = CompoundDecompose.decompose(loB)
        assertEquals("负向越界须夹到最小块数", 2, pLo1.chunks.size)
        assertEquals("块序列须逐位一致", pLo1.chunks, pLo2.chunks)
        assertEquals("查询结果须逐位一致", pLo1.minDistance(probe), pLo2.minDistance(probe))
    }

    @Test(expected = IllegalArgumentException::class)
    fun 近共线两轴构造即拒() {
        PartSpec("bad", Vec3.ZERO, Vec3.UNIT_Y, Vec3(1e-12, 1.0, 0.0), 30.0, 0, 0, 0, 1)
    }

    @Test
    fun 支撑适配器_恒等与绕Y转九十度对解析() {
        val ball = Sphere(Vec3.ZERO, 2.0)
        val id = LocalTransform.IDENTITY
        assertEquals(Vec3(2.0, 0.0, 0.0), id.worldPoint(ball.support(Vec3.UNIT_X)))
        val turned = checkNotNull(Rot3.fromAxisAngle(Vec3.UNIT_Y, PI / 2.0))
        val local = ball.support(turned.transposeTimes(Vec3.UNIT_X))
        val world = turned.times(local)
        // 球旋转不变：世界方向 X 问进去，转回世界后支撑点仍应沿 X（不是沿旋转后的局部轴）。
        assertEquals(2.0, world.x, 1e-12)
        assertEquals(0.0, world.y, 1e-12)
        assertEquals(0.0, world.z, 1e-12)
    }

    @Test
    fun 退化契约不变_零半径球与单点凸包经复合层仍sane() {
        val probe = Sphere(Vec3(2.0, 0.0, 0.0), 0.0)
        val chunks = listOf(
            ProxyChunk("z", Sphere(Vec3.ZERO, 0.0), LocalTransform.IDENTITY),
            ProxyChunk("p", ConvexHull(listOf(Vec3(2.0, 0.0, 0.0))), LocalTransform.IDENTITY),
        )
        val proxy = CompoundProxy("degenerate", chunks)
        val read = proxy.minDistance(probe)
        val direct = chunks.map { Gjk.distance(it.shape, probe) }
        assertEquals(0.0, read.distanceCm, 0.0)
        assertTrue(read.intersecting)
        assertEquals(direct.minOf { it.distanceCm }, read.distanceCm, 0.0)
        assertTrue(read.iterations <= 64)
        assertFalse(read.distanceCm.isNaN())
        assertFalse(read.distanceCm.isInfinite())
        assertEquals("p", read.nearestChunkId)
    }

    @Test
    fun 退化契约不变_零长胶囊与共线凸包逐字段对齐直调GJK() {
        val probe = Capsule(Vec3(4.0, 0.0, 0.0), Vec3(4.0, 0.0, 0.0), 0.0)
        val chunks = listOf(
            ProxyChunk("line", Capsule(Vec3.ZERO, Vec3.ZERO, 1.0), LocalTransform.IDENTITY),
            ProxyChunk(
                "col",
                ConvexHull(listOf(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(2.0, 0.0, 0.0))),
                LocalTransform.IDENTITY,
            ),
        )
        val proxy = CompoundProxy("degenerate2", chunks)
        val read = proxy.minDistance(probe)
        val direct = chunks.map { Gjk.distance(it.shape, probe) }
        assertEquals(direct.minOf { it.distanceCm }, read.distanceCm, 0.0)
        assertEquals(read.intersecting, proxy.anyHit(probe).intersecting)
        assertEquals(direct.any { it.intersecting }, read.intersecting)
        assertTrue(read.iterations <= 64)
        assertTrue(read.distanceCm >= 0.0)
    }

    @Test
    fun 最小距离块id与逐块手算一致() {
        val proxy = CompoundDecompose.decompose(specOf("probe-out", Vec3.UNIT_Y, Vec3.UNIT_X))
        val far = Sphere(Vec3(40.0, 0.0, 0.0), 1.0)
        val direct = proxy.chunks.map { c ->
            val wrapped = object : ConvexShape {
                override fun support(dir: Vec3): Vec3 =
                    c.transform.worldPoint(c.shape.support(c.transform.rotation.transposeTimes(dir)))
            }
            Gjk.distance(wrapped, far)
        }
        val read = proxy.minDistance(far)
        assertEquals(direct.minOf { it.distanceCm }, read.distanceCm, 0.0)
        assertEquals(proxy.chunks[direct.indexOfMin()].id, read.nearestChunkId)
    }

    private fun List<GjkResult>.indexOfMin(): Int = indices.minByOrNull { this[it].distanceCm } ?: 0
}
