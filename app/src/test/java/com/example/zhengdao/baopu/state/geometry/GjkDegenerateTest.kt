// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百一十二批 · GJK 退化构型回归（四类退化输入＋790 实跑触发对＋单纯形四点不变量）。
// 判据①：退化输入不崩（不抛越界/无 NaN/不超 64 轮）；判据③：四类各一个回归例＋触发对钉死；
// 单纯形 ≤4 是崩溃路径的总开关（>4 点才会进 solve 的错位分派）。
package com.example.zhengdao.baopu.state.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GjkDegenerateTest {

    private fun assertSane(tag: String, r: GjkResult) {
        assertFalse("$tag NaN", r.distanceCm.isNaN())
        assertFalse("$tag Inf", r.distanceCm.isInfinite())
        assertTrue("$tag 迭代超上限", r.iterations <= 64)
        assertTrue("$tag 单纯形超四点（越界路径的总开关）：${r.simplex.size}", r.simplex.size <= 4)
    }

    // ── 790 触发对（同族构造：臂胶囊 18.36/3.48 vs 区胶囊 2.2/1.34，近贴对数网格） ──

    private fun crasherPair(gapLog: Int, mix: Int, tilt: Int): Pair<Capsule, Capsule> {
        val rA = 3.475935828877006
        val rB = 1.3361375782450797
        val lenA = 18.36
        val lenB = 2.2
        val touch = rA + rB
        val gap = Math.pow(10.0, gapLog.toDouble())
        val lat = touch + gap * ((mix % 4) - 1.5)
        val ax = gap * ((mix / 4) - 1.5)
        val ang = tilt * Math.PI / 8
        val dx = if (tilt == 0) 0.0 else sin(ang) * lenB
        val dz = if (tilt == 0) 0.0 else cos(ang) * lenB
        return Capsule(Vec3(0.0, 0.0, 0.0), Vec3(0.0, -lenA, 0.0), rA) to
            Capsule(
                Vec3(lat, -lenA / 2 + ax, dz),
                Vec3(lat + dx, -lenA / 2 + ax - lenB * cos(ang), dz + lenB * sin(ang)),
                rB,
            )
    }

    private fun assertTriggerPairOk(tag: String, pair: Pair<Capsule, Capsule>) {
        val (a, b) = pair
        val r1 = Gjk.distance(a, b)
        assertSane(tag, r1)
        assertTrue("$tag 距离须有限且非负：${r1.distanceCm}", r1.distanceCm >= 0.0)
        val r2 = Gjk.distance(a, b)
        assertEquals("$tag 同输入两次须逐字段一致（确定性）", r1, r2)
    }

    @Test
    fun 触发对_轴倾斜八分派_间隙万分之一_不崩且单纯形不超四点() {
        assertTriggerPairOk("k641", crasherPair(-4, 0, 1))
    }

    @Test
    fun 触发对_轴倾斜八分之三_间隙十亿分之一_不崩() {
        assertTriggerPairOk("k383", crasherPair(-9, 15, 3))
    }

    @Test
    fun 触发对_轴倾斜四分派_间隙千万分之一_不崩() {
        assertTriggerPairOk("k482", crasherPair(-7, 8, 2))
    }

    @Test
    fun 触发对_轴倾斜八分派_间隙十万分之一_不崩() {
        assertTriggerPairOk("k597", crasherPair(-5, 5, 1))
    }

    @Test
    fun 触发对_轴倾斜八分派_间隙万分之一混合十四_不崩() {
        assertTriggerPairOk("k697", crasherPair(-4, 14, 1))
    }

    // ── 四类退化输入回归例（探针实跑核定读数） ─────────────────────────────

    @Test
    fun 类四_退化为点_单点凸包与球距离解析一致() {
        val r = Gjk.distance(ConvexHull(listOf(Vec3(3.0, 0.0, 0.0))), Sphere(Vec3.ZERO, 1.0))
        assertSane("点", r)
        assertEquals(2.0, r.distanceCm, 1e-9)
        assertFalse(r.intersecting)
    }

    @Test
    fun 类四_退化为点_两同位单点凸包判相交() {
        val r = Gjk.distance(ConvexHull(listOf(Vec3(1.0, 1.0, 0.0))), ConvexHull(listOf(Vec3(1.0, 1.0, 0.0))))
        assertSane("点同位", r)
        assertEquals(0.0, r.distanceCm, 0.0)
        assertTrue(r.intersecting)
    }

    @Test
    fun 类四_退化为点_零半径球解析一致() {
        val r = Gjk.distance(Sphere(Vec3.ZERO, 0.0), Sphere(Vec3(2.0, 0.0, 0.0), 0.0))
        assertSane("零半径", r)
        assertEquals(2.0, r.distanceCm, 1e-9)
    }

    @Test
    fun 类三_退化为线_共线凸包与球距离解析一致() {
        val r = Gjk.distance(
            ConvexHull(listOf(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(2.0, 0.0, 0.0))),
            Sphere(Vec3(4.0, 0.0, 0.0), 0.5),
        )
        assertSane("线", r)
        assertEquals(1.5, r.distanceCm, 1e-9)
    }

    @Test
    fun 类三_退化为线_零长胶囊即球_距离解析一致() {
        val r = Gjk.distance(Capsule(Vec3.ZERO, Vec3.ZERO, 1.0), Capsule(Vec3(2.5, 0.0, 0.0), Vec3(2.5, 0.0, 0.0), 1.0))
        assertSane("零长胶囊", r)
        assertEquals(0.5, r.distanceCm, 1e-9)
    }

    @Test
    fun 类二_共面_平板凸包与球距离解析一致() {
        val r = Gjk.distance(
            ConvexHull(listOf(Vec3(-1.0, -1.0, 0.0), Vec3(1.0, -1.0, 0.0), Vec3(1.0, 1.0, 0.0), Vec3(-1.0, 1.0, 0.0))),
            Sphere(Vec3(0.0, 0.0, 2.0), 0.5),
        )
        assertSane("面", r)
        assertEquals(1.5, r.distanceCm, 1e-9)
    }

    @Test
    fun 类二_共面_两平行平板距离解析一致() {
        val plate = { z: Double -> ConvexHull(listOf(Vec3(-1.0, -1.0, z), Vec3(1.0, -1.0, z), Vec3(1.0, 1.0, z), Vec3(-1.0, 1.0, z))) }
        val r = Gjk.distance(plate(0.0), plate(2.0))
        assertSane("双板", r)
        assertEquals(2.0, r.distanceCm, 1e-9)
    }

    @Test
    fun 类一_轴对齐_平行近贴胶囊判接触且深度近零() {
        val a = Capsule(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 0.0, 0.0), 1.0)
        val b = Capsule(Vec3(0.0, 2.0 - 1e-9, 0.0), Vec3(10.0, 2.0 - 1e-9, 0.0), 1.0)
        val r = Gjk.distance(a, b)
        assertSane("轴对齐", r)
        assertTrue("近贴应判相交/接触：${r.intersecting}", r.intersecting)
        assertEquals(0.0, r.distanceCm, 0.0)
        val depth = Epa.penetration(a, b)
        assertTrue("近贴 EPA 深度应近零：${depth?.depthCm}", depth != null && depth.depthCm <= 1e-6)
    }

    @Test
    fun 同心等球_显式退化且没有伪深度() {
        val ball = Sphere(Vec3.ZERO, 50.0)
        val gjk = Gjk.distance(ball, ball)
        val epa = Epa.penetration(ball, ball)

        assertEquals(KernelStatus.Degenerate, gjk.status)
        assertTrue(gjk.reason?.isNotBlank() == true)
        assertNotNull(epa)
        assertEquals(KernelStatus.Degenerate, epa!!.status)
        assertEquals(0.0, epa.depthCm, 0.0)
        assertEquals(Vec3.UNIT_Y, epa.normal)
        assertEquals(gjk.iterations, epa.iterations)
        assertTrue(epa.reason?.isNotBlank() == true)
    }

    // ── 单纯形四点不变量：崩溃路径总开关的结构性回归 ─────────────────────

    @Test
    fun 不变量_触发对族与四类输入单纯形恒不超四点() {
        val pairs = mutableListOf<Pair<ConvexShape, ConvexShape>>()
        for (gapLog in -9..-4) for (mix in 0 until 16) for (tilt in 1..3) {
            pairs.add(crasherPair(gapLog, mix, tilt))
        }
        val checked = pairs.count { (a, b) ->
            assertSane("invariant", Gjk.distance(a, b))
            true
        }
        assertTrue("不变量扫查须覆盖足够构型：$checked", checked >= 100)
    }
}
