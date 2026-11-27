// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百三十九批 · 发育单：一张「到哪一档」，一张「大致什么先后」，最后一行「为什么写」。
//
// 三张表分开的意义：档位表里只有部位与档名（写不出年龄门槛），
// 里程碑表里只有先后（写不出档位高低），目的判定只吃事实（写不出细节多少）。
// 三者不混排成一句，「发育早」就不会被顺手读成「能力强」或「可以写」。
package com.example.zhengdao.baopu.state.bodyparts

/**
 * 一份青春发育史。
 *
 * [tiers] 五维必须齐（缺一构造即抛）；[ageNote] 与 [details] 是**记述**，
 * 年龄数值、身体变化细节、既往史细节都能原样进来——这是撤销过紧口径之后的口径。
 * [milestoneOrder] 是这个人实际的先后；换过顺序照样合法，只标注。
 */
data class PubertyHistory(
    val tiers: Map<PubertyDim, PubertyTier>,
    val ageNote: String? = null,
    val details: List<String> = emptyList(),
    val milestoneOrder: List<PubertyMilestone> = PubertyMilestone.entries,
) {
    init {
        require(tiers.keys == PubertyDim.entries.toSet()) {
            val missing = PubertyDim.entries.filter { it !in tiers.keys }
            "五维一个不漏、一个不多：缺 " + missing.joinToString("、") { it.label }
        }
        require(milestoneOrder.size == PubertyMilestone.COUNT &&
            milestoneOrder.toSet().size == PubertyMilestone.COUNT) {
            "里程碑五条一个不漏、一条不重"
        }
        require(ageNote == null || ageNote.isNotBlank()) { "年龄行要么不写，要么写清" }
        require(details.none { it.isBlank() }) { "细节行不许空" }
    }

    /** 某一维的档位。五维齐全由 init 保证，这里不再兜底。 */
    fun tier(dim: PubertyDim): PubertyTier = tiers.getValue(dim)

    /** 先后是否换过（换过不是错，只标注）。 */
    fun swapped(): Boolean = MilestoneOrder.swapped(milestoneOrder)
}

/** 渲染器：五维档位 + 里程碑 + 目的判定。纯函数，无 IO。 */
object PubertySheet {

    const val HEAD = "== 青春发育（五维只给档）=="
    const val MILESTONE_HEAD = "== 里程碑（先后可换）=="
    const val JUDGE_HEAD = "== 目的判定（未成年＋性目的才红）=="
    const val TAIL = "-- 939 青春发育史结束 --"
    const val BLOCK_PREFIX = "[红] PUBERTY-NOTE-BLOCKED："
    const val AGE_PREFIX = "年龄："

    /**
     * 完整输出：先档位、再里程碑、最后判定。
     *
     * 档位行过 [PubertyGuard.tierLine]（档名不许带判词）；细节行过 [PubertyGuard.noteHit]，
     * 越界的那一行被拦下并留一行红，**其余照常读**——不让一条坏行吃掉整张单。
     */
    fun render(history: PubertyHistory, frame: PurposeFrame): String {
        val out = StringBuilder()
        out.append(HEAD).append('\n')
        PubertyDim.entries.forEach { dim ->
            out.append(PubertyGuard.tierLine(dim, history.tier(dim))).append('\n')
        }
        history.ageNote?.let { appendNote(out, AGE_PREFIX, it) }
        history.details.forEach { appendNote(out, "", it) }

        out.append(MILESTONE_HEAD).append('\n')
        MilestoneOrder.lines(history.milestoneOrder).forEach { out.append(it).append('\n') }
        out.append(MilestoneOrder.note(history.milestoneOrder)).append('\n')

        out.append(JUDGE_HEAD).append('\n')
        val findings = PubertyJudge.check(history, frame)
        findings.forEach { out.append(it.line()).append('\n') }
        out.append(PubertyJudge.summary(findings)).append('\n')
        out.append(TAIL)
        return out.toString()
    }

    /** 档位表单独的文本（人读用，不含判据）。 */
    fun tierTable(history: PubertyHistory): String =
        PubertyDim.entries.joinToString("\n") { PubertyGuard.tierLine(it, history.tier(it)) }

    /** 里程碑表单独的文本（含可换注）。 */
    fun milestoneTable(history: PubertyHistory): String =
        (MilestoneOrder.lines(history.milestoneOrder) + MilestoneOrder.note(history.milestoneOrder))
            .joinToString("\n")

    private fun appendNote(out: StringBuilder, prefix: String, text: String) {
        if (PubertyGuard.noteHit(text) == null) {
            out.append(prefix).append(text).append('\n')
        } else {
            out.append(BLOCK_PREFIX).append(PubertyGuard.BLOCKED_LINE).append('\n')
        }
    }
}
