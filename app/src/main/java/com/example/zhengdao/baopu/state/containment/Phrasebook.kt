// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百四十一批 · 形轨.空间容纳与受限姿态：文案表（判据③的唯一出处）。
// 三种三态各有自己的开头词，不许串用：进得去不写「勉强」，勉强不写「毫无阻碍」，
// 进不去不写「顺利完成」。文案只由本表生成，`ContainmentVerdict` 构造时会逐字校对——
// 手写一句好看的结论会当场抛异常（这也是判据③的第一道牙）。
package com.example.zhengdao.baopu.state.containment

import com.example.zhengdao.baopu.state.geometry.GeometryNotes

/** 结论文案的生成器：状态开头词 ＋ 受限原因逐个点名。 */
object Phrasebook {

    /** 进得去时的整句（不含任何「受限」字样）。 */
    const val FIT_TEXT: String = "进得去：档位与姿态都够，不必将就"

    /** 判据③的禁词表：状态 → 该状态下**不许出现**的词。 */
    val FORBIDDEN: Map<ContainmentState, List<String>> = linkedMapOf(
        ContainmentState.FIT to listOf("勉强", "进不去", "受限原因"),
        ContainmentState.MARGINAL to listOf("毫无阻碍", "顺利完成", "进得去"),
        ContainmentState.NOFIT to listOf("顺利完成", "毫无阻碍", "进得去"),
    )

    fun of(
        state: ContainmentState,
        reasons: List<LimitReason>,
        audit: StanceAudit,
    ): String = when (state) {
        ContainmentState.FIT -> FIT_TEXT
        ContainmentState.MARGINAL -> "勉强：受限原因＝" + points(reasons, audit)
        ContainmentState.NOFIT -> "进不去：受限原因＝" + points(reasons, audit)
    }

    private fun points(reasons: List<LimitReason>, audit: StanceAudit): String =
        reasons.joinToString("、") { phrase(it, audit) }

    /** 合法性两项点名是哪一项没过／哪一项没证明，别让人只看到一句笼统的「不合法」。 */
    private fun phrase(reason: LimitReason, audit: StanceAudit): String = when (reason) {
        LimitReason.LEGALITY_FAILED -> "姿态不合法（" + labels(audit.failures) + "）"
        LimitReason.LEGALITY_UNPROVEN -> "姿态合法性未证明（" + labels(audit.unproven) + "）"
        else -> reason.label
    }

    private fun labels(items: List<AuditItem>): String = items.joinToString("、") { it.label }

    /** 每类结论都带口径与「模拟非测量」，沿用 655/686 的读法。 */
    const val ASSUMPTION: String =
        "三态＝空间三轴档差＋开口/朝向相容性＋655 四项合法性（限位/可达/自碰撞/穿地）的合成；" +
            "只判档与三态，不出坐标与尺寸；同输入必得同结论。"

    val NOTE: String = GeometryNotes.NOT_MEASUREMENT
}
