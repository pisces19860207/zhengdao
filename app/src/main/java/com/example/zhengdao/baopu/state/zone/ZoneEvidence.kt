// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.state.zone

internal data class Absent(
    val pending: String? = null,
    val reason: String,
) {
    init {
        require(reason.isNotBlank()) { "缺席理由不得为空" }
        require(pending == null || pending.isNotBlank()) { "pending 不得为空串" }
    }
}

internal data class ZoneEvidence(
    val dim: String,
    val from: String,
    val why: String,
    val absent: Absent? = null,
) {
    init {
        require(dim in ZoneDimensions.ALL) { "未知 evidence 维度：$dim" }
        require(from.isNotBlank()) { "$dim 缺来源" }
        require(why.isNotBlank()) { "$dim 缺理由" }
    }
}

internal object ZoneDimensions {
    val ALL = listOf(
        "zone",
        "shape.radius",
        "shape.curvature",
        "shape.corona",
        "shape.rugosity",
        "pose.displace",
        "pose.flatten",
        "pose.hang",
        "pose.cling",
        "surface.wetFilm",
        "surface.colorShift",
        "surface.vesselVisible",
        "event.justHit",
        "event.cremasteric",
        "event.recovery",
        "prepuce.cover",
        "prepuce.area",
        "prepuce.skin",
        "pubicStyle",
    )
}

internal fun present(dim: String, from: String, why: String): ZoneEvidence =
    ZoneEvidence(dim, from, why)

internal fun pending(dim: String, source: String, pending: String, why: String): ZoneEvidence =
    ZoneEvidence(dim, source, why, Absent(pending, why))

internal fun notApplicable(dim: String, from: String, why: String): ZoneEvidence =
    ZoneEvidence(dim, from, why, Absent(reason = "not_applicable"))
