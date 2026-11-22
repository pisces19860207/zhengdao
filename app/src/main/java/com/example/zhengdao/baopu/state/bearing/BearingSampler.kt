// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百九十批 · 形轨.方位可达：确定性采样器（内部件）。
// Halton 确定性采样（与 655 同思路，无随机数）＋离散细化（坐标网格扰动，非连续求解、无积分器）；
// 合格构型＝指尖落带宽内且摆姿臂过 655 四项（自碰撞/穿地穿沙；采样角天然在限位内）。
// 浮点只活在内部读数，出口类型见 BearingTypes（零浮点）。
package com.example.zhengdao.baopu.state.bearing

import com.example.zhengdao.baopu.state.form.BodyState
import com.example.zhengdao.baopu.state.geometry.ChainPose
import com.example.zhengdao.baopu.state.geometry.ConvexShape
import com.example.zhengdao.baopu.state.geometry.Environment
import com.example.zhengdao.baopu.state.geometry.Epa
import com.example.zhengdao.baopu.state.geometry.Gjk
import com.example.zhengdao.baopu.state.geometry.JointChain
import com.example.zhengdao.baopu.state.geometry.SegmentProxy
import com.example.zhengdao.baopu.state.geometry.Sphere
import com.example.zhengdao.baopu.state.geometry.Vec3
import com.example.zhengdao.baopu.state.geometry.Violation
import com.example.zhengdao.baopu.state.geometry.ViolationKind
import com.example.zhengdao.baopu.state.geometry.lowestY

/** 单臂单实例的采样读数（内部件，可含浮点；结论层只取档与整数毫米）。 */
internal class ArmAttempt(
    val spanBlocked: Boolean,
    val bestErrorCm: Double,
    val bestViolations: List<Violation>,
    val bestUtils: List<Double>,
    val qualifyingUtils: List<Double>,
    val bestUtil: Double,
    val qualifyingUtil: Double,
    val hasEvidence: Boolean,
    val samplesTried: Int,
)

/** 诊断上下文：判定所需的一切静置输入。 */
internal class Ctx(
    val chain: JointChain,
    val staticSet: List<SegmentProxy>,
    val permissions: BearingGeometry.Permissions,
    val env: Environment,
    val targetCm: Vec3,
    val bandCm: Double,
    val side: String,
    val body: BodyState,
)

/** 方位可达采样器：同输入同读数，逐字节可复现（判据⑦）。 */
internal object BearingSampler {

    /** 单臂对单目标的完整评估：入口先用 655 链长三角界判跨距（spanBlocked），再采样＋细化找「带宽内且四项干净」的构型。 */
    fun evaluate(
        chain: JointChain,
        staticSet: List<SegmentProxy>,
        permissions: BearingGeometry.Permissions,
        env: Environment,
        targetCm: Vec3,
        bandCm: Double,
        side: String,
        body: BodyState,
        spanBlocked: Boolean,
    ): ArmAttempt {
        if (spanBlocked) {
            return ArmAttempt(true, 0.0, emptyList(), emptyList(), emptyList(), 0.0, 0.0, false, 0)
        }
        val ctx = Ctx(chain, staticSet, permissions, env, targetCm, bandCm, side, body)
        val search = Search(bandCm)
        for (k in 0 until BearingGeometry.SAMPLES) search.consider(rawAngles(k, chain), ctx)
        var refined = 0
        for (seed in search.refineSeeds()) refined += search.refineFrom(seed, ctx)
        val bestUtils = utilsOf(chain, search.bestAngles)
        val qualifyingUtils = utilsOf(chain, search.qualifyingAngles)
        return ArmAttempt(
            spanBlocked = false,
            bestErrorCm = search.bestErrorCm,
            bestViolations = search.bestViolations,
            bestUtils = bestUtils,
            qualifyingUtils = qualifyingUtils,
            bestUtil = bestUtils.maxOrNull() ?: 0.0,
            qualifyingUtil = if (search.hasEvidence) search.qualifyingUtil else 0.0,
            hasEvidence = search.hasEvidence,
            samplesTried = BearingGeometry.SAMPLES + refined,
        )
    }

    /** 逐关节利用率：|实际角|/限位上界（限位对称）；链末端即指尖，肩两自由度各算各。 */
    private fun utilsOf(chain: JointChain, angles: List<Double>?): List<Double> {
        if (angles == null) return emptyList()
        val pose = chain.pose(angles)
        return pose.joints.mapIndexed { i, js ->
            val hi = maxOf(kotlin.math.abs(chain.joints[i].limit.minRad), chain.joints[i].limit.maxRad)
            if (hi <= 0.0) 0.0 else kotlin.math.abs(js.appliedRad) / hi
        }
    }

    /** 第 k 个 Halton 构型：逐关节独立低差异序列铺满限位盒（与 655 采样同公式）。 */
    private fun rawAngles(k: Int, chain: JointChain): List<Double> = chain.joints.mapIndexed { i, j ->
        val u = BearingGeometry.halton(k + 1 + (i / BearingGeometry.BASES.size) * BearingGeometry.SAMPLES, BearingGeometry.BASES[i % BearingGeometry.BASES.size])
        j.limit.minRad + u * (j.limit.maxRad - j.limit.minRad)
    }
}

/** 采样搜索状态：最近构型＋合格（带宽内且四项干净）构型里利用率最低者。 */
internal class Search(private val bandCm: Double) {

    var bestErrorCm: Double = Double.MAX_VALUE
        private set
    var bestAngles: List<Double>? = null
        private set
    var bestViolations: List<Violation> = emptyList()
        private set
    var qualifyingUtil: Double = Double.MAX_VALUE
        private set
    var hasEvidence: Boolean = false
        private set
    private val goodAngles = ArrayList<List<Double>>()
    var qualifyingAngles: List<Double>? = null
        private set
    private val lowErrors = ArrayList<Pair<Double, List<Double>>>()

    /** 细化种子：有证据只精修证据构型；无证据精修 top-3 最近构型（多起点跳出局部坑）。 */
    fun refineSeeds(): List<List<Double>> {
        val best = bestAngles ?: return emptyList()
        if (hasEvidence) return listOf(best)
        val seeds = LinkedHashMap<List<Double>, Int>()
        seeds[best] = 0
        for ((_, a) in lowErrors) seeds.putIfAbsent(a, 0)
        return seeds.keys.take(BearingGeometry.REFINE_SEEDS).toList()
    }

    /** 从种子做离散坐标下降：逐关节 ±步长（步长随轮数收细）；候选一律 consider 入库，无改善即停。 */
    fun refineFrom(seed: List<Double>, ctx: Ctx): Int {
        var tried = 0
        var current = seed
        var currentErr = errorOf(current, ctx)
        for (round in 1..BearingGeometry.REFINE_ROUNDS) {
            var improved = false
            for (i in ctx.chain.joints.indices) {
                val span = ctx.chain.joints[i].limit.maxRad - ctx.chain.joints[i].limit.minRad
                val step = span / (32.0 * round)
                for (k in intArrayOf(-2, -1, 1, 2)) {
                    val cand = ctx.chain.joints.mapIndexed { j, spec ->
                        if (j == i) spec.limit.clamp(current[i] + k * step) else current[j]
                    }
                    consider(cand, ctx)
                    tried++
                    val err = errorOf(cand, ctx)
                    if (err < currentErr - 1E-12) {
                        current = cand
                        currentErr = err
                        improved = true
                    }
                }
            }
            if (!improved) break
        }
        return tried
    }

    /** 记一个构型：更新最近构型与 top-3 低误差名单（进诊断圈才做四项诊断）；干净且利用率更低者成为合格构型。 */
    fun consider(angles: List<Double>, ctx: Ctx) {
        val err = errorOf(angles, ctx)
        insertLowError(err, angles)
        if (err < bestErrorCm) {
            bestErrorCm = err
            bestAngles = angles
            bestViolations =
                if (err <= bandCm + BearingGeometry.DIAG_MARGIN_CM) diagnose(ctx.chain.pose(angles), ctx).violations else emptyList()
        }
        if (err <= bandCm) {
            val diag = diagnose(ctx.chain.pose(angles), ctx)
            if (!diag.clean) return
            val util = BearingGeometry.utilization(ctx.chain.pose(angles), ctx.chain)
            if (util < qualifyingUtil) {
                qualifyingUtil = util
                qualifyingAngles = angles
                hasEvidence = true
                if (goodAngles.size < BearingGeometry.REFINE_SEEDS) goodAngles.add(angles)
            }
        }
    }

    /** top-3 低误差名单：按 (误差, 插入序) 稳定去重，只留确定性种子。 */
    private fun insertLowError(err: Double, angles: List<Double>) {
        val entry = err to angles
        val idx = lowErrors.indexOfFirst { it.second == angles }
        if (idx >= 0) {
            if (err < lowErrors[idx].first) {
                lowErrors.removeAt(idx)
                insertSorted(entry)
            }
            return
        }
        insertSorted(entry)
        while (lowErrors.size > BearingGeometry.REFINE_SEEDS) {
            var worst = 0
            for (i in lowErrors.indices) if (lowErrors[i].first >= lowErrors[worst].first) worst = i
            lowErrors.removeAt(worst)
        }
    }

    private fun insertSorted(entry: Pair<Double, List<Double>>) {
        var at = lowErrors.size
        for (i in lowErrors.indices) {
            if (entry.first < lowErrors[i].first) { at = i; break }
        }
        lowErrors.add(at, entry)
    }

    private fun errorOf(angles: List<Double>, ctx: Ctx): Double =
        (ctx.chain.pose(angles).endEffectorCm - ctx.targetCm).norm()

    /** 摆姿臂 vs 静置体的 655 四项：自碰撞逐对 GJK/EPA（深度>接触容差才记）＋穿地穿沙；限位由采样区间内生成保证。 */
    private fun diagnose(pose: ChainPose, ctx: Ctx): Diagnosis {
        val arm = BearingGeometry.posedArm(pose, ctx.side, ctx.body)
        val out = ArrayList<Violation>()
        out += selfCollisions(arm, ctx)
        out += groundViolations(arm, ctx.env)
        return Diagnosis(out)
    }

    private fun selfCollisions(arm: List<SegmentProxy>, ctx: Ctx): List<Violation> {
        val out = ArrayList<Violation>()
        for (seg in arm) {
            for (s in ctx.staticSet) {
                val pair = seg.name to s.name
                if (pair in ctx.permissions.free) continue
                if (pair in ctx.permissions.guarded && !buried(s.shape, ctx.targetCm)) continue
                val reading = pairReading(seg.shape, s.shape)
                if (reading == null) {
                    out.add(
                        Violation(ViolationKind.SELF_COLLISION, seg.name, s.name, amountMilli = 0,
                            detail = "几何原语对退化构型不可判，保守记卡（fail-closed）"),
                    )
                    continue
                }
                val (dist, depth) = reading
                if (depth > BearingGeometry.CONTACT_DEPTH_TOL) {
                    out.add(
                        Violation(
                            ViolationKind.SELF_COLLISION, seg.name, s.name,
                            amountMilli = Math.round(depth * 10.0).toInt(), detail = "摆姿臂压入静置体",
                        ),
                    )
                }
            }
        }
        return out
    }

    /** 单对几何读数（距离，穿透深度）。655 原语在其测试域外的极端退化输入上可能抛错：接住 ⇒ 返回 null，调用方保守记卡（fail-closed，确定性不变）。 */
    private fun pairReading(a: ConvexShape, b: ConvexShape): Pair<Double, Double>? = try {
        val dist = Gjk.distance(a, b).distanceCm
        val depth = if (dist <= BearingGeometry.CONTACT_DEPTH_TOL) (Epa.penetration(a, b)?.depthCm ?: 0.0) else 0.0
        dist to depth
    } catch (e: IndexOutOfBoundsException) {
        null
    } catch (e: ArithmeticException) {
        null
    }

    /** 目标点是否深陷某静置凸体内（深度>守卫值）：深陷＝必须穿透它才够到 ⇒ 豁免不成立。原语不可判按深陷处理（fail-closed）。 */
    private fun buried(shape: ConvexShape, point: Vec3): Boolean {
        val depth = try {
            Epa.penetration(shape, Sphere(point, 0.01))?.depthCm ?: 0.0
        } catch (e: IndexOutOfBoundsException) {
            Double.MAX_VALUE
        } catch (e: ArithmeticException) {
            Double.MAX_VALUE
        }
        return depth > BearingGeometry.PRESS_GUARD_CM
    }

    private fun groundViolations(arm: List<SegmentProxy>, env: Environment): List<Violation> {
        val out = ArrayList<Violation>()
        for (s in arm) {
            val lowest = s.shape.lowestY()
            if (lowest < env.groundYCm) {
                out.add(Violation(ViolationKind.GROUND_PENETRATION, s.name, amountMilli = Math.round((env.groundYCm - lowest) * 10.0).toInt(), detail = "穿地"))
            } else if (lowest < env.sandYCm) {
                out.add(Violation(ViolationKind.SAND_PENETRATION, s.name, amountMilli = Math.round((env.sandYCm - lowest) * 10.0).toInt(), detail = "穿沙"))
            }
        }
        return out
    }
}

/** 单次摆姿的 655 四项诊断（限位项由采样区间内生成保证，顶限归因按利用率在判定层做）。 */
internal class Diagnosis(val violations: List<Violation>) {
    val clean: Boolean get() = violations.isEmpty()
}
