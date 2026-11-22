// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十四批 · 统一状态快照：输入与产出的类型层。
// 输入全部是既有包的类型（或调用方给的整数量）；本文件不定义任何状态档。
package com.example.zhengdao.baopu.state.snapshot

import com.example.zhengdao.baopu.state.anchor.AnchorProfile
import com.example.zhengdao.baopu.state.bodyparts.BodyParts
import com.example.zhengdao.baopu.state.bearing.BearingVerdict
import com.example.zhengdao.baopu.state.capacity.CapacityResult
import com.example.zhengdao.baopu.state.cavity.CavityGap
import com.example.zhengdao.baopu.state.containment.ContainmentVerdict
import com.example.zhengdao.baopu.state.environment.EnvironmentState
import com.example.zhengdao.baopu.state.environment.SolarSolver
import com.example.zhengdao.baopu.state.fluid.FluidBody
import com.example.zhengdao.baopu.state.form.BodyState
import com.example.zhengdao.baopu.state.geometry.PoseVerdict
import com.example.zhengdao.baopu.state.geometry.ReachResult
import com.example.zhengdao.baopu.state.injury.InjuryGrade
import com.example.zhengdao.baopu.state.injury.PainGrade
import com.example.zhengdao.baopu.state.physiology.LightPhysiology
import com.example.zhengdao.baopu.state.stimulus.RhythmReading

/** 时间输入：整数量，由调用方给；本层不读墙钟、不读随机。 */
internal data class TimeInput(
    val dayOfYear: Int? = null,
    val solarTime: SolarSolver.TrueSolarTime? = null,
)

/** 人体输入：全部引用既有包产出，缺哪样哪样 absent。 */
internal data class BodyInput(
    val body: BodyState? = null,
    val parts: BodyParts? = null,
    val injury: InjuryGrade? = null,
    val pain: PainGrade? = null,
    val rhythm: RhythmReading? = null,
    val anchor: AnchorProfile? = null,
)

/** 空间输入：姿态 655、可达 655、方位 790、容纳 741、腔隙 686、容量 795。 */
internal data class SpaceInput(
    val pose: PoseVerdict? = null,
    val reach: ReachResult? = null,
    val bearing: BearingVerdict? = null,
    val containment: ContainmentVerdict? = null,
    val cavities: List<CavityGap>? = null,
    val capacity: CapacityResult? = null,
)

/** 环境输入：657 一次求解的整体产出。 */
internal data class EnvironmentInput(
    val state: EnvironmentState? = null,
)

/** 液体输入：780 三液/四档 + 658 三液分道。 */
internal data class FluidInput(
    val body: FluidBody? = null,
    val physiology: LightPhysiology? = null,
)

/**
 * 一次快照的全部场景输入。
 *
 * [tick] 是场景逻辑时钟（第几拍），由调用方显式给；**不是** System.currentTimeMillis()。
 * 六段各自独立可空：没有哪一段会被默认值补齐。
 */
internal data class SnapshotInput(
    val tick: Long,
    val time: TimeInput = TimeInput(),
    val body: BodyInput = BodyInput(),
    val space: SpaceInput = SpaceInput(),
    val environment: EnvironmentInput = EnvironmentInput(),
    val fluid: FluidInput = FluidInput(),
)

/** 一格快照的值：要么有值，要么显式缺席并带理由。没有第三种形态。 */
internal sealed interface SnapshotSlot {
    data class Given(val text: String, val number: Boolean = false) : SnapshotSlot
    data class Absent(val reason: String) : SnapshotSlot
}

/** 一格快照 = 规格 id + 值。id 一定在 [SnapshotSpec.FIELDS] 里。 */
internal data class SnapshotField(val id: String, val slot: SnapshotSlot)

/** 一段快照 = 段名 + 按 [SnapshotSpec] 次序排列的字段。 */
internal data class SnapshotSection(val id: String, val fields: List<SnapshotField>)

/** 一张快照 = 版本 + 场景 tick + 六段。纯值，不含任何包的可变状态。 */
internal data class Snapshot(
    val version: String,
    val tick: Long,
    val sections: List<SnapshotSection>,
)
