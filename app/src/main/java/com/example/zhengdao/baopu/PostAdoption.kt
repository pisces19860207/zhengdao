// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百批（2026-10-21）：**采纳后效果回环**的纯逻辑层——事件模型、窗口、增量对比、四值结论。
//
// 为什么单独一个文件：这一层**零 I/O、零 Android、零网络**，全部可 JVM 直测。
// 落盘在 [PostAdoptionLog]，接线在 [PostAdoptionPort]，人批回滚在 [PostAdoptionRollback]，
// 三处各管一头；判定只在这里出现一次（UI 与 SQL 都不许再写第二份判据）。
//
// 三条边界（派工单 §0–§3 原文）：
//   1. 结论只是**建议**：KEEP / ROLLBACK / RETIRE / DEFER 谁也不执行，回滚要人点；
//   2. 数据不足一律 DEFER，**零样本不等于该退役**（沉默不是证据）；
//   3. 比的是**增量**（采纳后现读 − 采纳前基线），不是「现在一共几次」——所以不新增
//      时间窗 SQL、不动 `BaopuDb.VERSION`。
package com.example.zhengdao.baopu

/** 采纳是从哪条通道落盘的（三条通道的处置不同：前两条写技能正文，第三条写人设索引）。 */
internal enum class PostAdoptionChannel {
    /** 「这次学到」学习卡。 */
    LEARN_CARD,

    /** 人设／技能索引提案（`PersonaFiles.applyProposal`）。 */
    SKILL_PROPOSAL,

    /** 86 提案候选被采纳（99 之后才有执行面）。 */
    PROPOSAL_CANDIDATE,
}

/**
 * 采纳前基线的来源。**三态不是装饰**：
 *
 * - [NEW]：采纳前这个技能文件**不存在** ⇒ 基线全 0 是事实，不是猜的；
 * - [MEASURED]：文件已存在 ⇒ 采纳前现读一次 `SkillEffects.outcomesFor` 原样存下来；
 * - [UNKNOWN]：那次读取拿不到 ⇒ 基线未知。**判定时永远只出 DEFER**，
 *   绝不当 0 用——「不知道以前几斤几两」和「以前一次没用过」是两件事。
 */
internal enum class PostAdoptionBaseline { NEW, MEASURED, UNKNOWN }

/** 四值结论（派工单 §3）。 */
internal enum class PostAdoptionVerdict {
    /** 用下来是正向的 ⇒ 留着，只记审计不弹卡。 */
    KEEP,

    /** 负向且有备份 ⇒ 建议回滚（**建议**，人不点不动）。 */
    ROLLBACK,

    /** 负向且没备份 ⇒ 建议停用（仓库里**没有退役通道**，所以只能指路去技能列表手动处理）。 */
    RETIRE,

    /** 证据不足/未到期/判不了 ⇒ 什么都别做。 */
    DEFER,
}

/** 结论的理由码（每一个都被用例覆盖；只有它有资格进审计与卡面）。 */
internal enum class PostAdoptionReason {
    /** 窗口未到（次数不够、天数也没到）。 */
    WINDOW_NOT_DUE,

    /** 天数到了但样本仍少于 N。 */
    SAMPLES_INSUFFICIENT,

    /** 采纳前基线未知 ⇒ 无从比较。 */
    BASELINE_UNKNOWN,

    /** 当前读数读不到。 */
    EFFECT_UNREADABLE,

    /** 技能正文在采纳之后又被别人改过 ⇒ 判的不是这一版。 */
    BODY_CHANGED,

    /** 正向。 */
    EFFECT_POSITIVE,

    /** 负向、有备份。 */
    EFFECT_NEGATIVE_ROLLBACKABLE,

    /** 负向、没备份。 */
    EFFECT_NEGATIVE_NO_BACKUP,

    /** 样本够但正负相抵 ⇒ 判不了。 */
    EFFECT_TIE,

    /** 超过四倍窗口仍然一次没用过 ⇒ 结案，但**不判退役**。 */
    NO_USE,
}

/**
 * 窗口参数（派工单 §2）。
 *
 * [USES] **不写字面量**，直接取 [EvolutionPolicy.DEFAULT_MIN_EFFECT_SAMPLES]——
 * 两处各写一个 3 迟早会漂：策略抬到 5 而这里还是 3，就会出现「判定说样本够了、
 * 决策口说不够」的分裂。
 */
internal object PostAdoptionWindow {
    /** 天数窗口。 */
    const val DAYS: Int = 7

    const val MS_PER_DAY: Long = 24L * 60 * 60 * 1000

    /** 天数窗口的毫秒数（判定只用它，不在调用处乘一次除法）。 */
    const val MS: Long = DAYS * MS_PER_DAY

    /** 零使用结案倍数：4 × 7 = 28 天。 */
    const val NO_USE_DAYS: Int = DAYS * 4

    const val NO_USE_MS: Long = NO_USE_DAYS * MS_PER_DAY

    /** 次数窗口：与策略的样本下限同源。 */
    val USES: Int get() = EvolutionPolicy.DEFAULT_MIN_EFFECT_SAMPLES
}

/**
 * 一条采纳事件（派工单 §1 的字段表）。
 *
 * **正文一个字都不进来**：只有名字、正文摘要、四键计数、备份文件名。
 *
 * @param skillVersion 采纳后正文的 sha12（[SkillAudit.shaOf] 同口径）；拿不到写 `"-"`
 * @param baseline 采纳前四键计数（[PostAdoptionBaseline.UNKNOWN] 时为空 map）
 * @param backupIds 备份文件名里的时间戳（只名字，不读内容）
 */
internal data class PostAdoptionEvent(
    val eventId: String,
    val channel: PostAdoptionChannel,
    val candidateId: String?,
    val skillName: String,
    val skillVersion: String,
    val appliedAtMs: Long,
    val baseline: Map<SkillOutcome, Int> = emptyMap(),
    val baselineSource: PostAdoptionBaseline,
    val rollbackAvailable: Boolean,
    val backupIds: List<String> = emptyList(),
    val windowUses: Int = PostAdoptionWindow.USES,
    val windowDays: Int = PostAdoptionWindow.DAYS,
) {
    init {
        require(eventId.isNotBlank()) { "事件 id 不能为空" }
        require(skillName.isNotBlank()) { "技能名不能为空" }
        require(windowUses >= 1) { "次数窗口至少 1" }
        require(windowDays >= 1) { "天数窗口至少 1" }
    }

    /** 基线里某一键的次数（没记过就是 0）。 */
    fun baselineOf(outcome: SkillOutcome): Int = baseline[outcome] ?: 0
}

/** 结案/处置动作（与 [PostAdoptionVerdict] 是两个维度：结论说「该怎样」，动作说「做了什么」）。 */
internal enum class PostAdoptionAction {
    /** 已弹过建议卡（防重复弹）。 */
    SUGGESTED,

    /** 用户点了「稍后处理」——**不结案**，下次收尾再判。 */
    LATER,

    /** 结论 KEEP，结案。 */
    KEPT,

    /** 用户点了回滚且真的回滚成功。 */
    APPLIED,

    /** 用户点了「先留着」——接受风险，结案不再提。 */
    DISMISSED,

    /** 判不了/没得判（NO_USE、基线未知、正文被改），结案但不打扰用户。 */
    CLOSED,

    /** 同一技能又被采纳了一次，旧窗口作废（不重复数同一批使用）。 */
    SUPERSEDED,
    ;

    /** 终态：这些动作之后这条事件不再参与复测。 */
    val terminal: Boolean
        get() = when (this) {
            SUGGESTED, LATER -> false
            KEPT, APPLIED, DISMISSED, CLOSED, SUPERSEDED -> true
        }
}

/** 一行结案/处置记录（与事件同一份 JSONL，用 `type` 区分）。 */
internal data class PostAdoptionSettlement(
    val eventId: String,
    val verdict: PostAdoptionVerdict?,
    val action: PostAdoptionAction,
    val atMs: Long,
    val reason: PostAdoptionReason? = null,
    val delta: EffectSnapshot? = null,
) {
    init {
        require(eventId.isNotBlank()) { "结案记录的 eventId 不能为空" }
    }
}

/**
 * 判定结果。
 *
 * @param terminal 是否可以就此结案（KEEP、NO_USE、基线未知、正文被改都可以；
 *   ROLLBACK/RETIRE **不能**——它们要等人批；未到期/样本不足也不能）
 */
internal data class PostAdoptionJudgement(
    val verdict: PostAdoptionVerdict,
    val reason: PostAdoptionReason,
    val delta: EffectSnapshot,
    val terminal: Boolean,
)

/**
 * 增量 = 采纳后现读 − 采纳前基线（四键各自相减）。
 *
 * **为什么不用时间窗 SQL**：`BaopuSql.skillOutcomeCounts` 只有 `name` 一个条件，
 * 加时间条件就得改 SQL、改测试、还可能动 schema；增量法零 SQL 改动，且天然避开
 * `skill_version` 可能为 NULL 的漏计。
 *
 * 减出负数按 0 算（库被清过/行被删过）：增量不许为负，也不许因为这种历史变动让整条判定炸掉。
 */
internal fun postAdoptionDelta(event: PostAdoptionEvent, after: Map<SkillOutcome, Int>?): EffectSnapshot {
    fun d(outcome: SkillOutcome): Int =
        ((after?.get(outcome) ?: 0) - event.baselineOf(outcome)).coerceAtLeast(0)
    return EffectSnapshot(
        successes = d(SkillOutcome.SUCCESS),
        failures = d(SkillOutcome.FAILURE),
        corrections = d(SkillOutcome.USER_CORRECTED),
        retries = d(SkillOutcome.RETRIED),
    )
}

/**
 * 一条事件的结论（纯函数）。顺序写死，改顺序＝改语义：
 *
 *   基线未知 → 正文被改 → 读数读不到 → 零使用（28 天结案）→ 未到期 → 样本不足
 *   → 正向 KEEP → 负向（可回滚 ROLLBACK／不可回滚 RETIRE）→ 相抵 DEFER
 *
 * @param after 现在读到的四键计数；读不到传 null（⇒ DEFER，不猜）
 * @param currentVersion 现在盘上那份正文的 sha12；`"-"` 表示读不到（读不到就不做版本比对）
 */
internal fun judgePostAdoption(
    event: PostAdoptionEvent,
    after: Map<SkillOutcome, Int>?,
    nowMs: Long,
    currentVersion: String,
    minSamples: Int = PostAdoptionWindow.USES,
): PostAdoptionJudgement {
    val delta = postAdoptionDelta(event, after)
    val elapsed = nowMs - event.appliedAtMs
    val due = delta.attempts >= minSamples || elapsed >= event.windowDays * PostAdoptionWindow.MS_PER_DAY

    if (event.baselineSource == PostAdoptionBaseline.UNKNOWN) {
        return PostAdoptionJudgement(PostAdoptionVerdict.DEFER, PostAdoptionReason.BASELINE_UNKNOWN, delta, terminal = true)
    }
    if (currentVersion != "-" && event.skillVersion != "-" && currentVersion != event.skillVersion) {
        return PostAdoptionJudgement(PostAdoptionVerdict.DEFER, PostAdoptionReason.BODY_CHANGED, delta, terminal = true)
    }
    if (after == null) {
        return PostAdoptionJudgement(PostAdoptionVerdict.DEFER, PostAdoptionReason.EFFECT_UNREADABLE, delta, terminal = false)
    }
    if (delta.attempts == 0) {
        // 零使用：到期也不判死——沉默只是没人用，不是用坏了。
        return if (elapsed >= PostAdoptionWindow.NO_USE_MS) {
            PostAdoptionJudgement(PostAdoptionVerdict.DEFER, PostAdoptionReason.NO_USE, delta, terminal = true)
        } else {
            PostAdoptionJudgement(PostAdoptionVerdict.DEFER, PostAdoptionReason.WINDOW_NOT_DUE, delta, terminal = false)
        }
    }
    if (!due) {
        return PostAdoptionJudgement(PostAdoptionVerdict.DEFER, PostAdoptionReason.WINDOW_NOT_DUE, delta, terminal = false)
    }
    if (delta.attempts < minSamples) {
        return PostAdoptionJudgement(PostAdoptionVerdict.DEFER, PostAdoptionReason.SAMPLES_INSUFFICIENT, delta, terminal = false)
    }
    return when (EvolutionPolicy.effectVerdictOf(delta, minSamples)) {
        EffectVerdict.POSITIVE ->
            PostAdoptionJudgement(PostAdoptionVerdict.KEEP, PostAdoptionReason.EFFECT_POSITIVE, delta, terminal = true)

        EffectVerdict.NEGATIVE -> if (event.rollbackAvailable) {
            PostAdoptionJudgement(
                PostAdoptionVerdict.ROLLBACK,
                PostAdoptionReason.EFFECT_NEGATIVE_ROLLBACKABLE,
                delta,
                terminal = false,
            )
        } else {
            // 没有备份 ⇒ 退不回去，只能建议停用。⚠️ 仓库里没有退役通道：
            // 这条结论**只出卡指路**（去技能列表手动处理），不许新增删除/停用代码路径。
            PostAdoptionJudgement(
                PostAdoptionVerdict.RETIRE,
                PostAdoptionReason.EFFECT_NEGATIVE_NO_BACKUP,
                delta,
                terminal = false,
            )
        }

        EffectVerdict.INSUFFICIENT ->
            PostAdoptionJudgement(PostAdoptionVerdict.DEFER, PostAdoptionReason.EFFECT_TIE, delta, terminal = false)
    }
}

/**
 * 账本视图：从「事件 + 结案记录」算出还开着的那几条。
 *
 * 只读、纯逻辑（不碰文件），所以「谁算结案」「同一技能两条窗口怎么办」都能直测。
 */
internal class PostAdoptionLedger(
    val events: List<PostAdoptionEvent>,
    val settlements: List<PostAdoptionSettlement>,
) {

    private val terminalIds: Set<String> = settlements
        .filter { it.action.terminal }
        .map { it.eventId }
        .toSet()

    private val suggestedIds: Set<String> = settlements
        .filter { it.action == PostAdoptionAction.SUGGESTED }
        .map { it.eventId }
        .toSet()

    /** 还开着的事件（新→旧）。 */
    fun open(): List<PostAdoptionEvent> =
        events.filter { it.eventId !in terminalIds }.sortedByDescending { it.appliedAtMs }

    /** 这条事件是不是已经弹过建议卡（防重复弹）。 */
    fun alreadySuggested(eventId: String): Boolean = eventId in suggestedIds

    /**
     * 同一技能只允许一条未结案事件：新采纳到来时把旧的都作废。
     *
     * 不做这一步就会**双计**——同一批使用同时落进两个窗口，第二个窗口看着像独立证据。
     */
    fun supersedeOnNewAdoption(newEvent: PostAdoptionEvent, atMs: Long): List<PostAdoptionSettlement> =
        open()
            .filter { it.skillName == newEvent.skillName && it.eventId != newEvent.eventId }
            .map {
                PostAdoptionSettlement(
                    eventId = it.eventId,
                    verdict = null,
                    action = PostAdoptionAction.SUPERSEDED,
                    atMs = atMs,
                )
            }
}
