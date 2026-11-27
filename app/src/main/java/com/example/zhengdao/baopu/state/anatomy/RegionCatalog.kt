package com.example.zhengdao.baopu.state.anatomy

private val CATALOG_VERSION_PATTERN = Regex("""anatomy\.catalog\.v(0|[1-9][0-9]*)""")

private fun catalogVersionNumber(version: String): Int? {
    val match = CATALOG_VERSION_PATTERN.matchEntire(version)
    if (match == null) return null
    val (number) = match.destructured
    return number.toIntOrNull()
}

internal class RegionCatalog private constructor(
    val schemaVersion: String,
    val regionCatalogVersion: String,
    regions: List<RegionSpec>,
    extensionSlots: List<RegionTypeExtensionSlot>,
) {
    val regions: List<RegionSpec> = regions.toList()
    val extensionSlots: List<RegionTypeExtensionSlot> =
        if (extensionSlots.any { it.key == PERINEAL_EXTENSION_SLOT.key }) {
            extensionSlots.toList()
        } else {
            (extensionSlots + PERINEAL_EXTENSION_SLOT).toList()
        }

    init {
        require(schemaVersion == ANATOMY_SCHEMA_VERSION) { "不支持的 schemaVersion" }
        require(regionCatalogVersion.isNotBlank()) { "regionCatalogVersion 不许为空" }
        require(regions.all { it.schemaVersion == schemaVersion }) { "目录内混有不同 schemaVersion" }
        require(regions.map { it.regionId }.distinct().size == regions.size) { "目录内 regionId 重复" }
        require(this.extensionSlots.all { it.key.isNotBlank() }) { "扩展位 key 不许为空" }
        require(this.extensionSlots.all { it.enabledType == null || it.enabledType.key == it.key }) {
            "扩展位启用类型必须与 key 同名"
        }
        require(this.extensionSlots.map { it.key }.distinct().size == this.extensionSlots.size) { "扩展位 key 重复" }
    }

    fun append(
        spec: RegionSpec,
        nextCatalogVersion: String,
    ): Resolution<RegionCatalog> = append(spec, nextCatalogVersion, allowSameCatalogVersion = false)

    private fun append(
        spec: RegionSpec,
        nextCatalogVersion: String,
        allowSameCatalogVersion: Boolean,
    ): Resolution<RegionCatalog> {
        val currentVersion = catalogVersionNumber(regionCatalogVersion)
        if (currentVersion == null) {
            return Resolution.Absent(regionCatalogVersion, AbsenceReason.CATALOG_VERSION_INVALID)
        }
        val nextVersion = catalogVersionNumber(nextCatalogVersion)
        if (nextVersion == null) {
            return Resolution.Absent(nextCatalogVersion, AbsenceReason.CATALOG_VERSION_INVALID)
        }
        if (spec.schemaVersion != schemaVersion) {
            return Resolution.Absent(spec.regionId.value, AbsenceReason.SCHEMA_VERSION_MISMATCH)
        }
        if (spec.regionCatalogVersion != regionCatalogVersion) {
            return Resolution.Absent(spec.regionCatalogVersion, AbsenceReason.CATALOG_VERSION_MISMATCH)
        }
        if (!allowSameCatalogVersion) {
            when {
                nextVersion < currentVersion ->
                    return Resolution.Absent(nextCatalogVersion, AbsenceReason.CATALOG_VERSION_REGRESSED)
                nextVersion == currentVersion ->
                    return Resolution.Absent(nextCatalogVersion, AbsenceReason.CATALOG_VERSION_UNCHANGED)
            }
        }
        if (regions.any { it.regionId == spec.regionId }) {
            return Resolution.Absent(spec.regionId.value, AbsenceReason.DUPLICATE_REGION)
        }
        val slot = extensionSlots.firstOrNull { it.key == spec.regionType.key }
        if (slot != null && slot.enabledType != spec.regionType) {
            return Resolution.Absent(spec.regionType.key, AbsenceReason.RESERVED_REGION_TYPE)
        }
        return Resolution.Present(
            RegionCatalog(
                schemaVersion,
                nextCatalogVersion,
                regions + spec,
                extensionSlots,
            ),
        )
    }

    override fun toString(): String =
        "RegionCatalog(schemaVersion=$schemaVersion, regionCatalogVersion=$regionCatalogVersion, " +
            "regions=$regions, extensionSlots=$extensionSlots)"

    companion object {
        fun create(
            schemaVersion: String,
            regionCatalogVersion: String,
            regions: List<RegionSpec>,
            extensionSlots: List<RegionTypeExtensionSlot>,
        ): Resolution<RegionCatalog> {
            if (schemaVersion != ANATOMY_SCHEMA_VERSION) {
                return Resolution.Absent(schemaVersion, AbsenceReason.SCHEMA_VERSION_MISMATCH)
            }
            if (catalogVersionNumber(regionCatalogVersion) == null) {
                return Resolution.Absent(regionCatalogVersion, AbsenceReason.CATALOG_VERSION_INVALID)
            }
            var catalog = RegionCatalog(schemaVersion, regionCatalogVersion, emptyList(), extensionSlots)
            for (spec in regions) {
                val appended = catalog.append(spec, regionCatalogVersion, allowSameCatalogVersion = true)
                catalog = when (appended) {
                    is Resolution.Present -> appended.value
                    is Resolution.Absent -> return appended
                }
            }
            return Resolution.Present(catalog)
        }
    }
}
