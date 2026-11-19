// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-11-19-第七百二十八批 §一④（与 727 撞击档联动）。
//
// 727（撞击响应）本批尚未落代码，只有派工单。这里按它写死的冲击档 0–4 立一只读缝：
// 观察档吃外部喂进来的冲击档，**自己不算冲击**。727 合入后把 [UnwiredImpact]
// 换成真实现即可，三张映射表与判据一个字都不用改。
package com.example.zhengdao.baopu.state.injury

/** 冲击档来源（只读一个整数）。727 未合入时只有 [UnwiredImpact]。 */
internal interface ImpactTierSource {
    fun tier(): Int
}

/** 未接 727 的默认源：恒 0 档。这是「没接」的明示，不是「算出没有冲击」。 */
internal object UnwiredImpact : ImpactTierSource {
    override fun tier(): Int = 0
}

/** 冲击档 0–4 → 三套观察起点的分段映射（可查、单调，越界钳到端点）。 */
internal object ImpactLink {
    const val MAX_TIER = 4

    private val INJURY_START = intArrayOf(0, 0, 1, 2, 3)
    private val PAIN_START = intArrayOf(0, 0, 1, 2, 3)
    private val RECOVERY_START = intArrayOf(0, 0, 1, 2, 2)

    fun injuryStart(tier: Int): Int = INJURY_START[clamp(tier)]

    fun painStart(tier: Int): Int = PAIN_START[clamp(tier)]

    fun recoveryStart(tier: Int): RecoveryGrade = RecoveryGrade.values()[RECOVERY_START[clamp(tier)]]

    private fun clamp(tier: Int): Int = tier.coerceIn(0, MAX_TIER)
}
