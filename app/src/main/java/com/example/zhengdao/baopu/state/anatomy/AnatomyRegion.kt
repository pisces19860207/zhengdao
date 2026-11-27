package com.example.zhengdao.baopu.state.anatomy

internal data class Morphology(
    val shapeRepresentation: Resolution<String>,
    val profile: Resolution<String>,
    val envelope: Resolution<String>,
    val surface: Resolution<String>,
)

internal data class RegionState(
    val readings: Map<String, String>,
)

internal data class Provenance(
    val sourceType: String,
    val sourceId: String,
    val confidence: Double,
    val completeness: Completeness,
) {
    init {
        require(sourceType.isNotBlank()) { "provenance.sourceType 不许为空" }
        require(sourceId.isNotBlank()) { "provenance.sourceId 不许为空" }
        require(confidence in 0.0..1.0) { "provenance.confidence 须在 0..1" }
    }
}

internal data class AnatomicalRegionModel(
    val schemaVersion: String,
    val regionId: RegionId,
    val regionType: RegionType,
    val parentRegionId: Resolution<RegionId>,
    val side: Side,
    val anatomicalFrame: Resolution<AnatomicalFrame>,
    val morphology: Morphology,
    val topology: TopologyRelations,
    val state: Resolution<RegionState>,
    val constraints: Resolution<List<ConstraintDescriptor>>,
    val provenance: Provenance,
    val validation: ValidationReport,
) {
    init {
        require(schemaVersion == ANATOMY_SCHEMA_VERSION) { "不支持的 schemaVersion" }
        require(
            regionType.key != PERINEAL_EXTENSION_SLOT.key ||
                PERINEAL_EXTENSION_SLOT.enabledType == regionType,
        ) { "会阴扩展位本批未启用" }
        val completenessFindings = completenessValidation(provenance.completeness).findings
        require(validation.findings.containsAll(completenessFindings)) {
            "validation 必须包含 provenance.completeness 的校验结论"
        }
    }
}

internal typealias AnatomyRegion = AnatomicalRegionModel
