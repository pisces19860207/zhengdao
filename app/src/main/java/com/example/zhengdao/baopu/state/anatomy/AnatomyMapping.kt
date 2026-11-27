package com.example.zhengdao.baopu.state.anatomy

import com.example.zhengdao.baopu.state.subzone.SubzoneId

internal data class AnatomyMappingEntry(
    val regionId: RegionId,
    val subzoneId: SubzoneId,
    val zoneId: ZoneId,
)

internal data class RegionZoneLink(
    val regionId: RegionId,
    val zoneId: ZoneId,
)

internal class AnatomyMapping private constructor(
    entries: List<AnatomyMappingEntry>,
    regionZones: List<RegionZoneLink>,
) {
    val entries: List<AnatomyMappingEntry> = entries.toList()
    val regionZones: List<RegionZoneLink> = regionZones.toList()

    init {
        require(regionZones.isNotEmpty()) { "区域与区的显式映射不许为空" }
        require(regionZones.distinct().size == regionZones.size) { "区域与区只许有一条显式映射" }
        require(entries.map { it.subzoneId }.distinct().size == entries.size) {
            "每个 subzoneId 只许有一条显式映射"
        }
        require(entries.all { entry -> RegionZoneLink(entry.regionId, entry.zoneId) in regionZones }) {
            "子区映射必须落在已登记的区域与区映射上"
        }
    }

    fun forRegion(regionId: RegionId): Resolution<List<AnatomyMappingEntry>> {
        if (regionZones.none { it.regionId == regionId }) {
            return Resolution.Absent(regionId.value, AbsenceReason.UNKNOWN_REGION)
        }
        return Resolution.Present(entries.filter { it.regionId == regionId })
    }

    fun zoneIdsForRegion(regionId: RegionId): Resolution<List<ZoneId>> {
        val matches = regionZones.filter { it.regionId == regionId }.map { it.zoneId }
        return if (matches.isEmpty()) {
            Resolution.Absent(regionId.value, AbsenceReason.UNKNOWN_REGION)
        } else {
            Resolution.Present(matches)
        }
    }

    fun forSubzone(subzoneId: SubzoneId): Resolution<AnatomyMappingEntry> {
        val match = entries.firstOrNull { it.subzoneId == subzoneId }
        return if (match == null) {
            Resolution.Absent(subzoneId.code, AbsenceReason.UNKNOWN_SUBZONE)
        } else {
            Resolution.Present(match)
        }
    }

    fun forZone(zoneId: ZoneId): Resolution<List<AnatomyMappingEntry>> {
        if (regionZones.none { it.zoneId == zoneId }) {
            return Resolution.Absent(zoneId.value, AbsenceReason.UNKNOWN_ZONE)
        }
        return Resolution.Present(entries.filter { it.zoneId == zoneId })
    }

    fun regionForSubzone(subzoneId: SubzoneId): Resolution<RegionId> {
        return when (val entry = forSubzone(subzoneId)) {
            is Resolution.Present -> Resolution.Present(entry.value.regionId)
            is Resolution.Absent -> entry
        }
    }

    fun regionsForZone(zoneId: ZoneId): Resolution<List<RegionId>> {
        val matches = regionZones.filter { it.zoneId == zoneId }.map { it.regionId }.distinct()
        return if (matches.isEmpty()) {
            Resolution.Absent(zoneId.value, AbsenceReason.UNKNOWN_ZONE)
        } else {
            Resolution.Present(matches)
        }
    }

    override fun toString(): String = "AnatomyMapping(entries=$entries, regionZones=$regionZones)"

    companion object {
        fun of(
            entries: List<AnatomyMappingEntry>,
            regionZones: List<RegionZoneLink>,
        ): AnatomyMapping = AnatomyMapping(entries, regionZones)
    }
}
