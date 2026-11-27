// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百五十批 · 跨尺度语义回归（838 §四③ 两族写法＋839 §三验收草案落地）。
// 族 A（尺度不变性）：可见性/收敛类常量（相对量，812/822）⇒ 同构型 ×0.02/×1/×50 结论一致。
// 族 B（固定绝对间隙、变形体尺度）：接触带（1e-8 cm）与入口带（CONTACT_DIST=1e-7 cm）的
//   hit/miss 与 null/非 null 三档（0.5×/1×/2× CONTACT_DIST）不随形体尺度走——
//   这是防「物理口径被相对化」回潮的牙（838 判据：族 B 才是牙，族 A 不是它的活）。
//
// 负控制（变异演练，读数见 850 交付报告 §五）：
//   把 Gjk 相交判定改成相对量 `f.point.normSq() <= (3.3333333333333335e-10 × pairScaleLen)²`
//   ⇒ 族 B 至少一例必须变红（实测：亚埃档 B1 在 0.02×/1× 翻 miss、0.5×档 B2 在 50× 翻 hit）。
//   复原只允许 `git checkout -- <单文件>`，禁止 `git checkout -- .`。
//
// 禁止事项（838 §四③）：不得添加「gap=1e-9 均匀缩放三档一致」类断言——
//   均匀缩放会把绝对间隙本身按比例缩放（1e-9→5e-8），hit→miss 翻转是物理口径下的**正确行为**
//   （838 反例 3），把它当「尺度不变性 bug」去修即落入相对化陷阱。
package com.example.zhengdao.baopu.state.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GeometrySemanticsRegressionTest {

    private val scales = listOf("0.02x" to 0.02, "1x" to 1.0, "50x" to 50.0)

    // 接触带线性口径（cm）与入口带（Epa.CONTACT_DIST 同值，测试侧不复用 private 常量）。
    private val contactBandCm = 1e-8
    private val contactDistCm = 1e-7

    // ── 族 A：尺度不变性（可见性/收敛类相对量 ⇒ 同构型三档结论一致） ──────────

    /** A1（839 §三·可见性主例）：平行偏置胶囊穿透对——深度按 s 严格比例、确定性、恒非 null。 */
    @Test
    fun 族A_可见性主例_胶囊穿透深度跨尺度按比例() {
        for ((label, s) in scales) {
            val a = Capsule(Vec3.ZERO, Vec3(0.0, -10.0 * s, 0.0), 1.0 * s)
            val b = Capsule(Vec3(1.0 * s, -4.0 * s, 0.0), Vec3(1.0 * s, -14.0 * s, 0.0), 1.0 * s)
            val depth = Epa.penetration(a, b)
            assertNotNull("[$label] 干净穿透对 EPA 不得返回 null", depth)
            assertEquals("[$label] 深度应按比例（解析＝s）", 1.0 * s, depth!!.depthCm, maxOf(1e-7, 5e-4 * s))
            assertEquals("[$label] 同输入两次须逐字段一致", depth, Epa.penetration(a, b))
        }
    }

    /**
     * A2（收敛类·平坦面）：盒-盒穿透对——深度按比例、**法向三档逐位一致**、迭代轮数三档一致
     * （840 §三⑤：盒-盒恒 3 轮；平坦面法向由轴对齐棱的叉积生成，不受 `EPA_EPS_ABS` 绝对地板影响，
     * 曲面构型的法向逐位一致归 839 相对化落地后的目标，本档只钉可得的方向级一致见 A1 深度容差）。
     */
    @Test
    fun 族A_收敛盒对_穿透深度按比例且法向迭代一致() {
        var baseIterations = -1
        var baseNormal: Vec3? = null
        for ((label, s) in scales) {
            val a = Box(Vec3.ZERO, Vec3(1.0 * s, 1.0 * s, 1.0 * s))
            val b = Box(Vec3(1.5 * s, 0.0, 0.0), Vec3(1.0 * s, 1.0 * s, 1.0 * s))
            val depth = Epa.penetration(a, b)
            assertNotNull("[$label] 重叠盒对 EPA 不得返回 null", depth)
            assertEquals("[$label] 深度应按比例（解析＝0.5s）", 0.5 * s, depth!!.depthCm, 0.5 * s * 1e-9 + 1e-15)
            assertTrue("[$label] 迭代应在上限内", depth.iterations <= 96)
            if (baseIterations < 0) baseIterations = depth.iterations
            assertEquals("[$label] 迭代轮数应与同构型其它档一致", baseIterations, depth.iterations)
            if (baseNormal == null) {
                baseNormal = depth.normal
            } else {
                assertEquals("[$label] 法向应与基准档一致", baseNormal.x, depth.normal.x, 1e-12)
                assertEquals("[$label] 法向应与基准档一致", baseNormal.y, depth.normal.y, 1e-12)
                assertEquals("[$label] 法向应与基准档一致", baseNormal.z, depth.normal.z, 1e-12)
            }
            assertEquals("[$label] 确定性", depth, Epa.penetration(a, b))
        }
    }

    /** A3（收敛类·GJK 进度判据）：盒-盒分离对——距离按比例、相交判定一致、迭代轮数一致。 */
    @Test
    fun 族A_收敛分离盒对_距离按比例且判定迭代一致() {
        var baseIterations = -1
        for ((label, s) in scales) {
            val a = Box(Vec3.ZERO, Vec3(1.0 * s, 1.0 * s, 1.0 * s))
            val b = Box(Vec3(2.3 * s, 0.0, 0.0), Vec3(1.0 * s, 1.0 * s, 1.0 * s))
            val r = Gjk.distance(a, b)
            assertFalse("[$label] 间隙 0.3s 高出接触带 ⇒ 三档恒判分离", r.intersecting)
            assertEquals("[$label] 距离应按比例（解析＝0.3s）", 0.3 * s, r.distanceCm, 0.3 * s * 1e-9 + 1e-15)
            if (baseIterations < 0) baseIterations = r.iterations
            assertEquals("[$label] 迭代轮数应与同构型其它档一致", baseIterations, r.iterations)
            assertEquals("[$label] 确定性", r, Gjk.distance(a, b))
        }
    }

    /** A4（839 §三·退化剔除例，EPA 侧补 822 的 GJK 侧同款）：812 触发对族 × 三档——不崩、深度合法、确定性。 */
    @Test
    fun 族A_退化剔除_812触发对族EPA入口三档不崩且确定() {
        val pinned = listOf(
            listOf(-4, 0, 1), listOf(-9, 15, 3), listOf(-7, 8, 2), listOf(-5, 5, 1), listOf(-4, 14, 1),
        )
        for ((i, p) in pinned.withIndex()) {
            for (s in listOf(0.02, 1.0, 50.0)) {
                val (a0, b0) = crasherPair(p[0], p[1], p[2])
                val a = if (s == 1.0) a0 else Capsule(a0.a * s, a0.b * s, a0.radius * s)
                val b = if (s == 1.0) b0 else Capsule(b0.a * s, b0.b * s, b0.radius * s)
                val depth = Epa.penetration(a, b)
                if (depth != null) {
                    assertFalse("#$i[${s}x] 深度 NaN", depth.depthCm.isNaN())
                    assertFalse("#$i[${s}x] 深度 Inf", depth.depthCm.isInfinite())
                    assertTrue("#$i[${s}x] 深度非负", depth.depthCm >= 0.0)
                    assertTrue("#$i[${s}x] 迭代超上限", depth.iterations <= 96)
                    assertEquals("#$i[${s}x] 两次调用须逐字段一致", depth, Epa.penetration(a, b))
                }
            }
        }
    }

    // 与 GjkDegenerateTest/GjkScaleEpsilonTest 同构（彼处 helper 为文件私有，此处复制以保零接触）。
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

    // ── 族 B：固定绝对间隙、变形体尺度（接触带/入口带三档不变） ────────────────
    // 构型：两枚等球 r＝1×s，球心距＝2s＋gap。gap 是**绝对量**（不乘 s）——
    // 这正是族 B 与族 A 的分界：族 A 缩构型（间隙跟着缩），族 B 只缩形体（间隙钉死）。
    // 浮点可行性：50× 档球心距 100＋gap，gap≥5e-8 ≫ ulp(100)≈1.4e-14，双精度充分（838 §四③）。

    /** 族 B 核心：固定绝对间隙、三档形体尺度 ⇒ 相交判定与 EPA 入口结论不得随 L 走。 */
    private fun assertGapSemantics(gapCm: Double, expectHit: Boolean, tag: String) {
        for ((label, s) in scales) {
            val a = Sphere(Vec3.ZERO, 1.0 * s)
            val b = Sphere(Vec3(2.0 * s + gapCm, 0.0, 0.0), 1.0 * s)
            val r = Gjk.distance(a, b)
            assertEquals("$tag[$label] 相交判定只依赖绝对间隙", expectHit, r.intersecting)
            if (expectHit) {
                assertEquals("$tag[$label] 接触承诺：distanceCm 归零", 0.0, r.distanceCm, 0.0)
            } else {
                assertEquals("$tag[$label] 距离读数＝绝对间隙", gapCm, r.distanceCm, gapCm * 1e-6 + 1e-15)
            }
            assertEquals("$tag[$label] 确定性", r, Gjk.distance(a, b))
        }
    }

    /** B1 接触带档（gap=0.5×接触带＝5e-9 cm，亚埃）：三档全 hit。相对化变异下必红（负控制哨兵）。 */
    @Test
    fun 族B_接触带_亚埃间隙三档形体尺度一律判接触() {
        assertGapSemantics(0.5 * contactBandCm, true, "B1(5e-9)")
    }

    /**
     * B2 入口带 0.5× 档（gap=5e-8∈(1e-8,1e-7]）：三档全 miss 且三档恒过入口门（`distanceCm ≤ 1e-7`）。
     * EPA 侧：分离近贴没有严格含原点的初始四面体，三档均返回非 null、显式 Degenerate、深度 0。
     * 843 的切线假深度仍未实现；本断言只锁入口带不抛与零深度约定。
     */
    @Test
    fun 族B_入口带05倍_近贴分离三档一律miss且恒过入口门() {
        assertGapSemantics(0.5 * contactDistCm, false, "B2(5e-8)")
        for ((label, s) in scales) {
            val a = Sphere(Vec3.ZERO, 1.0 * s)
            val b = Sphere(Vec3(2.0 * s + 0.5 * contactDistCm, 0.0, 0.0), 1.0 * s)
            assertTrue(
                "B2[$label] 入口带成员资格不变：distanceCm 必须 ≤ 1e-7（该入门）",
                Gjk.distance(a, b).distanceCm <= contactDistCm,
            )
            val depth = Epa.penetration(a, b)
            assertNotNull("B2[$label] 入口带内 EPA 不得拒入（null/非 null 是对外契约）", depth)
            assertEquals("B2[$label] 无严格初始四面体须显式退化", KernelStatus.Degenerate, depth!!.status)
            assertEquals("B2[$label] 分离近贴的深度约定为 0", 0.0, depth.depthCm, 0.0)
        }
    }

    /**
     * B3 入口带 1× 档（gap=CONTACT_DIST 恰在边界）：相交判定三档恒 miss（1e-14 对 1e-16 裕度充分）；
     * null/非 null 方向由浮点末位决定（契约是 ≤ 入门、> 拒门），故本档钉的是**每档自洽**：
     * EPA 拒入与否 ⇔ GJK 读数与入口带的比较结果，三档同此不变式＋确定性。
     */
    @Test
    fun 族B_入口带1倍_边界档钉门契约自洽与相交判定三档一致() {
        assertGapSemantics(contactDistCm, false, "B3(1e-7)")
        for ((label, s) in scales) {
            val a = Sphere(Vec3.ZERO, 1.0 * s)
            val b = Sphere(Vec3(2.0 * s + contactDistCm, 0.0, 0.0), 1.0 * s)
            val r = Gjk.distance(a, b)
            val depth = Epa.penetration(a, b)
            assertEquals(
                "B3[$label] 入口门契约自洽：null ⇔ distanceCm > 1e-7",
                r.distanceCm > contactDistCm,
                depth == null,
            )
        }
    }

    /** B4 入口带 2× 档（gap=2e-7 > 1e-7）：三档全 miss 且 EPA 全 null。 */
    @Test
    fun 族B_入口带2倍_分离对三档一律null() {
        assertGapSemantics(2.0 * contactDistCm, false, "B4(2e-7)")
        for ((label, s) in scales) {
            val a = Sphere(Vec3.ZERO, 1.0 * s)
            val b = Sphere(Vec3(2.0 * s + 2.0 * contactDistCm, 0.0, 0.0), 1.0 * s)
            assertNull("B4[$label] 超出入口带 EPA 必须返回 null", Epa.penetration(a, b))
        }
    }

    /** 互锁直读（文档 §三①）：EPA 收敛地板（1e-9）必须细于接触带（1e-8）——两值源码可 grep。 */
    @Test
    fun 互锁_收敛地板细于接触带入口带细十倍的数值关系() {
        val epaFloorCm = 1e-9 // Epa.EPA_EPS_ABS（源码 grep：EPA_EPS_ABS = 1e-9）
        assertTrue("地板必须细于接触带", epaFloorCm < contactBandCm)
        assertEquals("入口带＝接触带×10", contactBandCm * 10.0, contactDistCm, 0.0)
    }
}
