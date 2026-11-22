// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十四批 · 只读示例：给定 tick 与场景输入 → 导出完整快照 JSON。
// 示例本身也是只读的：每次调用从常量输入重新装配，返回新字符串，不缓存、不落盘。
package com.example.zhengdao.baopu.state.snapshot

import com.example.zhengdao.baopu.state.anchor.AnchorProfile
import com.example.zhengdao.baopu.state.bearing.Bearing6
import com.example.zhengdao.baopu.state.bearing.BearingBaseline
import com.example.zhengdao.baopu.state.bearing.BearingGranularity
import com.example.zhengdao.baopu.state.bearing.BearingPath
import com.example.zhengdao.baopu.state.bearing.BearingReachState
import com.example.zhengdao.baopu.state.bearing.BearingVerdict
import com.example.zhengdao.baopu.state.bearing.DistanceTier
import com.example.zhengdao.baopu.state.bearing.JointTiers
import com.example.zhengdao.baopu.state.capacity.CapacityResult
import com.example.zhengdao.baopu.state.capacity.CapacityVerdict
import com.example.zhengdao.baopu.state.environment.EnvironmentState
import com.example.zhengdao.baopu.state.environment.HumiditySolver
import com.example.zhengdao.baopu.state.environment.SolarSolver
import com.example.zhengdao.baopu.state.environment.WindTable
import com.example.zhengdao.baopu.state.fluid.ChangeDriver
import com.example.zhengdao.baopu.state.fluid.DrynessGrade
import com.example.zhengdao.baopu.state.fluid.FluidBody
import com.example.zhengdao.baopu.state.fluid.FluidTempGrade
import com.example.zhengdao.baopu.state.fluid.GradeRead
import com.example.zhengdao.baopu.state.fluid.ViscosityGrade
import com.example.zhengdao.baopu.state.fluid.VolumeGrade
import com.example.zhengdao.baopu.state.geometry.PoseVerdict
import com.example.zhengdao.baopu.state.geometry.ReachResult
import com.example.zhengdao.baopu.state.geometry.Reachability
import com.example.zhengdao.baopu.state.injury.InjuryGrade
import com.example.zhengdao.baopu.state.injury.PainGrade
import com.example.zhengdao.baopu.state.physiology.BodyTempShift
import com.example.zhengdao.baopu.state.physiology.Fatigue
import com.example.zhengdao.baopu.state.physiology.FluidAmount
import com.example.zhengdao.baopu.state.physiology.FluidChannels
import com.example.zhengdao.baopu.state.physiology.FluidTrack
import com.example.zhengdao.baopu.state.physiology.GradeReading
import com.example.zhengdao.baopu.state.physiology.HeartRateBand
import com.example.zhengdao.baopu.state.physiology.LightPhysiology
import com.example.zhengdao.baopu.state.physiology.PhysioDriver
import com.example.zhengdao.baopu.state.physiology.SweatLevel
import com.example.zhengdao.baopu.state.stimulus.RhythmReading

/** 场景常量：全部写死，调用两次必得逐字节相同的 JSON。 */
internal object SnapshotExample {

    const val TICK: Long = 42
    private const val DAY_OF_YEAR = 80
    private const val LATITUDE = 39.9
    private const val LONGITUDE = 116.4
    private const val TZ_OFFSET = 8.0
    private const val CLOCK_HOUR = 12.0
    private const val TEMP_C = 22.0
    private const val RH_PCT = 55.0

    /** 只读示例出口：tick + 场景输入 ⇒ 完整快照 JSON。 */
    fun json(): String = SnapshotJson.render(SnapshotAssembler.assemble(input()))

    /** 场景输入（示例用）：所有值都是既有包类型或整数量。 */
    fun input(): SnapshotInput {
        val solar = SolarSolver.toTrueSolarTime(CLOCK_HOUR, LONGITUDE, TZ_OFFSET, DAY_OF_YEAR)
        return SnapshotInput(
            tick = TICK,
            time = TimeInput(DAY_OF_YEAR, solar),
            environment = EnvironmentInput(environment(solar)),
            body = BodyInput(
                anchor = AnchorProfile(id = "hero-01"),
                injury = InjuryGrade.COLOR,
                pain = PainGrade.TOUCH,
                rhythm = RhythmReading(2, 1, 0),
            ),
            space = SpaceInput(
                pose = PoseVerdict(true, emptyList()),
                reach = ReachResult(Reachability.State.REACHABLE, 0.0, 1, "闭式判定"),
                bearing = bearing(),
                capacity = CapacityResult(CapacityVerdict.UNKNOWN, emptyList(), emptyList(), "未给容量断言"),
            ),
            fluid = FluidInput(fluidBody(), physiology()),
        )
    }

    private fun environment(solar: SolarSolver.TrueSolarTime): EnvironmentState {
        val windMs = WindTable.representativeMs(3)
        return EnvironmentState(
            sun = SolarSolver.solve(LATITUDE, DAY_OF_YEAR, solar.hours),
            wind = WindTable.of(3, 90.0),
            humidity = HumiditySolver.humidityOf(TEMP_C, RH_PCT, windMs),
            sweat = HumiditySolver.sweatOf(2, TEMP_C, RH_PCT, windMs, 1),
            sand = HumiditySolver.sandMoistureOf(1),
            assumption = SolarSolver.ASSUMPTION,
        )
    }

    private fun bearing(): BearingVerdict = BearingVerdict(
        zoneId = "penis",
        zoneInstance = "shaft",
        subzoneRequested = false,
        bearing = Bearing6.FRONT,
        distance = DistanceTier.NEAR,
        state = BearingReachState.REACHABLE,
        jointTiers = JointTiers(3, 3, 3, 3, 3),
        path = BearingPath(true, emptyList(), "闭式可达"),
        granularity = BearingGranularity.ZONE,
        granularityReason = "未请求子区",
        baseline = BearingBaseline.FIXTURE,
        baselineReason = "699 未接线，恒 fixture",
        notThisOrder = emptyList(),
        usedArm = "right",
        samplesTried = 64,
        bestGapMm = 0,
        evidenceNote = "示例读数",
    )

    private fun fluidBody(): FluidBody = FluidBody(
        volume = GradeRead(VolumeGrade.TRACE, listOf(ChangeDriver.EVENT)),
        viscosity = GradeRead(ViscosityGrade.THIN, listOf(ChangeDriver.EVENT)),
        temperature = GradeRead(FluidTempGrade.MILD, emptyList()),
        dryness = GradeRead(DrynessGrade.WET, listOf(ChangeDriver.TIME)),
    )

    private fun physiology(): LightPhysiology = LightPhysiology(
        bodyTempShift = GradeReading(BodyTempShift.NORMAL, emptyList()),
        heartRateBand = GradeReading(HeartRateBand.REST, emptyList()),
        sweatLevel = GradeReading(SweatLevel.NONE, emptyList()),
        fatigue = GradeReading(Fatigue.AWAKE, emptyList()),
        fluids = FluidChannels(
            sweat = FluidTrack(true, FluidAmount.TRACE, listOf(PhysioDriver.ACTIVITY_INTENSITY)),
            saliva = FluidTrack(false, FluidAmount.NONE, emptyList()),
            preSeminal = FluidTrack(false, FluidAmount.NONE, emptyList()),
        ),
    )
}
