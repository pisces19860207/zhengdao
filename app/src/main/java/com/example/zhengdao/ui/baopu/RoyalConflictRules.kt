// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十七批（冲突入御案 · 卡面判据层）：把一条 [OpinionConflict] 拟成**既有御案卡**
// （[RoyalCard]，三行＝是什么／为什么冲突／不理会怎样），渲染仍走 [BaopuRoyalCard]——
// 不新造卡型、不新造容器（派工单 §一①「复用既有御案行渲染」）。
//
// 本文件与其它 *Rules 同一纪律：不许 @Composable、不许拿 Context、不许碰 IO；
// 文案全走 `R.string`（中英双份），卡面 ≤30 字与"无技术字段"由 [RoyalCardGate] 兜底。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.R
import com.example.zhengdao.baopu.inspect.OpinionConflict
import com.example.zhengdao.baopu.inspect.Source

/** 御案页签「冲突」组里的一行（卡＋原冲突；裁决时要拿回两侧做账）。 */
internal data class RoyalConflictRow(
    val id: String,
    val card: RoyalCard,
    val atMs: Long,
    val conflict: OpinionConflict,
)

internal object RoyalConflictRules {

    /**
     * 这一刻该上卡的冲突（**空列表 ⇒ 组不渲染**；已裁过的按御批账压制）。
     *
     * 顺序新→旧；不合规的卡不画（与 `RoyalQueueRules.prepare` 同一口径）。
     */
    fun rows(
        conflicts: List<OpinionConflict>,
        suppressed: Set<String>,
        s: DecisionStrRes,
    ): List<RoyalConflictRow> = conflicts
        .filter { it.id !in suppressed }
        .sortedByDescending { it.at }
        .mapNotNull { rowOf(it, s) }

    /** 单条：拟卡＋卡面自检；不合规 ⇒ null（一个像素都不画）。 */
    fun rowOf(conflict: OpinionConflict, s: DecisionStrRes): RoyalConflictRow? {
        val card = cardOf(conflict, s)
        if (!RoyalCardGate.renderable(card)) return null
        return RoyalConflictRow(conflict.id, card, conflict.at, conflict)
    }

    /** 三行式卡面；真假标注一律「未核实」（分歧本身没有谁真谁假，不冒充已核）。 */
    fun cardOf(conflict: OpinionConflict, s: DecisionStrRes): RoyalCard = RoyalCard(
        key = conflict.id,
        lines = listOf(
            RoyalLine(
                RoyalLineKind.WHAT,
                s.get(R.string.royal_conflict_what, conflict.a.gist, conflict.b.gist),
                RoyalTruth.UNVERIFIED,
            ),
            RoyalLine(
                RoyalLineKind.WHY,
                s.get(
                    R.string.royal_conflict_why,
                    sourceLabel(conflict.a.source, s),
                    sourceLabel(conflict.b.source, s),
                ),
                RoyalTruth.UNVERIFIED,
            ),
            RoyalLine(
                RoyalLineKind.IMPACT,
                s.get(R.string.royal_conflict_impact),
                RoyalTruth.UNVERIFIED,
            ),
        ),
        // 动作复用既有御案通道：批／驳／搁置（默认搁置，与普通御案卡同一条规矩）
        options = RoyalDrafts.options(s),
        // 技术字段只活在这里
        evidence = conflict.evidence + "conflict:${conflict.id}",
    )

    /** 来源 → 人话（枚举名不许上卡面；`RoyalCardGate.looksTechnical` 会拒）。 */
    fun sourceLabel(source: Source, s: DecisionStrRes): String = when (source) {
        Source.CLASSIFIER -> s.get(R.string.royal_conflict_src_classifier)
        Source.PATROL -> s.get(R.string.royal_conflict_src_patrol)
        Source.AUDIT -> s.get(R.string.royal_conflict_src_audit)
        Source.SHADOW -> s.get(R.string.royal_conflict_src_shadow)
        Source.SCOUT -> s.get(R.string.royal_conflict_src_scout)
    }
}
