// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.state.zone

import com.example.zhengdao.baopu.state.prepuce.AreaLevel
import com.example.zhengdao.baopu.state.prepuce.CoverState
import com.example.zhengdao.baopu.state.prepuce.SkinAmount

internal data class ZoneRef(
    val id: String,
    val name: String,
    val latin: String,
    val aliases: List<String>,
    val observable: List<String>,
    val commonErrors: List<String>,
)

internal data class ShapeState(
    val radius: Int?,
    val curvature: Int?,
    val corona: Int?,
    /** 皱缩档 0–3，对应 730 的 RUGOSITY 维。 */
    val rugosity: Int?,
)

internal data class PoseState(
    val displace: Int?,
    val flatten: Int?,
    val hang: Int?,
    val cling: Int?,
)

internal data class SurfaceState(
    val wetFilm: Int?,
    val colorShift: Int?,
    val vesselVisible: Int?,
) {
    fun names(): List<String> = listOf("wetFilm", "colorShift", "vesselVisible")
}

internal data class EventState(
    val justHit: Boolean?,
    val cremasteric: Boolean?,
    val recovery: Int?,
)

internal data class PrepuceReferences(
    val cover: CoverState,
    val area: AreaLevel,
    val skin: SkinAmount,
)

internal data class ZoneState(
    val zone: ZoneRef,
    val shape: ShapeState,
    val pose: PoseState,
    val surface: SurfaceState,
    val event: EventState,
    val prepuce: PrepuceReferences?,
    val pubicStyle: Nothing? = null,
    val evidence: List<ZoneEvidence>,
) {
    init {
        validate()
    }

    fun values(): Map<String, Any?> = linkedMapOf(
        "zone" to zone.id,
        "shape.radius" to shape.radius,
        "shape.curvature" to shape.curvature,
        "shape.corona" to shape.corona,
        "shape.rugosity" to shape.rugosity,
        "pose.displace" to pose.displace,
        "pose.flatten" to pose.flatten,
        "pose.hang" to pose.hang,
        "pose.cling" to pose.cling,
        "surface.wetFilm" to surface.wetFilm,
        "surface.colorShift" to surface.colorShift,
        "surface.vesselVisible" to surface.vesselVisible,
        "event.justHit" to event.justHit,
        "event.cremasteric" to event.cremasteric,
        "event.recovery" to event.recovery,
        "prepuce.cover" to prepuce?.cover,
        "prepuce.area" to prepuce?.area,
        "prepuce.skin" to prepuce?.skin,
        "pubicStyle" to pubicStyle,
    )

    fun intValues(): Map<String, Int> = values().filterValues { it is Int }.mapValues { it.value as Int }

    fun evidenceFor(dim: String): ZoneEvidence =
        evidence.firstOrNull { it.dim == dim } ?: throw IllegalArgumentException("缺 evidence：$dim")

    private fun validate() {
        val dims = evidence.map { it.dim }
        require(dims.size == ZoneDimensions.ALL.size && dims.toSet() == ZoneDimensions.ALL.toSet()) {
            "evidence 必须恰好覆盖 19 维且不重复"
        }
        for ((dim, value) in values()) {
            val record = evidenceFor(dim)
            require((value == null) == (record.absent != null)) { "$dim 的 null 与 absent 必须互为充要" }
        }
        require(zonesInRange())
        require(eventConsistent())
        require(prepuceConsistent())
        val pubic = evidenceFor("pubicStyle").absent
        require(pubStyleMissing(pubic)) { "pubicStyle 必须 absent(pending=723, reason=入口未核)" }
    }

    private fun zonesInRange(): Boolean = gradesInRange(
        shape.radius to 3,
        shape.curvature to 2,
        shape.corona to 2,
        shape.rugosity to 3,
        pose.displace to 3,
        pose.flatten to 3,
        pose.hang to 3,
        pose.cling to 3,
        surface.wetFilm to 3,
        surface.colorShift to 3,
        surface.vesselVisible to 2,
        event.recovery to 3,
    )

    private fun eventConsistent(): Boolean {
        val active = listOf(event.justHit, event.cremasteric, event.recovery)
        require(active.all { it == null } || active.all { it != null }) { "event 三字段必须同有或同缺" }
        return true
    }

    private fun prepuceConsistent(): Boolean {
        val present = listOf(prepuce?.cover, prepuce?.area, prepuce?.skin)
        require(present.all { it == null } || present.all { it != null }) { "prepuce 三字段必须同有或同缺" }
        return true
    }

    private fun pubStyleMissing(absent: Absent?): Boolean =
        absent?.pending == "723" && absent.reason == "入口未核"

    companion object {
        private fun gradesInRange(vararg fields: Pair<Int?, Int>): Boolean =
            fields.all { (value, max) -> value == null || value in 0..max }
    }
}
