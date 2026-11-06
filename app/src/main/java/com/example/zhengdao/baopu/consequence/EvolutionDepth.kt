// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零六批（2026-11-07）· 自进化深度裁定（派工单「追加」节，2026-11-06 用户原话）。
//
// 一句话：**左右相按需求决定进化到哪一步**。深度三档——
//   · 浅：只记不改（写账/经验）
//   · 中：改组织形态（合并/去重/指针化/重排，**不改语义**）
//   · 深：改结构（技能步骤/提示词结构/注入策略）⇒ **必须过本单的灰度**（见 [CanaryLadder]）
//
// 谁来定：左相看成本（预算/耐心/画像置信/当日打扰次数）＋ 右相看证据（三态/证据足不足/有无反证）。
// 合成规则：默认浅；证据 PASS＋预算有余＋画像高置信 ⇒ 中；连续 N 次成功＋可回滚已验证 ⇒ 深；
// **任一 FAIL / UNVERIFIABLE 增多 / 误拦上升 ⇒ 立刻降一档**（不等周期）。
//
// 三条「模型只是参谋」的硬线（追加判据⑤）在这里是**类型形状**上的做不到，不是注释：
//   · [AdvisorOpinion] 没有任何一格能装 `ChancellorVerdict` ⇒ 覆盖不了右相三态；
//   · 也没有任何一格能装 [EvolutionDepth] ⇒ 决定不了深度档；
//   · 给自己产出发通过 ⇒ [AdvisorRules.rejection] 直接拒，并要求**记一条账**（拒了不记等于没拦住）。
//
// 左相那一侧为什么是「事实位」而不是 import：`MediatorDecide` 属第四百九十九批
// （feat/ask-governance，此刻尚未提交），本批不能把编译挂在别人的在飞分支上。
// 所以本层收四个算好的成本位，499 落地时把它们喂进来即可——
// 这条依赖写在交付报告 §0，与 472→470 那条同格式。
//
// 右相那一侧**直接复用现成的三态类型**（`baopu/verify/ChancellorGate.kt:35`）：
// 自己另开一个 PASS/FAIL/UNVERIFIABLE 是本仓反复记过的那类债（两处各写一遍，改阈值必漏一处）。
package com.example.zhengdao.baopu.consequence

import com.example.zhengdao.baopu.verify.ChancellorVerdict

/**
 * 进化深度三档（**闭集**；[rank] 越大改得越深）。
 *
 * [SHALLOW] 是缺省档：新装首跑、没有历史、读数算不出，统统停在这一档。
 * 「浅＝只记不改」意味着这一档不需要任何权限——升档才是要被闸住的那一步。
 */
internal enum class EvolutionDepth(val wire: String, val cn: String, val rank: Int) {

    /** 只写账与经验，一个字节的内容都不改。 */
    SHALLOW("shallow", "浅", 0),

    /** 改组织形态：合并／去重／指针化／重排。语义不动。 */
    MEDIUM("medium", "中", 1),

    /** 改结构：技能步骤／提示词结构／注入策略。必须已经过了灰度这一关。 */
    DEEP("deep", "深", 2),
    ;

    companion object {
        /** 线值 ⇒ 档；认不出 ⇒ null（自造档位不渲染，也不静默当 [SHALLOW]）。 */
        fun ofWire(wire: String): EvolutionDepth? = entries.firstOrNull { it.wire == wire }

        /** 缺省档（追加判据②：新装首跑＝浅）。 */
        val DEFAULT: EvolutionDepth = SHALLOW
    }
}

/**
 * 左相的成本位（**全部由调用方算好**，本层不看预算表、不看画像、不看时钟）。
 *
 * @param budgetLeft 预算还有余
 * @param patient 用户此刻还接得住（耐心画像）
 * @param highConfidence 画像置信高
 * @param interruptionsToday 当日已打扰次数（超过 [DepthGate.MAX_INTERRUPTIONS_TODAY] 就不要再动档）
 */
internal data class DepthCostFacts(
    val budgetLeft: Boolean = false,
    val patient: Boolean = false,
    val highConfidence: Boolean = false,
    val interruptionsToday: Int = 0,
) {
    /** 升一档的三条成本条件齐不齐（追加口径「左相看成本」那三项）。 */
    val readyToRise: Boolean
        get() = budgetLeft && patient && highConfidence && interruptionsToday <= DepthGate.MAX_INTERRUPTIONS_TODAY
}

/**
 * 右相的证据位。
 *
 * @param verdict 右相三态（**唯一入口是这个已存在的枚举**，模型那一侧给不进来）
 * @param evidenceEnough 证据足不足（追加口径点名的一项）
 * @param hasCounterEvidence 有无反证（有反证 ⇒ 一条 PASS 也不许升）
 * @param consecutiveSuccess 连续成功次数（升「深」要看它）
 * @param rollbackVerified 可回滚**已验证**过（不是「标了可回滚」，是真回滚过一次）
 * @param unverifiableUp UNVERIFIABLE 在涨（追加口径的降档信号之一）
 * @param falseBlockUp 误拦在涨（同上）
 */
internal data class DepthEvidenceFacts(
    val verdict: ChancellorVerdict = ChancellorVerdict.UNVERIFIABLE,
    val evidenceEnough: Boolean = false,
    val hasCounterEvidence: Boolean = false,
    val consecutiveSuccess: Int = 0,
    val rollbackVerified: Boolean = false,
    val unverifiableUp: Boolean = false,
    val falseBlockUp: Boolean = false,
) {
    /** 坏消息到没到（追加口径：任一 FAIL / UNVERIFIABLE 增多 / 误拦上升 ⇒ 立刻降一档）。 */
    val badNews: Boolean
        get() = verdict == ChancellorVerdict.FAIL || unverifiableUp || falseBlockUp
}

/** 一次深度决定该落的那行账（追加判据①：谁定的／依据哪几项／当时读数）。 */
internal data class DepthChange(
    val capability: String,
    val before: String,
    val after: String,
    val actor: String,
    val atMs: Long,
    /** 依据哪几项（成本位＋证据位的名字，至少两项）。 */
    val basis: List<String>,
    /** 当时的读数（次数／通过率／灰度档……数字本体，不是「达标了」三个字）。 */
    val readings: List<String>,
)

/**
 * 深度档的闸。
 *
 * 合成规则按追加口径逐字实现，两条顺序上的讲究：
 *   1. **坏消息永远排在好消息前面**——降档不等周期，升档要等够条件；
 *      同一次里既像该升又像该降，就按该降办（取严不取宽，与 `AutonomyRules.worseOf` 同款）。
 *   2. **一次只动一档**：从浅直跳深没有这条路，必须先把中档跑出一段成功账。
 *      这条不是保守，是判据④的可验证性——一次跳两档时，出事分不清是哪一档带来的。
 */
internal object DepthGate {

    /** 升「深」要求的连续成功次数（追加口径原文的 N；这里定死一个值，不留配置口子）。 */
    const val CONSECUTIVE_SUCCESS_FOR_DEEP = 3

    /** 当日打扰上限：超过它就不许再动档（动档本身是一次打扰）。 */
    const val MAX_INTERRUPTIONS_TODAY = 3

    /**
     * 新装首跑那一轮该在哪档（追加判据②）。
     *
     * 单独一个函数是为了让「首跑＝浅」这一条**可被测试点名**：装配层必须调它，
     * 而不是在注释里说「第一次会停在浅档」。
     */
    fun firstRunDepth(): EvolutionDepth = EvolutionDepth.DEFAULT

    /** 坏消息 ⇒ 立刻降一档；已经在浅档就原样返回（浅档没有可降的下一档）。 */
    fun demoteOne(current: EvolutionDepth): EvolutionDepth =
        if (current.rank == 0) current else EvolutionDepth.entries.first { it.rank == current.rank - 1 }

    /**
     * 下一次该在哪档（**追加判据③④ 都在这一个函数里**）。
     *
     * @param canaryBand 该能力现在的灰度档（升「深」必须是 [CanaryBand.FULL]——
     *   原文「深 ⇒ 必须过本单的灰度」；灰度没达标就说结构可以改，那是把两件事的因果倒了）
     */
    fun nextDepth(
        current: EvolutionDepth,
        cost: DepthCostFacts,
        evidence: DepthEvidenceFacts,
        canaryBand: CanaryBand,
    ): EvolutionDepth {
        if (evidence.badNews) return demoteOne(current)
        if (!cost.readyToRise) return current
        val risen = EvolutionDepth.entries.firstOrNull { it.rank == current.rank + 1 } ?: return current
        return when (risen) {
            EvolutionDepth.MEDIUM -> if (canRiseToMedium(evidence)) risen else current
            EvolutionDepth.DEEP -> if (canRiseToDeep(evidence, canaryBand)) risen else current
            else -> current
        }
    }

    /** 升「中」：证据 PASS＋证据足＋无反证（成本三件在 [DepthCostFacts.readyToRise] 里已经查过）。 */
    fun canRiseToMedium(e: DepthEvidenceFacts): Boolean =
        e.verdict == ChancellorVerdict.PASS && e.evidenceEnough && !e.hasCounterEvidence

    /** 升「深」：连续 N 次成功＋可回滚已验证＋灰度已全开（**三条同时**，追加判据③）。 */
    fun canRiseToDeep(e: DepthEvidenceFacts, canaryBand: CanaryBand): Boolean =
        e.consecutiveSuccess >= CONSECUTIVE_SUCCESS_FOR_DEEP &&
            e.rollbackVerified &&
            canaryBand == CanaryBand.FULL

    /**
     * 这一笔深度变动该不该落账（null＝该落）。
     *
     * 四条牙都对着追加判据①⑤：
     *   · 模型定的档 ⇒ 拒（「模型参谋不决定深度档」）；
     *   · 没依据项 ⇒ 拒（事后没人知道为什么动的，那这一行等于没写）；
     *   · 没读数 ⇒ 拒（只有结论没有数字＝手改）；
     *   · 档没动 ⇒ 拒（不落空变动）。
     */
    fun refusalOf(c: DepthChange): String? {
        val from = EvolutionDepth.ofWire(c.before)
        val to = EvolutionDepth.ofWire(c.after)
        return when {
            c.actor == IdentityRules.ACTOR_MODEL -> "深度档不许由模型定（actor=model）"
            from == null || to == null -> "认不出的深度档（${c.before}→${c.after}）"
            !CanaryRegistry.knows(c.capability) -> "没登记在册的自动化能力：${c.capability}"
            // 升降都一次一档：`abs` 不是笔误——只写 `to - from > 1`，浅档被一次踢到深档就查不出来。
            kotlin.math.abs(to.rank - from.rank) > 1 -> "一次只许动一档（${from.cn}→${to.cn}）"
            from.rank == to.rank -> "深度档没动，不落空变动"
            c.basis.size < MIN_BASIS -> "深度档没写依据哪几项（要 $MIN_BASIS 项起）"
            c.readings.isEmpty() -> "深度档没带当时读数"
            else -> null
        }
    }

    /** 依据项最少几项：成本一项＋证据一项，缺一个就成了「单边说了算」。 */
    const val MIN_BASIS = 2
}

/**
 * 模型参谋的意见。
 *
 * **形状纪律**（这三句是判据⑤的实现，不是文体）：
 *   · 只有 `advice`（建议通过/不通过），没有 `verdict` ⇒ 覆盖不了右相三态；
 *   · 没有 `depth` 格 ⇒ 决定不了深度档；
 *   · `aboutOwnOutput` 为真就是「给自己发票」，由 [AdvisorRules.rejection] 拒掉。
 */
internal data class AdvisorOpinion(
    val advisor: String,
    /** 建议：`pass`／`fail`。这是**建议**，不改进右相那一格。 */
    val advice: String,
    val reason: String,
    val risks: List<String>,
    /** 被评的东西是不是这位参谋自己产出的。 */
    val aboutOwnOutput: Boolean,
    val atMs: Long,
)

/** 参谋意见的落点（`advisorOpinion` 是本批账行里的 evidence 前缀，见 [ConsequenceLedger]）。 */
internal object AdvisorRules {

    const val ADVICE_PASS: String = "pass"
    const val ADVICE_FAIL: String = "fail"

    /**
     * 这条意见能不能收（null＝可以收；非 null＝**被拦的原因，并且必须记账**）。
     *
     * 三条都点名自「模型参谋验收（不给票）」：
     *   · 给自己的产出发通过 ⇒ 拦；
     *   · 想拿建议冒充三态 ⇒ 拦（`advice` 写成 PASS/FAIL 那套右相字面量即算冒充）；
     *   · 想指定深度档 ⇒ 拦（本层没有那一格，所以这里拦的是「理由里写了要升到某档」）。
     */
    fun rejection(o: AdvisorOpinion): String? = when {
        o.advisor == IdentityRules.ACTOR_MODEL && o.aboutOwnOutput && o.advice == ADVICE_PASS ->
            "模型不许给自己的产出发通过"
        // 逐字（**不分大小写**就错了）比对：`pass`／`fail` 是建议这一族的合法线值，
        // 而 `PASS`／`FAIL`／`UNVERIFIABLE` 是右相那一族的字面量——
        // 拿 ignoreCase 一判，模型每条合法建议都会被判成"冒充三态"，闸就成了一句废话。
        o.advisor == IdentityRules.ACTOR_MODEL && o.advice in TRI_STATE_WORDS ->
            "参谋意见不许覆盖右相三态（advice=${o.advice}）"
        o.advisor == IdentityRules.ACTOR_MODEL && o.reason.containsAnyDepthWord() ->
            "参谋不许决定深度档（理由里点名了档位）"
        o.advice != ADVICE_PASS && o.advice != ADVICE_FAIL -> "认不出的建议值：${o.advice}"
        o.reason.isBlank() -> "建议没有理由，不收（一句「我觉得行」不是参谋）"
        else -> null
    }

    /** 意见是不是「通过」那一头。 */
    fun suggestsPass(o: AdvisorOpinion): Boolean = o.advice == ADVICE_PASS

    /** 右相三态的字面量（参谋拿来当建议值即算冒充）。 */
    private val TRI_STATE_WORDS = setOf("PASS", "FAIL", "UNVERIFIABLE")

    /** 理由里有没有点名深度档（浅/中/深 或 shallow/medium/deep）。 */
    private fun String.containsAnyDepthWord(): Boolean =
        EvolutionDepth.entries.any { this.contains(it.cn) || this.contains(it.wire, ignoreCase = true) }
}
