package com.example.zhengdao.baopu.state.anatomy

internal enum class ValidationLevel {
    PASS,
    WARN,
    INVALID,
}

internal data class ValidationFinding(
    val level: ValidationLevel,
    val rule: String,
    val detail: String,
)

internal data class ValidationReport(
    val findings: List<ValidationFinding>,
)

internal data class StructuralAbsence(
    val subject: String,
    val side: Side,
    val reason: String,
)

internal data class Unobserved(
    val subject: String,
    val side: Side,
    val reason: String,
)

internal class Completeness(
    structuralAbsences: List<StructuralAbsence>,
    unobserved: List<Unobserved>,
) {
    val structuralAbsences: List<StructuralAbsence> = structuralAbsences.toList()
    val unobserved: List<Unobserved> = unobserved.toList()

    init {
        val unobservedKeys = unobserved.map { it.subject to it.side }.toSet()
        require(structuralAbsences.none { (it.subject to it.side) in unobservedKeys }) {
            "同一对象同一侧不许同时标成结构缺失与未观测"
        }
    }

    override fun toString(): String =
        "Completeness(structuralAbsences=$structuralAbsences, unobserved=$unobserved)"
}

internal data class ProxyGeometryOverlap(
    val observed: Resolution<Boolean>,
)

internal data class SemanticConnection(
    val connected: Resolution<Boolean>,
)

internal fun completenessValidation(completeness: Completeness): ValidationReport {
    val findings = buildList {
        if (completeness.structuralAbsences.isNotEmpty()) {
            add(
                ValidationFinding(
                    ValidationLevel.PASS,
                    "completeness.structural_absence",
                    "真不存在：${completeness.structuralAbsences.map { it.subject to it.side }}",
                ),
            )
        }
        if (completeness.unobserved.isNotEmpty()) {
            add(
                ValidationFinding(
                    ValidationLevel.WARN,
                    "completeness.unobserved",
                    "存在但无数据：${completeness.unobserved.map { it.subject to it.side }}",
                ),
            )
        }
        if (completeness.structuralAbsences.isEmpty() && completeness.unobserved.isEmpty()) {
            add(ValidationFinding(ValidationLevel.PASS, "completeness.observed", "结构与观测均齐"))
        }
    }
    return ValidationReport(findings)
}

internal fun validateProxyOverlap(
    overlap: ProxyGeometryOverlap,
    connection: SemanticConnection,
): ValidationFinding {
    return when (val observed = overlap.observed) {
        is Resolution.Absent ->
            ValidationFinding(ValidationLevel.WARN, "proxy.overlap_unobserved", "代理几何重叠未观测")
        is Resolution.Present -> when {
            !observed.value ->
                ValidationFinding(ValidationLevel.PASS, "proxy.no_overlap", "已观测到无代理几何重叠")
            else -> when (val connected = connection.connected) {
                is Resolution.Absent ->
                    ValidationFinding(ValidationLevel.WARN, "proxy.semantic_connection_unobserved", "语义连接未观测")
                is Resolution.Present -> if (connected.value) {
                    ValidationFinding(ValidationLevel.WARN, "proxy.overlap_with_semantic_connection", "代理几何重叠但有语义连接")
                } else {
                    ValidationFinding(ValidationLevel.INVALID, "proxy.overlap_without_semantic_connection", "代理几何重叠且无语义连接")
                }
            }
        }
    }
}
