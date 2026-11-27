package com.example.zhengdao.baopu.state.anatomy

import com.example.zhengdao.baopu.state.subzone.SubzoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnatomyMappingTest {
    @Test
    fun `映射语义不依赖登记顺序`() {
        val region = RegionId("example")
        val entries = listOf(
            entry(region, SubzoneId.APEX, "glans"),
            entry(region, SubzoneId.GLANS_BODY, "glans"),
            entry(region, SubzoneId.RADIX, "radix"),
        )
        val links = listOf(RegionZoneLink(region, ZoneId("glans")), RegionZoneLink(region, ZoneId("radix")))
        val forward = AnatomyMapping.of(entries, links)
        val reversed = AnatomyMapping.of(entries.reversed(), links.reversed())
        println("OUTPUT=forward ${forward.forRegion(region)}")
        println("OUTPUT=reversed ${reversed.forRegion(region)}")

        assertEquals(forward.entries.toSet(), reversed.entries.toSet())
        assertEquals(valueOf(forward.forRegion(region)).toSet(), valueOf(reversed.forRegion(region)).toSet())
        assertEquals("glans", valueOf(reversed.forSubzone(SubzoneId.APEX)).zoneId.value)
        assertEquals("example", valueOf(forward.regionForSubzone(SubzoneId.GLANS_BODY)).value)
        assertEquals("glans", valueOf(forward.forSubzone(SubzoneId.GLANS_BODY)).zoneId.value)
        assertEquals(listOf("radix"), valueOf(forward.forZone(ZoneId("radix"))).map { it.zoneId.value })
    }

    @Test
    fun `已知区可没有子区而未知区必须absent`() {
        val region = RegionId("example")
        val mapping = AnatomyMapping.of(
            emptyList(),
            listOf(RegionZoneLink(region, ZoneId("scrotum"))),
        )

        assertEquals(emptyList<AnatomyMappingEntry>(), valueOf(mapping.forRegion(region)))
        assertEquals(emptyList<AnatomyMappingEntry>(), valueOf(mapping.forZone(ZoneId("scrotum"))))
        assertEquals(AbsenceReason.UNKNOWN_ZONE, absentOf(mapping.forZone(ZoneId("unknown"))).reason)
        assertEquals(AbsenceReason.UNKNOWN_SUBZONE, absentOf(mapping.forSubzone(SubzoneId.APEX)).reason)
    }

    @Test
    fun `重复子区与悬空宿主映射被拒`() {
        val region = RegionId("example")
        val duplicate = listOf(
            entry(region, SubzoneId.APEX, "glans"),
            entry(region, SubzoneId.APEX, "corona"),
        )
        val thrown = runCatching { AnatomyMapping.of(duplicate, listOf(RegionZoneLink(region, ZoneId("glans")))) }.exceptionOrNull()
        val orphan = listOf(entry(region, SubzoneId.APEX, "corona"))
        val orphanThrown = runCatching {
            AnatomyMapping.of(orphan, listOf(RegionZoneLink(region, ZoneId("glans"))))
        }.exceptionOrNull()
        println("OUTPUT=duplicate $thrown")
        println("OUTPUT=orphan $orphanThrown")

        assertTrue(thrown is IllegalArgumentException)
        assertTrue(orphanThrown is IllegalArgumentException)
    }

    @Test
    fun `映射快照调用方集合`() {
        val region = RegionId("example")
        val entries = mutableListOf(entry(region, SubzoneId.APEX, "glans"))
        val links = mutableListOf(RegionZoneLink(region, ZoneId("glans")))
        val mapping = AnatomyMapping.of(entries, links)
        entries.clear()
        links.clear()
        println("OUTPUT=mapping-snapshot $mapping")

        assertEquals("glans", valueOf(mapping.forSubzone(SubzoneId.APEX)).zoneId.value)
        assertEquals(listOf(region), valueOf(mapping.regionsForZone(ZoneId("glans"))))
    }

    private fun entry(region: RegionId, subzone: SubzoneId, zone: String): AnatomyMappingEntry =
        AnatomyMappingEntry(region, subzone, ZoneId(zone))

    private fun <T> valueOf(result: Resolution<T>): T = when (result) {
        is Resolution.Present -> result.value
        is Resolution.Absent -> throw AssertionError("期望 Present，实际 $result")
    }

    private fun absentOf(result: Resolution<*>): Resolution.Absent = when (result) {
        is Resolution.Absent -> result
        is Resolution.Present -> throw AssertionError("期望 Absent，实际 $result")
    }
}
