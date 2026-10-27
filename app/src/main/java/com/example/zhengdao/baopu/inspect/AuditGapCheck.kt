// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十九批 I1 · 审计漏行。
//
// 查什么（派工单 §1 原行）：**有动作痕迹（工具调用/文件变更）但 208 没有对应记录**，
// 交叉比对 `run_log` × 208，**漏行 ≥1 就报**。
//
// 两边的房型（都是从代码里读出来的，不是本文件自定的）：
// - 痕迹侧：`RunEngine.persistToolExchange` 在**一个事务**里落
//   `part(kind='tool')` ＋ `run_log(state='Acting', tool=<名>, ok=<成/败>, created_at=<now>)`；
//   写侧工具（`ToolSpec.readOnly == false`）成功一次 ⇒ 必然有一条 Acting 行。
// - 账本侧：`GuardPipeline.finish` 对同一个成功写动作落一条
//   `DecisionKind.AUTO_ACT`（`actor` 恒 MODEL、`evidence = "tool:<名>:<目标>"`、时刻就在前面几毫秒）。
//   两边在同一次工具交换里 ⇒ 按「同名 ＋ 时刻容差」配对，配不上的就是漏行。
//
// 三条纪律：
// 1. **只读**：本文件一个写出口都没有，改账不归纠察（§2.3）；
// 2. 输入只有日志与账本（§7.3 第三条），模型的自述一个字都不吃；
// 3. 账窗之外不冒充看见——208 的读盘是有上限的，比最老一行更早的动作**不算漏行**，
//    只在明细里如实标「超出可见账窗」。漏报一条比误报一条好修，反过来才是骗人。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.core.TimeFormats

/** 一条写侧动作痕迹（从 `run_log` 的 Acting 行折出来的最小形状）。 */
internal data class WriteTrace(
    val atMs: Long,
    val tool: String,
    val sessionId: Long,
    val step: Int,
)

/** I1 的结果（[gaps] 是漏行痕迹，[beyondLedgerWindow] 是账窗盖不住的行数）。 */
internal data class AuditGapResult(
    val gaps: List<WriteTrace>,
    val checked: Int,
    val beyondLedgerWindow: Int,
)

internal object AuditGapCheck {

    /** `GuardPipeline` 写进 `evidence` 的前缀（逐字取自 `GuardPipeline.kt:463`，别改一个字母）。 */
    const val EVIDENCE_PREFIX = "tool:"

    /**
     * 从 208 的 `evidence` 里取工具名。
     *
     * 房型是 `tool:<name>:<target>`，而 target 本身可能带冒号（路径里带时间的很少，但
     * `C:\` 这种会）⇒ 只切第一段，剩下的原样留着。不是这个房型 ⇒ null（不当成任何工具的账）。
     */
    fun toolOfEvidence(evidence: String): String? {
        if (!evidence.startsWith(EVIDENCE_PREFIX)) return null
        val rest = evidence.substring(EVIDENCE_PREFIX.length)
        val name = rest.substringBefore(':').trim()
        return name.ifEmpty { null }
    }

    /**
     * 交叉比对（纯函数，手搓两边即可）。
     *
     * @param acts 写侧动作痕迹（调用方已按 `readOnly == false` 与 `ok == true` 筛过）
     * @param ledger 208 的决策记录（新→旧或旧→新都行，本函数自己排）
     * @param toleranceMs 配对容差
     */
    fun compare(
        acts: List<WriteTrace>,
        ledger: List<DecisionRecord>,
        toleranceMs: Long = InspectLimits.GAP_TOLERANCE_MS,
    ): AuditGapResult {
        val booked = ledger.filter { it.kind == DecisionKind.AUTO_ACT }
            .mapNotNull { rec -> toolOfEvidence(rec.evidence)?.let { it to rec.atMs } }
            .sortedBy { it.second }
        if (booked.isEmpty()) return AuditGapResult(emptyList(), 0, acts.size)

        val visibleFrom = booked.first().second - toleranceMs
        val inWindow = acts.filter { it.atMs >= visibleFrom }
        val taken = BooleanArray(booked.size)
        val gaps = mutableListOf<WriteTrace>()
        for (act in inWindow.sortedBy { it.atMs }) {
            // 贪心配对：同名、时刻最近、且还没被更早的痕迹占用的那一行。
            var best = -1
            var bestDelta = Long.MAX_VALUE
            booked.forEachIndexed { i, (name, at) ->
                if (taken[i] || name != act.tool) return@forEachIndexed
                val delta = kotlin.math.abs(at - act.atMs)
                if (delta <= toleranceMs && delta < bestDelta) {
                    bestDelta = delta
                    best = i
                }
            }
            if (best >= 0) taken[best] = true else gaps += act
        }
        return AuditGapResult(
            gaps = gaps.sortedBy { it.atMs },
            checked = inWindow.size,
            beyondLedgerWindow = acts.size - inWindow.size,
        )
    }

    /** 一行明细（只进巡按栏）。 */
    fun evidenceOf(act: WriteTrace): String =
        "${TimeFormats.monthDay(act.atMs)} ${TimeFormats.hourMinute(act.atMs)} " +
            "写动作 ${act.tool}（会话 ${act.sessionId}·步 ${act.step}），208 无行"

    /** 一条勘验结论。 */
    fun check(
        acts: List<WriteTrace>,
        ledger: List<DecisionRecord>,
        toleranceMs: Long = InspectLimits.GAP_TOLERANCE_MS,
    ): InspectFinding {
        val res = compare(acts, ledger, toleranceMs)
        val bookedCount = ledger.count { it.kind == DecisionKind.AUTO_ACT }
        if (acts.isEmpty()) {
            return InspectFinding(
                check = InspectCheck.AUDIT_GAP,
                verdict = InspectVerdict.CLEAR,
                oneLine = InspectText.line("审计漏行：窗口内没有写侧动作"),
                suggestion = "无需处理",
                count = 0,
            )
        }
        if (bookedCount == 0) {
            return InspectFinding(
                check = InspectCheck.AUDIT_GAP,
                verdict = InspectVerdict.NO_DATA,
                oneLine = InspectText.line("审计漏行：208 无 auto_act 行，无从比对"),
                suggestion = "先确认 208 已装配（DecisionLedger.install）再巡",
                evidence = listOf("写侧动作 ${acts.size} 条", "208 里 auto_act 0 行"),
                count = 0,
            )
        }
        val hits = res.gaps.size
        val evidence = res.gaps.take(InspectDetailLimits.MAX_EVIDENCE_PER_FINDING).map(::evidenceOf) +
            if (res.beyondLedgerWindow > 0) {
                listOf("另有 ${res.beyondLedgerWindow} 条早于可见账窗，未计入漏行")
            } else {
                emptyList()
            }
        return if (!InspectSample.reportable(hits, InspectLimits.GAP_MIN_HITS)) {
            InspectFinding(
                check = InspectCheck.AUDIT_GAP,
                verdict = InspectVerdict.CLEAR,
                oneLine = InspectText.line("审计漏行 0 条（比对 ${res.checked} 条痕迹）"),
                suggestion = "无需处理",
                evidence = evidence,
                count = 0,
            )
        } else {
            val latest = res.gaps.last()
            InspectFinding(
                check = InspectCheck.AUDIT_GAP,
                verdict = InspectVerdict.HIT,
                oneLine = InspectText.line("审计漏 $hits 条：${latest.tool} 有动作无账"),
                suggestion = "查最后一条为何没落 208：GuardPipeline.finish 的 auto_act 写点漏了还是写失败",
                evidence = evidence,
                count = hits,
            )
        }
    }
}
