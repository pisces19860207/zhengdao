// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百七十八批 · 读数消费的**输入快照**（纯数据，零 IO、零 Android）。
//
// 为什么单独造一层快照而不是让调解器直接读 722/753/756/747 的产物：
//   · `:policy` 的白名单只许依赖 `:core`（`PolicyBoundaryGateTest` 钉死），四类产物全在
//     `:app` 且多为 internal —— 结构上够不着，只能由装配层投影成一份**只读事实**递进来。
//   · 快照字段全部带"无"默认值（false/0/null）：provider 未装配 ⇒ 一份全空事实 ⇒
//     三条规则一条都不命中 ⇒ 调解器行为与接线前**逐字节一致**（判据②）。
//   · 本文件零判定、零规则：只描述"读到了什么"，不描述"该怎么办"——判定在 ReadingCodex，
//     决定在 MediatorDecide（判定权不迁，判据⑤）。
package com.example.zhengdao.baopu

/**
 * 一次调解决定所能看到的**读数事实快照**。
 *
 * 四个来源一一对应派工单的输入面（722/753/756/747）；字段默认值就是"这项没读数"，
 * 全默认 ⇒ [ReadingCodex.evaluate] 返回空命中 ⇒ 调解器不参与（effective 不变）。
 */
data class ReadingFacts(
    /** 722：三行读回是否已装配（有上下文/无上下文）。只作来源标注，不改规则判定。 */
    val readbackPresent: Boolean = false,

    /** 753：上一次卡在哪一环（wire 名 retrieval/plan/salience…）；null＝没读到卡步。 */
    val stuckLoop: String? = null,
    /** 753：再上一次的卡步；与 [stuckLoop] 相等才算"卡步相同"。 */
    val previousStuckLoop: String? = null,
    /** 753：同一组参数下的重试次数（含本轮）；规则①的阈值见 [ReadingCodex.RETRY_MIN]。 */
    val sameParamRetries: Int = 0,

    /** 756：连续为负的 RPE 次数（rpe=-1 的连击）；规则②的阈值见 [ReadingCodex.RPE_NEG_MIN]。 */
    val rpeNegativeStreak: Int = 0,
    /** 756：这串负 RPE 的原因是否同一类（cause 指针相同）；不同来源不算"原因相同"。 */
    val rpeCauseSame: Boolean = false,

    /** 747：同一关系**已越线但拿不出证据**（crossed 为真且 evidence 为空）；规则③的开关。 */
    val crossedWithoutEvidence: Boolean = false,
) {
    /** 是否一项读数都没有（全默认）——供判定层一眼短路，避免空事实也算"读数参与"。 */
    val isEmpty: Boolean get() =
        !readbackPresent && stuckLoop == null && previousStuckLoop == null &&
            sameParamRetries == 0 && rpeNegativeStreak == 0 && !rpeCauseSame &&
            !crossedWithoutEvidence
}
