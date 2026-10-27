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
    /** 没跑成的原因（null = 跑成了）。 */
    val failReason: String? = null,
)

internal object PatrolRunner {

    /** 日志类窗口（I1/I4/I6 都按这个回看；§2.1 每日一次 ⇒ 一周够摊开且不拖垮手机）。 */
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
        val projectRoot: File = runCatching { BaopuRuntime.workspaceRoot(ctx) }
            .getOrDefault(root)

        val from = windowFrom(nowMs)
        val ledger = PatrolSources.ledger(root)
        val acts = PatrolSources.writeTraces(db, from)
        val confirms = PatrolSources.confirmTraces(db, from)
        val shadows = PatrolSources.shadows(root)
        val answers = PatrolSources.answerPool(db, from)
        val findings = listOf(
            // I1：痕迹×账本交叉比对（漏 1 条就报）。
            AuditGapCheck.check(acts, ledger),
            // I2：抽 10 条回答跑 227 的判据。
            NumberGroundingCheck.check(answers),
            // I3：抽 5 条技能跑 233 的验收清单。
            ContractHollowCheck.check(PatrolSources.skillPool(projectRoot, root)),
            // I4：四率（算不出的走 NO_DATA，不冒充清白）。
            MediatorBiasCheck.check(
                BiasInputs(
                    silentActs = PatrolSources.silentActCount(acts, confirms),
                    rollbacksAfterSilent = PatrolSources.silentRollbackCount(ledger, acts, confirms),
                    confirms = confirms.size,
                    tasks = PatrolSources.taskCount(db, from),
                    answerRounds = NumberGroundingCheck.sample(answers).size,
                    overclaimRounds = NumberGroundingCheck.overclaimRounds(
                        NumberGroundingCheck.sample(answers),
                    ),
                    gaps = PatrolSources.approvalGaps(acts, confirms),
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
        )
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
