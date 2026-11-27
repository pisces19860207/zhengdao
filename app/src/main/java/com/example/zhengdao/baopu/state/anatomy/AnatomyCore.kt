// GZAG · 抱朴 · :core · 第918批（2026-11-27）
// 区域树不承担几何计算。
// 拓扑 ≠ 碰撞。
// 形态 ≠ 状态。
// 无消费方参数本批不接收；后续只可留 extensions/experimental，RegionSpec 拒绝其进入主 schema。

package com.example.zhengdao.baopu.state.anatomy

internal const val ANATOMY_SCHEMA_VERSION: String = "anatomy.schema.v1"
internal const val ANATOMY_REGION_CATALOG_VERSION: String = "anatomy.catalog.v0"

@JvmInline
internal value class RegionId(val value: String) {
    init {
        require(value.isNotBlank()) { "regionId 不许为空" }
    }
}

@JvmInline
internal value class ZoneId(val value: String) {
    init {
        require(value.isNotBlank()) { "zoneId 不许为空" }
    }
}

@JvmInline
internal value class RegionType(val key: String) {
    init {
        require(key.isNotBlank()) { "regionType 不许为空" }
    }
}

internal data class RegionTypeExtensionSlot(
    val key: String,
    val enabledType: RegionType?,
) {
    init {
        require(key.isNotBlank()) { "扩展位 key 不许为空" }
        require(enabledType == null || enabledType.key == key) { "扩展位启用类型必须与 key 同名" }
    }
}

internal val PERINEAL_EXTENSION_SLOT: RegionTypeExtensionSlot =
    RegionTypeExtensionSlot("perineal", null)

internal enum class Side {
    LEFT,
    RIGHT,
    MIDLINE,
}

internal enum class Capability {
    SHAPE,
    FRAME,
    TOPOLOGY,
    SURFACE,
    INTERIOR,
    MOTION,
    SANITIZE,
}

internal interface RegionCapability {
    val capability: Capability
}

internal enum class AbsenceReason {
    UNKNOWN_REGION,
    UNKNOWN_SUBZONE,
    UNKNOWN_ZONE,
    UNDECLARED_PARAMETER,
    NO_CONSUMER,
    PARAMETER_VALUE_MISSING,
    NO_PARENT_REGION,
    NOT_OBSERVED,
    RESERVED_REGION_TYPE,
    SCHEMA_VERSION_MISMATCH,
    CATALOG_VERSION_MISMATCH,
    CATALOG_VERSION_UNCHANGED,
    CATALOG_VERSION_INVALID,
    CATALOG_VERSION_REGRESSED,
    DUPLICATE_REGION,
    SUBZONE_REGION_MISMATCH,
}

internal sealed interface Resolution<out T> {
    data class Present<out T>(val value: T) : Resolution<T>

    data class Absent(
        val subject: String,
        val reason: AbsenceReason,
    ) : Resolution<Nothing>
}
