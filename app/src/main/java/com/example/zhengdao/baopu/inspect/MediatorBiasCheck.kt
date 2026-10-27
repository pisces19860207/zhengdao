// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十九批 I4 · 调和器偏袒（周报口径，四条率任一越线即报）。
//
// 先说清楚一件事，不然这一项就是假绿：**调和器（238）本身不落盘**。
// `HarmonyMediator` 是纯判据（零 IO、零时钟），`HarmonyStateCodec` 只是编解码壳，
// 全仓没有一处调用它——所以「它偏没偏袒」不能问它自己，只能看**它管的那两方的行为账**：
// 免问了多少次、其中回滚了几次、每个任务摆了几张卡、有没有说了没做、人是不是秒点。
// 这四项全部只从 `run_log` × 208 反推，一条也不取自模型或调和器的自述（§7.3 第三条）。
//
// 四条率（派工单 §1 那行的四个数）：
//   ① 免问回滚率 >5%          —— 免问放行的动作事后被 208 撤销
//   ② 确认密度   >5 次/任务    —— 人还是被问死（236 的**目标**是 ≤2，那是舒适线；
//                                本条查的是**离谱线**，两个数合成一个就两头都不准）
//   ③ 虚报率     >20%          —— 说"没找到"又下结论／疑似推算（227 AnswerGate 的②③两条）
//   ④ 秒批率     >60%          —— 摆卡到落账不足 5 秒（御批退化成橡皮图章）
//
// 两处口径缺口**如实摆出来**，不拿 0 冒充合格（这是 [InspectVerdict.NO_DATA] 存在的全部理由）：
// - 「人什么时候点的」没落库：`ConfirmFlow.resumeWithApproval` 完成 deferred 就走，
//   `run_trace.duration_ms` 在批准路径上刻意填 `startedAtMs = 0` ⇒ 等待时长只能**推算**
//   （AwaitConfirm 行与同会话下一行 run_log 的 created_at 之差），它把引擎自己的耗时也算进去了，
//   只会**低估**秒批、不会高估 ⇒ 越线时报的数是保守的。
// - 「说了没做」没有专门账：虚报率用 227 那两条判据在抽检样本上算，样本线就是派工单定的 10 条。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.DelegationMetrics

/** 一次摆卡（`run_log.state = 'AwaitConfirm'` 行的最小形状；`tool` 与 `step` 同 Acting 行）。 */
internal data class ConfirmTrace(
    val atMs: Long,
    val sessionId: Long,
    val step: Int,
    val tool: String,
)

/** 一条摆卡到下一步落账的间隔；由取数侧算好递给判据，判据不碰库。 */
internal data class ApprovalGap(
    val confirmAtMs: Long,
    val sessionId: Long,
    val step: Int,
    val deltaMs: Long,
)

/** I4 的输入（四个分母/分子 ＋ 摆卡间隔；全部来自日志与账本）。 */
internal data class BiasInputs(
    /** 免问直行的写动作数（同会话同步没有摆过卡）。 */
    val silentActs: Int = 0,
    /** 其中事后被 208 回滚的行数。 */
    val rollbacksAfterSilent: Int = 0,
    /** 窗口内摆卡次数。 */
    val confirms: Int = 0,
    /** 窗口内任务数（`run_trace` 的 distinct run_id；取不到 ⇒ 0 ⇒ 密度项无从算）。 */
    val tasks: Int = 0,
    /** 抽到的回答轮次（分母）。 */
    val answerRounds: Int = 0,
    /** 其中被 227 判「自相矛盾／疑似推算」的轮次（分子）。 */
    val overclaimRounds: Int = 0,
    /** 摆卡→落账的间隔样本。 */
    val gaps: List<ApprovalGap> = emptyList(),
)

/** 一条率的读数（[value] 为 null ⇒ 分母不够或样本不足，算不出）。 */
internal data class BiasRate(
    val key: String,
    val cn: String,
    val value: Double?,
    val limit: Double,
    /** 显示单位：`%` 或 ` 次/任务`（前导空格是给拼句用的，别让调用方再拼）。 */
    val unit: String,
    /**
     * 分子（§10④：展示时**分子分母都给**——真机第 6 行的教训：只印分母时
     * 「样本不足（9 个）」看不出 9 里有多少是坏的）。
     */
    val num: Int,
    /** 分母（**动作实例**数：免问直行数／任务数／回答轮次／摆卡次数——§10④「不按会话」）。 */
    val denom: Int,
    /** 目标值（只进建议句，不参与判定；②那条的 2.0 就靠这个说清和 5.0 的分工）。 */
    val target: Double? = null,
) {
    val computable: Boolean get() = value != null
    val breached: Boolean get() = value != null && value > limit

    /** 明细那一行（算不出就明说算不出，**且把分子分母亮出来**，不许印 0%）。 */
    fun evidence(): String = if (value == null) {
        "$cn：分子 $num／分母 $denom，样本不足，算不出率"
    } else {
        val tail = if (target != null) "、目标 ≤${InspectText.pct(target)}$unit" else ""
        "$cn ${InspectText.pct(value)}$unit（分子 $num／分母 $denom，" +
            "红线 ${InspectText.pct(limit)}$unit$tail）⇒ " +
            (if (breached) "越线" else "在界内")
    }
}

/** I4 的结果。 */
internal data class BiasResult(
    val rates: List<BiasRate>,
    val breaches: List<BiasRate>,
    val unknowns: List<BiasRate>,
)

internal object MediatorBiasCheck {

    /** 虚报与秒批两项的样本线：就是派工单给 I2 定的抽检规模，不另立一个数。 */
    const val MIN_RATE_SAMPLE = InspectLimits.ANSWER_SAMPLE

    /** 免问 ⟺ 同会话同步没摆过卡（`enterAwaitConfirm` 与 `persistToolExchange` 用的是同一个 step）。 */
    fun isSilent(act: WriteTrace, confirms: List<ConfirmTrace>): Boolean =
        confirms.none { it.sessionId == act.sessionId && it.step == act.step }

    /** 四条率一次算完（比率算术复用 236 的 `DelegationMetrics`，两处不能各一套算法）。
     *
     * 窗口归取数侧（PatrolRunner）：虚报率与秒批率的分母按 §10④ 从 30 天窗取
     * （[InspectLimits.RATE_WINDOW_DAYS]），免问回滚率与确认密度仍吃 7 天日志窗；
     * 本层只管拿到的实例数怎么算。
     */
    fun rates(input: BiasInputs): List<BiasRate> {
        val instant = input.gaps.count { it.deltaMs in 0L until InspectLimits.INSTANT_MS }
        return listOf(
            BiasRate(
                key = "silent_rollback",
                cn = "免问回滚率",
                // 与 236 同口径：样本够了按比率判；样本不够但**已经出现回滚**也照报数（保守收回）。
                value = if (input.silentActs >= DelegationMetrics.MIN_SAMPLE || input.rollbacksAfterSilent > 0) {
                    DelegationMetrics.rollbackRatePct(input.silentActs, input.rollbacksAfterSilent)
                } else {
                    null
                },
                limit = InspectLimits.SILENT_ROLLBACK_PCT,
                unit = "%",
                num = input.rollbacksAfterSilent,
                denom = input.silentActs,
            ),
            BiasRate(
                key = "confirm_density",
                cn = "确认密度",
                value = if (input.tasks >= 1 && input.confirms >= 1) {
                    DelegationMetrics.confirmDensity(input.confirms, input.tasks)
                } else {
                    null
                },
                limit = InspectLimits.CONFIRM_DENSITY_LINE,
                unit = " 次/任务",
                num = input.confirms,
                denom = input.tasks,
                target = InspectLimits.CONFIRM_DENSITY_TARGET,
            ),
            BiasRate(
                key = "overclaim",
                cn = "虚报率",
                value = if (input.answerRounds >= MIN_RATE_SAMPLE) {
                    input.overclaimRounds * 100.0 / input.answerRounds
                } else {
                    null
                },
                limit = InspectLimits.OVERCLAIM_PCT,
                unit = "%",
                num = input.overclaimRounds,
                denom = input.answerRounds,
            ),
            BiasRate(
                key = "instant_approval",
                cn = "秒批率",
                value = if (input.gaps.size >= MIN_RATE_SAMPLE) {
                    instant * 100.0 / input.gaps.size
                } else {
                    null
                },
                limit = InspectLimits.INSTANT_APPROVAL_PCT,
                unit = "%",
                num = instant,
                denom = input.gaps.size,
            ),
        )
    }

    /**
     * 认定态（三态都可能出现，顺序不许倒）：
     * 有越线 ⇒ HIT；没越线但有算不出的 ⇒ NO_DATA；四条全算得出且都在界内 ⇒ CLEAR。
     *
     * 「算不出」排在「清白」前面是有意的：把查不了写成查过没问题，就是本批要纠的弊。
     */
    fun verdictOf(rates: List<BiasRate>): InspectVerdict = when {
        rates.any { it.breached } -> InspectVerdict.HIT
        rates.any { !it.computable } -> InspectVerdict.NO_DATA
        else -> InspectVerdict.CLEAR
    }

    fun analyse(input: BiasInputs): BiasResult {
        val all = rates(input)
        return BiasResult(all, all.filter { it.breached }, all.filter { !it.computable })
    }

    /** 明细四行（越线的排前面）。 */
    fun evidenceOf(res: BiasResult): List<String> {
        val ordered = res.breaches + res.rates.filter { !it.breached }
        return ordered.map { it.evidence() }
    }

    /** 无从勘验时那句实话（写死在判据侧，UI 不许自己编）。 */
    const val NO_DATA_NOTE = "口径缺口：批准时刻未落库、虚报无专门账 ⇒ 这两条只能推算，取不到样本就不算通过"

    private fun suggestionOf(worst: BiasRate?): String = when (worst?.key) {
        "silent_rollback" -> "免问额度先收回（236 revokedByRollbackRate 同判据），再查那几次回滚是谁放的行"
        "confirm_density" -> "把确认密度压回目标 ≤2 次/任务：查范围授权为什么没接住"
        "overclaim" -> "虚报那几轮回看 227 那句：要么给读数，要么明说查不到"
        "instant_approval" -> "秒批＝御批失效：重要节点的卡要把差异摆够，别让人闭眼点"
        else -> "缺的是持久口不是判据：先把批准时刻与虚报账落下来（见明细）"
    }

    fun check(input: BiasInputs): InspectFinding {
        val res = analyse(input)
        val verdict = verdictOf(res.rates)
        val worst = res.breaches.firstOrNull()
        val head = when (verdict) {
            InspectVerdict.HIT -> "调和器偏袒 ${res.breaches.size} 项：${worst?.cn}越线"
            InspectVerdict.NO_DATA -> "调和器偏袒：${res.unknowns.size} 项无从算"
            InspectVerdict.CLEAR -> "调和器偏袒 0 项：四率都在界内"
        }
        return InspectFinding(
            check = InspectCheck.MEDIATOR_BIAS,
            verdict = verdict,
            oneLine = InspectText.line(head),
            suggestion = if (verdict == InspectVerdict.CLEAR) "无需处理" else suggestionOf(worst),
            evidence = evidenceOf(res) +
                if (verdict == InspectVerdict.NO_DATA) listOf(NO_DATA_NOTE) else emptyList(),
            count = res.breaches.size,
        )
    }
}
