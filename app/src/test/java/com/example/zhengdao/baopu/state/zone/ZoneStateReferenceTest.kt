// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.state.zone

import com.example.zhengdao.baopu.state.impact.ImpactSite
import com.example.zhengdao.baopu.state.prepuce.AreaLevel
import com.example.zhengdao.baopu.state.prepuce.CoverState
import com.example.zhengdao.baopu.state.prepuce.SkinAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneStateReferenceTest {

    @Test
    fun `包皮只引用804831三个真实类型`() {
        val refs = ZoneFixtures.prepuce()
        val state = ZoneFixtures.query(prepuce = refs).getZoneState("prepuce")
        assertEquals(CoverState.PARTIAL, state.prepuce?.cover)
        assertEquals(AreaLevel.OVER_HALF, state.prepuce?.area)
        assertEquals(SkinAmount.MODERATE, state.prepuce?.skin)
        assertEquals("804/831", state.evidenceFor("prepuce.cover").from)
        assertEquals("804/831", state.evidenceFor("prepuce.area").from)
        assertEquals("804/831", state.evidenceFor("prepuce.skin").from)
    }

    @Test
    fun `非包皮区不复制包皮引用`() {
        val state = ZoneFixtures.query(prepuce = ZoneFixtures.prepuce()).getZoneState("glans")
        assertNull(state.prepuce)
        assertEquals("not_applicable", state.evidenceFor("prepuce.cover").absent?.reason)
    }

    @Test
    fun `包皮引用缺失时显式pending804831`() {
        val state = ZoneFixtures.query().getZoneState("prepuce")
        assertNull(state.prepuce)
        assertEquals("804/831", state.evidenceFor("prepuce.cover").absent?.pending)
    }

    @Test
    fun `pubicStyle入口未核时固定absent723`() {
        val state = ZoneFixtures.query().getZoneState("mons")
        assertNull(state.pubicStyle)
        val absent = state.evidenceFor("pubicStyle").absent
        assertEquals("723", absent?.pending)
        assertEquals("入口未核", absent?.reason)
    }

    @Test
    fun `727包皮内外板都归并到区级prepuce`() {
        for (site in listOf(ImpactSite.PREPUCE_INNER, ImpactSite.PREPUCE_OUTER)) {
            val state = ZoneFixtures.query(impact = ZoneFixtures.impact(site)).getZoneState("prepuce")
            assertEquals(true, state.event.justHit)
            assertTrue(requireNotNull(state.event.recovery) > 0)
        }
    }

    @Test
    fun `727证据只保留区级作用点不泄漏子区码`() {
        for (site in listOf(ImpactSite.PREPUCE_INNER, ImpactSite.PREPUCE_OUTER, ImpactSite.PERINEUM)) {
            val query = ZoneFixtures.query(impact = ZoneFixtures.impact(site))
            for (id in ZoneFixtures.anatomyZones.map { it.zoneId }) {
                val rendered = query.getZoneState(id).evidence.joinToString {
                    "${it.from}|${it.why}|${it.absent?.reason}|${it.absent?.pending}"
                }
                for (code in listOf("prepuce.inner", "prepuce.outer", "perineum.x1", "e1")) {
                    assertFalse("$site → $id 证据泄漏 $code", rendered.contains(code))
                }
            }
        }
    }

    @Test
    fun `727作用点逐类映射到十三区且不按枚举名猜`() {
        val expected = mapOf(
            ImpactSite.GLANS to "glans",
            ImpactSite.CORONA to "corona",
            ImpactSite.FRENULUM to "frenulum",
            ImpactSite.MEATUS to "meatus",
            ImpactSite.PREPUCE_INNER to "prepuce",
            ImpactSite.PREPUCE_OUTER to "prepuce",
            ImpactSite.SHAFT to "shaft",
            ImpactSite.ROOT to "radix",
            ImpactSite.SCROTUM to "scrotum",
            ImpactSite.TESTIS to "testis",
            ImpactSite.CORD to "cord",
            ImpactSite.PUBIS to "mons",
        )
        for ((site, target) in expected) {
            val query = ZoneFixtures.query(impact = ZoneFixtures.impact(site, cremasteric = true))
            for (id in ZoneFixtures.anatomyZones.map { it.zoneId }) {
                val state = query.getZoneState(id)
                assertEquals("$site → $id 刚受击", id == target, state.event.justHit)
                assertEquals("$site → $id 提睾", id == target, state.event.cremasteric)
                if (id == target) {
                    assertTrue(requireNotNull(state.event.recovery) > 0)
                    assertTrue(requireNotNull(state.pose.flatten) > 0)
                    assertEquals("727", state.evidenceFor("event.recovery").from)
                    assertEquals("730/727", state.evidenceFor("event.cremasteric").from)
                } else {
                    assertEquals(0, state.event.recovery)
                    assertEquals(0, state.pose.flatten)
                }
            }
        }
    }

    @Test
    fun `727会阴作用点不进入十三区`() {
        val state = ZoneFixtures.query(impact = ZoneFixtures.impact(ImpactSite.PERINEUM)).getZoneState("scrotum")
        assertNull(state.event.justHit)
        assertNull(state.event.recovery)
        assertEquals("877", state.evidenceFor("event.recovery").absent?.pending)
        assertEquals("会阴未定案", state.evidenceFor("event.recovery").absent?.reason)
    }

    @Test
    fun `727缺作用点时事件显式pending727`() {
        val impact = ZoneFixtures.impact().copy(site = null)
        val state = ZoneFixtures.query(impact = impact).getZoneState("scrotum")
        assertNull(state.event.justHit)
        assertNull(state.pose.flatten)
        assertEquals("727", state.evidenceFor("event.recovery").absent?.pending)
    }
}
