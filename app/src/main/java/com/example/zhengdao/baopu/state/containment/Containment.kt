// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百四十一批 · 形轨.空间容纳与受限姿态：三态判定本体。
// 判的是「这个空间里这个姿势够不够得着」，不是「能不能挤进去」：
//   ① 开口位置与朝向先过相容性（硬冲突 ⇒ 进不去）
//   ② 三轴档差各算一次：差一档 ⇒ 勉强（高度差一档且弯得下腰则记「需弯腰」），差两档以上 ⇒ 进不去
//   ③ 655 四项台账：不过 ⇒ 进不去；未证明 ⇒ 最多勉强（未证明既不是过，也不是不过）
//   ④ 可达项单列：空间够但够不着，照样进不去——这就是姿态与空间的耦合
// 全是档与三态的运算，不出现坐标与尺寸；同输入必得同结论。
package com.example.zhengdao.baopu.state.containment

/** 三态判定与总表。 */
object Containment {

    /** 单档判定：空间档 ＋ 站位 ＋ 朝向 ＋ 该站位的四项台账 ⇒ 三态结论。 */
    fun judge(
        space: SpaceProfile,
        stance: Stance,
        facing: Facing,
        audit: StanceAudit,
    ): ContainmentVerdict {
        require(audit.stance == stance) { "台账姿态 ${audit.stance.label} 与被判姿态 ${stance.label} 不一致" }
        val demand = StanceTable.of(stance)
        val reasons = reasonsOf(space, stance, facing, audit)
        val state = stateOf(reasons, demand, space)
        return ContainmentVerdict(
            state = state,
            reasons = reasons,
            space = space,
            stance = stance,
            facing = facing,
            audit = audit,
            text = Phrasebook.of(state, reasons, audit),
        )
    }

    /** 一个空间档 × 一个朝向的总表：四档各判一次。四档台账缺一即拒。 */
    fun survey(space: SpaceProfile, facing: Facing, audits: Map<Stance, StanceAudit>): ContainmentSurvey {
        require(audits.keys == Stance.entries.toSet()) {
            "四档站位的四项台账必须齐备：缺 ${Stance.entries.filter { it !in audits }.map { it.label }}"
        }
        val verdicts = Stance.entries.map { judge(space, it, facing, audits.getValue(it)) }
        return ContainmentSurvey(space, facing, verdicts)
    }

    /** 受限原因全集：开口／朝向硬冲突 ＋ 三轴档差 ＋ 四项台账 ＋ 可达单列（去重后按固定次序）。 */
    fun reasonsOf(
        space: SpaceProfile,
        stance: Stance,
        facing: Facing,
        audit: StanceAudit,
    ): List<LimitReason> {
        val demand = StanceTable.of(stance)
        val out = ArrayList<LimitReason>()
        if (space.opening !in demand.openings) out += LimitReason.OPENING_POSITION_CONFLICT
        if (facing !in demand.facings) out += LimitReason.FACING_CONFLICT
        out += axisReasons(space, demand)
        out += legalityReasons(audit)
        return out.distinct().sortedBy { it.ordinal }
    }

    private fun axisReasons(space: SpaceProfile, demand: StanceDemand): List<LimitReason> {
        val out = ArrayList<LimitReason>()
        val height = shortageOf(demand.height, space.height)
        if (height > 0) out += heightReason(height, demand.bend)
        if (shortageOf(demand.width, space.width) > 0) out += LimitReason.OPENING_NARROW
        if (shortageOf(demand.depth, space.depth) > 0) out += LimitReason.DEPTH_SHORT
        return out
    }

    /** 高度差一档且这一档弯得下腰 ⇒ 记「需弯腰」；弯不下或差两档以上 ⇒ 记「高度不够」。 */
    private fun heightReason(shortage: Int, bend: BendAllowance): LimitReason =
        if (shortage == 1 && bend != BendAllowance.NONE) LimitReason.NEEDS_BEND else LimitReason.HEIGHT_SHORT

    /** 限位／自碰撞／穿地三项合成一条「姿态不合法」；可达单列，因为它是「够不着」而不是「站不住」。 */
    private fun legalityReasons(audit: StanceAudit): List<LimitReason> {
        val out = ArrayList<LimitReason>()
        for (item in POSTURE_ITEMS) {
            when (audit.stateOf(item)) {
                AuditState.FAIL -> out += LimitReason.LEGALITY_FAILED
                AuditState.UNPROVEN -> out += LimitReason.LEGALITY_UNPROVEN
                AuditState.PASS -> Unit
            }
        }
        when (audit.stateOf(AuditItem.REACH)) {
            AuditState.FAIL -> out += LimitReason.REACH_SHORT
            AuditState.UNPROVEN -> out += LimitReason.REACH_UNPROVEN
            AuditState.PASS -> Unit
        }
        return out
    }

    private fun stateOf(
        reasons: List<LimitReason>,
        demand: StanceDemand,
        space: SpaceProfile,
    ): ContainmentState {
        if (reasons.any { it.hard }) return ContainmentState.NOFIT
        if (worstShortage(demand, space) >= 2) return ContainmentState.NOFIT
        return if (reasons.isEmpty()) ContainmentState.FIT else ContainmentState.MARGINAL
    }

    /** 三轴里最糟的那一档差：差两档以上就不再谈「将就」。 */
    private fun worstShortage(demand: StanceDemand, space: SpaceProfile): Int = maxOf(
        shortageOf(demand.height, space.height),
        shortageOf(demand.width, space.width),
        shortageOf(demand.depth, space.depth),
    )

    /** 「站得住」的三项（可达不在此列，它是「够得着」）。 */
    private val POSTURE_ITEMS = listOf(AuditItem.LIMIT, AuditItem.SELF_COLLISION, AuditItem.GROUND)
}
