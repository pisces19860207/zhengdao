// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.state.zone

import com.example.zhengdao.baopu.body.AnatomyZone
import com.example.zhengdao.baopu.body.AnatomyZones
import com.example.zhengdao.baopu.state.form.BodyState
import com.example.zhengdao.baopu.state.form.ZoneTable
import com.example.zhengdao.baopu.state.geometry.Environment
import com.example.zhengdao.baopu.state.impact.ImpactResult
import com.example.zhengdao.baopu.state.thermo.ThermoReading

internal class ZoneStateQuery private constructor(private val states: Map<String, ZoneState>) {

    fun getZoneState(zoneId: String): ZoneState =
        states[zoneId] ?: throw IllegalArgumentException("未知区或非区级 id：$zoneId")

    companion object {
        fun of(
            body: BodyState,
            anatomy: List<AnatomyZone>,
            zones: ZoneTable,
            environment: Environment,
            thermo: ThermoReading?,
            impact: ImpactResult?,
            prepuce: PrepuceReferences?,
        ): ZoneStateQuery {
            require(zones.zonesRef == AnatomyZones.REL_PATH) { "731 分区表必须引用 669 词表" }
            val ids = anatomy.map { it.zoneId }
            require(ids.size == 13 && ids.toSet().size == ids.size) { "731 只接受 13 个唯一区 id" }
            require(ids.toSet() == zones.zones.map { it.zoneId }.toSet()) { "669 词表与 660 分区表必须一一对应" }
            val adapters = ZoneAdapters(body, anatomy, zones, environment, thermo, impact, prepuce)
            val states = anatomy.associate { it.zoneId to adapters.stateOf(it) }
            return ZoneStateQuery(states)
        }
    }
}
