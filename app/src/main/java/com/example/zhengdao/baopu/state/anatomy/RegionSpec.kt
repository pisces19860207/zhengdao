package com.example.zhengdao.baopu.state.anatomy

internal enum class ParameterConsumer {
    GEOMETRY_KERNEL_QUERY,
    SNAPSHOT_834,
    EXTRACTION_VALIDATION_853,
    GENERATION_WITH_PROVENANCE,
    CONSTRAINT_ENGINE,
}

internal data class ParameterDeclaration(
    val name: String,
    val consumers: Set<ParameterConsumer>,
) {
    init {
        require(name.isNotBlank()) { "parameter name 不许为空" }
    }
}

internal class AdmittedParameters private constructor(
    val spec: RegionSpec,
    values: Map<String, String>,
) {
    private val values: Map<String, String> = values.toMap()

    fun read(name: String): Resolution<String> {
        val admitted = spec.parameter(name)
        if (admitted is Resolution.Absent) return admitted
        val value = values[name]
        return if (value == null) {
            Resolution.Absent(name, AbsenceReason.PARAMETER_VALUE_MISSING)
        } else {
            Resolution.Present(value)
        }
    }

    companion object {
        internal fun resolve(
            spec: RegionSpec,
            values: Map<String, String>,
        ): Resolution<AdmittedParameters> {
            val unknown = values.keys.firstOrNull { spec.parameter(it) is Resolution.Absent }
            if (unknown != null) {
                return Resolution.Absent(unknown, AbsenceReason.UNDECLARED_PARAMETER)
            }
            val missing = spec.parameters.firstOrNull { it.name !in values }
            if (missing != null) {
                return Resolution.Absent(missing.name, AbsenceReason.PARAMETER_VALUE_MISSING)
            }
            return Resolution.Present(AdmittedParameters(spec, values))
        }
    }
}

internal class RegionSpec private constructor(
    val schemaVersion: String,
    val regionCatalogVersion: String,
    val regionId: RegionId,
    val regionType: RegionType,
    subzones: List<Subzone>,
    capabilities: Set<Capability>,
    parameters: List<ParameterDeclaration>,
) {
    val subzones: List<Subzone> = subzones.toList()
    val capabilities: Set<Capability> = capabilities.toSet()
    val parameters: List<ParameterDeclaration> = parameters.map { it.copy(consumers = it.consumers.toSet()) }

    init {
        require(schemaVersion == ANATOMY_SCHEMA_VERSION) { "不支持的 schemaVersion" }
        require(regionCatalogVersion.isNotBlank()) { "regionCatalogVersion 不许为空" }
        require(parameters.all { it.consumers.isNotEmpty() }) { "主 schema 参数必须有消费方" }
        require(parameters.all { it.name.isNotBlank() }) { "parameter name 不许为空" }
        require(subzones.all { it.regionId == regionId }) { "子区必须属于本区域" }
        require(parameters.map { it.name }.distinct().size == parameters.size) { "参数名重复" }
        require(subzones.map { it.id }.distinct().size == subzones.size) { "子区重复" }
    }

    fun parameter(name: String): Resolution<ParameterDeclaration> {
        val admitted = parameters.firstOrNull { it.name == name }
        return if (admitted == null) {
            Resolution.Absent(name, AbsenceReason.UNDECLARED_PARAMETER)
        } else {
            Resolution.Present(admitted)
        }
    }

    fun resolveParameters(values: Map<String, String>): Resolution<AdmittedParameters> =
        AdmittedParameters.resolve(this, values)

    override fun toString(): String =
        "RegionSpec(schemaVersion=$schemaVersion, regionCatalogVersion=$regionCatalogVersion, regionId=$regionId, " +
            "regionType=$regionType, subzones=$subzones, capabilities=$capabilities, parameters=$parameters)"

    companion object {
        fun declare(
            schemaVersion: String,
            regionCatalogVersion: String,
            regionId: RegionId,
            regionType: RegionType,
            subzones: List<Subzone>,
            capabilities: Set<Capability>,
            parameters: List<ParameterDeclaration>,
            extensionSlots: List<RegionTypeExtensionSlot>,
        ): Resolution<RegionSpec> {
            if (schemaVersion != ANATOMY_SCHEMA_VERSION) {
                return Resolution.Absent(schemaVersion, AbsenceReason.SCHEMA_VERSION_MISMATCH)
            }
            require(regionCatalogVersion.isNotBlank()) { "regionCatalogVersion 不许为空" }
            val zeroConsumer = parameters.firstOrNull { it.consumers.isEmpty() }
            if (zeroConsumer != null) {
                return Resolution.Absent(zeroConsumer.name, AbsenceReason.NO_CONSUMER)
            }
            val slot = extensionSlots.firstOrNull { it.key == regionType.key }
            if (slot != null && slot.enabledType != regionType) {
                return Resolution.Absent(regionType.key, AbsenceReason.RESERVED_REGION_TYPE)
            }
            val foreignSubzone = subzones.firstOrNull { it.regionId != regionId }
            if (foreignSubzone != null) {
                return Resolution.Absent(foreignSubzone.id.code, AbsenceReason.SUBZONE_REGION_MISMATCH)
            }
            val conflictingHost = subzones.firstOrNull { subzone ->
                when (val mapped = ExistingAnatomyMapping.forSubzone(subzone.id)) {
                    is Resolution.Present -> mapped.value.regionId != subzone.regionId
                    is Resolution.Absent -> false
                }
            }
            if (conflictingHost != null) {
                return Resolution.Absent(conflictingHost.id.code, AbsenceReason.SUBZONE_REGION_MISMATCH)
            }
            return Resolution.Present(
                RegionSpec(
                    schemaVersion,
                    regionCatalogVersion,
                    regionId,
                    regionType,
                    subzones,
                    capabilities,
                    parameters,
                ),
            )
        }
    }
}
