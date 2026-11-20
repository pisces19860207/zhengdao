// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百八十六批 · 交付④：腔隙＝13 区与相邻结构之间的可用空隙。
// 输入是 660 的 AnatomyZones（相邻关系＋方位口径原文）与 660 的 BodyProxies（经 ProxySimplify）。
package com.example.zhengdao.baopu.state.cavity

import com.example.zhengdao.baopu.state.geometry.GeometryNotes
import com.example.zhengdao.baopu.state.geometry.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CavityMapTest {

    private fun gap(gaps: List<CavityGap>, a: String, b: String): CavityGap =
        gaps.first { (it.zoneId == a && it.otherId == b) || (it.zoneId == b && it.otherId == a) }

    @Test
    fun `相邻表是有向的_腔隙按无序并集出条目`() {
        val anatomy = CavityFixtures.anatomyZones()
        val directed = anatomy.flatMap { z -> z.relations.adjacent.map { z.zoneId to it } }
        val union = directed.map { listOf(it.first, it.second).sorted() }.toSet()
        assertEquals("660 事实表相邻共 38 条有向关系", 38, directed.size)
        assertEquals("去掉方向后 22 对（其中六对只写了单向，取并集才不漏）", 22, union.size)
        val gaps = CavityMap.of(CavityFixtures.zoneSpheres(1.5), anatomy)
        assertEquals(union.size, gaps.size)
        assertTrue(
            "prepuce→corona 只写了单向，取并集才不漏这一条",
            gaps.any { setOf(it.zoneId, it.otherId) == setOf("corona", "prepuce") },
        )
    }

    @Test
    fun `条目按区名升序_一对只出一次`() {
        val gaps = CavityMap.of(CavityFixtures.zoneSpheres(1.5), CavityFixtures.anatomyZones())
        val keys = gaps.map { it.zoneId to it.otherId }
        assertEquals(keys.sortedWith(compareBy({ it.first }, { it.second })), keys)
        assertEquals(gaps.size, gaps.map { listOf(it.zoneId, it.otherId).sorted() }.distinct().size)
        assertTrue("两侧必须规范化排序", gaps.all { it.zoneId < it.otherId })
        assertTrue("不许自己和自己出腔隙", gaps.all { it.zoneId != it.otherId })
    }

    @Test
    fun `每腔隙给整数毫米＋方向档＋方位口径原文透传`() {
        val anatomy = CavityFixtures.anatomyZones()
        val position = anatomy.associate { it.zoneId to it.relations.position }
        for (g in CavityMap.of(CavityFixtures.zoneSpheres(1.5), anatomy)) {
            assertTrue("方向必须是六档之一：${g.direction}", g.direction in NormalBucket.values().toList())
            assertEquals("方位口径原样透传 660，不改写", position.getValue(g.zoneId), g.anatomyPosition)
            assertTrue("${g.zoneId} 缺假设", g.assumption.isNotBlank())
            assertEquals(GeometryNotes.NOT_MEASUREMENT, g.note)
        }
    }

    @Test
    fun `空隙三档_贴合与穿透都算堵死`() {
        assertEquals(CavityState.BLOCKED, CavityMap.stateOf(-5))
        assertEquals(CavityState.BLOCKED, CavityMap.stateOf(0))
        assertEquals(CavityState.NARROW, CavityMap.stateOf(1))
        assertEquals(CavityState.NARROW, CavityMap.stateOf(CavityTolerances.CAVITY_OPEN_MILLI - 1))
        assertEquals(CavityState.OPEN, CavityMap.stateOf(CavityTolerances.CAVITY_OPEN_MILLI))
        assertEquals(CavityState.OPEN, CavityMap.stateOf(CavityTolerances.CAVITY_OPEN_MILLI + 500))
    }

    @Test
    fun `推开邻体_空隙不减且档位只朝OPEN迁移`() {
        val anatomy = CavityFixtures.anatomyZones()
        val base = CavityFixtures.zoneSpheres(1.5)
        var prev = mutableMapOf<String, Int>()
        var prevStates = mutableMapOf<String, CavityState>()
        val rank = listOf(CavityState.BLOCKED, CavityState.NARROW, CavityState.OPEN)
        for (k in 0..12) {
            val moved = base.map { if (it.id == "glans") it.shiftedBy(Vec3(0.0, 0.0, k * 1.5)) else it }
            val gaps = CavityMap.of(moved, anatomy)
            for (g in gaps.filter { it.zoneId == "glans" || it.otherId == "glans" }) {
                val key = "${g.zoneId}|${g.otherId}"
                prev[key]?.let { assertTrue("$key 空隙应随推开不减：$it → ${g.gapMilli}", g.gapMilli >= it) }
                prevStates[key]?.let {
                    assertTrue("$key 档位不许倒退：$it → ${g.state}", rank.indexOf(g.state) >= rank.indexOf(it))
                }
                prev[key] = g.gapMilli
                prevStates[key] = g.state
            }
        }
        assertTrue("推到最后总有腔隙进入 OPEN", prevStates.values.any { it == CavityState.OPEN })
    }

    @Test
    fun `不相邻的区不出条目`() {
        val gaps = CavityMap.of(CavityFixtures.zoneSpheres(1.5), CavityFixtures.anatomyZones())
        assertTrue("meatus 只与 glans 相邻", gaps.none { setOf(it.zoneId, it.otherId) == setOf("meatus", "scrotum") })
        assertEquals(1, gaps.count { "meatus" in listOf(it.zoneId, it.otherId) })
    }

    @Test
    fun `多实例区取最小的那条空隙`() {
        val anatomy = CavityFixtures.anatomyZones()
        val near = CavityMap.of(CavityFixtures.zoneSpheres(1.5), anatomy)
        val withFar = CavityMap.of(CavityFixtures.zoneSpheresWithFarTwin(1.5, 400.0), anatomy)
        val a = gap(near, "scrotum", "testis")
        val b = gap(withFar, "scrotum", "testis")
        assertEquals("远处多挂一个睾丸实例，不该改变最近的那条空隙", a.gapMilli, b.gapMilli)
    }

    @Test
    fun `区缺代理体即拒_免得静默漏腔隙`() {
        val anatomy = CavityFixtures.anatomyZones()
        val missing = CavityFixtures.zoneSpheres(1.5).filter { it.id != "sulcus" }
        val e = assertThrows(IllegalArgumentException::class.java) { CavityMap.of(missing, anatomy) }
        assertTrue("报错要点名缺哪个区：${e.message}", e.message!!.contains("sulcus"))
    }

    @Test
    fun `相邻指向表里没有的区即拒`() {
        val anatomy = CavityFixtures.anatomyZones()
        val bad = anatomy.map {
            if (it.zoneId == "glans") it.copy(relations = it.relations.copy(adjacent = it.relations.adjacent + "no_such_zone"))
            else it
        }
        val e = assertThrows(IllegalArgumentException::class.java) {
            CavityMap.of(CavityFixtures.zoneSpheres(1.5), bad)
        }
        assertTrue("报错要点名悬空的相邻区：${e.message}", e.message!!.contains("no_such_zone"))
    }

    @Test
    fun `判据六_腔隙表同输入两次逐字节一致`() {
        val anatomy = CavityFixtures.anatomyZones()
        val ps = CavityFixtures.fromBody()
        assertEquals(CavityMap.of(ps, anatomy).toString(), CavityMap.of(ps, anatomy).toString())
    }

    @Test
    fun `真形体的每条腔隙都有读数且三态齐备`() {
        val gaps = CavityMap.of(CavityFixtures.fromBody(), CavityFixtures.anatomyZones())
        assertEquals(22, gaps.size)
        for (g in gaps) {
            assertTrue("${g.zoneId}↔${g.otherId} 方向档缺失", g.direction in NormalBucket.values().toList())
        }
        assertTrue("相邻区天然贴合，至少有堵死的", gaps.any { it.state == CavityState.BLOCKED })
    }

    @Test
    fun `相邻对给判据一当自碰撞名单_必须是真实存在的代理体名`() {
        val ps = CavityFixtures.fromBody()
        val pairs = CavityMap.adjacentPairs(ps, CavityFixtures.anatomyZones())
        val ids = ps.map { it.id }.toSet()
        assertTrue("相邻对为空说明区名对不上代理体名", pairs.isNotEmpty())
        assertTrue("配对两侧都必须是代理体名", pairs.all { it.first in ids && it.second in ids })
        assertTrue("不许自配", pairs.all { it.first != it.second })
        assertTrue("规范化排序", pairs.all { it.first < it.second })
        assertEquals(pairs.size, pairs.map { listOf(it.first, it.second).sorted() }.distinct().size)
    }

    @Test
    fun `相邻对展开双实例`() {
        val ps = CavityFixtures.zoneSpheresWithFarTwin(1.5, 400.0)
        val pairs = CavityMap.adjacentPairs(ps, CavityFixtures.anatomyZones())
        val zone = { id: String -> id.substringBefore('.') }
        assertEquals("scrotum 与两个 testis 实例都该成对", 2, pairs.count { setOf(zone(it.first), zone(it.second)) == setOf("scrotum", "testis") })
        assertTrue("两实例的代理体名不同", pairs.map { it.first to it.second }.distinct().size == pairs.size)
    }
}
