// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百四十一批 · 形轨.空间容纳与受限姿态：结论与其自检。
// `ContainmentVerdict` 一构造就做四件事：原因去重排序、三态与原因自洽、
// **非法姿态必判进不去**（判据①）、文案必须是文案表生成的那一句（判据③）。
// 结论对象里没有任何浮点、坐标、长度字段（判据④）——它只装档、三态、原因与一句话。
package com.example.zhengdao.baopu.state.containment

/** 一次判定的结论：三态＋受限原因＋参与判定的输入快照（空间档／站位／朝向／四项台账）。 */
data class ContainmentVerdict(
    val state: ContainmentState,
    val reasons: List<LimitReason>,
    val space: SpaceProfile,
    val stance: Stance,
    val facing: Facing,
    val audit: StanceAudit,
    val text: String,
    val assumption: String = Phrasebook.ASSUMPTION,
    val note: String = Phrasebook.NOTE,
) {
    init {
        require(audit.stance == stance) { "台账的姿态 ${audit.stance.label} 与被判的姿态 ${stance.label} 不是同一个" }
        require(reasons == reasons.distinct().sortedBy { it.ordinal }) { "受限原因须去重且按固定次序" }
        require(state == ContainmentState.FIT || reasons.isNotEmpty()) { "勉强与进不去都必须给受限原因" }
        require(state != ContainmentState.FIT || reasons.isEmpty()) { "进得去不许带受限原因" }
        require(state != ContainmentState.FIT || audit.allPass) { "655 未判过四项 ⇒ 不许说进得去" }
        require(audit.failures.isEmpty() || state == ContainmentState.NOFIT) { "655 判姿态非法 ⇒ 三态必为进不去" }
        require(text == Phrasebook.of(state, reasons, audit)) { "文案只能由文案表生成，不许手写：$text" }
        val banned = Phrasebook.FORBIDDEN.getValue(state).filter { it in text }
        require(banned.isEmpty()) { "${state.label} 的文案里不许出现 $banned" }
    }

    /** 一行的机读快照（只含档名、三态与文案，用于两跑逐字比对）。 */
    fun line(): String = "${stance.label}| ${state.label}| $text| " +
        audit.readings.joinToString(" ") { "${it.item.label}=${it.state.label}" }
}

/**
 * 一个空间档 × 一个朝向的总表：四档站位各一条结论。
 *
 * 构造即要求四档齐备：这就是「站位档每档必须过 655 四项」的落点——少一档台账，
 * 总表根本构造不出来，而不是悄悄少写一行。
 */
data class ContainmentSurvey(
    val space: SpaceProfile,
    val facing: Facing,
    val verdicts: List<ContainmentVerdict>,
) {
    init {
        require(verdicts.size == Stance.entries.size) { "四档站位各要一条结论，实得 ${verdicts.size} 条" }
        require(verdicts.map { it.stance }.toSet() == Stance.entries.toSet()) { "四档各一条：重复或缺失都不许" }
        require(verdicts.all { it.space == space && it.facing == facing }) { "总表里不许混进别处判的结论" }
        require(verdicts.map { it.stance } == Stance.entries.toList()) { "次序按站位枚举，保证两跑逐字一致" }
    }

    /** 能用的档（进得去或勉强），按站位次序。 */
    val usable: List<Stance> get() = verdicts.filter { it.state != ContainmentState.NOFIT }.map { it.stance }

    fun verdictOf(stance: Stance): ContainmentVerdict = verdicts.first { it.stance == stance }

    /** 整表快照：空间档／朝向 ＋ 四行结论。 */
    fun snapshot(): String = buildString {
        append(space.label).append("| ").append(facing.label).append('\n')
        for (v in verdicts) append(v.line()).append('\n')
    }
}
