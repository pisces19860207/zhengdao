// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.state.zone

import com.example.zhengdao.SrcRoots
import com.example.zhengdao.baopu.body.ZoneRelations
import com.example.zhengdao.baopu.state.impact.ImpactSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneStateContractTest {

    private val expectedDimensions = listOf(
        "zone",
        "shape.radius",
        "shape.curvature",
        "shape.corona",
        "shape.rugosity",
        "pose.displace",
        "pose.flatten",
        "pose.hang",
        "pose.cling",
        "surface.wetFilm",
        "surface.colorShift",
        "surface.vesselVisible",
        "event.justHit",
        "event.cremasteric",
        "event.recovery",
        "prepuce.cover",
        "prepuce.area",
        "prepuce.skin",
        "pubicStyle",
    )

    @Test
    fun `十三区全部可查且字段档域完整`() {
        val query = ZoneFixtures.query()
        val ids = ZoneFixtures.anatomyZones.map { it.zoneId }
        assertEquals(13, ids.size)
        assertEquals(expectedDimensions, ZoneDimensions.ALL)
        for (id in ids) {
            val state = query.getZoneState(id)
            assertEquals(id, state.zone.id)
            assertEquals(expectedDimensions, state.evidence.map { it.dim })
            assertEquals(expectedDimensions, state.values().keys.toList())
            assertTrue(state.evidence.all { it.from.isNotBlank() && it.why.isNotBlank() })
            for ((dim, value) in state.values()) {
                assertEquals("$dim 的 null 与 absent 必须互为充要", value == null, state.evidenceFor(dim).absent != null)
            }
            assertRanges(state)
        }
    }

    @Test
    fun `surface字段名逐字对齐730`() {
        val surface = ZoneFixtures.query().getZoneState("scrotum").surface
        assertEquals(listOf("wetFilm", "colorShift", "vesselVisible"), surface.names())
    }

    @Test
    fun `同输入两次独立查询逐字一致`() {
        val first = ZoneFixtures.query()
        val second = ZoneFixtures.query()
        for (id in ZoneFixtures.anatomyZones.map { it.zoneId }) {
            assertEquals(id, first.getZoneState(id), second.getZoneState(id))
        }
    }

    @Test
    fun `缺上游读数时null不是默认零`() {
        val state = ZoneFixtures.query(thermo = null, impact = null).getZoneState("scrotum")
        assertNull(state.shape.rugosity)
        assertNull(state.pose.hang)
        assertNull(state.surface.wetFilm)
        assertNull(state.surface.colorShift)
        assertNull(state.surface.vesselVisible)
        assertNull(state.event.justHit)
        assertNull(state.event.cremasteric)
        assertNull(state.event.recovery)
        assertEquals("730", state.evidenceFor("surface.wetFilm").absent?.pending)
        assertEquals("727", state.evidenceFor("event.recovery").absent?.pending)
    }

    @Test
    fun `无腔隙且无730时cling缺席不补零`() {
        val anatomy = ZoneFixtures.anatomyZones.map {
            it.copy(relations = ZoneRelations(emptyList(), it.relations.position))
        }
        val state = ZoneFixtures.query(anatomy = anatomy, thermo = null, impact = null).getZoneState("scrotum")
        assertNull(state.pose.cling)
        assertEquals("686/730", state.evidenceFor("pose.cling").absent?.pending)
    }

    @Test
    fun `分区数量唯一性词表对齐与档域越界即拒`() {
        val anatomy = ZoneFixtures.anatomyZones
        assertThrows { ZoneFixtures.query(anatomy = anatomy.dropLast(1)) }
        assertThrows { ZoneFixtures.query(anatomy = anatomy + anatomy.first().copy(zoneId = "extra")) }
        assertThrows { ZoneFixtures.query(anatomy = anatomy.dropLast(1) + anatomy.first()) }
        assertThrows { ZoneFixtures.query(zones = ZoneFixtures.zoneTable.copy(zones = ZoneFixtures.zoneTable.zones.dropLast(1))) }
        val state = ZoneFixtures.query().getZoneState("scrotum")
        assertThrows { state.copy(shape = state.shape.copy(radius = 4)) }
        assertThrows { state.copy(surface = state.surface.copy(vesselVisible = 3)) }
        assertThrows { state.copy(event = state.event.copy(recovery = 4)) }
    }

    @Test
    fun `分区表来源必须指向669词表`() {
        val forged = ZoneFixtures.zoneTable.copy(zonesRef = "forged-zone-catalog")
        assertThrows { ZoneFixtures.query(zones = forged) }
    }

    @Test
    fun `每字段恰好一条evidence_缺一条即拒`() {
        val state = ZoneFixtures.query().getZoneState("scrotum")
        assertThrows { state.copy(evidence = state.evidence.dropLast(1)) }
        assertThrows { state.copy(evidence = state.evidence + state.evidence.first()) }
    }

    @Test
    fun `evidence来源与理由空白即拒`() {
        val state = ZoneFixtures.query().getZoneState("scrotum")
        assertThrows {
            state.copy(evidence = state.evidence.map { if (it.dim == "zone") it.copy(from = " ") else it })
        }
        assertThrows {
            state.copy(evidence = state.evidence.map { if (it.dim == "zone") it.copy(why = "") else it })
        }
    }

    @Test
    fun `值与absent互斥_零与false也算值`() {
        val state = ZoneFixtures.query().getZoneState("scrotum")
        val evidence = state.evidence.map {
            if (it.dim == "surface.wetFilm") it.copy(absent = Absent("730", "假缺席")) else it
        }
        assertThrows { state.copy(evidence = evidence) }
        assertThrows { state.copy(surface = state.surface.copy(wetFilm = null)) }

        val other = ZoneFixtures.query(impact = ZoneFixtures.impact(ImpactSite.GLANS)).getZoneState("shaft")
        val zeroAbsent = other.evidence.map {
            if (it.dim == "event.recovery") it.copy(absent = Absent("727", "假缺席")) else it
        }
        assertThrows { other.copy(evidence = zeroAbsent) }
        val falseAbsent = other.evidence.map {
            if (it.dim == "event.justHit") it.copy(absent = Absent("727", "假缺席")) else it
        }
        assertThrows { other.copy(evidence = falseAbsent) }
    }

    @Test
    fun `非零档与true必须带规则或事件来源`() {
        val query = ZoneFixtures.query()
        var checked = 0
        for (id in ZoneFixtures.anatomyZones.map { it.zoneId }) {
            for ((dim, value) in query.getZoneState(id).values()) {
                val active = (value is Int && value != 0) || value == true
                if (!active) continue
                checked += 1
                val evidence = query.getZoneState(id).evidenceFor(dim)
                assertFalse("$dim 不许用 none", evidence.from == "none")
                assertFalse("$dim 不许用 default", evidence.from.contains("default", ignoreCase = true))
                assertNull(evidence.absent)
            }
        }
        assertTrue(checked > 0)
    }

    @Test
    fun `区级字段不夹带几何或子区维度`() {
        val state = ZoneFixtures.query().getZoneState("glans")
        val forbidden = listOf("mesh", "vertex", "bitmap", "apex", "glansbody", "shaftMid")
        assertTrue(ZoneDimensions.ALL.none { dim -> forbidden.any { dim.contains(it, ignoreCase = true) } })
        assertTrue(state.intValues().keys.all { it in ZoneDimensions.ALL })
    }

    @Test
    fun `未知区与子区code一律拒收`() {
        val query = ZoneFixtures.query()
        assertThrows { query.getZoneState("perineum.x1") }
        assertThrows { query.getZoneState("apex") }
        assertThrows { query.getZoneState("shaftMid") }
    }

    @Test
    fun `注入的区词表与分区表真的被消费`() {
        val anatomy = ZoneFixtures.anatomyZones.map {
            if (it.zoneId == "glans") it.copy(name = "注入区名") else it
        }
        val zones = ZoneFixtures.zoneTable.copy(
            zones = ZoneFixtures.zoneTable.zones.map {
                if (it.zoneId == "corona") it.copy(form = null) else it
            },
        )
        val query = ZoneFixtures.query(anatomy = anatomy, zones = zones)
        assertEquals("注入区名", query.getZoneState("glans").zone.name)
        val corona = query.getZoneState("corona")
        assertNull(corona.shape.corona)
        assertEquals("not_applicable", corona.evidenceFor("shape.corona").absent?.reason)
    }

    @Test
    fun `运行期零文件IO且不复制十三区目录`() {
        val files = listOf("ZoneState.kt", "ZoneEvidence.kt", "ZoneAdapters.kt", "ZoneStateQuery.kt")
        val code = files.joinToString("\n") { name ->
            SrcRoots.find("app/src/main/java/com/example/zhengdao/baopu/state/zone/$name")
                .readText()
                .replace(Regex("(?s)/\\*.*?\\*/"), "")
                .replace(Regex("//.*"), "")
        }
        for (token in listOf("AnatomyZones.load", "docs/facts", "readText(", "File(", "Files.", "java.io")) {
            assertFalse("运行期不许出现 $token", code.contains(token))
        }
        for (token in listOf("CoverageArea", "RetractState", "hairGradient")) {
            assertFalse("本期不许引用 $token", code.contains(token))
        }
        val zoneIds = ZoneFixtures.anatomyZones.map { it.zoneId }
        assertEquals(emptyList<String>(), architectureViolations(code, zoneIds))
    }

    @Test
    fun `架构闸负例_mapOf目录别名IO与加载口都拒收`() {
        val zoneIds = ZoneFixtures.anatomyZones.map { it.zoneId }
        val catalog = "val zones = mapOf(" + zoneIds.joinToString { "\"$it\" to 1" } + ")"
        assertTrue(architectureViolations(catalog, zoneIds).isNotEmpty())
        assertTrue(architectureViolations("import java.io.File as Io", zoneIds).isNotEmpty())
        assertTrue(architectureViolations("AnatomyZones.load(repoRoot)", zoneIds).isNotEmpty())
        assertEquals(emptyList<String>(), architectureViolations("val path = AnatomyZones.REL_PATH", zoneIds))
    }

    private fun architectureViolations(code: String, zoneIds: List<String>): List<String> {
        val problems = mutableListOf<String>()
        val imports = Regex("(?m)^import\\s+(.+)").findAll(code).map { it.groupValues[1] }.toList()
        val badImports = imports.filter { !it.startsWith("com.example.zhengdao.") && !it.startsWith("kotlin.") }
        if (badImports.isNotEmpty()) problems += "非法 import: $badImports"
        val io = Regex(
            "\\b(?:File|Files|Path|Paths|URI|URL|Socket|Runtime|ProcessBuilder|Class)\\b" +
                "|\\b(?:readText|readBytes|forName|load)\\s*\\(",
        )
        if (io.containsMatchIn(code)) problems += "运行期 IO/反射/加载口"
        if (zoneIds.count { code.contains("\"$it\"") } >= 5) problems += "十三区 ID 目录"
        return problems
    }

    private fun assertRanges(state: ZoneState) {
        assertGrade("shape.radius", state.shape.radius, 3)
        assertGrade("shape.curvature", state.shape.curvature, 2)
        assertGrade("shape.corona", state.shape.corona, 2)
        assertGrade("shape.rugosity", state.shape.rugosity, 3)
        assertGrade("pose.displace", state.pose.displace, 3)
        assertGrade("pose.flatten", state.pose.flatten, 3)
        assertGrade("pose.hang", state.pose.hang, 3)
        assertGrade("pose.cling", state.pose.cling, 3)
        assertGrade("surface.wetFilm", state.surface.wetFilm, 3)
        assertGrade("surface.colorShift", state.surface.colorShift, 3)
        assertGrade("surface.vesselVisible", state.surface.vesselVisible, 2)
        assertGrade("event.recovery", state.event.recovery, 3)
    }

    private fun assertGrade(dim: String, value: Int?, max: Int) {
        assertTrue("$dim 档域", value == null || value in 0..max)
    }

    private fun assertThrows(block: () -> Unit) {
        var threw = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("应抛 IllegalArgumentException", threw)
    }
}
