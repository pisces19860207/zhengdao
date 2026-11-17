// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百五十五批 · 形轨.几何判定：可达性（只判「能不能到」，不是 IK 求解器）。
// 两关节：余弦定理闭式（含限位）；长链：链长界闭式先判「必不可能」，
// 界内再用确定性 Halton 采样找可达证据。采样找不到 ≠ 不可达 ⇒ 三态 UNKNOWN。
// 语义：只判「末端到根的距离」能否达到，不判方位；不含时间积分。
package com.example.zhengdao.baopu.state.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** 可达性三态与读数。bestErrorCm＝采样最好成绩与目标的距离差（闭式路径为 0）。 */
data class ReachResult(
    val state: Reachability.State,
    val bestErrorCm: Double,
    val samplesTried: Int,
    val assumption: String,
    val note: String = GeometryNotes.NOT_MEASUREMENT,
)

/** 可达性判定（纯函数）。 */
object Reachability {

    enum class State { REACHABLE, UNREACHABLE, UNKNOWN }

    private val BASES = intArrayOf(2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53)
    private const val CLOSED = "闭式判定：链长三角界＋余弦定理（含限位），无采样"
    private const val SAMPLED = "Halton 确定性采样（只判距离可达，不判方位）；采样未中不代表不可达"

    fun ofChain(
        chain: JointChain,
        targetDistanceCm: Double,
        tolCm: Double = 0.5,
        samples: Int = 1024,
    ): ReachResult {
        require(targetDistanceCm >= 0.0 && tolCm >= 0.0 && samples >= 1) { "距离/容差非负，采样数为正" }
        val lengths = chain.joints.map { it.lengthCm }
        val total = lengths.sum()
        val lower = max(0.0, 2.0 * lengths.max() - total)
        if (beyond(targetDistanceCm, lower, total, tolCm)) {
            return ReachResult(State.UNREACHABLE, 0.0, 0, CLOSED)
        }
        if (chain.joints.size == 1) return oneLink(lengths[0], targetDistanceCm, tolCm)
        if (chain.joints.size == 2) return twoLinks(lengths, chain.joints[1].limit, targetDistanceCm, tolCm)
        return sampled(chain, targetDistanceCm, tolCm, samples)
    }

    private fun oneLink(length: Double, d: Double, tol: Double): ReachResult {
        val err = abs(d - length)
        val state = if (err == 0.0 || err < tol) State.REACHABLE else State.UNREACHABLE
        return ReachResult(state, err, 0, CLOSED)
    }

    /** 两关节：d² = L1²+L2²+2L1L2·cosθ2；θ2 限位区间上 cos 的值域给出 d 的闭式区间。 */
    private fun twoLinks(lengths: List<Double>, limit2: JointLimit, d: Double, tol: Double): ReachResult {
        val (cMin, cMax) = cosRange(limit2.minRad, limit2.maxRad)
        val base = lengths[0] * lengths[0] + lengths[1] * lengths[1]
        val cross = 2.0 * lengths[0] * lengths[1]
        val dMin = sqrt(max(0.0, base + cross * cMin))
        val dMax = sqrt(max(0.0, base + cross * cMax))
        val state = if (beyond(d, dMin, dMax, tol)) State.UNREACHABLE else State.REACHABLE
        return ReachResult(state, 0.0, 0, CLOSED)
    }

    /**
     * 容差口径：d 恰在 [lo, hi] 内 ⇒ 可达；区间外误差须严格小于 tol 才算可达，
     * 误差 ≥ tol（含恰好压线）⇒ 不可达。tol=0 时退化为纯闭式区间判定。
     */
    private fun beyond(d: Double, lo: Double, hi: Double, tol: Double): Boolean {
        val err = when {
            d < lo -> lo - d
            d > hi -> d - hi
            else -> 0.0
        }
        return err > 0.0 && err >= tol
    }

    private fun sampled(chain: JointChain, d: Double, tol: Double, samples: Int): ReachResult {
        var best = Double.MAX_VALUE
        for (k in 0 until samples) {
            val angles = chain.joints.mapIndexed { i, j ->
                val u = halton(k + 1 + (i / BASES.size) * samples, BASES[i % BASES.size])
                j.limit.minRad + u * (j.limit.maxRad - j.limit.minRad)
            }
            val end = chain.pose(angles).endEffectorCm
            val err = abs((end - chain.rootPosCm).norm() - d)
            if (err < best) best = err
            if (best == 0.0 || best < tol) return ReachResult(State.REACHABLE, best, k + 1, SAMPLED)
        }
        return ReachResult(State.UNKNOWN, best, samples, SAMPLED)
    }
}

/** cos 在 [lo, hi] 上的值域：端点值＋区间内经过的极值点（2kπ 处 +1，(2k+1)π 处 −1）。 */
private fun cosRange(lo: Double, hi: Double): Pair<Double, Double> {
    val candidates = ArrayList<Double>()
    candidates.add(cos(lo))
    candidates.add(cos(hi))
    if (containsPeriodic(lo, hi, 2.0 * PI, 0.0)) candidates.add(1.0)
    if (containsPeriodic(lo, hi, 2.0 * PI, PI)) candidates.add(-1.0)
    return (candidates.minOrNull() ?: 0.0) to (candidates.maxOrNull() ?: 0.0)
}

/** 区间 [lo, hi] 是否包含 offset + k·period 的某个整数 k。 */
private fun containsPeriodic(lo: Double, hi: Double, period: Double, offset: Double): Boolean =
    ceil((lo - offset) / period) <= floor((hi - offset) / period)

/** Halton  radical inverse（确定性低差异序列，不用随机数）。 */
private fun halton(index: Int, base: Int): Double {
    var f = 1.0
    var r = 0.0
    var idx = index
    while (idx > 0) {
        f /= base
        r += f * (idx % base)
        idx /= base
    }
    return r
}