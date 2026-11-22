// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百二十二批 · epsilon 量纲与尺度归一回归（655/812 后续）：
// ① 尺度不变性：同一构型 0.02×/1×/50× 结论一致（相交判定不变、距离/深度按比例、迭代一致）；
// ② 812 触发对族跨尺度不崩（0.02× 时 L<1 cm，兜底档生效）；
// ③ 小尺度（L<1 cm）兜底不崩、解析对拍；大尺度（L>1 m）不误合；
// ④ 连续性锚点：米级档（L=100 cm）重复点平方阈值等效旧绝对常数 1e-20；
// ⑤ EPA 穿透深度跨尺度同比例（EPA 重复点阈值同源改造）。
// 说明：相交判定阈 ZERO_NORM_SQ（1e-16，物理接触口径，655 原样）不在本单两常量范围——
// 亚 1e-8 cm 绝对间隙的 hit/miss 不随尺度走，见交付报告「边界与遗留」。
package com.example.zhengdao.baopu.state.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GjkScaleEpsilonTest {

    private fun assertSane(tag: String, r: GjkResult) {
        assertFalse("$tag NaN", r.distanceCm.isNaN())
        assertFalse("$tag Inf", r.distanceCm.isInfinite())
        assertTrue("$tag 迭代超上限", r.iterations <= 64)
        assertTrue("$tag 单纯形超四点：${r.simplex.size}", r.simplex.size <= 4)
    }

    /** 三尺度跑同一构型：相交判定一致、距离按比例、迭代轮数一致（干净构型路径逐轮同影）。 */
    private fun assertScaleInvariant(
        tag: String,
        build: (Double) -> Pair<ConvexShape, ConvexShape>,
        baseDistance: Double,
        expectedHit: Boolean,
    ) {
        var baseIterations = -1
        for ((label, s) in listOf("0.02x" to 0.02, "1x" to 1.0, "50x" to 50.0)) {
            val (a, b) = build(s)
            val r = Gjk.distance(a, b)
            assertSane("$tag[$label]", r)
            val expected = baseDistance * s
            assertEquals("$tag[$label] 距离应按比例缩放", expected, r.distanceCm, expected * 1e-9 + 1e-15)
            assertEquals("$tag[$label] 相交判定应一致", expectedHit, r.intersecting)
            if (baseIterations < 0) baseIterations = r.iterations
            assertEquals("$tag[$label] 迭代轮数应一致", baseIterations, r.iterations)
            assertEquals("$tag[$label] 同输入两次须逐字段一致（确定性）", r, Gjk.distance(a, b))
        }
    }

    // ── 判据②：尺度不变性（三个尺度各一例） ─────────────────────────────

    @Test
    fun 尺度不变_分离球对_三尺度距离按比例且判分离() {
        // 解析锚点（1×）：d = |c2−c1| − r1 − r2 = 2.0；0.02× ⇒ 0.04，50× ⇒ 100。
        assertEquals(2.0, Gjk.distance(Sphere(Vec3.ZERO, 1.0), Sphere(Vec3(5.0, 0.0, 0.0), 2.0)).distanceCm, 1e-9)
        assertScaleInvariant("球对分离", { s ->
            Sphere(Vec3.ZERO, 1.0 * s) to Sphere(Vec3(5.0 * s, 0.0, 0.0), 2.0 * s)
        }, 2.0, false)
    }

    @Test
    fun 尺度不变_相交球对_三尺度判相交() {
        // |c2−c1| = 1.5 < r1+r2 = 2 ⇒ 距离 0 且相交；三尺度结论一致。
        assertScaleInvariant("球对相交", { s ->
            Sphere(Vec3.ZERO, 1.0 * s) to Sphere(Vec3(1.5 * s, 0.0, 0.0), 1.0 * s)
        }, 0.0, true)
    }

    @Test
    fun 尺度不变_同轴分离胶囊_三档标定_L约2cm_20cm_100cm解析对拍() {
        // 胶囊 (0,0,0)→(0,−10k,0) 半径 k，球心 (0,5k,0) 半径 k ⇒ 距离 = 3k；
        // L = diag(胶囊)+diag(球) = k(√152 + 2√3) ≈ 15.79k：k=0.13/1.27/6.34 ⇒ L≈2.05/20.1/100.1 cm，
        // 对应三档标定 0.02/0.2/1 m。
        var lastIterations = -1
        for (k in listOf(0.13, 1.27, 6.34)) {
            val r = Gjk.distance(Capsule(Vec3.ZERO, Vec3(0.0, -10.0 * k, 0.0), k), Sphere(Vec3(0.0, 5.0 * k, 0.0), k))
            assertSane("三档k=$k", r)
            assertFalse("三档k=$k 应判分离", r.intersecting)
            assertEquals("三档k=$k 解析距离 3k", 3.0 * k, r.distanceCm, 3.0 * k * 1e-9 + 1e-15)
            if (lastIterations < 0) lastIterations = r.iterations
            assertEquals("三档k=$k 迭代轮数应与同构型其它档一致", lastIterations, r.iterations)
        }
    }

    @Test
    fun 尺度不变_近贴胶囊对_三尺度结论一致且距离按比例() {
        // 790 同族：臂胶囊 18.36/3.476 vs 区胶囊 2.2/1.336，横向净隙 1e-3（判分离）。
        // 近退化窗口内缩放舍入可致 ±1~2 轮路径差，迭代只约束带宽。
        val base = nearMissPair(1.0)
        val baseR = Gjk.distance(base.first, base.second)
        assertSane("近贴1x", baseR)
        assertFalse("近贴1x 应判分离", baseR.intersecting)
        for ((label, s) in listOf("0.02x" to 0.02, "50x" to 50.0)) {
            val (a, b) = nearMissPair(s)
            val r = Gjk.distance(a, b)
            assertSane("近贴[$label]", r)
            assertEquals("近贴[$label] 相交判定应与 1x 一致", baseR.intersecting, r.intersecting)
            assertEquals(
                "近贴[$label] 距离应按比例",
                baseR.distanceCm * s,
                r.distanceCm,
                baseR.distanceCm * s * 1e-7 + 1e-15,
            )
            assertTrue(
                "近贴[$label] 迭代应在基准 ±2 轮内：${r.iterations} vs ${baseR.iterations}",
                kotlin.math.abs(r.iterations - baseR.iterations) <= 2,
            )
        }
    }

    @Test
    fun 尺度不变_相交胶囊_EPA深度跨尺度按比例() {
        // 平行偏置胶囊（轴线 x 向错开 s，段投影重叠）⇒ 穿透深度 = rA+rB−轴距 = s；
        // 最近面为平坦侧壁（EPA 无端帽弦差），读数应精确。812 类一（近贴 1e-9）是工作尺度锚点。
        for (s in listOf(0.02, 1.0, 50.0)) {
            val a = Capsule(Vec3.ZERO, Vec3(0.0, -10.0 * s, 0.0), 1.0 * s)
            val b = Capsule(Vec3(1.0 * s, -4.0 * s, 0.0), Vec3(1.0 * s, -14.0 * s, 0.0), 1.0 * s)
            val depth = Epa.penetration(a, b)
            assertNotNull("EPA 应给出深度（s=$s）", depth)
            assertEquals("EPA 深度应按比例（s=$s）", 1.0 * s, depth!!.depthCm, maxOf(1e-7, 5e-4 * s))
        }
    }

    // ── 判据④延续：812 触发对族跨尺度不崩 ─────────────────────────────

    // 与 GjkDegenerateTest.crasherPair 同构（该 helper 为彼文件私有，此处复制以保零接触）：
    // 臂胶囊 18.36/3.476 vs 区胶囊 2.2/1.336，轴倾斜 π/8·π/4，接触距附近对数网格。
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

    private fun nearMissPair(s: Double): Pair<Capsule, Capsule> {
        val p = crasherPair(-3, 3, 1) // gap=1e-3、横向 +1.5×gap ⇒ 净隙 1e-3（分离侧）
        return scaleCapsule(p.first, s) to scaleCapsule(p.second, s)
    }

    private fun scaleCapsule(c: Capsule, s: Double) = Capsule(c.a * s, c.b * s, c.radius * s)

    @Test
    fun 触发对族_三尺度不崩且确定_单纯形不超四点() {
        // 812 钉死的 5 个触发参数 × {0.02×, 1×, 50×}：0.02× 时 L≈0.66<1 cm（兜底档生效）。
        val pinned = listOf(
            listOf(-4, 0, 1), listOf(-9, 15, 3), listOf(-7, 8, 2), listOf(-5, 5, 1), listOf(-4, 14, 1),
        )
        for ((i, p) in pinned.withIndex()) {
            for (s in listOf(0.02, 1.0, 50.0)) {
                val (a, b) = crasherPair(p[0], p[1], p[2])
                val pair = if (s == 1.0) a to b else scaleCapsule(a, s) to scaleCapsule(b, s)
                val r = Gjk.distance(pair.first, pair.second)
                assertSane("触发对#$i[${s}x]", r)
                assertTrue("触发对#$i[${s}x] 距离须有限非负", r.distanceCm >= 0.0)
                assertEquals("触发对#$i[${s}x] 两次调用须逐字段一致", r, Gjk.distance(pair.first, pair.second))
            }
        }
    }

    // ── 判据③：小尺度兜底 / 大尺度不误合 ─────────────────────────────

    @Test
    fun 小尺度兜底_L小于1cm_解析对拍不崩() {
        // 两枚 r=0.02 球心距 0.5 ⇒ d=0.46；L=0.14<1 ⇒ 兜底档按 1 cm 计。
        val r1 = Gjk.distance(Sphere(Vec3(0.5, 0.0, 0.0), 0.02), Sphere(Vec3.ZERO, 0.02))
        assertSane("小尺度球", r1)
        assertFalse("小尺度球应判分离", r1.intersecting)
        assertEquals(0.46, r1.distanceCm, 1e-12)
        // 极端：1e-6 cm 球 ⇒ 裸 L=1.4e-5，兜底后阈值仍在绝对噪声底之上，读数不漂。
        val r2 = Gjk.distance(Sphere(Vec3(5e-6, 0.0, 0.0), 1e-6), Sphere(Vec3.ZERO, 1e-6))
        assertSane("极端小", r2)
        assertEquals(3e-6, r2.distanceCm, 1e-14)
        // 微型胶囊端对端净隙 0.005 ⇒ d=0.005；L≈0.74<1 ⇒ 兜底档。
        val r3 = Gjk.distance(
            Capsule(Vec3.ZERO, Vec3(0.0, -0.3, 0.0), 0.02),
            Capsule(Vec3(0.0, -0.345, 0.0), Vec3(0.0, -0.645, 0.0), 0.02),
        )
        assertSane("小尺度胶囊", r3)
        assertFalse(r3.intersecting)
        assertEquals(0.005, r3.distanceCm, 1e-12)
    }

    @Test
    fun 小尺度兜底_同位微形判相交_标尺下限生效() {
        // L=0 的退化输入（两枚单点凸包）：兜底后重复点阈值仍为正，判据链不断；同位 ⇒ 相交。
        val pointHull = ConvexHull(listOf(Vec3(1.0, 1.0, 0.0)))
        val r = Gjk.distance(pointHull, pointHull)
        assertSane("同位单点", r)
        assertTrue(r.intersecting)
        assertEquals(0.0, r.distanceCm, 0.0)
        // 直接断言标尺下限（internal 可见）：零尺寸形体 ⇒ L 兜底为 1 cm。
        assertEquals(1.0, pairScaleLen(pointHull, pointHull), 0.0)
    }

    @Test
    fun 大尺度不误合_两米胶囊毫米间隙_判分离且距离准() {
        // L≈443 cm（4.4 m）：段端距 = 0.1+20 ⇒ 表面净隙 0.1 cm（1 mm），不得误判相交。
        val a = Capsule(Vec3.ZERO, Vec3(0.0, -200.0, 0.0), 10.0)
        val b = Capsule(Vec3(0.0, -220.1, 0.0), Vec3(0.0, -420.1, 0.0), 10.0)
        val r = Gjk.distance(a, b)
        assertSane("两米胶囊", r)
        assertFalse("毫米间隙不得误合", r.intersecting)
        assertEquals(0.1, r.distanceCm, 1e-9)
        // 同构型缩到 L≈8.9 cm：结论不变（分离），距离按比例。
        val s = 0.02
        val r2 = Gjk.distance(Capsule(a.a * s, a.b * s, a.radius * s), Capsule(b.a * s, b.b * s, b.radius * s))
        assertSane("两米胶囊0.02x", r2)
        assertFalse("缩放后结论应一致", r2.intersecting)
        assertEquals(0.1 * s, r2.distanceCm, 0.1 * s * 1e-9 + 1e-15)
    }

    @Test
    fun 大尺度不误合_混合尺度对_长细比四千_距离准() {
        // 2 m 胶囊 vs 0.05 cm 胶囊（长细比 ~4000 ⇒ κ̂≈1.6e7，权重窗随噪声地板抬升的可达路径）；
        // 端对端净隙 1e-3 cm：混合尺度下不得把同一特征判成两个、也不得误合。
        val a = Capsule(Vec3.ZERO, Vec3(0.0, -200.0, 0.0), 5.0)
        val b = Capsule(Vec3(0.0, -205.011, 0.0), Vec3(0.0, -205.061, 0.0), 0.01)
        val r = Gjk.distance(a, b)
        assertSane("混合尺度", r)
        assertFalse("混合尺度净隙不得误合", r.intersecting)
        assertEquals(1e-3, r.distanceCm, 1e-9)
    }

    // ── 判据①：阈值确由 L 推出（连续性锚点＋标尺直读） ───────────────────

    @Test
    fun 连续性_米级档重复点平方阈等效旧绝对常数() {
        // 线段凸包对角线恰 100 ＋ 单点凸包对角线 0 ⇒ L=100 cm（米级档）：
        // dup 平方阈 = (1e-12×100)² ≈ 1e-20 —— 与 655 旧绝对常数 DUP_NORM_SQ 同值（连续性锚点）。
        val seg = ConvexHull(listOf(Vec3(0.0, 0.0, 0.0), Vec3(100.0, 0.0, 0.0)))
        val tol = dupTolSq(seg, ConvexHull(listOf(Vec3(7.0, 0.0, 0.0))))
        assertEquals(1e-20, tol, 1e-26)
    }

    @Test
    fun 标尺直读_三档与兜底档的L与平方阈() {
        // L≈2 cm 档：线段凸包 1.4 ＋ 线段凸包 0.6 ⇒ L=2.0；
        // 平方阈 = (1e-12×2)² = 4e-24——比旧绝对值紧 2500 倍（毫米级不误合的机理）。
        val small = ConvexHull(listOf(Vec3(0.0, 0.0, 0.0), Vec3(1.4, 0.0, 0.0)))
        val tiny = ConvexHull(listOf(Vec3(3.0, 0.0, 0.0), Vec3(3.6, 0.0, 0.0)))
        assertEquals(2.0, pairScaleLen(small, tiny), 1e-9)
        assertEquals(4e-24, dupTolSq(small, tiny), 4e-30)
        // L<1 cm 兜底：两枚 r=0.02 球 ⇒ 裸 L=0.14，兜底按 1 计。
        assertEquals(1.0, pairScaleLen(Sphere(Vec3.ZERO, 0.02), Sphere(Vec3(0.5, 0.0, 0.0), 0.02)), 0.0)
    }
}
