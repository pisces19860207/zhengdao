// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十四批 · 统一状态快照：只读装配（同 731 zoneNow 原则）。
// 本文件只做一件事：把既有包已经算好的产出，按 SnapshotSpec 的次序抄成一格一格。
// 不判定、不推算新档、不写任何包、不落盘、不读墙钟、不读随机。
package com.example.zhengdao.baopu.state.snapshot

import com.example.zhengdao.baopu.state.environment.SolarSolver
import com.example.zhengdao.baopu.state.physiology.FluidTrack
import com.example.zhengdao.baopu.state.stimulus.RhythmReading

/** 唯一装配出口。输入不变、输出逐字节确定；装配前后各包状态不变。 */
internal object SnapshotAssembler {

    fun assemble(input: SnapshotInput): Snapshot = Snapshot(
        version = SnapshotSpec.VERSION,
        tick = input.tick,
        sections = listOf(
            section(SnapshotSpec.SEC_BODY, body(input)),
            section(SnapshotSpec.SEC_TIME, time(input)),
            section(SnapshotSpec.SEC_SPACE, space(input)),
            section(SnapshotSpec.SEC_ENVIRONMENT, environment(input)),
            section(SnapshotSpec.SEC_LIGHT, light(input)),
            section(SnapshotSpec.SEC_FLUID, fluid(input)),
        ),
    )

    private fun section(id: String, fields: List<SnapshotField>): SnapshotSection =
        SnapshotSection(id, SnapshotSpec.fieldsOf(id).map { spec -> fields.first { it.id == spec.id } })

    // —— 一格：有值就 Given，没值就用规格表里写死的理由 Absent ——
    private fun cell(id: String, value: String?): SnapshotField =
        if (value.isNullOrEmpty()) SnapshotField(id, SnapshotSlot.Absent(SnapshotSpec.of(id).missing))
        else SnapshotField(id, SnapshotSlot.Given(value))

    private fun number(id: String, value: Double?): SnapshotField {
        if (value == null || !value.isFinite()) return cell(id, value?.let { "Infinity" })
        return SnapshotField(id, SnapshotSlot.Given(value.toString(), number = true))
    }

    private fun intNumber(id: String, value: Int?): SnapshotField =
        if (value == null) cell(id, null)
        else SnapshotField(id, SnapshotSlot.Given(value.toString(), number = true))

    // —— ① 人体逐区态 ——
    private fun body(input: SnapshotInput): List<SnapshotField> {
        val b = input.body.body
        val rhythm = input.body.rhythm
        return listOf(
            cell("body.spec.ok", b?.ok?.toString()),
            cell("body.spec.ratios", b?.ratiosInPopulationRange?.toString()),
            cell("body.spec.variants", b?.variants?.let { "已采样" }),
            cell("body.zones.now", null),
            intNumber("body.parts.count", input.body.parts?.size),
            cell("body.injury.grade", input.body.injury?.label),
            cell("body.pain.grade", input.body.pain?.label),
            cell("body.stimulus.rhythm", rhythm?.line()),
            cell("body.anchor.id", input.body.anchor?.id),
        )
    }

    // —— ② 时间：tick 由调用方给；昼夜/节气缺位一律 Absent ——
    private fun time(input: SnapshotInput): List<SnapshotField> {
        val t = input.time
        return listOf(
            SnapshotField("time.tick", SnapshotSlot.Given(input.tick.toString(), number = true)),
            intNumber("time.dayOfYear", t.dayOfYear),
            number("time.solarTime", t.solarTime?.hours),
            cell("time.solarTerm", null),
        )
    }

    // —— ③ 空间：位置/姿态/可达/容纳/腔隙/容量各取既有结论 ——
    private fun space(input: SnapshotInput): List<SnapshotField> {
        val s = input.space
        val cavityStates = s.cavities?.map { it.state.name }?.distinct()?.joinToString(",")
        return listOf(
            cell("space.position.zone", s.bearing?.zoneId),
            cell("space.pose.valid", s.pose?.valid?.toString()),
            intNumber("space.pose.violations", s.pose?.violations?.size),
            cell("space.reach.state", s.reach?.state?.name),
            cell("space.bearing", s.bearing?.bearing?.name),
            cell("space.distance", s.bearing?.distance?.name),
            cell("space.containment.state", s.containment?.state?.label),
            cell("space.containment.stance", s.containment?.stance?.label),
            cell("space.cavity.state", cavityStates),
            cell("space.capacity.verdict", s.capacity?.verdict?.label),
            cell("space.motion.now", null),
        )
    }

    // —— ④ 环境档：657 一次求解的八格 ——
    private fun environment(input: SnapshotInput): List<SnapshotField> {
        val e = input.environment.state
        return listOf(
            number("environment.sun.azimuth", e?.sun?.azimuthDeg),
            number("environment.sun.elevation", e?.sun?.elevationDeg),
            number("environment.sun.shadowRatio", e?.sun?.shadowRatio),
            cell("environment.sun.intensity", e?.sun?.intensity),
            cell("environment.wind.feels", e?.wind?.feels),
            cell("environment.humidity.evaporation", e?.humidity?.evaporation),
            intNumber("environment.sweat.grade", e?.sweat?.grade),
            cell("environment.sand.label", e?.sand?.label),
        )
    }

    // —— ⑤ 光影六元组（824 子段）：有生产承载的三格取 657，三格 + 两验收格 Absent ——
    private fun light(input: SnapshotInput): List<SnapshotField> {
        val e = input.environment.state
        val sun: SolarSolver.SunState? = e?.sun
        return listOf(
            cell("light.sourceType", null),
            cell("light.intensity", sun?.intensity),
            cell("light.colorTemp", null),
            number("light.direction", sun?.azimuthDeg),
            number("light.period", input.time.solarTime?.hours),
            cell("light.weather", null),
            cell("light.shadow.hardness", null),
            cell("light.material.response", null),
        )
    }

    // —— ⑥ 液体态：780 四档 + 658 三液分道 + 806 未装配 ——
    private fun fluid(input: SnapshotInput): List<SnapshotField> {
        val f = input.fluid.body
        val channels = input.fluid.physiology?.fluids
        return listOf(
            cell("fluid.volume", f?.volume?.grade?.label),
            cell("fluid.viscosity", f?.viscosity?.grade?.label),
            cell("fluid.temperature", f?.temperature?.grade?.label),
            cell("fluid.dryness", f?.dryness?.grade?.label),
            cell("fluid.channels.sweat", channels?.sweat?.line()),
            cell("fluid.channels.saliva", channels?.saliva?.line()),
            cell("fluid.channels.preSeminal", channels?.preSeminal?.line()),
            cell("fluid.surfaceFlow", null),
        )
    }

    private fun FluidTrack.line(): String = "${if (present) "有" else "无"}/${amount.label}"

    private fun RhythmReading.line(): String = "$frequency/$depth/$withdraw"
}
