// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百八十六批 · 形轨.器官代理与腔隙碰撞：口径常数的唯一居所。
// 容差／上限／轮次一律不进函数签名——否则同一批数据能被算出两个答案（判据⑥确定性）。
package com.example.zhengdao.baopu.state.cavity

/** 686 全部固定口径。接触点数口径来源：686 澄清 2026-11-20（原文摘要见交付报告）。 */
object CavityTolerances {

    /**
     * ε 的相对系数：某特征点算不算「同一次接触」，判据是
     * `该点深度 ≥ 本对最大深度 − CONTACT_EPS_REL × 出点方包围盒对角线`。
     * 固定常数，不许按场次调。
     */
    const val CONTACT_EPS_REL = 1e-3

    /** 凸包特征点上限：超出按规范字典序取前 N，并在读数里记 truncated 与被截掉的个数。 */
    const val HULL_FEATURE_CAP = 12

    /** 细判接触容差：0＝恰好接触算接触，不放额外余量（与 655 EPA 的接触阈值同口径）。 */
    const val CONTACT_TOL_CM = 0.0

    /** 腔隙档位：空隙 ≥ 该毫米数记 OPEN，(0, 该数) 记 NARROW，≤ 0 记 BLOCKED。 */
    const val CAVITY_OPEN_MILLI = 10

    /** 重力落定的贴地／互撑容差（厘米）：落定读数按此容差判「还穿不穿」。 */
    const val SETTLE_TOL_CM = 1e-7

    /** 可达项转交 655 Reachability 时的容差与采样数（本单不按场次调）。 */
    const val REACH_TOL_CM = 0.5
    const val REACH_SAMPLES = 1024

    /** 深度口径：厘米 → 整数毫米的倍率。 */
    const val MILLIMETRE_PER_CM = 10.0
}

/**
 * 厘米 → 整数毫米（四舍五入到最近整数，.5 向上）。
 * 带符号量（间隙、重叠）用它；成对穿透深度的非负／负零校验在 ContactProbe.depthMilli。
 */
internal fun signedMilli(cm: Double): Int = Math.round(cm * CavityTolerances.MILLIMETRE_PER_CM).toInt()
