// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十九批（2026-11-09）· 「今天干过什么」的**取数壳**（只读，几本账，零写口）。
//
// 判据⑥要的是「数据只来自账本」——所以读账只发生在这里，屏幕层拿到的是已归一的行：
//   · 执行账：`run_log`（SQLite）—— 模型每一次动工具（`state='Acting'`）与还没动手的那一步
//     （`state='AwaitConfirm'`＝等你确认，**计划未执行**，由判据层丢掉）；
//   · 自决账：`追认队列.jsonl` —— 判了"不打扰"并已按默认走完的那几条（结果＝跳过）；
//   · 自动维护账：`修复记录.jsonl`（御医）＋ `进化提案.jsonl`（进化器，账上每 id 取最新一版）。
//
// 三条纪律（同 239／281 的取数壳）：
//   1. **只读**：没有 `execSQL`、没有 `appendText`、没有建目录——看板不许改一个字节的账；
//   2. **每条 SQL 都带 `LIMIT`**，每本账都带上界：看板不是账本导出；
//   3. **一路读不出不影响别路**（各自 `runCatching` 成空表）：少一条就少一条，不编。
//
// 「翻译账本行」的那几支（`runLogRow`／`selfRulingCandidates`／`repairRow`／`proposalStatus`）
// 都是**纯函数**：IO 只是"把行读出来"，口径全在纯函数上，JVM 单测逐支可断。
//
// ⚠️ 「被压」的落点是 `打扰治理.jsonl` 的 `suppress` 行——**本屏不读那本账**，
// 所以被压的条目在结构上就进不来；判据层仍留 [ActivityBoardRules.WIRE_SUPPRESSED]
// 这条线值当负例（将来谁把那条路接进来，也不会漏出去）。
package com.example.zhengdao.ui.baopu

import android.content.Context
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.agent.SelfRuling
import com.example.zhengdao.baopu.agent.SelfRulingLedger
import com.example.zhengdao.baopu.evolution.EvolutionProposal
import com.example.zhengdao.baopu.evolution.EvolutionWiring
import com.example.zhengdao.baopu.evolution.ProposalStatus
import com.example.zhengdao.baopu.repair.RepairEntry
import com.example.zhengdao.baopu.repair.RepairLedger
import java.io.File

internal object ActivityBoardSource {

    /** 每本账一次最多翻几行（新→旧截断；四本账合计有界）。 */
    const val READ_LIMIT = 400

    /** `run_log` 里"动了手"的线值（与引擎同字，别改）。 */
    private const val STATE_ACTED = "Acting"

    /** `run_log` 里"等你确认"的线值（还没动手 ⇒ 计划未执行）。 */
    private const val STATE_WAIT = "AwaitConfirm"

    /**
     * 一次取全（四路各自兜住；哪一路读不出就少哪一路，绝不用别路的数去补）。
     *
     * @param appCtx 只用来开库（`BaopuRuntime.db` 是进程级单例，不新建第二份 helper）
     * @param nowMs 现在（"今天"的那一刀，与判据层同一把尺）
     */
    fun candidates(appCtx: Context, nowMs: Long): List<ActivityCandidate> {
        val from = ActivityBoardRules.fromMs(nowMs, 0L)
        return runLog(appCtx, from) + selfRulings(from) + repairs(from) + proposals(from)
    }

    // ── 执行账：run_log ─────────────────────────────────────────────────────

    private fun runLog(appCtx: Context, fromMs: Long): List<ActivityCandidate> = runCatching {
        BaopuRuntime.db(appCtx).read { d ->
            val rows = ArrayList<ActivityCandidate>()
            d.rawQuery(
                "SELECT tool, ok, state, created_at FROM run_log " +
                    "WHERE created_at >= ? AND tool != '' AND state IN (?, ?) " +
                    "ORDER BY id DESC LIMIT ?",
                arrayOf(fromMs.toString(), STATE_ACTED, STATE_WAIT, READ_LIMIT.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    rows += runLogRow(c.getString(0), c.getInt(1), c.getString(2), c.getLong(3))
                }
            }
            rows
        }
    }.getOrDefault(emptyList())

    /**
     * `run_log` 一行 → 候选（**纯**：JVM 可测；state／ok 的翻译只在这一处）。
     *
     * `Acting` 且 `ok=1` ⇒ 成功；`Acting` 且 `ok=0` ⇒ 失败；
     * 其余（`AwaitConfirm`）⇒ 计划未执行，交给判据层丢。
     */
    fun runLogRow(tool: String, ok: Int, state: String, atMs: Long): ActivityCandidate =
        ActivityCandidate(
            atMs = atMs,
            who = ActivityWho.MODEL,
            what = tool,
            status = when {
                state != STATE_ACTED -> ActivityBoardRules.WIRE_PLANNED
                ok == 1 -> ActivityBoardRules.WIRE_ACTED
                else -> ActivityBoardRules.WIRE_FAILED
            },
        )

    // ── 自决账：追认队列.jsonl ──────────────────────────────────────────────

    private fun selfRulings(fromMs: Long): List<ActivityCandidate> = runCatching {
        selfRulingCandidates(SelfRulingLedger.replay(READ_LIMIT), fromMs)
    }.getOrDefault(emptyList())

    /**
     * 自决账行 → 候选（**纯**）。
     *
     * 一行自决单＝"这次没打扰你，因为……"，它**是有结论的一次执行**（判了、并按默认走完），
     * 所以结果记「跳过」；这与"被压"（卡根本没摆出来、什么都没发生）不是一回事。
     */
    fun selfRulingCandidates(rows: List<SelfRuling>, fromMs: Long): List<ActivityCandidate> = rows
        .asSequence()
        .filter { it.atMs >= fromMs }
        .map { row ->
            ActivityCandidate(
                atMs = row.atMs,
                who = ActivityWho.MEDIATOR,
                // gist 是"当时在处理什么"，reason 是"为什么不必打扰"——前者更像"做了什么"
                what = row.gist.ifBlank { row.reason },
                status = ActivityBoardRules.WIRE_SKIPPED,
            )
        }
        .toList()

    // ── 自动维护账①：修复记录.jsonl（御医）──────────────────────────────────

    private fun repairs(fromMs: Long): List<ActivityCandidate> {
        // 账根没装配 ⇒ 这一路就是空的（不是"今天没修过"——屏幕只管列出发生过的，不解释为什么空）
        val root: File = DecisionLedger.installedRoot() ?: return emptyList()
        return runCatching {
            repairCandidates(RepairLedger.read(root, READ_LIMIT), fromMs)
        }.getOrDefault(emptyList())
    }

    /** 修复账行 → 候选（**纯**；一次修复两行，只有 `result` 那行算"有过结果"）。 */
    fun repairCandidates(rows: List<RepairEntry>, fromMs: Long): List<ActivityCandidate> = rows
        .asSequence()
        .filter { it.atMs >= fromMs }
        .map { row -> repairRow(row.kind, row.target, row.phase, row.outcome, row.atMs) }
        .toList()

    /**
     * 修复账一行 → 候选（**纯**）。
     *
     * 一次修复写两行：`intent`（要修什么，先落盘）＋ `result`（修完怎样）。
     * **`intent` 就是"计划未执行"**——它落上了、修复还没完（或没成就被杀），不配上屏。
     */
    fun repairRow(kind: String, target: String, phase: String, outcome: String, atMs: Long): ActivityCandidate =
        ActivityCandidate(
            atMs = atMs,
            who = ActivityWho.REPAIRER,
            what = if (target.isBlank()) kind else "$kind $target",
            status = when {
                phase != RepairLedger.PHASE_RESULT -> ActivityBoardRules.WIRE_PLANNED
                outcome == RepairLedger.OUTCOME_DONE -> ActivityBoardRules.WIRE_ACTED
                else -> ActivityBoardRules.WIRE_FAILED
            },
        )

    // ── 自动维护账②：进化提案.jsonl（进化器）────────────────────────────────

    private fun proposals(fromMs: Long): List<ActivityCandidate> = runCatching {
        // 追加-only 的账：同一 id 会有多行（状态变更各写一行），读口已按 id 取最新一版
        proposalCandidates(EvolutionWiring.latestProposals(), fromMs)
    }.getOrDefault(emptyList())

    /** 进化提案行 → 候选（**纯**）。 */
    fun proposalCandidates(rows: List<EvolutionProposal>, fromMs: Long): List<ActivityCandidate> = rows
        .asSequence()
        .filter { it.atMs >= fromMs }
        .map { p ->
            ActivityCandidate(
                atMs = p.atMs,
                who = ActivityWho.EVOLVER,
                what = p.id,
                status = proposalStatus(p.status),
            )
        }
        .toList()

    /**
     * 提案状态 → 线值（**纯**；六档全覆盖，没有 else 兜底）。
     *
     * `LANDED`⇒成功；`ROLLED_BACK`⇒已回滚；`OPEN`／`ADMITTED`⇒还没走到验证（计划未执行）；
     * `RETURNED`／`REFUSED`⇒被拒。
     */
    fun proposalStatus(status: ProposalStatus): String = when (status) {
        ProposalStatus.LANDED -> ActivityBoardRules.WIRE_ACTED
        ProposalStatus.ROLLED_BACK -> ActivityBoardRules.WIRE_ROLLED_BACK
        ProposalStatus.OPEN, ProposalStatus.ADMITTED -> ActivityBoardRules.WIRE_PLANNED
        ProposalStatus.RETURNED, ProposalStatus.REFUSED -> ActivityBoardRules.WIRE_REFUSED
    }
}
