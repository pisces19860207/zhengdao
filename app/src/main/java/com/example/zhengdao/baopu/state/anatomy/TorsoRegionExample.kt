package com.example.zhengdao.baopu.state.anatomy

internal object TorsoRegionExample {
    const val PROVENANCE_REFERENCE = "torso.provenance.reference"

    fun declare(): Resolution<RegionSpec> {
        val parameter = ParameterDeclaration(
            name = PROVENANCE_REFERENCE,
            consumers = setOf(ParameterConsumer.GENERATION_WITH_PROVENANCE),
        )
        return RegionSpec.declare(
            schemaVersion = ANATOMY_SCHEMA_VERSION,
            regionCatalogVersion = ANATOMY_REGION_CATALOG_VERSION,
            regionId = RegionId("torso"),
            regionType = RegionType("torso"),
            subzones = emptyList(),
            capabilities = setOf(Capability.SURFACE),
            parameters = listOf(parameter),
            extensionSlots = listOf(PERINEAL_EXTENSION_SLOT),
        )
    }

    fun parameterValue(provenanceReference: String): Resolution<String> {
        return when (val spec = declare()) {
            is Resolution.Present -> when (
                val admitted = spec.value.resolveParameters(
                    mapOf(PROVENANCE_REFERENCE to provenanceReference),
                )
            ) {
                is Resolution.Present -> admitted.value.read(PROVENANCE_REFERENCE)
                is Resolution.Absent -> admitted
            }
            is Resolution.Absent -> spec
        }
    }
}
