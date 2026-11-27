// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百八十六批 · 三量输出单测。
// 口径来源：686 澄清 2026-11-20——接触点数＝满足「深度 ≥ 最大深度 − ε」的特征点个数，
// ε＝CavityTolerances.CONTACT_EPS_REL（固定常数，归一单位＝出点方包围盒对角线），不许按场次调。
package com.example.zhengdao.baopu.state.cavity

import com.example.zhengdao.baopu.state.geometry.GeometryNotes
import com.example.zhengdao.baopu.state.geometry.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 交付③＋判据③＋补条 A/C：穿透深度（整数毫米）／接触点数／法线方向档。 */
class ContactProbeTest {

    private fun balls(centerGap: Double): Pair<OrganProxy, OrganProxy> =
        CavityFixtures.sphereAtZ("a", 0.0) to CavityFixtures.sphereAtZ("b", centerGap)

    @Test
    fun `判据三_穿透深度对球球的解析解_整数毫米`() {
        // 期望值都取**非 .5 边界**的整数毫米：EPA 的深度从下方收敛，落在 .5 上的档位
        // 由浮点尾巴决定（半进向上本身另有 `补条A_取整写死为半进向上` 直接钉住）。
        // 两心完全重合是退化输入（EPA 只能给下界，实测比 2cm 小一毫米档），
        // 那里由 `判据三_接近距离变小深度不减_扫十三档` 扫到 0.0 钉单调，不钉解析值。
        val cases = listOf(1.5 to 5, 1.2 to 8, 1.0 to 10, 0.9 to 11)
        for ((gap, exact) in cases) {
            val (a, b) = balls(gap)
            val r = ContactProbe.read(a, b)
            assertNotNull("中心距 $gap 应相交", r)
            assertEquals("中心距 $gap 的深度", exact, r!!.depthMilli)
        }
    }

    @Test
    fun `补条A_取整写死为半进向上`() {
        assertEquals(0, ContactProbe.depthMilli(0.0))
        assertEquals(1, ContactProbe.depthMilli(0.05))
        assertEquals(1, ContactProbe.depthMilli(0.149))
        assertEquals(2, ContactProbe.depthMilli(0.15))
        assertEquals(3, ContactProbe.depthMilli(0.25))
        assertEquals(17, ContactProbe.depthMilli(1.749))
        assertEquals(18, ContactProbe.depthMilli(1.75))
    }

    @Test
    fun `补条A_NaN正负无穷负零负深度一律抛_不许静默归零`() {
        assertThrows(IllegalArgumentException::class.java) { ContactProbe.depthMilli(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { ContactProbe.depthMilli(Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { ContactProbe.depthMilli(Double.NEGATIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { ContactProbe.depthMilli(-0.0) }
        assertThrows(IllegalArgumentException::class.java) { ContactProbe.depthMilli(-0.1) }
    }

    @Test
    fun `判据三_接近距离变小深度不减_扫十三档`() {
        var prev = -1
        var seen = 0
        for (k in 0..12) {
            val gap = 3.0 - k * 0.25
            // 同心等球（gap 0.0）是**显式退化**：915 的边界语义说「没有唯一分离方向 ⇒
            // 不造假深度」，由 GjkDegenerateTest#同心等球_显式退化且没有伪深度 与
            // KernelFuzzTest D1 钉住。它不是「更深一档的接触」，故不参与单调扫——
            // 2026-11-27 修 emit #340：原来把它算作第 9 档，0mm < 17mm 当场红。
            if (gap == 0.0) continue
            val r = ContactProbe.read(CavityFixtures.sphereAtZ("a", 0.0), CavityFixtures.sphereAtZ("b", gap))
            if (r == null) continue
            seen++
            assertTrue("距离 $gap 的深度 ${r.depthMilli} 不该小于上一档 $prev", r.depthMilli >= prev)
            prev = r.depthMilli
        }
        assertEquals("应有八档进入接触或相交（gap 0.0 是显式退化，已跳过）", 8, seen)
    }

    @Test
    fun `分离对不出接触读数_恰好接触深度零`() {
        assertNull(ContactProbe.read(CavityFixtures.sphereAtZ("a", 0.0), CavityFixtures.sphereAtZ("b", 2.5)))
        val touching = ContactProbe.read(CavityFixtures.sphereAtZ("a", 0.0), CavityFixtures.sphereAtZ("b", 2.0))
        assertNotNull("恰接触该出读数", touching)
        assertEquals(0, touching!!.depthMilli)
    }

    @Test
    fun `接触点数按特征点口径_球球两心都算`() {
        val (a, b) = balls(0.5)
        val r = ContactProbe.read(a, b)!!
        assertEquals("两球心都落在对方面内（心距 0.5 < 半径 1）", 2, r.contactRaw)
        assertEquals(ContactBucket.FEW, r.contactBucket)
        assertEquals(listOf("a" to 0, "b" to 0), r.counted.map { it.proxyId to it.index })
    }

    @Test
    fun `接触点数按深度差ε的口径_不超特征点总数`() {
        val e = CavityFixtures.ellipsoid("e", Vec3.ZERO, Vec3(1.0, 1.0, 1.0))
        val s = CavityFixtures.sphereAtZ("s", 1.6, 1.0)
        val r = ContactProbe.read(e, s)!!
        assertTrue("至少一个点：${r.contactRaw}", r.contactRaw >= 1)
        assertTrue("至多七个点（六极加球心）：${r.contactRaw}", r.contactRaw <= 7)
        assertEquals(r.contactRaw, r.counted.size)
    }

    @Test
    fun `补条C_顶点超上限时截断必须可见`() {
        val r = ContactProbe.read(CavityFixtures.flatHull("h1", Vec3(0.0, 0.0, 0.7), 20), CavityFixtures.sphereAtZ("s0", 1.2))!!
        assertTrue("超上限必须标 truncated", r.truncated)
        assertEquals("被截掉的个数要记进读数", 8, r.droppedFeatureCount)
        assertEquals("留下的 12 个特征点同址同深，全都算接触", 12, r.contactRaw)
        assertEquals(12, r.counted.count { it.proxyId == "h1" })
        assertEquals("深度＝点到球面的有符号距离 0.5cm", 5, r.depthMilli)
    }

    @Test
    fun `补条C_不截断时记账为零`() {
        val (a, b) = balls(1.5)
        val r = ContactProbe.read(a, b)!!
        assertEquals(0, r.droppedFeatureCount)
        assertTrue(!r.truncated)
    }

    @Test
    fun `档与原始数自洽_bucket为零当且仅当raw为零`() {
        assertEquals(ContactBucket.ZERO, ContactProbe.bucketOf(0))
        assertEquals(ContactBucket.ONE, ContactProbe.bucketOf(1))
        assertEquals(ContactBucket.FEW, ContactProbe.bucketOf(2))
        assertEquals(ContactBucket.FEW, ContactProbe.bucketOf(3))
        assertEquals(ContactBucket.MANY, ContactProbe.bucketOf(4))
        assertEquals(ContactBucket.MANY, ContactProbe.bucketOf(99))
        for (ps in listOf(CavityFixtures.overlappingCluster(5), CavityFixtures.verticalStack(5), CavityFixtures.fromBody())) {
            for (r in ContactProbe.readAll(ps)) {
                assertEquals("bucket=0 却 raw>0 是自相矛盾：$r", ContactProbe.bucketOf(r.contactRaw), r.contactBucket)
                assertEquals(r.contactRaw, r.counted.size)
            }
        }
    }

    @Test
    fun `法线六档主轴带符号`() {
        assertEquals(NormalBucket.PLUS_X, ContactProbe.normalBucket(Vec3(0.9, 0.1, 0.2)))
        assertEquals(NormalBucket.MINUS_X, ContactProbe.normalBucket(Vec3(-0.9, 0.1, 0.2)))
        assertEquals(NormalBucket.PLUS_Y, ContactProbe.normalBucket(Vec3(0.1, 0.9, 0.2)))
        assertEquals(NormalBucket.MINUS_Y, ContactProbe.normalBucket(Vec3(0.1, -0.9, 0.2)))
        assertEquals(NormalBucket.PLUS_Z, ContactProbe.normalBucket(Vec3(0.1, 0.2, 0.9)))
        assertEquals(NormalBucket.MINUS_Z, ContactProbe.normalBucket(Vec3(0.1, 0.2, -0.9)))
    }

    @Test
    fun `法线平局按X大于Y大于Z_零分量记正档`() {
        assertEquals(NormalBucket.PLUS_X, ContactProbe.normalBucket(Vec3(0.5, 0.5, 0.5)))
        assertEquals(NormalBucket.MINUS_X, ContactProbe.normalBucket(Vec3(-0.5, -0.5, 0.0)))
        assertEquals(NormalBucket.PLUS_Y, ContactProbe.normalBucket(Vec3(0.0, 0.4, -0.4)))
        assertEquals(NormalBucket.MINUS_Z, ContactProbe.normalBucket(Vec3(0.0, 0.0, -1.0)))
    }

    @Test
    fun `法线零向量即拒`() {
        assertThrows(IllegalArgumentException::class.java) { ContactProbe.normalBucket(Vec3.ZERO) }
    }

    @Test
    fun `法线档对解析穿透方向正确`() {
        val east = ContactProbe.read(CavityFixtures.sphereAtZ("a", 0.0), CavityFixtures.sphereAtX("b", 1.5))!!
        assertEquals("A 在原点穿 X 向的 B：方向该是 +X", NormalBucket.PLUS_X, east.normal)
        val south = ContactProbe.read(
            CavityFixtures.sphereAtZ("a", 0.0),
            CavityFixtures.sphere("b", Vec3(0.0, -1.5, 0.0)),
        )!!
        assertEquals(NormalBucket.MINUS_Y, south.normal)
    }

    @Test
    fun `成对调转后深度与点数同档_法线反向`() {
        val a = CavityFixtures.sphereAtX("a", 0.0)
        val b = CavityFixtures.sphereAtX("b", 1.5)
        val ab = ContactProbe.read(a, b)!!
        val ba = ContactProbe.read(b, a)!!
        assertEquals(ab.pair, ba.pair)
        assertEquals(ab.depthMilli, ba.depthMilli)
        assertEquals(ab.contactRaw, ba.contactRaw)
        assertEquals(ab.counted, ba.counted)
        assertEquals(NormalBucket.PLUS_X, ab.normal)
        assertEquals(NormalBucket.MINUS_X, ba.normal)
    }

    @Test
    fun `读数都带假设与模拟非测量口径`() {
        for (r in ContactProbe.readAll(CavityFixtures.overlappingCluster(4))) {
            assertTrue(r.assumption.isNotBlank())
            assertEquals(GeometryNotes.NOT_MEASUREMENT, r.note)
        }
    }

    @Test
    fun `判据六_三量输出同输入两次逐字节一致`() {
        val ps = CavityFixtures.fromBody()
        assertEquals(ContactProbe.readAll(ps).toString(), ContactProbe.readAll(ps).toString())
        val far = CavityFixtures.flatHull("h1", Vec3(0.0, 0.0, 0.7), 20)
        val hot = CavityFixtures.sphereAtZ("s0", 1.2)
        val one = ContactProbe.read(far, hot).toString()
        val twice = ContactProbe.read(far, hot).toString()
        assertEquals(one, twice)
    }

    @Test
    fun `读全部时只出真接触对_不混进分离对`() {
        val all = ContactProbe.readAll(CavityFixtures.spreadSpheres(6, 1.9))
        assertEquals("六球链只有五个相邻对", 5, all.size)
        assertTrue(all.all { it.depthMilli >= 0 })
    }
}
