// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十批 · 形轨.形体参数：柔韧档 → 关节限位 → 可达性三态＋余量（工单追加①②③）。
// 限位随档位变，不是常数表；不内置任何"人类不能"清单——极端姿态按 body_spec 判，只附风险提示。
package com.example.zhengdao.baopu.state.form

import com.example.zhengdao.baopu.state.geometry.GeometryNotes
import com.example.zhengdao.baopu.state.geometry.JointLimit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

/** 可达性三态（工单追加②用词；与 655 的 REACHABLE/UNREACHABLE/UNKNOWN 是不同口径，故不共用）。 */
enum class FlexReach(val label: String) {
    REACHABLE("reachable"),
    MARGINAL("marginal"),
    UNREACHABLE("unreachable"),
}

/** 一个动作对各关节的需求角（度，单侧绝对值）。动作由调用方给，本单不内置动作清单。 */
data class PoseDemand(
    val id: String,
    val degrees: Map<String, Double>,
    val source: String,
    val asOf: String,
) {
    init {
        require(id.isNotBlank()) { "动作缺 id" }
        require(degrees.isNotEmpty()) { "动作 $id 需求角为空" }
        require(source.isNotBlank()) { "动作 $id 缺 source，拒收" }
        require(asOf.isNotBlank()) { "动作 $id 缺 asOf，拒收" }
        require(degrees.values.all { it.isFinite() && it >= 0.0 }) { "动作 $id 需求角须为非负有限数" }
    }
}

/** 一个关节在某个柔韧档下的限位：基准区间＋缩放后区间＋655 可用的弧度限位。 */
data class JointFlexLimit(
    val joint: String,
    val baseDeg: FactRange,
    val scaledDeg: FactRange,
    val limit: JointLimit,
    val scale: Double,
)

/** 柔韧档读数（档从哪来、系数多少，都带出身）。 */
data class FlexReading(
    val grade: Int,
    val ageBand: String?,
    val scale: Double,
    val origin: Origin,
    val source: String,
    val asOf: String,
)

/** 三态读数：unreachable 必带差多少度；极端姿态附风险提示，只提示不禁止。名用 Reading＝几何读数（同 655 ReachResult），不是准入结论：check-criteria-gates 尺四禁 Verdict 型带浮点。 */
data class ReachReading(
    val demandId: String,
    val grade: Int,
    val state: FlexReach,
    val marginDeg: Double,
    val perJoint: Map<String, FlexReach>,
    val risks: List<String>,
    val assumption: String = FLEX_ASSUMPTION,
    val note: String = GeometryNotes.NOT_MEASUREMENT,
)

private const val FLEX_ASSUMPTION =
    "限位＝口径表基准活动度区间上界×柔韧档系数（度转弧度给 655）；" +
        "三态按需求角与限位及边缘带比较，余量＝最大超出度数；基准区间是方向性工程假设，非临床值域"

private const val DEG_TO_RAD = PI / 180.0

/** 柔韧档与可达性。纯函数：同输入同输出，零随机、零时钟、零联网。 */
object Flexibility {

    fun reading(spec: HumanBodySpec, props: Proportions): FlexReading {
        val f = props.flexibility
        val given = spec.flexibilityGrade
        val g = given ?: f.defaultGrade(spec.ageBand)
        require(g.toDouble() in f.grade.low..f.grade.high) {
            "柔韧档 $g 超域 ${f.grade.low}..${f.grade.high}"
        }
        return FlexReading(
            grade = g, ageBand = spec.ageBand, scale = f.scale(g),
            origin = if (given == null) Origin.DERIVED else Origin.MEASURED,
            source = if (given == null) f.source else "调用方给定柔韧档",
            asOf = f.asOf,
        )
    }

    fun limits(grade: Int, props: Proportions): List<JointFlexLimit> {
        val f = props.flexibility
        val k = f.scale(grade)
        return f.jointBaseDeg.keys.sorted().map { joint ->
            val base = f.jointBaseDeg.getValue(joint)
            val lo = base.low * k
            val hi = base.high * k
            JointFlexLimit(
                joint = joint,
                baseDeg = FactRange("$joint.base_deg", base.low, base.high, "deg", base.source, base.asOf, base.evidence),
                scaledDeg = FactRange("$joint.scaled_deg", lo, hi, "deg", base.source, base.asOf, base.evidence),
                limit = JointLimit(-hi * DEG_TO_RAD, hi * DEG_TO_RAD),
                scale = k,
            )
        }
    }

    fun judge(demand: PoseDemand, grade: Int, props: Proportions): ReachReading {
        val f = props.flexibility
        val limits = limits(grade, props).associateBy { it.joint }
        require(demand.degrees.keys.all { it in limits }) {
            "动作 ${demand.id} 需求角键悬空：${demand.degrees.keys - limits.keys}"
        }
        val band = f.marginalBand.default
        val perJoint = LinkedHashMap<String, FlexReach>()
        var margin = 0.0
        for (joint in demand.degrees.keys.sorted()) {
            val need = abs(demand.degrees.getValue(joint))
            val maxDeg = limits.getValue(joint).scaledDeg.high
            perJoint[joint] = stateOf(need, maxDeg, band)
            margin = max(margin, need - maxDeg)
        }
        return ReachReading(
            demandId = demand.id, grade = grade,
            state = perJoint.values.maxByOrNull { it.ordinal } ?: FlexReach.REACHABLE,
            marginDeg = max(0.0, margin), perJoint = perJoint,
            risks = risksOf(demand, f),
        )
    }

    private fun stateOf(need: Double, maxDeg: Double, band: Double): FlexReach = when {
        need <= maxDeg -> FlexReach.REACHABLE
        need <= maxDeg * band -> FlexReach.MARGINAL
        else -> FlexReach.UNREACHABLE
    }

    /** 需求角超出基准区间上界＝极端姿态：按关节附提示，不拦判定。 */
    private fun risksOf(demand: PoseDemand, f: FlexRules): List<String> =
        demand.degrees.keys.sorted()
            .filter { abs(demand.degrees.getValue(it)) > f.jointBaseDeg.getValue(it).high }
            .mapNotNull { f.riskNotes[it] }
            .distinct()
}