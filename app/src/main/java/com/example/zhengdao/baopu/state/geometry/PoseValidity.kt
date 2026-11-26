// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百五十五批 · 形轨.几何判定：姿态合法性。
// 三类检查：自碰撞（非相邻段对，GJK 距离＋EPA 深度，穿透深度超过接触容差才记违规，
// 恰好接触深度 0 不算）、穿地穿沙（最低点 vs 地面/沙面高度）、限位越界（消费
// JointChain 的截断留痕）。
// 相邻段对（如大腿与小腿）由调用方给名单跳过——它们天然接触，不算自碰撞。
package com.example.zhengdao.baopu.state.geometry

/** 一段身体代理体：名字＋凸体（由 660 形体参数导出，本单不造形体）。 */
data class SegmentProxy(val name: String, val shape: ConvexShape)

/** 环境高度（cm）：地面在下、沙面在上；沙面不得低于地面。 */
data class Environment(val groundYCm: Double, val sandYCm: Double) {
    init {
        require(sandYCm >= groundYCm) { "沙面不得低于地面" }
    }
}

/** 违规类别。 */
enum class ViolationKind { SELF_COLLISION, GROUND_PENETRATION, SAND_PENETRATION, LIMIT_EXCEEDED }

/** 量值单位（判据产物只存整数：310 禁标量分，判据结论类型不许带浮点）。 */
enum class ViolationUnit { MILLIMETRE, MILLIRADIAN }

/** 单条违规：类别＋当事段＋（碰撞时的）对方段＋越限量＋一句话。 */
data class Violation(
    val kind: ViolationKind,
    val segment: String,
    val other: String? = null,
    /**
     * 量值，**整数**：穿透类＝毫米；越限类＝毫弧度（[unit] 区分）。
     *
     * 为什么不存 Double 厘米：310「禁标量分」——本文件含判据结论类型 [PoseVerdict]，
     * 文件里出现 `val x: Double` 就红（ci #2046 真因）。厘米在落账前乘 10 取整到毫米，
     * 分辨率足够（几何内核仍用厘米浮点算，只有结论这一格是整数）。
     */
    val amountMilli: Int = 0,
    val unit: ViolationUnit = ViolationUnit.MILLIMETRE,
    val detail: String = "",
)

/** 姿态合法性读数。 */
data class PoseVerdict(
    val valid: Boolean,
    val violations: List<Violation>,
    val assumption: String = "静态姿态检查：GJK/EPA 自碰撞＋最低点穿地穿沙＋关节限位留痕（无时间积分）",
    val note: String = GeometryNotes.NOT_MEASUREMENT,
)

/** 姿态合法性判定（纯函数）。 */
object PoseValidity {

    fun check(
        segments: List<SegmentProxy>,
        env: Environment,
        chain: ChainPose? = null,
        adjacent: Set<Pair<String, String>> = emptySet(),
        contactTolCm: Double = 0.0,
        compounds: Map<String, CompoundProxy> = emptyMap(),
    ): PoseVerdict {
        val violations = ArrayList<Violation>()
        violations += selfCollisions(segments, adjacent, contactTolCm, compounds)
        violations += groundViolations(segments, env)
        violations += limitViolations(chain)
        return PoseVerdict(violations.isEmpty(), violations)
    }

    private fun selfCollisions(
        segments: List<SegmentProxy>,
        adjacent: Set<Pair<String, String>>,
        tol: Double,
        compounds: Map<String, CompoundProxy>,
    ): List<Violation> {
        val out = ArrayList<Violation>()
        for (i in segments.indices) {
            for (j in i + 1 until segments.size) {
                val a = segments[i]
                val b = segments[j]
                if (isAdjacent(a.name, b.name, adjacent)) continue
                val reading = CompoundPairs.read(a, b, compounds)
                val dist = reading?.distanceCm ?: Gjk.distance(a.shape, b.shape).distanceCm
                if (dist > tol) continue
                val depth = reading?.depthCm ?: Epa.penetration(a.shape, b.shape)?.depthCm ?: 0.0
                if (depth > tol) {
                    out.add(
                        Violation(
                            ViolationKind.SELF_COLLISION,
                            a.name,
                            b.name,
                            amountMilli = milliOf(depth),
                            detail = "段间穿透",
                        ),
                    )
                }
            }
        }
        return out
    }

    private fun isAdjacent(a: String, b: String, adjacent: Set<Pair<String, String>>): Boolean =
        (a to b) in adjacent || (b to a) in adjacent

    /** 厘米 → 毫米整数（四舍五入；只用于把结论写成整数，内核仍按厘米算）。 */
    private fun milliOf(
        cm: Double,
    ): Int = Math.round(cm * 10.0).toInt()

    private fun groundViolations(segments: List<SegmentProxy>, env: Environment): List<Violation> {
        val out = ArrayList<Violation>()
        for (s in segments) {
            val lowest = s.shape.lowestY()
            if (lowest < env.groundYCm) {
                out.add(
                    Violation(
                        ViolationKind.GROUND_PENETRATION,
                        s.name,
                        amountMilli = milliOf(env.groundYCm - lowest),
                        detail = "穿地",
                    ),
                )
            } else if (lowest < env.sandYCm) {
                out.add(
                    Violation(
                        ViolationKind.SAND_PENETRATION,
                        s.name,
                        amountMilli = milliOf(env.sandYCm - lowest),
                        detail = "穿沙",
                    ),
                )
            }
        }
        return out
    }

    private fun limitViolations(chain: ChainPose?): List<Violation> =
        chain?.joints.orEmpty().filter { !it.withinLimit }.map {
            Violation(
                ViolationKind.LIMIT_EXCEEDED,
                it.name,
                amountMilli = Math.round(kotlin.math.abs(it.requestedRad - it.appliedRad) * 1000.0).toInt(),
                unit = ViolationUnit.MILLIRADIAN,
                detail = "请求 ${it.requestedRad} 截到 ${it.appliedRad}",
            )
        }
}