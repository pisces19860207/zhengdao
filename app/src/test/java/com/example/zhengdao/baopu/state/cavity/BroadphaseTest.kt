// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.baopu.state.cavity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 交付②＋判据②：包围盒粗剔 → 近邻细判；粗剔漏掉的近邻对不许在细判出现（假阴性率 0）。 */
class BroadphaseTest {

    /** 暴力全对细判：不经任何粗剔，作为判据②的对照集。 */
    private fun bruteFine(proxies: List<OrganProxy>): Set<ProxyPair> {
        val out = linkedSetOf<ProxyPair>()
        for (i in proxies.indices) {
            for (j in i + 1 until proxies.size) {
                if (Broadphase.inContact(proxies[i], proxies[j])) out.add(ProxyPair.of(proxies[i].id, proxies[j].id))
            }
        }
        return out
    }

    private val scenarios = listOf(
        "散开 12 球（间距 9cm）" to CavityFixtures.spreadSpheres(12, 9.0),
        "散开 12 球（间距 2.1cm）" to CavityFixtures.spreadSpheres(12, 2.1),
        "散开 12 球（间距 1.9cm）" to CavityFixtures.spreadSpheres(12, 1.9),
        "重叠一团 9 球" to CavityFixtures.overlappingCluster(9),
        "竖直堆叠 8 球" to CavityFixtures.verticalStack(8),
        "660 真形体全代理解" to CavityFixtures.fromBody(),
    )

    @Test
    fun `判据二_粗剔候选集是细判结果集的超集_六场景全核`() {
        for ((name, ps) in scenarios) {
            val missed = bruteFine(ps) - Broadphase.coarse(ps).toSet()
            assertTrue("$name 粗剔漏掉了近邻对：$missed", missed.isEmpty())
        }
    }

    @Test
    fun `判据二_两级管道与暴力全对逐对相等_假阴性率零`() {
        for ((name, ps) in scenarios) {
            assertEquals("$name 两级管道应与暴力一致", bruteFine(ps), Broadphase.fine(ps).toSet())
        }
    }

    @Test
    fun `判据二_零散场景细判为空时不许反而冒出候选`() {
        val ps = CavityFixtures.spreadSpheres(10, 40.0)
        assertEquals(emptySet<ProxyPair>(), Broadphase.fine(ps).toSet())
        assertEquals(emptyList<ProxyPair>(), Broadphase.coarse(ps))
    }

    @Test
    fun `剔除真的有牙_候选远少于全对且一个真近邻没丢`() {
        val ps = CavityFixtures.spreadSpheres(16, 1.9)
        val all = ps.size * (ps.size - 1) / 2
        val coarse = Broadphase.coarse(ps)
        val near = bruteFine(ps)
        assertEquals("间距 1.9 的 16 球：120 对里只该留 15 对相邻候选", 15, coarse.size)
        assertEquals("真近邻恰好就是这 15 对", 15, near.size)
        assertTrue("候选 ${coarse.size} 应远小于全对 $all", coarse.size * 4 < all)
        assertTrue("一个真近邻都不许被粗剔掉", coarse.toSet().containsAll(near))
    }

    @Test
    fun `差一点接触的排布_粗剔细判双双为空`() {
        val ps = CavityFixtures.spreadSpheres(16, 2.1)
        assertEquals(emptyList<ProxyPair>(), Broadphase.coarse(ps))
        assertEquals(emptySet<ProxyPair>(), Broadphase.fine(ps).toSet())
        assertEquals(emptySet<ProxyPair>(), bruteFine(ps))
    }

    @Test
    fun `紧贴两球的对必须入选_三球只留相邻两对`() {
        val ps = CavityFixtures.spreadSpheres(3, 1.9)
        assertEquals(2, Broadphase.coarse(ps).size)
        assertEquals(2, Broadphase.fine(ps).size)
    }

    @Test
    fun `AABB 边界相切也算候选_宁多不漏`() {
        val a = CavityFixtures.sphereAtX("a", 0.0)
        val b = CavityFixtures.sphereAtX("b", 2.0)
        assertTrue("恰接触（间距＝两倍半径）AABB 必重叠", a.aabb().overlaps(b.aabb()))
        assertEquals("恰接触的球对必进候选与细判", 1, Broadphase.coarse(listOf(a, b)).size)
        assertEquals(1, Broadphase.fine(listOf(a, b)).size)
    }

    @Test
    fun `XZ 投影重叠单独可判_同轴上下者重叠横向远者不重叠`() {
        val low = CavityFixtures.sphereAtY("low", 0.0)
        assertTrue(low.aabb().overlapsXz(CavityFixtures.sphereAtY("high", 5.0).aabb()))
        assertTrue(!low.aabb().overlapsXz(CavityFixtures.sphereAtX("far", 50.0).aabb()))
    }

    @Test
    fun `成对键规范化_名字小的在前且不许直接构造反序对`() {
        val p = ProxyPair.of("b", "a")
        assertEquals("a", p.aId)
        assertEquals("b", p.bId)
        assertThrows(IllegalArgumentException::class.java) { ProxyPair("b", "a") }
        assertThrows(IllegalArgumentException::class.java) { ProxyPair("a", "a") }
        assertEquals(emptyList<ProxyPair>(), Broadphase.coarse(listOf(CavityFixtures.sphereAtZ("only", 0.0))))
        assertEquals(emptyList<ProxyPair>(), Broadphase.coarse(emptyList()))
    }

    @Test
    fun `候选对集与包围盒相交集逐对相等_粗剔不许夹带私货`() {
        val ps = CavityFixtures.fromBody()
        val byBox = linkedSetOf<ProxyPair>()
        for (i in ps.indices) {
            for (j in i + 1 until ps.size) {
                if (ps[i].aabb().overlaps(ps[j].aabb())) byBox.add(ProxyPair.of(ps[i].id, ps[j].id))
            }
        }
        assertEquals(byBox, Broadphase.coarse(ps).toSet())
    }

    @Test
    fun `判据六_两级剔除同输入两次逐字节一致`() {
        val ps = CavityFixtures.fromBody()
        assertEquals(Broadphase.coarse(ps).toString(), Broadphase.coarse(ps).toString())
        assertEquals(Broadphase.fine(ps).toString(), Broadphase.fine(ps).toString())
    }

    @Test
    fun `输出按成对键升序排_不留哈希序尾巴`() {
        val pairs = Broadphase.coarse(CavityFixtures.overlappingCluster(7))
        assertEquals(pairs.sortedWith(ProxyPair.BY_ID), pairs)
        assertTrue("七球一团该有一批候选", pairs.size > 10)
    }
}
