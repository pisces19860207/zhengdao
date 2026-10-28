// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第二百三十九批 §2：巡按的**编排壳**——取数（[PatrolSources]）→ 六项判据 →
// 三行报告（[Inspector]）→ 落自己的账（[PatrolLedger]）→ 算勘验通过率与额度档（§7.2）。
//
// 两个入口（§2.1，别的都不许自己发起巡按）：
// - 手动：界面那颗「巡一遍」（[manual] = true）；
// - 夜间低峰每日一次：由宿主在低峰调一次，[PatrolScheduler] 决定这趟该不该跑。
//
// 三条纪律：
// 1. 本文件**不写被纠对象**（技能正文、账本、工作区文件一个都不改）；
//    它只追加两本自己的账：巡按记录与（由装配层落盘的）额度变动草稿；
// 2. 额度变动在这里**只出草稿与依据**，写 208 的手在执行侧——
//    纠察替自己改额度＝既当运动员又当裁判（§7.3，负例「通过率高就顺手放开」同源）；
// 3. 一次巡按的耗时进台账（[PatrolEntry.elapsedMs]）：真机三天要能证明
//    "不许变成第二个负担"这句不是空话。
package com.example.zhengdao.baopu.inspect

import android.content.Context
import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.core.RunLog
import java.io.File

/** 一次巡按的全部产物（界面只消费 [report]，其余给门禁与交付报告读数）。 */
internal data class PatrolResult(
    val report: PatrolReport,
    val elapsedMs: Long,
    val saved: Boolean,
    /** §7.2：近 30 天勘验通过率（无账 ⇒ [PassRate.UNKNOWN_PCT]，不假装知道）。 */
    val rate: PassRate,
    /** 现在生效的档（从 208 的 `allowance:` 行读回来；没有 ⇒ 默认档）。 */
    val currentBand: AllowanceBand,
    /** 这次勘验该落在哪一档（与 current 不同 ⇒ 产出一条待落账的变动）。 */
    val nextBand: AllowanceBand,
    /** 额度那一行的读数（进台账，不进正文三行）。 */
    val allowanceLine: String,
    /** 待落账的额度变动（null = 档没动／没依据可写）。 */
    val pendingChange: AllowanceLadder.AllowanceChange?,
    /**
     * 第二百七十二批：RunTrace ↔ 208 旁路对拍（法典 L28）的读数（null = 没跑成／没轮到）。
     * findings 只带非 CLEAR 的维度；Opinion(source=PATROL, lawRef=L28) 已在其列，
     * 走勘验的路，不直接打扰用户。对拍器自己不写账。
     */
    val crosscheck: CrossCheckPass? = null,
    /** 没跑成的原因（null = 跑成了）。 */
    val failReason: String? = null,
)

internal object PatrolRunner {

    /** 日志类窗口（I1/I2/I3/I5/I6 与 I4 另两率都按这个回看；§2.1 每日一次 ⇒ 一周够摊开且不拖垮手机）。 */
    fun windowFrom(nowMs: Long, days: Int = InspectLimits.WEEK_WINDOW_DAYS): Long =
        nowMs - days * 24L * 3600_000L

    /**
     * 巡一遍。
     *
     * @param manual true = 用户点的「巡一遍」；false = 夜间低峰自己跑的那一趟
     * @return 报告与读数（**不抛**：取数失败出 [PatrolResult.failReason]，界面照旧有字）
     */
    fun run(
        ctx: Context,
        manual: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): PatrolResult {
        val t0 = System.currentTimeMillis()
        val root = PatrolSources.memoryRoot()
        if (root == null) {
            return notRun(
                nowMs,
                "208 没装配落点（DecisionLedger.install），巡不了",
                System.currentTimeMillis() - t0,
            )
        }
        val db: BaopuDb = runCatching { BaopuRuntime.db(ctx) }.getOrNull()
            ?: return notRun(nowMs, "库打不开，巡不了", System.currentTimeMillis() - t0)
        val projectRoot: File = runCatching { BaopuRuntime.workspaceRoot(ctx) }.getOrDefault(root)

        val from = windowFrom(nowMs)
        // §10④（2026-10-27）：两条率（虚报率、秒批率）单独吃 **30 天滚动窗**、按动作实例
        // 计数——它们俩是"没有持久口、只能推算"的那两条（报告 §10 第 6 问），7 天窗里
        // 样本永远凑不齐；其余检查与 I4 另两率的口径**都不动**。
        val rateFrom = windowFrom(nowMs, days = InspectLimits.RATE_WINDOW_DAYS)
        val ledger = PatrolSources.ledger(root)
        val acts = PatrolSources.writeTraces(db, from)
        val confirms = PatrolSources.confirmTraces(db, from)
        val rateActs = PatrolSources.writeTraces(db, rateFrom)
        val rateConfirms = PatrolSources.confirmTraces(db, rateFrom)
        val shadows = PatrolSources.shadows(root)
        val answers = PatrolSources.answerPool(db, from)
        val rateAnswers = PatrolSources.answerPool(db, rateFrom)
        val findings = listOf(
            // I1：痕迹×账本交叉比对（漏 1 条就报）。
            AuditGapCheck.check(acts, ledger),
            // I2：抽 10 条回答跑 227 的判据。
            NumberGroundingCheck.check(answers),
            // I3：抽 5 条技能跑 233 的验收清单。
            ContractHollowCheck.check(PatrolSources.skillPool(projectRoot, root)),
            // I4：四率（算不出的走 NO_DATA，不冒充清白）。虚报率/秒批率两个分母
            // 从 §10④ 的 30 天窗取；免问回滚率/确认密度仍吃 7 天窗（口径不动）。
            MediatorBiasCheck.check(
                BiasInputs(
                    silentActs = PatrolSources.silentActCount(acts, confirms),
                    rollbacksAfterSilent = PatrolSources.silentRollbackCount(ledger, acts, confirms),
                    confirms = confirms.size,
                    tasks = PatrolSources.taskCount(db, from),
                    answerRounds = NumberGroundingCheck.sample(rateAnswers).size,
                    overclaimRounds = NumberGroundingCheck.overclaimRounds(
                        NumberGroundingCheck.sample(rateAnswers),
                    ),
                    gaps = PatrolSources.approvalGaps(rateActs, rateConfirms),
                ),
            ),
            // I5：7 天内 ≥3 次失败却没收拾。
            UnarchivedFailureCheck.check(
                PatrolSources.failures(db, from),
                PatrolSources.archivedSkillNames(ledger, projectRoot, root),
                nowMs,
            ),
            // I6：影子台账×写侧痕迹（不一致却放行／该预演没预演）。
            ShadowCheck.check(PatrolSources.shadowTraces(db, from), shadows),
        )

        val rate = PassRate.of(PatrolSources.reviews(root), nowMs)
        val current = PatrolSources.currentBand(ledger)
        val next = AllowanceLadder.nextBand(current, rate)
        val readout = AllowanceLadder.readout(rate, current, next)
        val change = if (next == current) {
            null
        } else {
            AllowanceLadder.AllowanceChange(
                id = "allow:${nowMs}",
                atMs = nowMs,
                actor = com.example.zhengdao.baopu.DecisionActor.AUTO.wire,
                basisPatrolId = Inspector.patrolIdOf(nowMs),
                passRatePct = rate.pct,
                samples = rate.judged,
                before = current,
                after = next,
            )
        }
        val report = Inspector.report(findings, nowMs, rate)
        val elapsed = System.currentTimeMillis() - t0
        val entry = PatrolLedger.entryOf(report, manual, elapsed, readout)
        val saved = PatrolLedger.append(root, entry)
        if (!saved) RunLog.log("勘验：巡按记录没落盘（${PatrolLedger.FILE_NAME}）")
        return PatrolResult(
            report = report,
            elapsedMs = elapsed,
            saved = saved,
            rate = rate,
            currentBand = current,
            nextBand = next,
            allowanceLine = readout,
            pendingChange = change?.takeIf { AllowanceLadder.refusalOf(it) == null },
            crosscheck = crosscheckOf(db, root, nowMs),
        )
    }

    /**
     * 第二百七十二批 · L28 旁路对拍（RunTrace ↔ 208）的一趟：六项判据之外的独立一路，
     * 产出自己的 finding／Opinion（差一条就报，连续 3 次不一致升 CRITICAL），
     * 不进六项的闭集、不写 208、不弹卡。读失败出「没跑成」的空读数，巡按主体照常。
     */
    private fun crosscheckOf(db: BaopuDb, root: File, nowMs: Long): CrossCheckPass {
        val pass = runCatching { TraceCrossCheckSources.pass(db, root, nowMs) }.getOrNull()
            ?: CrossCheckPass(0, emptyList(), emptyList(), "对拍没跑成（取数失败）")
        if (pass.checked > 0) RunLog.log("勘验对拍：${pass.summaryLine}")
        return pass
    }

    /** 没跑成的报告也要有字（§2.4：一行结论——"巡不了"就是那一句，不许留空白）。 */
    fun notRun(nowMs: Long, why: String, elapsedMs: Long): PatrolResult {
        val report = Inspector.report(
            listOf(
                InspectFinding(
                    check = InspectCheck.AUDIT_GAP,
                    verdict = InspectVerdict.NO_DATA,
                    oneLine = InspectText.line("巡不了：$why"),
                    suggestion = "先装配 208 落点与库，再巡",
                ),
            ),
            nowMs,
        )
        return PatrolResult(
            report = report,
            elapsedMs = elapsedMs,
            saved = false,
            rate = PassRate(PassRate.UNKNOWN_PCT, 0, 0, 0, InspectLimits.PASS_RATE_WINDOW_DAYS),
            currentBand = AllowanceBand.DEFAULT,
            nextBand = AllowanceBand.DEFAULT,
            allowanceLine = InspectText.line("额度：无勘验账，维持 ${AllowanceBand.DEFAULT.cn}"),
            pendingChange = null,
            failReason = why,
        )
    }
}
