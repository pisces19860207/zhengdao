// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.baopu.state.cavity

import com.example.zhengdao.baopu.state.form.FormFixtures
import com.example.zhengdao.baopu.state.geometry.Box
import com.example.zhengdao.baopu.state.geometry.Capsule
import com.example.zhengdao.baopu.state.geometry.ConvexHull
import com.example.zhengdao.baopu.state.geometry.Ellipsoid
import com.example.zhengdao.baopu.state.geometry.Gjk
import com.example.zhengdao.baopu.state.geometry.Epa
import com.example.zhengdao.baopu.state.geometry.Rot3
import com.example.zhengdao.baopu.state.geometry.Sphere
import com.example.zhengdao.baopu.state.geometry.Vec3
import com.example.zhengdao.baopu.state.geometry.ConvexShape
import com.example.zhengdao.baopu.state.geometry.lowestY
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 交付①＋判据①：四类简化代理体、特征点、包围盒，且都能被 655 的 GJK/EPA 判。 */
class OrganProxyTest {

    private val dirs = listOf(
        Vec3(1.0, 0.0, 0.0), Vec3(-1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0),
        Vec3(0.0, -1.0, 0.0), Vec3(0.0, 0.0, 1.0), Vec3(0.0, 0.0, -1.0),
        Vec3(1.0, 1.0, 0.0), Vec3(1.0, -1.0, 1.0), Vec3(-1.0, 1.0, -1.0),
        Vec3(1.0, 1.0, 1.0), Vec3(-1.0, -1.0, 1.0), Vec3(1.0, -1.0, 1.0),
        Vec3(-1.0, 1.0, 1.0), Vec3(2.0, -3.0, 0.5),
    )

    private fun supportValue(s: ConvexShape, d: Vec3) = s.support(d).dot(d)

    @Test
    fun `四类各归其类_盒走降阶_不新增形状原语`() {
        assertEquals(ProxyKind.SPHERE, ProxySimplify.of("a", Sphere(Vec3.ZERO, 1.0)).kind)
        assertEquals(ProxyKind.CAPSULE, ProxySimplify.of("b", Capsule(Vec3.ZERO, Vec3(0.0, 2.0, 0.0), 0.5)).kind)
        assertEquals(ProxyKind.ELLIPSOID, ProxySimplify.of("c", Ellipsoid(Vec3.ZERO, Vec3(1.0, 2.0, 3.0))).kind)
        assertEquals(ProxyKind.HULL, ProxySimplify.of("d", ConvexHull(listOf(Vec3.ZERO, Vec3(1.0, 0.0, 0.0)))).kind)
        assertEquals(ProxyKind.HULL, CavityFixtures.boxProxy("e", Vec3.ZERO, Vec3(1.0, 1.0, 1.0)).kind)
    }

    @Test
    fun `盒降阶成八角凸包_支撑函数与盒逐方向同值`() {
        val box = Box(Vec3(1.0, 2.0, 3.0), Vec3(0.5, 1.5, 2.5))
        val hull = CavityFixtures.boxProxy("f", Vec3(1.0, 2.0, 3.0), Vec3(0.5, 1.5, 2.5))
        assertEquals(8, (hull.shape as ConvexHull).vertices.size)
        for (d in dirs) {
            assertTrue(
                "方向 $d 支撑值应同盒：${supportValue(hull.shape, d)} vs ${supportValue(box, d)}",
                abs(supportValue(hull.shape, d) - supportValue(box, d)) < 1e-9,
            )
        }
    }

    @Test
    fun `判据一_四类代理体都能被655的GJK判距离`() {
        // 探针球球心在 z＝40、半径 1；四类都关于 Z 轴对称，最近点是各自的 z 向界
        // ⇒ 期望距离＝40 − 自身 z 向界 − 1。球界 1、胶囊界 0.5（半径）、椭球界 3（第三半轴）、凸包界 1（顶点 (0,0,1)）。
        val probe = CavityFixtures.sphereAtZ("probe", 40.0, 1.0)
        val others = listOf(
            CavityFixtures.sphereAtZ("s", 0.0) to 38.0,
            CavityFixtures.capsule("c", Vec3.ZERO, Vec3(0.0, 2.0, 0.0), 0.5) to 38.5,
            CavityFixtures.ellipsoid("e", Vec3.ZERO, Vec3(1.0, 2.0, 3.0)) to 36.0,
            CavityFixtures.hull(
                "h",
                listOf(Vec3.ZERO, Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0)),
            ) to 38.0,
        )
        for ((o, expectCm) in others) {
            val g = Gjk.distance(o.shape, probe.shape)
            assertEquals("${o.id} 到探针球面的距离（40 − z 向界 − 1）", expectCm, g.distanceCm, 1e-6)
            assertFalse("${o.id} 距 $expectCm 不该判成相交", g.intersecting)
        }
    }

    @Test
    fun `判据一_四类代理体都能被655的EPA判穿透`() {
        val others = listOf(
            CavityFixtures.sphereAtZ("s", 0.6),
            CavityFixtures.capsule("c", Vec3(0.0, 0.0, 0.6), Vec3(0.0, 2.0, 0.6), 0.5),
            CavityFixtures.ellipsoid("e", Vec3(0.0, 0.0, 0.6), Vec3(1.0, 1.0, 1.0)),
            CavityFixtures.hull("h", listOf(Vec3(0.0, 0.0, 0.6), Vec3(2.0, 0.0, 0.6), Vec3(0.0, 2.0, 0.6), Vec3(0.0, 0.0, 2.6))),
        )
        val a = CavityFixtures.sphereAtZ("a", 0.0)
        for (o in others) {
            val r = Epa.penetration(a.shape, o.shape)
            assertTrue("${o.id} 应判出相交并给深度", r != null && r.depthCm >= 0.0)
        }
    }

    @Test
    fun `特征点数量按四类钉死_球一胶囊二椭球六`() {
        assertEquals(1, CavityFixtures.sphereAtZ("s", 0.0).features().points.size)
        assertEquals(2, CavityFixtures.capsule("c", Vec3.ZERO, Vec3(0.0, 2.0, 0.0), 0.5).features().points.size)
        assertEquals(6, CavityFixtures.ellipsoid("e", Vec3.ZERO, Vec3(1.0, 2.0, 3.0)).features().points.size)
    }

    @Test
    fun `球特征点是球心_胶囊是两端点`() {
        assertEquals(Vec3(0.0, 0.0, 1.5), CavityFixtures.sphereAtZ("s", 1.5).features().points[0].point)
        val c = CavityFixtures.capsule("c", Vec3(1.0, 2.0, 3.0), Vec3(4.0, 5.0, 6.0), 0.5).features()
        assertEquals(listOf(Vec3(1.0, 2.0, 3.0), Vec3(4.0, 5.0, 6.0)), c.points.map { it.point })
    }

    @Test
    fun `椭球六特征点是三轴极点_带朝向一起转`() {
        val f = CavityFixtures.ellipsoid("e", Vec3.ZERO, Vec3(1.0, 2.0, 3.0)).features().points
        val expect = listOf(
            Vec3(1.0, 0.0, 0.0), Vec3(-1.0, 0.0, 0.0), Vec3(0.0, 2.0, 0.0),
            Vec3(0.0, -2.0, 0.0), Vec3(0.0, 0.0, 3.0), Vec3(0.0, 0.0, -3.0),
        )
        assertEquals(expect.map { it.x }, f.map { it.point.x })
        assertEquals(expect.map { it.y }, f.map { it.point.y })
        assertEquals(expect.map { it.z }, f.map { it.point.z })
        val rot = checkNotNull(Rot3.fromAxisAngle(Vec3.UNIT_Z, 1.5707963267948966))
        val turned = CavityFixtures.ellipsoid("r", Vec3.ZERO, Vec3(1.0, 2.0, 3.0), rot).features().points
        assertTrue("转 90° 后 +X 极应落到 +Y", abs(turned[0].point.y - 1.0) < 1e-9)
    }

    @Test
    fun `凸包特征点上限十二_超出记截断与条数`() {
        val f = CavityFixtures.bigHull("h").features()
        assertEquals(12, f.points.size)
        assertTrue("超上限必须标 truncated", f.truncated)
        assertEquals("截掉个数要记账", 8, f.droppedCount)
    }

    @Test
    fun `凸包恰好十二个顶点_不标截断`() {
        val f = CavityFixtures.hull("h", (0 until 12).map { Vec3(it.toDouble(), (it % 3).toDouble(), (it % 4).toDouble()) }).features()
        assertEquals(12, f.points.size)
        assertFalse(f.truncated)
        assertEquals(0, f.droppedCount)
    }

    @Test
    fun `凸包顶点按规范字典序编号_打乱输入序结果逐字节同`() {
        val a = CavityFixtures.bigHull("h").features()
        val b = CavityFixtures.bigHullShuffled("h").features()
        assertEquals(a.points.map { it.point }, b.points.map { it.point })
        assertEquals(a.points.map { it.index }, b.points.map { it.index })
        assertEquals(a.droppedCount, b.droppedCount)
    }

    @Test
    fun `特征点序号从零连续升序`() {
        val f = CavityFixtures.bigHull("h").features()
        assertEquals((0 until 12).toList(), f.points.map { it.index })
    }

    @Test
    fun `包围盒对解析体精确`() {
        val s = CavityFixtures.sphereAtZ("s", 2.0, 1.5).aabb()
        assertEquals(0.5, s.min.z, 1e-9); assertEquals(3.5, s.max.z, 1e-9)
        assertEquals(-1.5, s.min.x, 1e-9); assertEquals(1.5, s.max.x, 1e-9)
        val c = CavityFixtures.capsule("c", Vec3(1.0, 0.0, 0.0), Vec3(3.0, 0.0, 0.0), 0.5).aabb()
        assertEquals(0.5, c.min.x, 1e-9); assertEquals(3.5, c.max.x, 1e-9)
        assertEquals(-0.5, c.min.y, 1e-9); assertEquals(0.5, c.max.y, 1e-9)
        val e = CavityFixtures.ellipsoid("e", Vec3(1.0, 2.0, 3.0), Vec3(0.5, 1.0, 2.0)).aabb()
        assertEquals(0.5, e.min.x, 1e-9); assertEquals(5.0, e.max.z, 1e-9)
    }

    @Test
    fun `包围盒保守_装得下全部特征点也装得下全部支撑点`() {
        for (p in listOf(
            CavityFixtures.sphereAtZ("s", 1.0), CavityFixtures.capsule("c", Vec3.ZERO, Vec3(0.0, 2.0, 0.0), 0.5),
            CavityFixtures.ellipsoid("e", Vec3(1.0, 1.0, 1.0), Vec3(1.0, 2.0, 3.0), checkNotNull(Rot3.fromAxisAngle(Vec3.UNIT_X, 0.7))),
            CavityFixtures.bigHull("h"),
        )) {
            val box = p.aabb()
            for (f in p.features().points) assertTrue("${p.id} 特征点须在盒内", box.contains(f.point))
            for (d in dirs) {
                val w = p.shape.support(d)
                val g = p.shape.support(-d)
                assertTrue("${p.id} 支撑点须在盒内", box.contains(w) && box.contains(g))
            }
        }
    }

    @Test
    fun `包围半径取半对角线_确实包住形状`() {
        val p = CavityFixtures.ellipsoid("e", Vec3(1.0, 1.0, 1.0), Vec3(1.0, 3.0, 2.0), checkNotNull(Rot3.fromAxisAngle(Vec3.UNIT_Y, 0.9)))
        val c = p.aabb().center()
        for (d in dirs) assertTrue((p.shape.support(d) - c).norm() <= p.boundingRadiusCm() + 1e-9)
    }

    @Test
    fun `对角线随形状变大而变大`() {
        val small = CavityFixtures.sphereAtZ("a", 0.0, 1.0).aabb().diagonalCm()
        val big = CavityFixtures.sphereAtZ("b", 0.0, 4.0).aabb().diagonalCm()
        assertTrue("$big 应大于 $small", big > small)
    }

    @Test
    fun `平移四类代理体_形状与kind都跟着走`() {
        for (p in listOf(
            CavityFixtures.sphereAtZ("s", 0.0), CavityFixtures.capsule("c", Vec3.ZERO, Vec3(0.0, 2.0, 0.0), 0.5),
            CavityFixtures.ellipsoid("e", Vec3.ZERO, Vec3(1.0, 2.0, 3.0)), CavityFixtures.hull("h", listOf(Vec3.ZERO, Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))),
        )) {
            val moved = p.shiftedBy(Vec3(0.0, 5.0, 0.0))
            assertEquals(p.kind, moved.kind)
            assertEquals(p.id, moved.id)
            assertEquals(p.lowestY() + 5.0, moved.lowestY(), 1e-9)
        }
    }

    @Test
    fun `lowestY与highestY与655的支撑口径一致`() {
        val p = CavityFixtures.boxProxy("b", Vec3(0.0, 3.0, 0.0), Vec3(1.0, 2.0, 1.0))
        assertEquals(p.shape.lowestY(), p.lowestY(), 1e-9)
        assertEquals(1.0, p.lowestY(), 1e-9)
        assertEquals(5.0, p.highestY(), 1e-9)
    }

    @Test
    fun `id空白即拒_kind与形状不符即拒`() {
        assertThrows(IllegalArgumentException::class.java) { OrganProxy("  ", ProxyKind.SPHERE, Sphere(Vec3.ZERO, 1.0)) }
        assertThrows(IllegalArgumentException::class.java) { OrganProxy("x", ProxyKind.SPHERE, Capsule(Vec3.ZERO, Vec3(0.0, 1.0, 0.0), 0.5)) }
        assertThrows(IllegalArgumentException::class.java) { OrganProxy("x", ProxyKind.HULL, Sphere(Vec3.ZERO, 1.0)) }
    }

    @Test
    fun `判据六_同一形状两次构造特征点与包围盒逐字节一致`() {
        val a = CavityFixtures.bigHull("h")
        val b = CavityFixtures.bigHull("h")
        assertEquals(a.features().toString(), b.features().toString())
        assertEquals(a.aabb().toString(), b.aabb().toString())
        assertEquals(a.boundingRadiusCm(), b.boundingRadiusCm(), 0.0)
    }

    @Test
    fun `交付四_从660导出三十二个代理体_名与代理体一一对应`() {
        val src = FormFixtures.state().proxies
        val out = ProxySimplify.fromBody(src)
        assertEquals(src.all.size, out.size)
        assertEquals(src.all.map { it.name }.toSet(), out.map { it.id }.toSet())
        assertEquals(4, out.map { it.kind }.toSet().size)
    }

    @Test
    fun `从660导出的代理体包围盒都非退化`() {
        for (p in CavityFixtures.fromBody()) {
            assertTrue("${p.id} 对角线应为正", p.aabb().diagonalCm() > 0.0)
            assertTrue("${p.id} 特征点至少一个", p.features().points.isNotEmpty())
        }
    }

    @Test
    fun `简化阶梯_任一代理体都能降成球椭球胶囊凸包`() {
        val src = CavityFixtures.ellipsoid("e", Vec3(1.0, 2.0, 3.0), Vec3(1.0, 2.0, 4.0))
        for ((id, f) in listOf(
            "hull" to ProxyKind.HULL, "capsule" to ProxyKind.CAPSULE,
            "ellipsoid" to ProxyKind.ELLIPSOID, "sphere" to ProxyKind.SPHERE,
        )) {
            val d = ProxySimplify.simplify(src, id, f)
            assertEquals(f, d.kind)
            assertTrue("${d.id} 应包住原形状的全部特征点", d.aabb().diagonalCm() >= src.aabb().diagonalCm() - 1e-9)
        }
    }

    @Test
    fun `降阶成球后半径即半对角线`() {
        val src = CavityFixtures.ellipsoid("e", Vec3.ZERO, Vec3(1.0, 2.0, 2.0))
        val ball = ProxySimplify.simplify(src, "b", ProxyKind.SPHERE)
        assertEquals(src.boundingRadiusCm(), (ball.shape as Sphere).radius, 1e-9)
        assertEquals(Vec3.ZERO, (ball.shape as Sphere).center)
    }
}
