// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.state.zone

import com.example.zhengdao.baopu.body.AnatomyZone
import com.example.zhengdao.baopu.state.cavity.CavityMap
import com.example.zhengdao.baopu.state.cavity.CavityTolerances
import com.example.zhengdao.baopu.state.cavity.GravityContact
import com.example.zhengdao.baopu.state.cavity.ProxySimplify
import com.example.zhengdao.baopu.state.form.BodyState
import com.example.zhengdao.baopu.state.form.ZoneSpec
import com.example.zhengdao.baopu.state.form.ZoneTable
import com.example.zhengdao.baopu.state.geometry.Environment
import com.example.zhengdao.baopu.state.impact.ImpactResult
import com.example.zhengdao.baopu.state.thermo.ThermoReading
import kotlin.math.ceil
import kotlin.math.floor

internal class ZoneAdapters(
    private val body: BodyState,
    anatomy: List<AnatomyZone>,
    private val zones: ZoneTable,
    environment: Environment,
    private val thermo: ThermoReading?,
    private val impact: ImpactResult?,
    private val prepuce: PrepuceReferences?,
) {
    private val geometry = GeometryReadings.of(body, anatomy, environment)
    private val target = targetOf()

    fun stateOf(zone: AnatomyZone): ZoneState {
        val spec = zones.zone(zone.zoneId)
        val shape = shapeOf(zone, spec)
        val pose = poseOf(zone, spec)
        val surface = surfaceOf()
        val event = eventOf(zone.zoneId)
        return ZoneState(
            zone = ZoneRef(
                zone.zoneId, zone.name, zone.latin, zone.aliases.toList(),
                zone.observable.toList(), zone.commonErrors.toList(),
            ),
            shape = shape,
            pose = pose,
            surface = surface,
            event = event,
            prepuce = prepuceOf(zone.zoneId),
            evidence = evidenceOf(zone, spec),
        )
    }

    private fun shapeOf(zone: AnatomyZone, spec: ZoneSpec): ShapeState {
        val radius = geometry.radii.getValue(zone.zoneId)
        val radiusGrade = floor(radius / body.genital.refRadiusMm * 4).toInt().coerceIn(0, 3)
        val curvature = if (spec.lengthRef == "length" && spec.axial.low >= 0.0) {
            floor(body.genital.curvature * 3).toInt().coerceIn(0, 2)
        } else {
            null
        }
        return ShapeState(radiusGrade, curvature, coronaOf(zone.zoneId, spec), thermo?.rugosity)
    }

    private fun coronaOf(zoneId: String, spec: ZoneSpec): Int? = when (zoneId) {
        "corona" -> if (spec.form == "ridge") 2 else null
        "sulcus" -> if (narrowerThanShaft(zoneId)) 1 else null
        else -> null
    }

    private fun narrowerThanShaft(zoneId: String): Boolean {
        val shaft = geometry.radii.getValue("shaft")
        return geometry.radii.getValue(zoneId) < shaft
    }

    private fun poseOf(zone: AnatomyZone, spec: ZoneSpec): PoseState {
        val gravity = gravityGrade(zone.zoneId)
        val impactGrade = if (target?.zoneId == zone.zoneId) impact?.displace?.level ?: 0 else 0
        val displace = maxOf(gravity, impactGrade)
        val hang = if (spec.tempLinked) thermo?.hang else null
        val cling = clingOf(zone.zoneId)
        return PoseState(displace, flattenOf(zone.zoneId), hang, cling)
    }

    private fun clingOf(zoneId: String): Int? {
        val thermal = thermo?.cling
        val cavity = geometry.cling.getValue(zoneId)
        return when {
            thermal == null -> cavity
            cavity == null -> thermal
            else -> maxOf(thermal, cavity)
        }
    }

    private fun gravityGrade(zoneId: String): Int =
        ceil(geometry.gravity.getValue(zoneId) / 10.0).toInt().coerceIn(0, 3)

    private fun flattenOf(zoneId: String): Int? = when {
        target == null -> null
        target.perineum -> null
        target.zoneId == zoneId -> impact?.flatten?.level
        else -> 0
    }

    private fun surfaceOf(): SurfaceState = thermo?.surface?.let {
        SurfaceState(it.wetFilm, it.colorShift, it.vesselVisible)
    } ?: SurfaceState(null, null, null)

    private fun eventOf(zoneId: String): EventState = when {
        target == null || target.perineum -> EventState(null, null, null)
        target.zoneId == zoneId -> EventState(
            impact?.event?.justHit, impact?.event?.cremasteric, impact?.event?.recovery,
        )
        else -> EventState(false, false, 0)
    }

    private fun prepuceOf(zoneId: String): PrepuceReferences? = prepuce?.takeIf { zoneId == "prepuce" }

    private fun targetOf(): Target? {
        val site = impact?.site ?: return null
        val zoneId = site.zoneId.substringBefore('.')
        return Target(zoneId, zoneId == "perineum")
    }

    private fun evidenceOf(
        zone: AnatomyZone,
        spec: ZoneSpec,
    ): List<ZoneEvidence> = listOf(
        present("zone", "669", "区名与口径取自注入的 AnatomyZone"),
        radiusEvidence(zone.zoneId),
        curvatureEvidence(spec),
        coronaEvidence(zone.zoneId, spec),
        thermoEvidence("shape.rugosity", "730", "皱缩档对应 730 的 RUGOSITY 维"),
        displaceEvidence(zone.zoneId),
        flattenEvidence(zone.zoneId),
        hangEvidence(spec),
        clingEvidence(zone.zoneId),
        thermoEvidence("surface.wetFilm", "730", "湿膜档逐字引用 730 surface"),
        thermoEvidence("surface.colorShift", "730", "色变档逐字引用 730 surface"),
        thermoEvidence("surface.vesselVisible", "730", "血管可见档逐字引用 730 surface"),
        eventEvidence("event.justHit", zone.zoneId),
        eventEvidence("event.cremasteric", zone.zoneId),
        eventEvidence("event.recovery", zone.zoneId),
        prepuceEvidence("prepuce.cover", zone.zoneId, prepuce?.cover != null),
        prepuceEvidence("prepuce.area", zone.zoneId, prepuce?.area != null),
        prepuceEvidence("prepuce.skin", zone.zoneId, prepuce?.skin != null),
        pubicEvidence(),
    )

    private fun radiusEvidence(zoneId: String): ZoneEvidence = present(
        "shape.radius", "660", "$zoneId 多实例取最大半径高端，floor(r/R×4) 量化到 0–3",
    )

    private fun curvatureEvidence(spec: ZoneSpec): ZoneEvidence =
        if (spec.lengthRef == "length" && spec.axial.low >= 0.0) {
            present("shape.curvature", "660", "轴向区消费 genital.curvature，floor(c×3) 量化到 0–2")
        } else {
            notApplicable("shape.curvature", "660", "非 length 轴向区不适用曲率档")
        }

    private fun coronaEvidence(zoneId: String, spec: ZoneSpec): ZoneEvidence = when {
        zoneId == "corona" && spec.form == "ridge" -> present("shape.corona", "660", "660 form=ridge，冠沟明显档 2")
        zoneId == "sulcus" && narrowerThanShaft(zoneId) -> present("shape.corona", "660", "660 半径窄于柱身，冠沟明显档 1")
        else -> notApplicable("shape.corona", "660", "本区无冠沟明显口径")
    }

    private fun thermoEvidence(dim: String, source: String, why: String): ZoneEvidence =
        if (thermo == null) pending(dim, source, source, "730 读数缺席，不补默认零") else present(dim, source, why)

    private fun displaceEvidence(zoneId: String): ZoneEvidence {
        val gravity = gravityGrade(zoneId)
        val hit = target?.takeIf { !it.perineum && it.zoneId == zoneId }?.let { impact?.displace?.level ?: 0 } ?: 0
        return if (hit > gravity) {
            present("pose.displace", "727", "727 受击位移强于 686 重力位移，取较强档")
        } else {
            present("pose.displace", "686", "686 落定位移沿 −Y，每 10mm 一档；受击不更强时保留重力档")
        }
    }

    private fun flattenEvidence(zoneId: String): ZoneEvidence = when {
        target == null -> pending("pose.flatten", "727", "727", "727 读数缺席，不补默认零")
        target.perineum -> pending("pose.flatten", "877", "877", "会阴未定案")
        target.zoneId == zoneId -> present("pose.flatten", "727", "727 作用点 ${target.zoneId} 的压扁档")
        else -> present("pose.flatten", "727", "727 作用点 ${target.zoneId}；本区非目标区，压扁档 0")
    }

    private fun hangEvidence(spec: ZoneSpec): ZoneEvidence =
        if (!spec.tempLinked) notApplicable("pose.hang", "660", "非 tempLinked 区不适用垂度档")
        else thermoEvidence("pose.hang", "730", "730 HangGrade 垂度档 0–3")

    private fun clingEvidence(zoneId: String): ZoneEvidence {
        val thermal = thermo?.cling
        val cavity = geometry.cling.getValue(zoneId)
        if (thermal == null && cavity == null) {
            return pending(
                "pose.cling",
                "686/730",
                "686/730",
                "686 腔隙与 730 贴合读数均缺席，不补默认零",
            )
        }
        val from = when {
            thermal == null -> "686"
            cavity == null -> "730"
            cavity > thermal -> "686"
            thermal > cavity -> "730"
            cavity > 0 -> "686/730"
            else -> "730"
        }
        return present("pose.cling", from, "730 ClingGrade 与 686 腔隙贴合取最强；腔隙每 10mm 一档")
    }

    private fun eventEvidence(dim: String, zoneId: String): ZoneEvidence = when {
        target == null -> pending(dim, "727", "727", "727 读数缺席，不补默认零")
        target.perineum -> pending(dim, "877", "877", "会阴未定案")
        target.zoneId == zoneId -> if (dim == "event.cremasteric") {
            present(dim, "730/727", "730 提睾档经 727 event 透传到区级事件")
        } else {
            present(dim, "727", "727 作用点 ${target.zoneId} 的区级事件")
        }
        else -> present(dim, "727", "727 作用点 ${target.zoneId}；本区非目标区，显式 false/0")
    }

    private fun prepuceEvidence(dim: String, zoneId: String, hasValue: Boolean): ZoneEvidence = when {
        zoneId != "prepuce" -> notApplicable(dim, "804/831", "非包皮区不复制包皮引用")
        hasValue -> present(dim, "804/831", "逐字引用 804/831 的 CoverState/AreaLevel/SkinAmount")
        else -> pending(dim, "804/831", "804/831", "包皮引用缺席，不重定义档位")
    }

    private fun pubicEvidence(): ZoneEvidence = ZoneEvidence(
        "pubicStyle", "723", "入口未核", Absent("723", "入口未核"),
    )

    private data class Target(val zoneId: String, val perineum: Boolean)

    private data class GeometryReadings(
        val radii: Map<String, Double>,
        val gravity: Map<String, Int>,
        val cling: Map<String, Int?>,
    ) {
        companion object {
            fun of(body: BodyState, anatomy: List<AnatomyZone>, environment: Environment): GeometryReadings {
                val ids = anatomy.map { it.zoneId }.toSet()
                val radii = body.genital.zones.groupBy { it.zoneId }.mapValues { (_, instances) ->
                    instances.maxOf { it.radiusMm.high }
                }
                require(radii.keys == ids) { "660 形体区与注入词表不一致：${(ids - radii.keys) + (radii.keys - ids)}" }
                val proxies = ProxySimplify.fromBody(body.proxies)
                val gravity = GravityContact.settle(proxies, environment).groupBy { it.proxyId.substringBefore('.') }
                    .mapValues { (_, contacts) -> contacts.maxOf { it.dropMilli } }
                val gaps = CavityMap.of(proxies, anatomy)
                val cling = ids.associate { id ->
                    val grade = gaps.filter { id == it.zoneId || id == it.otherId }.maxOfOrNull {
                        3 - ceil(it.gapMilli / CavityTolerances.CAVITY_OPEN_MILLI.toDouble()).toInt().coerceIn(0, 3)
                    }
                    id to grade
                }
                return GeometryReadings(radii, ids.associateWith { gravity[it] ?: 0 }, cling)
            }
        }
    }
}
