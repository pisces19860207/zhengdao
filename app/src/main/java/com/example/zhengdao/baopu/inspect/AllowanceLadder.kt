// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-26-第二百三十九批-纠察 §7.2（下放额度由**勘验通过率**决定）
// ＋ §7.3（三条硬边界，任何额度下都不变）。
//
// 这一层把「勘验说了什么」翻译成「下一次同类动作还问不问」，并且**只做翻译**：
// - 额度是 236 `ExecutionDelegation` 已经在管的免问额度，本文件不新造一套额度；
// - 落账复用 208 的既有 kind（`tool_grant`）——`DecisionKind` 那个枚举在
//   `DecisionLedger.kt` 里，而那个文件**不在本批占用范围**（在飞表只给了
//   `baopu/inspect/` 与 `DecisionCenterScreen.kt`），所以这里产出**待写的草稿**，
//   由装配层去落盘；
// - 零 IO、零时钟、零 Android：全部判据拿手搓的 `DecisionReview.Entry` 直测。
//
// §7.3 第三条在这里有牙：额度是**读账本**算出来的，输入类型只有 `DecisionReview.Entry`
// 与调用方传进来的事实位——没有任何一个入口收模型的自述（负例「勘验用模型自述当输入」）。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.DecisionReview
import com.example.zhengdao.baopu.RiskClass

/**
 * 下放额度三档（§7.2 那张表的三行）。
 *
 * `wire` 是额度台账的稳定键（写进 208 的 `evidence`）；[HELD] 是**默认档**——
 * 没有勘验账、样本不够，都停在这一档，不升也不收。
 * 「没账就当没事」是本批要纠的弊，额度层自己第一条不能犯。
 */
internal enum class AllowanceBand(val wire: String, val cn: String) {

    /** ≥95%：R1 免问（已定）＋部分 R2 低危子类提为「免问但事后勘验」。 */
    RAISED("raised", "放"),

    /** 85%–95%：R1 免问，R2 一律问。 */
    HELD("held", "维持"),

    /** <85%：R1 也恢复必问，直到通过率回到 95%。 */
    REVOKED("revoked", "收回"),
    ;

    companion object {
        /** 线值 ⇒ 档；认不出 ⇒ null（自造档位不渲染，也不静默当 [HELD]）。 */
        fun ofWire(wire: String): AllowanceBand? = entries.firstOrNull { it.wire == wire }

        /** 起始档（装配层没落过账时按这个走）。 */
        val DEFAULT: AllowanceBand = HELD
    }
}

/**
 * 近 30 天的勘验通过率（分母只有**判过**的抽检结论）。
 *
 * 为什么分母不是「抽检总条数」：[InspectVerdict.NO_DATA] 那种「查不了」的结论
 * 既不是通过也不是不通过，塞进分母会把「没账」洗成「通过率高」。
 * 分子分母只认 `confirmed`／`wrong` 两个字面量——这正是 §7.3 第三条要的「输入只能是账」。
 */
internal data class PassRate(
    /** 百分比（0–100）；[UNKNOWN_PCT] = 窗口内一条判过的结论都没有。 */
    val pct: Double,
    /** 窗口内判过的结论条数（`confirmed + wrong`）。 */
    val judged: Int,
    val confirmed: Int,
    val wrong: Int,
    val windowDays: Int,
) {
    /** 样本够不够动额度（少于 [InspectLimits.MIN_RATIO_SAMPLE] 条只出「维持」）。 */
    val enoughSamples: Boolean get() = judged >= InspectLimits.MIN_RATIO_SAMPLE

    /** 这个读数算得出吗（算不出＝没账，谁也不许拿它升档或收档）。 */
    val known: Boolean get() = pct >= 0.0 && enoughSamples

    companion object {
        /** 「算不出」的字面量（不是 0%——0% 会被读成「一条都没通过」）。 */
        const val UNKNOWN_PCT = -1.0

        /**
         * 由抽检结论算通过率。
         *
         * 先按记录 id 取**当前**结论（同一记录改判过两次，以最后一次为准），
         * 再按窗口筛——窗口按 `at`（判定的时刻）而不是被纠动作的时刻：
         * 「近 30 天的勘验」问的是勘验这件事本身发生在什么时候。
         */
        fun of(
            entries: List<DecisionReview.Entry>,
            nowMs: Long,
            windowDays: Int = InspectLimits.PASS_RATE_WINDOW_DAYS,
        ): PassRate {
            val from = nowMs - windowDays * DAY_MS
            val latest = DecisionReview.latestPerRecord(entries).values
                .filter { it.atMs >= from }
            var confirmed = 0
            var wrong = 0
            latest.forEach { e ->
                if (e.verdict == DecisionRecord.VERDICT_CONFIRMED) confirmed++
                else if (e.verdict == DecisionRecord.VERDICT_WRONG) wrong++
            }
            val judged = confirmed + wrong
            if (judged == 0) return PassRate(UNKNOWN_PCT, 0, 0, 0, windowDays)
            return PassRate(confirmed * 100.0 / judged, judged, confirmed, wrong, windowDays)
        }

        private const val DAY_MS = 86_400_000L
    }
}

/**
 * 额度表（§7.2 的三行 ＋ §7.3 的三条硬边界）。
 *
 * **带滞回**：通过率从 <85% 回到 90% 时仍停在收回档——
 * 派工单原话是「直到通过率回到 **95%**」，不是「回到 85%」。
 * 这一条必须有字：没有滞回，收回档在每个 85%–95% 的读数上都会自己爬回「维持」，
 * 而收回的意义恰恰是**要它一直疼到真正干净为止**。
 */
internal object AllowanceLadder {

    /** §7.2 放档线（%）。 */
    const val RAISE_PCT = 95.0

    /** §7.2 收档线（%）：跌破它即收回。 */
    const val REVOKE_PCT = 85.0

    /**
     * 下一次该在哪一档（[current] = 现在生效的档）。
     *
     * 算不出（没账/样本不够）⇒ **原样返回**：升档要依据，收档也要依据，
     * 一个都拿不出时动档就是自己在改政策。
     */
    fun nextBand(current: AllowanceBand, rate: PassRate): AllowanceBand {
        if (!rate.known) return current
        return when {
            rate.pct < REVOKE_PCT -> AllowanceBand.REVOKED
            rate.pct >= RAISE_PCT -> AllowanceBand.RAISED
            else -> if (current == AllowanceBand.REVOKED) AllowanceBand.REVOKED else AllowanceBand.HELD
        }
    }

    /** 一句话读数（巡按栏/额度卡的同一句；≤40 字由 [InspectText] 钳）。 */
    fun readout(rate: PassRate, current: AllowanceBand, next: AllowanceBand): String {
        val pct = if (rate.known) InspectText.pct(rate.pct) + "%" else "无账"
        val band = if (next == current) current.cn else "${current.cn}→${next.cn}"
        return InspectText.line("勘验通过率 $pct（${rate.judged} 条）⇒ 额度 $band")
    }

    // ── §7.3：一次动作在该档下要不要问 ─────────────────────────────────────

    /**
     * 判据事实（**全部由调用方算好**，本层不读盘、不看模型）。
     *
     * @param risk 219 判的档（null = 没判过 ⇒ 一律问，fail-closed）
     * @param mustAskByRule 219 的 `MUST_ASK`（额度不许推翻它）
     * @param hardDeny 219 的硬拒（那是「拒」不是「问」，本层只如实透传）
     * @param cClass C 类自改：权限位／风险档／`protected`／删技能／工具白名单
     * @param lowRiskR2Subclass §7.2 点名的 R2 低危子类（工作区内移动、改自己产物目录）
     * @param patrolAvailable 「免问但事后勘验」的前提：勘验真的跑得起来
     * @param shadow 影子执行判定：null = 没跑（该跑而没跑 ⇒ 问）
     */
    data class AskFacts(
        val tool: String = "",
        val risk: RiskClass? = null,
        val mustAskByRule: Boolean = false,
        val hardDeny: Boolean = false,
        val insideWorkspace: Boolean = true,
        val reversible: Boolean = true,
        val rollbackPointReady: Boolean = false,
        val cClass: Boolean = false,
        val selfProductDir: Boolean = false,
        val lowRiskR2Subclass: Boolean = false,
        val patrolAvailable: Boolean = false,
        val shadow: ShadowVerdict? = null,
    )

    /** 一个动作在额度表里的落点。 */
    enum class Verdict(val wire: String, val cn: String) {
        /** 免问直行。 */
        SILENT("silent", "免问"),

        /** 摆卡问人（御批）。 */
        ASK("ask", "必问"),

        /** 219 已经拒了（本层不另造一套拒）。 */
        DENIED("denied", "已拒"),
    }

    /**
     * §9：哪些动作**必须**先跑影子（R2 以上或不可逆；R0/R1 跑影子是浪费）。
     *
     * 判据住 [ShadowRehearsal.shouldRehearse]（一处一个口径），这里只做转写——
     * 两处各写一遍「R2/R3/不可逆」，改阈值时必漏一处。
     */
    fun requiresShadow(f: AskFacts): Boolean = ShadowRehearsal.shouldRehearse(f.risk, f.reversible)

    /**
     * 该不该问（顺序＝优先级，硬边界永远在档位之前）。
     *
     * 读法：先排掉「任何额度都不变」的三条（硬拒／R3／C 类），
     * 再看 219 自己的必问，再看 I6 的影子，最后才轮到档位放宽。
     */
    fun verdictOf(f: AskFacts, band: AllowanceBand): Verdict = when {
        f.hardDeny -> Verdict.DENIED
        // 硬边界①：R3 永不自动（支付/登录/发布/提交）——通过率 100% 也不松。
        f.risk == RiskClass.R3 -> Verdict.ASK
        // 硬边界②：C 类永不自改——这条判的是「改的是不是规则本身」，与风险档无关。
        f.cClass -> Verdict.ASK
        // 219 的必问不能被额度推翻：额度管的是「本来可以免问的那一批」。
        f.mustAskByRule -> Verdict.ASK
        // §9 负例④：影子不一致还放行——这里反过来钉，不一致一律问。
        f.shadow == ShadowVerdict.MISMATCH -> Verdict.ASK
        // 该跑影子而没跑（没装配/没落账）⇒ 问。缺判据时不许假定通过。
        requiresShadow(f) && f.shadow == null -> Verdict.ASK
        // §7.2 收回档：R1 也恢复必问。R0（读/列目录/搜索）不在额度讨论内——
        // 派工单那一行写的是「R1 也恢复必问」，多收 R0 是我自己加的，不这么干。
        band == AllowanceBand.REVOKED -> if (f.risk == RiskClass.R0) Verdict.SILENT else Verdict.ASK
        band == AllowanceBand.HELD -> if (f.risk == RiskClass.R0 || f.risk == RiskClass.R1) {
            Verdict.SILENT
        } else {
            Verdict.ASK
        }
        // 放档：R1 免问 ＋ R2 低危子类「免问但事后勘验」。
        band == AllowanceBand.RAISED && (f.risk == RiskClass.R0 || f.risk == RiskClass.R1) -> Verdict.SILENT
        band == AllowanceBand.RAISED && r2SubclassSilent(f) -> Verdict.SILENT
        else -> Verdict.ASK
    }

    /**
     * R2 低危子类能不能免问（§7.2 那句「工作区内移动、改自己产物目录」）。
     *
     * 四个前提一个都不能少：子类点名、在工作区内、可逆且回滚点已经在、勘验真的跑得起来。
     * 最后一条是「免问但**事后勘验**」的字面意思——事后没人查，免问就只是少一道闸。
     */
    fun r2SubclassSilent(f: AskFacts): Boolean =
        f.lowRiskR2Subclass &&
            f.insideWorkspace &&
            f.reversible &&
            f.rollbackPointReady &&
            f.patrolAvailable &&
            f.shadow == ShadowVerdict.CONSISTENT

    // ── §7.2：额度变动留痕（写 208：谁调的、依据哪次勘验、可回滚）─────────────

    /**
     * 一次额度变动（**必须先有一条能拒绝它的理由**：见 [refusalOf]）。
     *
     * @param actor 线值（`user`／`auto`／`model`）；`model` 一律拒。
     * @param basisPatrolId 依据哪次巡按（空 ⇒ 拒：无依据调额度是负例）。
     */
    data class AllowanceChange(
        val id: String,
        val atMs: Long,
        val actor: String,
        val basisPatrolId: String,
        val passRatePct: Double,
        val samples: Int,
        val before: AllowanceBand,
        val after: AllowanceBand,
    )

    /** 额度行在 208 里的 evidence 前缀（读回来也认它；改字面量＝老额度行读不出）。 */
    const val EVIDENCE_PREFIX = "allowance:"

    /** 这一笔该不该落账（返回 null＝可以落；非 null＝拒绝的理由，调用方 log 出来）。 */
    fun refusalOf(c: AllowanceChange): String? = when {
        // 模型自己把额度调高＝既当运动员又当裁判。额度的手只能是人或系统按勘验走。
        c.actor == DecisionActor.MODEL.wire -> "额度不许由模型自调（actor=model）"
        c.basisPatrolId.isBlank() -> "调额度没有依据（缺 basis_patrol_id）"
        c.before == c.after -> "档位没动，不落空变动"
        // 无账 ⇒ 谁也不许拿「算不出」去升档或收档。
        c.passRatePct < 0.0 -> "通过率算不出（窗口内没有判过的抽检结论）"
        else -> null
    }

    /**
     * 待写草稿（**复用 `tool_grant`，不新增 kind**）。
     *
     * 为什么复用而不是新造：`DecisionKind` 住在 `DecisionLedger.kt`，那文件不在本批占用里；
     * 更重要的是语义也对得上——`tool_grant` 记的就是「哪一批动作被授权免问」，
     * 额度变动正是这件事（104 批注：复用，不新造语义）。
     * `rollback_ref` 装依据的巡按 id ⇒ 决策中心那颗撤销钮拿它退回上一档。
     */
    fun draftOf(c: AllowanceChange): DecisionDraft? {
        if (refusalOf(c) != null) return null
        return DecisionDraft(
            kind = DecisionKind.TOOL_GRANT,
            actor = DecisionActor.ofWire(c.actor),
            reason = "下放额度 ${c.before.cn}→${c.after.cn}（勘验 ${InspectText.pct(c.passRatePct)}%／${c.samples} 条）",
            evidence = "$EVIDENCE_PREFIX${c.after.wire}:${c.basisPatrolId}",
            before = c.before.wire,
            after = c.after.wire,
            atMs = c.atMs,
            rollbackRef = c.basisPatrolId,
        )
    }
}
