package com.example.zhengdao.baopu.state.anatomy

import com.example.zhengdao.baopu.state.subzone.SubzoneId

private val GENITAL_REGION = RegionId("genital")

internal val ExistingAnatomyMapping: AnatomyMapping = AnatomyMapping.of(
    entries = listOf(
        AnatomyMappingEntry(GENITAL_REGION, SubzoneId.APEX, ZoneId("glans")),
        AnatomyMappingEntry(GENITAL_REGION, SubzoneId.GLANS_BODY, ZoneId("glans")),
        AnatomyMappingEntry(GENITAL_REGION, SubzoneId.GLANS_CROWN, ZoneId("corona")),
        AnatomyMappingEntry(GENITAL_REGION, SubzoneId.SULCUS, ZoneId("sulcus")),
        AnatomyMappingEntry(GENITAL_REGION, SubzoneId.FRENULUM, ZoneId("frenulum")),
        AnatomyMappingEntry(GENITAL_REGION, SubzoneId.SHAFT_PROXIMAL, ZoneId("shaft")),
        AnatomyMappingEntry(GENITAL_REGION, SubzoneId.SHAFT_MID, ZoneId("shaft")),
        AnatomyMappingEntry(GENITAL_REGION, SubzoneId.SHAFT_DISTAL, ZoneId("shaft")),
        AnatomyMappingEntry(GENITAL_REGION, SubzoneId.RADIX, ZoneId("radix")),
        AnatomyMappingEntry(GENITAL_REGION, SubzoneId.MEATUS_RIM, ZoneId("meatus")),
        AnatomyMappingEntry(GENITAL_REGION, SubzoneId.MEATUS_SLIT, ZoneId("meatus")),
        AnatomyMappingEntry(GENITAL_REGION, SubzoneId.PREPUCE_INNER, ZoneId("prepuce")),
        AnatomyMappingEntry(GENITAL_REGION, SubzoneId.PREPUCE_OUTER, ZoneId("prepuce")),
        AnatomyMappingEntry(GENITAL_REGION, SubzoneId.PREPUCE_ORIFICE, ZoneId("prepuce")),
    ),
    regionZones = listOf(
        RegionZoneLink(GENITAL_REGION, ZoneId("radix")),
        RegionZoneLink(GENITAL_REGION, ZoneId("shaft")),
        RegionZoneLink(GENITAL_REGION, ZoneId("raphe")),
        RegionZoneLink(GENITAL_REGION, ZoneId("glans")),
        RegionZoneLink(GENITAL_REGION, ZoneId("corona")),
        RegionZoneLink(GENITAL_REGION, ZoneId("sulcus")),
        RegionZoneLink(GENITAL_REGION, ZoneId("frenulum")),
        RegionZoneLink(GENITAL_REGION, ZoneId("meatus")),
        RegionZoneLink(GENITAL_REGION, ZoneId("prepuce")),
        RegionZoneLink(GENITAL_REGION, ZoneId("scrotum")),
        RegionZoneLink(GENITAL_REGION, ZoneId("testis")),
        RegionZoneLink(GENITAL_REGION, ZoneId("cord")),
        RegionZoneLink(GENITAL_REGION, ZoneId("mons")),
    ),
)
