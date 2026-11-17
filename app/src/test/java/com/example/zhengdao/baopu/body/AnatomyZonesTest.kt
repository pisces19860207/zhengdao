// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百六十九批 · 13 区口径表核验：字段齐・唯一・引用不悬空・md/json 双真相・fail-closed。
// 只读 docs/facts 两件与源码，零写盘、零临时目录（水位 607 口径不受影响）。
package com.example.zhengdao.baopu.body

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnatomyZonesTest {

    private fun zonesFile(): File = listOf(
        File(AnatomyZones.REL_PATH),
        File("../${AnatomyZones.REL_PATH}"),
    ).first { it.isFile }

    private fun repoRoot(): File =
        if (File(AnatomyZones.REL_PATH).isFile) File(".") else File("..")

    private val zones = AnatomyZones.parse(zonesFile().readText())

    @Test
    fun `十三区一条不多一条不少`() {
        assertEquals(13, zones.size)
    }

    @Test
    fun `每区字段齐全且非空`() {
        for (z in zones) {
            assertTrue("${z.zoneId} zone_id/name/latin 须非空", z.zoneId.isNotBlank() && z.name.isNotBlank() && z.latin.isNotBlank())
            assertTrue("${z.zoneId} 别名须非空", z.aliases.isNotEmpty() && z.aliases.all { it.isNotBlank() })
            assertTrue("${z.zoneId} 可观察特征须非空", z.observable.isNotEmpty() && z.observable.all { it.isNotBlank() })
            assertTrue("${z.zoneId} 常见误写须非空", z.commonErrors.isNotEmpty() && z.commonErrors.all { it.isNotBlank() })
            assertTrue("${z.zoneId} 相邻区须非空", z.relations.adjacent.isNotEmpty())
            assertTrue("${z.zoneId} 方位口径须非空", z.relations.position.isNotBlank())
        }
    }

    @Test
    fun `zone_id唯一且相邻引用不悬空`() {
        val ids = zones.map { it.zoneId }.toSet()
        assertEquals(13, ids.size)
        for (z in zones) {
            assertTrue("${z.zoneId} 相邻引用悬空", z.relations.adjacent.all { it in ids })
        }
    }

    @Test
    fun `md与json双真相一致`() {
        val md = File(zonesFile().parentFile, "anatomy-zones.md").readText()
        for (z in zones) {
            assertTrue("md 缺 zone_id：${z.zoneId}", z.zoneId in md)
            assertTrue("md 缺名称：${z.name}", z.name in md)
        }
        assertTrue("md 须指明共用真源", "anatomy-zones.json" in md)
    }

    @Test
    fun `load与parse同果`() {
        assertEquals(zones, AnatomyZones.load(repoRoot()))
    }

    @Test
    fun `解析确定性_两次同果`() {
        val text = zonesFile().readText()
        assertEquals(AnatomyZones.parse(text), AnatomyZones.parse(text))
    }

    @Test
    fun `缺字段即抛_fail_closed`() {
        val broken = JSONObject(zonesFile().readText())
        broken.getJSONArray("zones").getJSONObject(0).remove("observable")
        assertThrows(IllegalArgumentException::class.java) { AnatomyZones.parse(broken.toString()) }
    }

    @Test
    fun `空表即抛_fail_closed`() {
        val empty = JSONObject(zonesFile().readText()).put("zones", org.json.JSONArray())
        assertThrows(IllegalArgumentException::class.java) { AnatomyZones.parse(empty.toString()) }
    }

    @Test
    fun `悬空相邻引用即抛_fail_closed`() {
        val broken = JSONObject(zonesFile().readText())
        broken.getJSONArray("zones").getJSONObject(0)
            .getJSONObject("relations").put("adjacent", org.json.JSONArray(listOf("nowhere")))
        assertThrows(IllegalArgumentException::class.java) { AnatomyZones.parse(broken.toString()) }
    }
}