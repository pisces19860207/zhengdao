// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百九十批 · 形轨.方位可达：三轴输入与七项输出的类型层。
// 655 头注释原话「只判『末端到根的距离』能否达到，不判方位；不含时间积分」——
// 本单补的就是方位这一层：同目标只改方位，结论必须能变（判据①）。
// 判据结论类型零浮点（310 尺四口径，同 655 PoseVerdict 的 Violation.amountMilli 做法）：
// 量值只以整数毫米落档，几何浮点全部活在内部采样器里，不进结论。
package com.example.zhengdao.baopu.state.bearing

import com.example.zhengdao.baopu.state.geometry.GeometryNotes

/** 轴②方位六档：相对该区（子区）自身的表面方位。前＝远端向、后＝根向、上＝背侧向、下＝腹侧向。 */
enum class Bearing6 {
    FRONT,
    BACK,
    LEFT,
    RIGHT,
    UP,
    DOWN,
}

/** 轴③距离档：贴体/近/中/远，与 655 可达半径同用厘米，不另设单位（档位偏移是工程假设○）。 */
enum class DistanceTier {
    CONTACT,
    NEAR,
    MID,
    FAR,
}

/** 结论三态。reachable＝够到且不别着；awkward＝够得到但别着·需扭转；unreachable＝够不到（必带 blockedBy）。 */
enum class BearingReachState {
    REACHABLE,
    AWKWARD,
    UNREACHABLE,
}

/** 判到多细：zone＝只判到区；zone_and_subzone＝区＋子区（775 落地后才有）。 */
enum class BearingGranularity {
    ZONE,
    ZONE_AND_SUBZONE,
}

/** 底盘口径：fixture＝660 默认站姿代理体（肘腕陷骨盆属夹具）；real＝真形体（699 接线后，本单恒 fixture）。 */
enum class BearingBaseline {
    FIXTURE,
    REAL,
}

/** 明示不做的事（边界照工单）：779 进入可行性、741/795 空间容纳、686 穿透。 */
enum class NotThisOrder(val label: String) {
    ENTRY_779("进入可行性＝779，未判"),
    CONTAINMENT_741_795("空间容纳＝741/795，未判"),
    PENETRATION_686("穿透后果＝686，未判"),
}

/** 肩/肘/腕/髋/膝各一档（1–5 档刻度），是档不是角度（判据⑥：档位是工程读数○）。 */
data class JointTiers(
    val shoulder: Int,
    val elbow: Int,
    val wrist: Int,
    val hip: Int,
    val knee: Int,
) {
    init {
        val all = listOf(shoulder, elbow, wrist, hip, knee)
        require(all.all { it in 1..5 }) { "关节档位须在 1..5：$all" }
    }

    fun asMap(): Map<String, Int> = linkedMapOf(
        "shoulder" to shoulder,
        "elbow" to elbow,
        "wrist" to wrist,
        "hip" to hip,
        "knee" to knee,
    )
}

/** 路径可行性＋blockedBy（卡在哪）。blockedBy 取值与 655 四项一致：四种违规类＋REACH_SPAN（链长三角界）。 */
data class BearingPath(
    val feasible: Boolean,
    val blockedBy: List<String>,
    val detail: String,
)

/**
 * 七项结论（工单 §输出）。本类及其所在文件零浮点声明（310 尺四：判据结论类型不许带浮点）；
 * 量化证据以整数毫米落 [bestGapMm]。unreachable 必带非空 blockedBy（判据③）。
 */
data class BearingVerdict(
    val zoneId: String,
    val zoneInstance: String,
    val subzoneRequested: Boolean,
    val bearing: Bearing6,
    val distance: DistanceTier,
    val state: BearingReachState,
    val jointTiers: JointTiers,
    val path: BearingPath,
    val granularity: BearingGranularity,
    val granularityReason: String,
    val baseline: BearingBaseline,
    val baselineReason: String,
    val notThisOrder: List<NotThisOrder>,
    val usedArm: String,
    val samplesTried: Int,
    val bestGapMm: Int,
    val evidenceNote: String,
    val assumption: String = BEARING_ASSUMPTION,
    val note: String = GeometryNotes.NOT_MEASUREMENT,
)

/** 三轴缺一（或子区源缺位）时的诚实回执：缺什么列什么，不许默认补全（判据②⑤）。 */
data class BearingIncomplete(
    val missing: List<String>,
    val given: String,
    val assumption: String = INCOMPLETE_ASSUMPTION,
    val note: String = GeometryNotes.NOT_MEASUREMENT,
)

/** 判定出口：要么七项齐，要么 incomplete。 */
sealed interface BearingOutcome {
    data class Judged(val verdict: BearingVerdict) : BearingOutcome
    data class Incomplete(val incomplete: BearingIncomplete) : BearingOutcome
}

const val BEARING_ASSUMPTION =
    "方位可达＝工程判定：目标点取该区代理体支撑面沿方位六档的表面点＋距离档偏移（偏移/带宽/别扭阈值均标工程假设○，非事实底盘）；" +
        "手臂链为肩(两自由度)+肘+腕四关节工程抽象，确定性 Halton 采样＋离散细化找证据；" +
        "unreachable＝链长三角界闭式证明，或采样＋细化后最近构型被 655 四项之一卡住（越限即红）；" +
        "采样未中且最近构型干净时按本单三态口径记 awkward 并在证据注写明证据不足（与 655 UNKNOWN 语义的差异已声明）。" +
        "不判进入（779）、不判容纳（741/795）、不判穿透后果（686）、不做连续 IK、不含时间积分"

const val INCOMPLETE_ASSUMPTION =
    "三轴缺一即 incomplete（判据②），不给默认补全；子区轴唯一来源＝SubzoneSource（775 已落地：state/subzone/SubzoneId，判据⑤不许假装判到子区）"
