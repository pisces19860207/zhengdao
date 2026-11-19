// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第七百四十七批 · 人物锚定：越界与回流引擎（派工单②③⑤，判据②③④⑤的正面）。
// 状态集**只用 670 的 RelationTrack 11 态**（T0 T3 T4 T4P T5 T6 TE1 TE2 TG TT TX），
// 本文件一个态都不新增、一条边都不改（拓扑锁死，改须人批＋升 TRACK_SPEC_MAJOR）。
//
// 与 670 的关系：包一层，不改它。RelationTrack.step 已经会验「边存在＋guard 证据齐」，
// 本引擎只往上加派工单要求的三件事：
// · 判据② 每一次跨格都要带证据，连 670 表里 guard=null 的边也不例外（规格 §5·补：看触发牌，不看惯性）；
// · 触发牌闸 进 T4 必须出示 §五 的那五张牌之一，不许"自动推进"；
// · 回流闸 TX 的出边必须出示「全中」——回流三条件凑齐才许走（派工单⑤，X≠终点）。
// 过了闸才落账（CrossLedger），账本里因此只有真发生过的跨格，没有引擎自己造的。
package com.example.zhengdao.baopu.state.anchor

import com.example.zhengdao.baopu.state.track.RelationEdge
import com.example.zhengdao.baopu.state.track.RelationModifier
import com.example.zhengdao.baopu.state.track.RelationState
import com.example.zhengdao.baopu.state.track.RelationTable
import com.example.zhengdao.baopu.state.track.RelationTrack

/** 环境三条件（§5·补三 那张表的三行；让 3→4 变容易的是环境，不是身份）。 */
enum class EnvCondition(val cn: String) {
    ABSENT_WOMEN("女性长期缺席"),
    HEAVY_CONTACT("高强度身体接触"),
    LIFE_BOND("生死相依"),
}

/** 一次跨格的结论：要么带记录，要么带拒因，不存在第三种（不许静默推进）。 */
data class Crossing(
    val accepted: Boolean,
    val fromState: RelationState,
    val toState: RelationState,
    val evidence: List<String> = emptyList(),
    val reason: String,
    val record: CrossRecord? = null,
) {
    val refused: Boolean get() = !accepted
    val grid: String get() = AnchorTrack.gridOf(if (accepted) toState else fromState)
}

/**
 * 越界引擎。写状态唯一入口是 step(to, evidence, who)——
 * 没有任何「无证据推进」「定时推进」「按档案推算推进」的口子（判据② 的负例就是这么设计的）。
 */
class AnchorTrack(
    val profile: AnchorProfile,
    modifier: RelationModifier? = null,
    val ledger: CrossLedger = CrossLedger(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val relation = RelationTrack(modifier)

    /** 当前格（670 的 11 态之一，初值 T0）。 */
    val state: RelationState get() = relation.state

    /** T4 越界是否已发生（670 的不可逆标记，一次即永久）。 */
    val crossed: Boolean get() = relation.crossed

    /** 现在有哪些出边：只回答「规格允许往哪儿」，不替你走。 */
    fun edges(): List<RelationEdge> = RelationTable.EDGES.filter { it.from == state }

    /** 目标格要求出示什么（guard 原文＋本引擎加的三道闸），给面板和测试用。 */
    fun requiredEvidence(to: RelationState): List<String> {
        val edge = edges().firstOrNull { it.to == to } ?: return emptyList()
        val needs = mutableListOf(edge.guard ?: "")
        if (to == RelationState.T4) needs += TRIGGER_CARD
        if (edge.from == RelationState.TX) needs += BACKFLOW_FULL
        return needs.filter { it.isNotEmpty() }
    }

    /** 提议跨到 to：证据不齐一律拒（拒因写清缺哪一样），过了闸才进账。 */
    fun step(to: RelationState, evidence: List<String>, who: String, at: Long = now()): Crossing {
        val from = state
        val refusal = gate(to, evidence)
        if (refusal != null) return Crossing(false, from, to, evidence, refusal)
        if (!relation.step(to, evidence.toSet())) {
            return Crossing(false, from, to, evidence, "670 引擎拒：guard 未满足（证据没带住那条边）")
        }
        val record = ledger.append(from, to, evidence, who, at)
        return Crossing(true, from, to, evidence, "已进账 seq=${record.seq}，现在在${gridOf(to)}", record)
    }

    /** 判据⑤：给时间点重建「当时第几格＋当时七维」。 */
    fun replay(at: Long): Replay = ledger.replay(at)

    /** 把当前七维存一条快照（回放的另一半；档案改过就要重新存）。 */
    fun noteDimensions(at: Long = now()): DimSnapshot = ledger.appendSnapshot(profile, at)

    /** 回流三条件的判定串（照抄 670 的 RelationTable，本批不改语义）。 */
    fun backflowToken(innerScoreAtLeast60: Boolean, crossedFourth: Boolean, endedBadly: Boolean): String =
        RelationTable.backflowEvidence(innerScoreAtLeast60, crossedFourth, endedBadly)

    /** 环境证据词（§5·补三）：调用方拿它组 evidence，引擎自己不判断环境。 */
    fun envEvidence(present: Set<EnvCondition>): List<String> = present.map { it.cn }

    // ── 闸（判据②③⑤）───────────────────────────────────────────────

    /** 返回拒因；null＝闸全过。不可逆臂排在边臂之前，否则拒因会说谎（明明是越界过，却报「没这条边」）。 */
    private fun gate(to: RelationState, evidence: List<String>): String? {
        if (crossed && to in PRE_CROSS) return "已越界（T4 不可逆），不许回到越界前的格"
        val edge = edges().firstOrNull { it.to == to } ?: return "规格没有这条边：$state→$to（不许另造转移）"
        if (evidence.none { it.isNotBlank() }) return "没出示证据，拒跨格（判据②：不许自动推进）"
        if (to == RelationState.T4 && evidence.none { it in RelationTable.TRIGGER_CARDS }) {
            return "进第 4 格要出示触发牌，五张之一：${RelationTable.TRIGGER_CARDS.joinToString("／")}"
        }
        if (edge.from == RelationState.TX && BACKFLOW_FULL !in evidence) {
            return "X 不是终点，但出边要回流三条件凑齐：出示「$BACKFLOW_FULL」（缺一条走 TE1）"
        }
        return if (edge.guard != null && edge.guard !in evidence) "guard「${edge.guard}」未在证据里" else null
    }

    companion object {
        /** 进第 4 格要出示的东西：§五 的五张触发牌（牌名在 670 的 RelationTable，这里不重抄一份）。 */
        const val TRIGGER_CARD = "触发牌"

        /** TG→T4P 的 guard 原文＝回流三条件全中（670 的串，照用）。 */
        const val BACKFLOW_FULL = "全中"

        /** 账本为空时的回放读法。 */
        const val GRID_EMPTY = "轨迹还没起步（无记录，不编初始态）"

        /**
         * 越界前的那几个态。判定权在 670（RelationTrack 自己那份是 private，这里不替它改），
         * 本引擎只用它把**拒因说准**：已越界时先报「不可逆」，别让「没有这条边」顶在前面。
         */
        val PRE_CROSS = setOf(RelationState.T0, RelationState.T3, RelationState.TT)

        /** 「第几格」：规格只给 T0／T3／T4／T5／T6 编了号，其余态没给格号 ⇒ ○ 待核，不替规格补。 */
        fun gridOf(state: RelationState): String = when (state) {
            RelationState.T0 -> "第0格"
            RelationState.T3 -> "第3格"
            RelationState.T4 -> "第4格"
            RelationState.T5 -> "第5格"
            RelationState.T6 -> "第6格"
            else -> "${state.name}（规格未给格号 ⇒ ○ 待核）"
        }
    }
}
