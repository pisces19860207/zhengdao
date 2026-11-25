// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.state.zone

import com.example.zhengdao.baopu.state.cavity.CavityMap
import com.example.zhengdao.baopu.state.cavity.ProxySimplify
import com.example.zhengdao.baopu.state.geometry.Environment
import com.example.zhengdao.baopu.state.geometry.SegmentProxy
import com.example.zhengdao.baopu.state.geometry.Sphere
import com.example.zhengdao.baopu.state.geometry.Vec3
import com.example.zhengdao.baopu.state.impact.ImpactSite
import com.example.zhengdao.baopu.state.thermo.HumidityBand
import com.example.zhengdao.baopu.state.thermo.Medium
import com.example.zhengdao.baopu.state.thermo.ThermalBand
import kotlin.math.ceil
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneStateDirectionTest {

    @Test
    fun `冷使阴囊皱缩升_热使皱缩降`() {
        val cold = ZoneFixtures.query(thermo = ZoneFixtures.thermo(ThermalBand.COOL)).getZoneState("scrotum")
        val hot = ZoneFixtures.query(thermo = ZoneFixtures.thermo(ThermalBand.HOT)).getZoneState("scrotum")
        assertTrue("冷档皱缩必须高于热档", requireNotNull(cold.shape.rugosity) > requireNotNull(hot.shape.rugosity))
        assertEquals("730", cold.evidenceFor("shape.rugosity").from)
    }

    @Test
    fun `湿使surface湿膜升`() {
        val dry = ZoneFixtures.query(
            thermo = ZoneFixtures.thermo(humidity = HumidityBand.DRY, medium = Medium.NONE),
        ).getZoneState("shaft")
        val wet = ZoneFixtures.query(
            thermo = ZoneFixtures.thermo(humidity = HumidityBand.SATURATED, medium = Medium.WATER),
        ).getZoneState("shaft")
        assertTrue("湿档湿膜必须高于干档", requireNotNull(wet.surface.wetFilm) > requireNotNull(dry.surface.wetFilm))
    }

    @Test
    fun `受击区event非零且恢复随轮次下降`() {
        val justHit = ZoneFixtures.query(
            impact = ZoneFixtures.impact(rounds = 0, cremasteric = true),
        ).getZoneState("scrotum")
        val later = ZoneFixtures.query(impact = ZoneFixtures.impact(rounds = 3)).getZoneState("scrotum")
        assertEquals(true, justHit.event.justHit)
        assertEquals(true, justHit.event.cremasteric)
        assertTrue(requireNotNull(justHit.event.recovery) > 0)
        assertTrue("恢复档必须随轮次下降", requireNotNull(later.event.recovery) < requireNotNull(justHit.event.recovery))
        assertTrue(requireNotNull(justHit.pose.flatten) > 0)
        assertEquals("727", justHit.evidenceFor("event.recovery").from)
    }

    @Test
    fun `未受击区明确false与零而不是缺席`() {
        val state = ZoneFixtures.query(impact = ZoneFixtures.impact(ImpactSite.GLANS)).getZoneState("shaft")
        assertEquals(false, state.event.justHit)
        assertEquals(false, state.event.cremasteric)
        assertEquals(0, state.event.recovery)
        assertEquals(0, state.pose.flatten)
        assertTrue(state.evidenceFor("event.justHit").why.contains("glans"))
    }

    @Test
    fun `686重力位移进入pose且留来源`() {
        val base = ZoneFixtures.bodyState
        val body = base.copy(
            proxies = base.proxies.copy(
                genital = base.proxies.genital.map {
                    if (it.name == "scrotum.mid") {
                        SegmentProxy(it.name, Sphere(Vec3(0.0, 10.0, 0.0), 1.0))
                    } else {
                        it
                    }
                },
            ),
        )
        val grounded = ZoneFixtures.query(body = body, impact = null, environment = Environment(9.0, 9.0))
            .getZoneState("scrotum")
        val falling = ZoneFixtures.query(body = body, impact = null, environment = Environment(8.0, 8.0))
            .getZoneState("scrotum")
        assertEquals(0, grounded.pose.displace)
        assertEquals(1, falling.pose.displace)
        assertEquals("686", falling.evidenceFor("pose.displace").from)
    }

    @Test
    fun `686腔隙贴合进入pose且多实例取最强`() {
        val query = ZoneFixtures.query(thermo = ZoneFixtures.zeroThermo(), impact = null)
        val gaps = CavityMap.of(ProxySimplify.fromBody(ZoneFixtures.bodyState.proxies), ZoneFixtures.anatomyZones)
        val expected = ZoneFixtures.anatomyZones.associate { zone ->
            val grade = gaps.filter { zone.zoneId in listOf(it.zoneId, it.otherId) }
                .maxOfOrNull { 3 - ceil(it.gapMilli / 10.0).toInt().coerceIn(0, 3) } ?: 0
            zone.zoneId to grade
        }
        assertTrue("夹具必须真有非零贴合档", expected.values.any { it > 0 })
        for ((id, grade) in expected) {
            val state = query.getZoneState(id)
            assertEquals("$id 贴合档", grade, state.pose.cling)
            if (grade > 0) assertEquals("686", state.evidenceFor("pose.cling").from)
        }
    }

    @Test
    fun `区级差异不被平均抹平`() {
        val query = ZoneFixtures.query()
        val radii = ZoneFixtures.anatomyZones.map { query.getZoneState(it.zoneId).shape.radius }
        assertTrue(radii.distinct().size > 1)
        for (id in ZoneFixtures.anatomyZones.map { it.zoneId }) {
            val maxRadius = ZoneFixtures.bodyState.genital.zones.filter { it.zoneId == id }.maxOf { it.radiusMm.high }
            val expected = floor(maxRadius / ZoneFixtures.bodyState.genital.refRadiusMm * 4).toInt().coerceIn(0, 3)
            assertEquals("$id 半径档", expected, query.getZoneState(id).shape.radius)
        }
        val corona = query.getZoneState("corona").shape.corona
        val sulcus = query.getZoneState("sulcus").shape.corona
        val glans = query.getZoneState("glans").shape.corona
        assertEquals(2, corona)
        assertEquals(1, sulcus)
        assertNull(glans)
        assertEquals("660", query.getZoneState("corona").evidenceFor("shape.corona").from)
    }
}
