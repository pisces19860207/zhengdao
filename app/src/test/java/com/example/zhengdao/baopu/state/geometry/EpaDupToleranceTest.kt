// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百四十批 · EPA 重复点判据收紧（822 后续）的量化与回归。
// 822 把 Epa.kt 的旧绝对阈 DUP_NORM_SQ=1e-18（线性 1e-9 cm）并入「由标尺 L 推出」的相对口径
// （Gjk.dupTolSq = DUP_REL²·L²），报告只写「一并退役」——**没量化倍率、没验证行为变化**。
// 本文件把两件事钉住：
//   ① 换算表：L=10 / 100 / 1000 cm ⇒ 新阈 1e-22 / 1e-20 / 1e-18（相对旧值 1e-4 / 1e-2 / 1×，
//      即 10^4 倍 / 100 倍 / 等值；平方律 ⇒ 等值点在 L=1000 cm）；
//   ② 行为回归：迭代与深度锚点（822 前后逐位相同，实测见交付报告）、应相交构型零 null、
//      退化四类的 fail-closed 契约、尺度扫描 0.1×/1×/10×/100× 的语义一致。
// 口径：本文件只锁「EPA 的重复点判据」；EPA 其余常数（EPA_EPS_ABS/VIS_EPS/CONTACT_DIST、
// 初始四面体体积下限）归 839，不在本单。
package com.example.zhengdao.baopu.state.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class EpaDupToleranceTest {

    // 822 退役的 EPA 旧绝对平方阈（线性 1e-9 cm），只在换算表里当参照基准。
    private val retiredAbsSq = 1e-18

    /** 两球各半径 r ⇒ 标尺 L ＝ 2·diag(球) ＝ 4√3·r；反解出目标 L。 */
    private fun pairOfScale(targetL: Double): Pair<Sphere, Sphere> {
        val r = targetL / (4.0 * sqrt(3.0))
        val s = Sphere(Vec3.ZERO, r)
        return s to s
    }

    /** 厚度 t、中心 z 的方形板（8 顶点凸包；两板中心差 t/2 时 z 向重叠恰 t/2）。 */
    private fun plateAt(t: Double, z: Double): ConvexHull = ConvexHull(
        listOf(
            Vec3(-1.0, -1.0, z - t / 2), Vec3(1.0, -1.0, z - t / 2),
            Vec3(1.0, 1.0, z - t / 2), Vec3(-1.0, 1.0, z - t / 2),
            Vec3(-1.0, -1.0, z + t / 2), Vec3(1.0, -1.0, z + t / 2),
            Vec3(1.0, 1.0, z + t / 2), Vec3(-1.0, 1.0, z + t / 2),
        ),
    )

    // ── 判据① 换算表：三点倍率 ────────────────────────────────────────────

    @Test
    fun 判据一_换算表_L10cm_新阈比旧绝对严一万倍() {
        val (a, b) = pairOfScale(10.0)
        assertEquals("标尺应恰为 10 cm", 10.0, pairScaleLen(a, b), 1e-9)
        val t = dupTolSq(a, b)
        assertEquals("新阈应＝1e-24×100", 1e-22, t, 1e-29)
        assertEquals("相对旧值 1e-18 的倍率＝1e-4（严 10^4 倍）", 1e-4, t / retiredAbsSq, 1e-10)
    }

    @Test
    fun 判据一_换算表_L100cm_新阈比旧绝对严一百倍() {
        val (a, b) = pairOfScale(100.0)
        assertEquals(100.0, pairScaleLen(a, b), 1e-6)
        val t = dupTolSq(a, b)
        assertEquals("新阈应＝1e-20", 1e-20, t, 1e-27)
        assertEquals("倍率＝1e-2（严 100 倍）——822 报告缺的正是这个数", 1e-2, t / retiredAbsSq, 1e-8)
    }

    @Test
    fun 判据一_换算表_L1000cm_与旧绝对等值() {
        val (a, b) = pairOfScale(1000.0)
        assertEquals(1000.0, pairScaleLen(a, b), 1e-4)
        val t = dupTolSq(a, b)
        assertEquals("等值点就在 L＝1000 cm", 1.0, t / retiredAbsSq, 1e-6)
    }

    @Test
    fun 判据一_平方律_尺度扩十倍阈扩百倍() {
        val (a1, b1) = pairOfScale(10.0)
        val (a10, b10) = pairOfScale(100.0)
        assertEquals(100.0, dupTolSq(a10, b10) / dupTolSq(a1, b1), 1e-6)
    }

    @Test
    fun 判据一_兜底档_小于1cm时标尺不随形体缩到零() {
        val tiny = Sphere(Vec3.ZERO, 1e-4)
        assertEquals("L<1 cm 兜底为 1.0 cm", 1.0, pairScaleLen(tiny, tiny), 0.0)
        assertEquals(1e-24, dupTolSq(tiny, tiny), 1e-30)
    }

    // ── 判据② 行为回归：迭代与深度锚点（822 前后实测逐位相同）────────────

    private fun assertAnchor(tag: String, a: ConvexShape, b: ConvexShape, iter: Int, depth: Double, tol: Double = 1e-12) {
        val r = Epa.penetration(a, b)
        assertNotNull("$tag 应相交", r)
        assertEquals("$tag 迭代轮数（822 前后实测值）", iter, r!!.iterations)
        assertEquals("$tag 穿透深度", depth, r.depthCm, tol)
    }

    @Test
    fun 判据二_锚点_盒盒收敛构型迭代与深度() {
        val a = Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0))
        assertAnchor("盒-盒 dx=1.5", a, Box(Vec3(1.5, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)), 3, 0.5, 0.0)
        assertAnchor("盒-盒 三轴浅穿", a, Box(Vec3(1.5, 1.9, 1.95), Vec3(1.0, 1.0, 1.0)), 2, 0.05)
    }

    @Test
    fun 判据二_锚点_曲面构型与点退化构型() {
        assertAnchor("球-盒 dx=1.0", Sphere(Vec3(1.0, 0.0, 0.0), 1.0), Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0)), 12, 0.9999999999146885)
        assertAnchor("点入盒", ConvexHull(listOf(Vec3(0.2, 0.1, 0.0))), Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0)), 5, 0.8, 1e-15)
    }

    @Test
    fun 判据二_锚点_近共面薄板不受重复点阈收紧影响() {
        // 两块同厚 t 的板：A 中心 z=0、B 中心 z=t/2 ⇒ z 向重叠恰为 t/2（解析深度）。
        // t=1e-3：支撑点最近距离 4e-18 远高于新阈 ⇒ 由 gain 判据停（3 轮）；
        // t=1e-12：最近距离 4e-24 低于新阈（≈3.2e-23）⇒ 仍由重复点判据停（1 轮）。
        // 两种停法在 822 前后逐位一致（交付报告 §三）。
        assertAnchor("薄板 t=1e-3", plateAt(1e-3, 0.0), plateAt(1e-3, 5e-4), 3, 5e-4, 1e-15)
        assertAnchor("薄板 t=1e-12（重复点判据仍开火）", plateAt(1e-12, 0.0), plateAt(1e-12, 5e-13), 1, 5e-13, 1e-24)
    }

    // ── 判据② 失败面：应相交构型零 null、退化不抛 ────────────────────────

    @Test
    fun 判据二_失败面_应相交构型无一返回null() {
        val pairs: List<Pair<String, Pair<ConvexShape, ConvexShape>>> = listOf(
            "球-球 深插" to (Sphere(Vec3.ZERO, 1.0) to Sphere(Vec3(0.5, 0.0, 0.0), 1.0)),
            "盒-盒 深插" to (Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0)) to Box(Vec3(1.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0))),
            "胶囊-胶囊 微穿 1e-9" to (
                Capsule(Vec3.ZERO, Vec3(2.0, 0.0, 0.0), 0.5) to
                    Capsule(Vec3(0.0, 1.0 - 1e-9, 0.0), Vec3(2.0, 1.0 - 1e-9, 0.0), 0.5)
                ),
            "椭球-盒" to (Ellipsoid(Vec3(1.0, 0.0, 0.0), Vec3(1.0, 0.6, 0.4)) to Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0))),
        )
        for ((tag, p) in pairs) {
            assertNotNull("$tag 应判相交（822 前后均为非 null）", Epa.penetration(p.first, p.second))
        }
        // 分离构型（间隙 > CONTACT_DIST＝1e-7）仍返回 null
        assertNull(
            "间隙 1e-6 > 入口门 1e-7 ⇒ 判分离",
            Epa.penetration(
                Capsule(Vec3.ZERO, Vec3(2.0, 0.0, 0.0), 0.5),
                Capsule(Vec3(0.0, 1.0 + 1e-6, 0.0), Vec3(2.0, 1.0 + 1e-6, 0.0), 0.5),
            ),
        )
    }

    @Test
    fun 判据二_退化四类_812契约不抛且读数有限() {
        val plate = ConvexHull(
            listOf(Vec3(-1.0, -1.0, 0.0), Vec3(1.0, -1.0, 0.0), Vec3(1.0, 1.0, 0.0), Vec3(-1.0, 1.0, 0.0)),
        )
        val cases = listOf(
            "退化点 同位" to (ConvexHull(listOf(Vec3(1.0, 1.0, 0.0))) to ConvexHull(listOf(Vec3(1.0, 1.0, 0.0)))),
            "退化线 共线重合" to (
                ConvexHull(listOf(Vec3(-1.0, 0.0, 0.0), Vec3.ZERO, Vec3(1.0, 0.0, 0.0))) to
                    ConvexHull(listOf(Vec3(-1.0, 0.0, 0.0), Vec3.ZERO, Vec3(1.0, 0.0, 0.0)))
                ),
            "共面 同平面重叠" to (plate to plate),
            "退化线 零半径胶囊交叉" to (
                Capsule(Vec3(-2.0, 0.0, 0.0), Vec3(2.0, 0.0, 0.0), 0.0) to
                    Capsule(Vec3(0.0, -2.0, 0.0), Vec3(0.0, 2.0, 0.0), 0.0)
                ),
        )
        for ((tag, p) in cases) {
            val r = Epa.penetration(p.first, p.second)
            assertNotNull("$tag 不抛异常且给出读数（fail-closed 不靠 catch）", r)
            assertFalse("$tag 深度有限", r!!.depthCm.isNaN() || r.depthCm.isInfinite())
            assertTrue("$tag 深度非负：${r.depthCm}", r.depthCm >= 0.0)
            assertTrue("$tag 不超上限轮", r.iterations <= 96)
            assertEquals("$tag 无内体积 ⇒ 深度 0（812 契约）", 0.0, r.depthCm, 1e-9)
            assertEquals("$tag 同输入两次一致", r, Epa.penetration(p.first, p.second))
        }
    }

    // ── 判据② 尺度扫描 0.1× / 1× / 10× / 100× ─────────────────────────────

    @Test
    fun 判据二_尺度扫描_盒盒四档深度按比例且迭代一致() {
        for (k in listOf(0.1, 1.0, 10.0, 100.0)) {
            val a = Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0) * k)
            val b = Box(Vec3(1.5 * k, 0.0, 0.0), Vec3(1.0, 1.0, 1.0) * k)
            val r = Epa.penetration(a, b)!!
            assertEquals("k=$k 深度应＝0.5×k", 0.5 * k, r.depthCm, 1e-12 * k + 1e-15)
            assertEquals("k=$k 迭代应稳定在 3 轮", 3, r.iterations)
        }
    }

    @Test
    fun 判据二_尺度扫描_胶囊四档深度按比例() {
        var prevRatio = 0.0
        for (k in listOf(0.1, 1.0, 10.0, 100.0)) {
            val a = Capsule(Vec3.ZERO, Vec3(2.0 * k, 0.0, 0.0), 0.5 * k)
            val b = Capsule(Vec3(0.0, 0.5 * k, 0.0), Vec3(2.0 * k, 0.5 * k, 0.0), 0.5 * k)
            val r = Epa.penetration(a, b)!!
            val ratio = r.depthCm / k
            assertEquals("k=$k 解析深度＝0.5×k（±1e-6 相对）", 0.5, ratio, 1e-6)
            if (prevRatio != 0.0) assertEquals("各档 depth/k 一致", prevRatio, ratio, 1e-6)
            prevRatio = ratio
        }
    }

    @Test
    fun 判据二_尺度扫描_近共面薄板四档同结论() {
        for (k in listOf(0.1, 1.0, 10.0, 100.0)) {
            val t = 1e-3 * k
            val r = Epa.penetration(plateAt(t, 0.0), plateAt(t, t / 2))!!
            assertEquals("k=$k 薄板深度＝t/2", 5e-4 * k, r.depthCm, 1e-15 * k + 1e-18)
            assertEquals("k=$k 迭代 3 轮", 3, r.iterations)
        }
    }

    // ── 判据③ 接受收紧的书面前提：读数入册（822 前/后/关闭 dup 三态实测同值）─────

    @Test
    fun 判据三_收紧不改判_薄板与盒盒的迭代数仍为实测值() {
        // 如实记：**dup 判据在本测域内是冗余的** —— 交付报告 §三/§四 的 V3 对照（把 dup 整个
        // 关掉）在 20,000 例扫查上读数逐位相同，因此本断言不会因 dup 口径变化而红。它锁的是
        // "读数入册"（822 前 = 822 后 = 关闭 dup 三态同值），dup 口径本身由判据一的换算表锁死。
        // 这两个构型的重复点最近距离分别落在新阈之下（薄板 t=1e-12）与远高于新阈（盒盒），
        // 覆盖"靠 dup 停"与"靠 gain 停"两条路。
        assertEquals(
            1,
            Epa.penetration(plateAt(1e-12, 0.0), plateAt(1e-12, 5e-13))!!.iterations,
        )
        val box = Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0))
        assertEquals(3, Epa.penetration(box, Box(Vec3(1.5, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)))!!.iterations)
    }
}
