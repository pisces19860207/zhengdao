package com.example.zhengdao.baopu.state.anatomy

import com.example.zhengdao.baopu.state.subzone.SubzoneId
import com.example.zhengdao.baopu.state.subzone.SubzoneRefs
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExistingAnatomyMappingTest {
    @Test
    fun `十四个子区code与775冻结账逐字相同`() {
        val actual = ExistingAnatomyMapping.entries.map { it.subzoneId.code }.toSet()
        println("OUTPUT=mapping $ExistingAnatomyMapping")
        println("OUTPUT=subzones $actual")

        assertEquals(SubzoneRefs.Codes, actual)
        assertEquals(14, SubzoneId.entries.size)
    }

    @Test
    fun `十三个区与660真源逐字相同且五区无子区仍登记`() {
        val actual = valueOf(ExistingAnatomyMapping.zoneIdsForRegion(RegionId("genital"))).map { it.value }
        val sourceIds = Regex(""""zone_id"\s*:\s*"([^"]+)"""").findAll(jsonSource()).map { it.groupValues[1] }.toList()
        println("OUTPUT=zones $actual")
        println("OUTPUT=source $sourceIds")

        assertEquals(sourceIds, actual)
        assertEquals(13, actual.size)
        val noSubzone = listOf("raphe", "scrotum", "testis", "cord", "mons")
        for (zone in noSubzone) {
            assertTrue(valueOf(ExistingAnatomyMapping.forZone(ZoneId(zone))).isEmpty())
            assertEquals(listOf(RegionId("genital")), valueOf(ExistingAnatomyMapping.regionsForZone(ZoneId(zone))))
        }
    }

    @Test
    fun `每个子区宿主都与775枚举显式zoneId一致`() {
        for (subzone in SubzoneId.entries) {
            val mapped = valueOf(ExistingAnatomyMapping.forSubzone(subzone))
            assertEquals(subzone.zoneId, mapped.zoneId.value)
            assertEquals(RegionId("genital"), mapped.regionId)
        }
    }

    private fun <T> valueOf(result: Resolution<T>): T = when (result) {
        is Resolution.Present -> result.value
        is Resolution.Absent -> throw AssertionError("期望 Present，实际 $result")
    }

    private fun jsonSource(): String {
        val cwd = Path.of(System.getProperty("user.dir"))
        val root = generateSequence(cwd) { it.parent }.firstOrNull { it.resolve("docs/facts/anatomy-zones.json").toFile().isFile }
        requireNotNull(root) { "找不到 anatomy-zones.json" }
        return Files.readString(root.resolve("docs/facts/anatomy-zones.json"))
    }
}
