// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第二百三十九批 I6 · 影子执行不符（**巡按侧**）。
//
// [ShadowRehearsal] 是执行**前**的判定（纠察只出定性，阻断归管线）；本文件是巡按**后**的复核：
// 拿影子自己的账（[ShadowLedger]）× 写侧动作痕迹（`run_log` 的 Acting 行）对表，查两种事：
// - **不一致却执行了**：影子里已经写着 MISMATCH/MISWIRED，同一个 (会话,步骤) 却留下了真动作 ⇒
//   §9 负例④「不一致还放行」在生产账上的形状；
// - **该预演没预演**：按 §9 该跑影子的动作（R2+ 或不可逆）在影子台账里一行都没有。
//
// 三条纪律同 I1：只读、只吃日志与账、**影子台账空着不许冒充查过**（那是 [InspectVerdict.NO_DATA]，
// 不是清白——装配层还没接 I6 这件事本身就长在这一态里，一眼看得见）。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.core.TimeFormats

/** 一条需要影子预演的写侧动作痕迹（[requiresShadow] 由调用方按 219 的档与可逆性算好传入）。 */
internal data class ShadowTrace(
    val atMs: Long,
    val sessionId: Long,
    val step: Int,
    val tool: String,
    val requiresShadow: Boolean,
)

/** I6 比对的产物。 */
internal data class ShadowGapResult(
    /** 影子里写着不符，却还是执行了的那些。 */
    val released: List<ShadowEntry>,
    /** 该跑影子却没跑的那些动作。 */
    val missing: List<ShadowTrace>,
    /** 影子跑了但结论「验不了」的条数（降整项为 [InspectVerdict.NO_DATA]，不进命中）。 */
    val unverifiable: Int,
    /** 参与比对的影子记录条数。 */
    val checked: Int,
)

internal object ShadowCheck {

    /** 痕迹 ↔ 影子记录的联键（与 I1/I4 同一把：`(会话,步骤)`）。 */
    fun keyOf(t: ShadowTrace): String = ShadowLedger.keyOf(t.sessionId, t.step)

    /** 交叉比对（纯函数）。 */
    fun compare(traces: List<ShadowTrace>, entries: List<ShadowEntry>): ShadowGapResult {
        val needed = traces.filter { it.requiresShadow }
        val byKey = entries.associateBy { it.key }
        val released = needed.mapNotNull { byKey[keyOf(it)] }.filter { it.violated }.distinctBy { it.id }
        val missing = needed.filter { keyOf(it) !in byKey.keys }
        return ShadowGapResult(
            released = released.sortedBy { it.atMs },
            missing = missing.sortedBy { it.atMs },
            unverifiable = entries.count { it.verdict == ShadowVerdict.UNVERIFIABLE },
            checked = entries.size,
        )
    }

    /** 一行明细（只进巡按栏）。 */
    fun releasedEvidence(e: ShadowEntry): String =
        "${TimeFormats.monthDay(e.atMs)} ${TimeFormats.hourMinute(e.atMs)} " +
            "影子=${e.verdict.cn} 仍执行 ${e.actions.firstOrNull() ?: e.riskWire}（会话 ${e.sessionId}·步 ${e.step}）"

    /** 一行明细。 */
    fun missingEvidence(t: ShadowTrace): String =
        "${TimeFormats.monthDay(t.atMs)} ${TimeFormats.hourMinute(t.atMs)} " +
            "${t.tool} 该跑影子（R2+/不可逆），影子台账无行（会话 ${t.sessionId}·步 ${t.step}）"

    /** 一条勘验结论。 */
    fun check(traces: List<ShadowTrace>, entries: List<ShadowEntry>): InspectFinding {
        val needed = traces.count { it.requiresShadow }
        if (needed == 0) {
            return InspectFinding(
                check = InspectCheck.SHADOW_MISMATCH,
                verdict = InspectVerdict.CLEAR,
                oneLine = InspectText.line("影子执行：窗口内没有 R2+/不可逆动作"),
                suggestion = "无需处理",
                count = 0,
            )
        }
        if (entries.isEmpty()) {
            return InspectFinding(
                check = InspectCheck.SHADOW_MISMATCH,
                verdict = InspectVerdict.NO_DATA,
                oneLine = InspectText.line("影子执行：影子台账 0 行，无从比对"),
                suggestion = "I6 还没接进执行前（挂接点＝GuardPipeline act 之前），先装配再巡",
                evidence = listOf("该跑影子的动作 $needed 条", "影子记录 0 条"),
                count = 0,
            )
        }
        val res = compare(traces, entries)
        val hits = res.released.size + res.missing.size
        val evidence = res.released.take(InspectDetailLimits.MAX_EVIDENCE_PER_FINDING).map(::releasedEvidence) +
            res.missing.take(InspectDetailLimits.MAX_EVIDENCE_PER_FINDING).map(::missingEvidence) +
            if (res.unverifiable > 0) listOf("另有 ${res.unverifiable} 条影子跑不出结论（不计入命中）") else emptyList()
        return when {
            hits > 0 -> InspectFinding(
                check = InspectCheck.SHADOW_MISMATCH,
                verdict = InspectVerdict.HIT,
                oneLine = InspectText.line(lineOf(res.released.size, res.missing.size)),
                suggestion = "放行侧：act 之前必须读影子判定；缺口侧：R2+/不可逆一律先跑影子再动真盘",
                evidence = evidence,
                count = hits,
            )
            // 「验不了」不许冒充清白：全过且没有跑不出结论的，才算查过没问题。
            res.unverifiable > 0 -> InspectFinding(
                check = InspectCheck.SHADOW_MISMATCH,
                verdict = InspectVerdict.NO_DATA,
                oneLine = InspectText.line("影子执行 ${res.unverifiable} 条跑不出结论"),
                suggestion = "补上影子的实测读数（推演标了未知/没查回滚点/没有验收判据）",
                evidence = evidence,
                count = 0,
            )
            else -> InspectFinding(
                check = InspectCheck.SHADOW_MISMATCH,
                verdict = InspectVerdict.CLEAR,
                oneLine = InspectText.line("影子执行 ${res.checked} 条全与推演一致"),
                suggestion = "无需处理",
                evidence = evidence,
                count = 0,
            )
        }
    }

    /**
     * 那一行的字（两种命中分开说，别混成一句"影子有问题"）。
     *
     * 「不一致却执行」比「没预演」严重一级：前者是闸形同虚设，后者是闸还没装。
     */
    fun lineOf(released: Int, missing: Int): String = when {
        released > 0 && missing > 0 -> "影子 $released 条不符仍执行／$missing 条没预演"
        released > 0 -> "影子不符却放行 $released 条"
        else -> "该预演没预演 $missing 条"
    }
}
