// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零六批（2026-11-07）· 473 灰度——每项自动化能力的三档，与「达标就自己转全开」的机制。
//
// 派工单 §二 四句话，逐句对应到本文件的哪一处：
//   ① 三档（关闭／灰度／全开），**缺省＝关闭或灰度** ⇒ [CanaryBand] ＋ [CanaryCapability.defaultBand]
//      （登记表里没有一档缺省写 FULL——不是巧合，是类型上不给写：见 [CanaryRegistry] 的注释）
//   ② 指标**从账本算**（次数／失败率／人工介入） ⇒ [CanaryMetrics.of]，输入只有 [CanarySample]
//   ③ 达标 ⇒ **机制自动转全开**（不再要人逐个点）；未达标不许手改数字 ⇒ [CanaryLadder.nextBand] ＋ [refusalOf]
//   ④ 一键回灰 ＋ **回灰自动**（触发条件写死） ⇒ [CanaryLadder.revertBand]（阈值是本文件常量，不读配置、不读模型）
//
// 房型逐字照 `baopu/inspect/AllowanceLadder.kt`（239 批）：那是本仓「三档＋带滞回＋指标从账本读」
// 的既有样板，本批不另造一套形状。**档位读回来读的是账，不是 SharedPreferences**——
// 理由那条账已经写过（`PatrolSources.currentBand` 的 KDoc）：
// 「账本一份、开关一份，回滚就只改得动账本改不动开关——那是最坏的一种『看起来有账』」。
//
// L3 放宽口径（2026-11-06 用户）在本层落地成一句：转全开**不需要人点**，但**必须落账**
// （actor=auto ＋ 依据那一轮读数）。落账不是仪式：不落的自动转档，事后没人知道是哪一轮放开的。
//
// 边界（派工单 §六）：本层不联网、不采集人的信息、不做经济赔付。
package com.example.zhengdao.baopu.consequence

/**
 * 灰度三档（**闭集**）。
 *
 * 顺序即「放开程度」从低到高：[OFF] 什么都不能自己跑，[CANARY] 只跑被点名的窄范围，
 * [FULL] 不再拦它。档位名一旦落账就是稳定键（[wire]），改字面量＝老档位行读不出来。
 */
internal enum class CanaryBand(val wire: String, val cn: String, val rank: Int) {

    /** 谁也不许自己跑（缺省档之一）。 */
    OFF("off", "关闭", 0),

    /** 试运行：可以跑，但每一次都要被数进账里（缺省档之二）。 */
    CANARY("canary", "灰度", 1),

    /** 达标之后机制自己转上来的档；不是「人点通过的档」。 */
    FULL("full", "全开", 2),
    ;

    companion object {
        /** 线值 ⇒ 档；认不出 ⇒ null（自造档位不渲染，也不静默当 [OFF]）。 */
        fun ofWire(wire: String): CanaryBand? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 一项自动化能力（**闭集登记表**，派工单 §二·① 的「每项」）。
 *
 * `defaultBand` 只许是 [CanaryBand.OFF] 或 [CanaryBand.CANARY]。这条不靠注释守着：
 * [CanaryRegistry.violations] 会把任何缺省写成 [CanaryBand.FULL] 的能力点名出来，测试拿它做负例
 * （改一行枚举即红）。为什么单挑这一条给闸：缺省全开是这一节唯一「写错就永远没人发现」的字段——
 * 读数不对会写在卡面上，缺省不对只在**第一次装机**那一刻露头，而那一刻没人看着。
 *
 * @param ledgerKey 这一族的账按什么键筛（落账时进 evidence，见 [ConsequenceLedger]）
 * @param scope 灰度期到底窄在哪（一句话；装配层照它实现「被点名的窄范围」）
 */
internal enum class CanaryCapability(
    val wire: String,
    val cn: String,
    val defaultBand: CanaryBand,
    val ledgerKey: String,
    val scope: String,
) {
    /** 236 A 档：反思改技能正文。 */
    REFLECTION_AUTO_EDIT("reflection_auto_edit", "反思自改", CanaryBand.CANARY, "autonomy_tier", "只 A 档文本层改动"),

    /** 432 暗卫：每周自动抽检。 */
    NOCTURNAL_PATROL("nocturnal_patrol", "暗卫自动抽检", CanaryBand.CANARY, "patrol", "每周 1 次上限照旧"),

    /** 214 编排引擎：多步任务自己串。 */
    ORCHESTRATION("orchestration", "过程编排", CanaryBand.OFF, "orchestrate", "单会话内、≤3 步"),

    /** 206 对拍：评测自己跑。 */
    PROD_EVAL("prod_eval", "线上对拍评测", CanaryBand.CANARY, "eval", "只跑白名单场景"),

    /** 464 人设自整理：合并／去重／重排。 */
    PERSONA_ORGANIZE("persona_organize", "人设自整理", CanaryBand.OFF, "persona_organize", "锁头三条不动、原文先归档"),

    /** 409 御医：自修数据。 */
    DATA_REPAIR("data_repair", "御医自修数据", CanaryBand.CANARY, "data_repair", "索引/账本/临时三类"),

    /** 399 触发源：手机侧自己接活。 */
    EVENT_TRIGGER("event_trigger", "事件触发自治", CanaryBand.OFF, "trigger", "定时到点＋日程前 N 分钟"),

    /** 451 自由文本收编：把两份 .md 洗进结构化存储。 */
    MEMORY_IMPORT("memory_import", "记忆收编", CanaryBand.CANARY, "memory_import", "解析不了的行原样保留"),

    /** 290 斥候：未完事项清点。 */
    SCOUT("scout", "斥候清点", CanaryBand.OFF, "scout", "只记不判"),
    ;

    companion object {
        /** 线值 ⇒ 能力；认不出 ⇒ null（不在登记表里的能力一律当没这回事，fail-closed）。 */
        fun ofWire(wire: String): CanaryCapability? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 一次灰度**观察到的执行**（从账本里读回来的最小单位）。
 *
 * 为什么只有这四个字段：派工单 ② 点的是「次数／失败率／人工介入」三件事，
 * 多给一个字段就多一个「内存里攒着、账上没有」的入口。
 * [permanentLoss] 是给 ④ 的回灰触发条件用的——它来自 472 的赔付账（见 [Compensation]）。
 */
internal data class CanarySample(
    val atMs: Long,
    val ok: Boolean,
    val askedHuman: Boolean,
    val permanentLoss: Boolean = false,
)

/**
 * 灰度指标（全部由 [of] 从样本算，**没有任何一个入口收内存计数**）。
 *
 * 「算不出」用一个负数字面量表示（不是 0）：0% 会被读成「一次都没失败」，
 * 那正是判据③点名的「手改内存值骗不过」要防的那张脸——没账必须长成没账的样子。
 */
internal data class CanaryMetrics(
    val runs: Int,
    val failures: Int,
    val askedHuman: Int,
    val permanentLosses: Int,
    val windowDays: Int,
) {
    /** 失败率（%）；[UNKNOWN_PCT]＝窗口内一条账都没有。 */
    val failRatePct: Double get() = if (runs == 0) UNKNOWN_PCT else failures * 100.0 / runs

    /** 人工介入率（%）；同样，没账就是没账。 */
    val interventionPct: Double get() = if (runs == 0) UNKNOWN_PCT else askedHuman * 100.0 / runs

    /** 样本够不够动档。 */
    val enoughSamples: Boolean get() = runs >= CanaryLadder.MIN_RUNS

    /** 读得出来（有账且样本够）才算数。 */
    val known: Boolean get() = runs > 0 && enoughSamples

    /** 这一轮的账能不能支持「转全开」（③ 的「达标」在这一句里说死）。 */
    val passedForFull: Boolean
        get() = known &&
            permanentLosses == 0 &&
            failRatePct >= 0.0 && failRatePct <= CanaryLadder.FULL_FAIL_LIMIT_PCT &&
            interventionPct >= 0.0 && interventionPct <= CanaryLadder.FULL_INTERVENTION_LIMIT_PCT

    companion object {
        /** 「算不出」的字面量。 */
        const val UNKNOWN_PCT = -1.0

        /**
         * 由样本算指标：先按时刻筛窗口，再数。
         *
         * 窗口按**观察时刻**（sample 自己带的 `atMs`）而不是被灰度动作的时刻——
         * 问的是「近 N 天跑得怎么样」。
         */
        fun of(
            samples: List<CanarySample>,
            nowMs: Long,
            windowDays: Int = CanaryLadder.WINDOW_DAYS,
        ): CanaryMetrics {
            val from = nowMs - windowDays * DAY_MS
            val inWindow = samples.filter { it.atMs >= from }
            return CanaryMetrics(
                runs = inWindow.size,
                failures = inWindow.count { !it.ok },
                askedHuman = inWindow.count { it.askedHuman },
                permanentLosses = inWindow.count { it.permanentLoss },
                windowDays = windowDays,
            )
        }

        private const val DAY_MS = 86_400_000L
    }
}

/** 一次档位变动（**先问该不该落**，见 [CanaryLadder.refusalOf]）。 */
internal data class CanaryChange(
    val capability: String,
    val before: String,
    val after: String,
    val actor: String,
    val atMs: Long,
    val metrics: CanaryMetrics,
    /** 为什么转（`auto-pass`＝达标自动转；`auto-revert`＝条件写死自动回灰；`human`＝人按的一键回灰）。 */
    val trigger: String,
)

/**
 * 档位表。
 *
 * 两条与 [AllowanceLadder] 同款的**滞回**纪律，这里是反方向用的：
 * 灰度→全开要过线，全开→灰度只看坏消息，不看「是不是又回到线上了」。
 * 没有滞回，一档会在阈值上下反复横跳，而每一次横跳都落一条账——那是把账本当示波器。
 */
internal object CanaryLadder {

    /** 转全开的失败率上限（%）。 */
    const val FULL_FAIL_LIMIT_PCT = 5.0

    /** 转全开的人工介入率上限（%）：达标了还老要问人，说明它并不省心。 */
    const val FULL_INTERVENTION_LIMIT_PCT = 20.0

    /** 自动回灰的失败率线（%）：比转全开的线宽，因为回灰判的是「它不行了」而不是「它最优」。 */
    const val REVERT_FAIL_LIMIT_PCT = 15.0

    /** 自动回灰的人工介入率线（%）。 */
    const val REVERT_INTERVENTION_LIMIT_PCT = 40.0

    /** 窗口天数（指标只看这段，超一段就翻篇——老账不许永久压着新账）。 */
    const val WINDOW_DAYS = 14

    /** 动档最少样本（少于这个数 ⇒ 什么都算不出，照 [CanaryMetrics.known] 判）。 */
    const val MIN_RUNS = 10

    /** 自动转全开要求的连续达标轮数（一轮＝一次读数窗口；1 轮就转是拿运气当依据）。 */
    const val PASS_ROUNDS_FOR_FULL = 2

    /** 触发条件写死的自动回灰（派工单 §二·④）。 */
    const val TRIGGER_AUTO_REVERT = "auto-revert"
    const val TRIGGER_AUTO_PROMOTE = "auto-pass"
    const val TRIGGER_HUMAN = "human"

    /**
     * 下一档（[current]＝现在生效的档）。
     *
     * 拿不到读数 ⇒ **原样返回**：升档要依据、降档也要依据，两个都拿不出时动档就是自己在改政策。
     * [OFF]→[CANARY] 不在这里发生：那是人拨的（见 [humanEnable]），机制不许自己把关掉的东西打开。
     */
    fun nextBand(current: CanaryBand, m: CanaryMetrics, passedRounds: Int = 1): CanaryBand = when {
        // 退档只在**高位**发生：[OFF] 时窗口里几条老坏账不许把能力自己打开——
        // 「关→灰」是人拨的（本函数 KDoc 那条），拿坏消息当开关就是自己打自己那条纪律。
        current == CanaryBand.FULL && (m.permanentLosses > 0 || overRevertLine(m)) -> CanaryBand.CANARY
        !m.known -> current
        current == CanaryBand.CANARY && m.passedForFull && passedRounds >= PASS_ROUNDS_FOR_FULL -> CanaryBand.FULL
        else -> current
    }

    /** 坏消息到没到线（两个指标任一越线即算；线值是本文件常量，**不读配置、不读模型自述**）。 */
    fun overRevertLine(m: CanaryMetrics): Boolean {
        if (!m.known) return false
        return m.failRatePct > REVERT_FAIL_LIMIT_PCT || m.interventionPct > REVERT_INTERVENTION_LIMIT_PCT
    }

    /**
     * 该不该自动回灰（④「回灰自动」的触发条件，**写死**在这里）：
     *   · 窗口内出现一次**永久损失**（472 判的「赔不了」）；
     *   · 或失败率／介入率任一到线。
     *
     * 只有 [CanaryBand.FULL] 需要它回答「要不要退」；[CanaryBand.CANARY] 本来就在低位，
     * 把「已在灰度」报成「已回灰」是假动作，账上会多一条什么都没变的行。
     */
    fun revertBand(current: CanaryBand, m: CanaryMetrics): CanaryBand? =
        if (current == CanaryBand.FULL && (m.permanentLosses > 0 || overRevertLine(m))) CanaryBand.CANARY else null

    /** 人按的那颗「一键回灰」：不管指标说什么，一律退到灰度（不许退到关闭——那是另一件事）。 */
    fun humanRevert(current: CanaryBand): CanaryBand? =
        if (current == CanaryBand.OFF) null else CanaryBand.CANARY

    /** 人拨的「从关闭打开到灰度」；[CanaryBand.FULL] 不许被直接拨出来（必须走达标自动转）。 */
    fun humanEnable(capability: CanaryCapability): CanaryBand = capability.defaultBand

    /**
     * 这一笔该不该落账（null＝可以落）。
     *
     * 四条牙：
     *   · 模型自己转档 ⇒ 拒（既当运动员又当裁判；转档的手只能是达标机制或人）；
     *   · 没读数 ⇒ 拒（谁也不许拿「算不出」当依据动档）；
     *   · 没达标却转全开 ⇒ 拒（③「未达标不许手改数字」——手改的是**档位**，数字本身在账上算不出来，
     *     所以这里连「把失败率填成 0」都一并拒：[CanaryMetrics] 根本没有构造读数的公开入口之外的路）；
     *   · 档没动 ⇒ 拒（不落空变动）。
     */
    fun refusalOf(c: CanaryChange): String? {
        val from = CanaryBand.ofWire(c.before)
        val to = CanaryBand.ofWire(c.after)
        return when {
            c.actor == IdentityRules.ACTOR_MODEL -> "灰度档位不许由模型自调（actor=model）"
            from == null || to == null -> "认不出的档位（${c.before}→${c.after}）"
            !CanaryRegistry.knows(c.capability) -> "没登记在册的自动化能力：${c.capability}"
            !c.metrics.known -> "读数算不出（窗口 ${c.metrics.windowDays} 天内不足 ${MIN_RUNS} 条账）——不动档"
            from.wire == to.wire -> "档位没动，不落空变动"
            // ③ 的两半在这一句：达标 ⇒ 机制自动转全开（不需要人点）；未达标 ⇒ 谁也不许把它转上去。
            to == CanaryBand.FULL && !c.metrics.passedForFull ->
                "未达标不许转全开（失败 ${pct(c.metrics.failRatePct)}%／介入 ${pct(c.metrics.interventionPct)}%" +
                    "／永久损失 ${c.metrics.permanentLosses} 条）"
            to == CanaryBand.OFF && c.trigger != TRIGGER_HUMAN -> "回关闭只许人拨（机制不许自己把能力关掉）"
            else -> null
        }
    }

    /** 百分比读数（拿 [CanaryMetrics.UNKNOWN_PCT] 进来出「无账」，不出 -1%）。 */
    fun pct(value: Double): String = if (value < 0.0) "无账" else value.toString()

    /** 一句话读数（灰度卡的同一句）。 */
    fun readout(capability: CanaryCapability, m: CanaryMetrics, before: CanaryBand, after: CanaryBand): String {
        val band = if (after == before) after.cn else "${before.cn}→${after.cn}"
        return "${capability.cn}：${m.runs} 条／失败 ${pct(m.failRatePct)}%／介入 ${pct(m.interventionPct)}%" +
            "／永久损失 ${m.permanentLosses} ⇒ 档位 $band"
    }
}

/**
 * 登记表（把「缺省不许全开」钉成一句可执行的断言，而不是注释里的形容词）。
 *
 * 判据红在这里说清楚：任何一条 [CanaryCapability] 的 `defaultBand` 写成 [CanaryBand.FULL]
 * ⇒ [violations] 立刻点名。测试用例把这条当负例跑（改一行枚举即红）。
 */
internal object CanaryRegistry {

    /** 全部在册能力。 */
    val all: List<CanaryCapability> get() = CanaryCapability.entries.toList()

    /** 某一档缺省的能力清单（装配层冷启动读它）。 */
    fun defaults(): Map<CanaryCapability, CanaryBand> = all.associateWith { it.defaultBand }

    /** 违反「缺省＝关闭或灰度」的能力名（空＝合规）。 */
    fun violations(): List<String> =
        all.filter { it.defaultBand == CanaryBand.FULL }.map { "${it.wire} 缺省写成了全开" }

    /** 认得这个能力吗（不在表里的能力一律拒，fail-closed）。 */
    fun knows(wire: String): Boolean = CanaryCapability.ofWire(wire) != null
}
