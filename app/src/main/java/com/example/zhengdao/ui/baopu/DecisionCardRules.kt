// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百零四批（2026-10-21，派工单 §0/§2/§3）：**决策卡家族与全局单卡槽的纯判据层**。
//
// 设计依据（定稿 v1.4，_zd-private/docs/抱朴UI设计方案-2026-10-21.md）：
//   - §0 决策 #3：授权卡/建议卡/提案卡/效果卡不是四个新组件，而是同一个「决策卡」
//     骨架的四个变体（四种事、一张卡）；
//   - §2.3：对话流有且只有一个决策卡槽，按优先级占槽（授权 > 效果 > 提案 > 建议），
//     新卡到来不抢走正在展示的卡；处理完释放、队首补位；KEEP/DEFER 不占槽；
//   - §3.6b：徽标＝待授权＋建议＋提案的待处理总数（记录页不计入）。
//
// 纪律（与本仓其它 *Rules 文件一致）：本文件不许出现 @Composable、不许拿 Context、
// 不许碰控制器或 IO——输入普通值，输出普通值/数据类，全部可在 JVM 里直测。
// 渲染在 `ui/baopu/DecisionCard.kt`，接线在 `BaopuChatController` / `BaopuScreen`。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.R
import com.example.zhengdao.baopu.EvolutionRecommendation
import com.example.zhengdao.baopu.EvolutionReasonCode
import com.example.zhengdao.baopu.EvolutionRisk
import com.example.zhengdao.baopu.ProposalKind

// ─────────────────────────────────────────────────────────────────────────────
// 一、槽位优先级（设计稿 §2.3 顺位表）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 决策卡的四种事（设计稿 §2「四变体」）。
 *
 * 声明序即**展示优先级**（§2.3 顺位表：授权 > 效果 > 提案 > 建议）——
 * 排序只认这个序，别处再写一套比较器就是第二份判据。
 */
internal enum class DecisionVariant {
    /** 工具组授权卡（97/98：取消/本次会话/一直）。 */
    AUTHORIZATION,

    /** 采纳后效果卡（100：先留着/稍后处理/回滚到采纳前）。 */
    POST_ADOPTION,

    /** B/C 人设提案卡（96：拒绝/本工作区/全局仅B）。 */
    PERSONA,

    /** 进化建议卡（99：拒绝/搁置/采纳）。 */
    SUGGESTION,
}

/**
 * 槽里的一件待办（kind＋稳定 id）。
 *
 * id 的构成（接线层保证稳定，判据层只认「同一个 id 同一件」）：
 * - AUTHORIZATION：`auth:<group key>`（同一组同时只弹一张卡，ToolGroupGate 合并）
 * - POST_ADOPTION：`effect:<eventId>`（同一条事件只弹一次，账本 alreadySuggested 兜底）
 * - PERSONA：`persona:<layer>`（引擎 choice 单槽，同一时刻只有一件）
 * - SUGGESTION：`suggestion:<candidateId>`（一条候选一件，可多条排队）
 */
internal data class DecisionItem(val kind: DecisionVariant, val id: String) {
    init {
        require(id.isNotBlank()) { "决策卡条目必须有稳定 id" }
    }
}

/**
 * 全局单卡槽：这一刻该展示哪一件（设计稿 §2.3 第 1/2/5 条）。
 *
 * 三条口径（负例靶子各对应一条）：
 * 1. **不抢占**：current 仍在 pending 里 ⇒ 原样返回它，新来的再急也排队
 *    （负例：改成恒取 pending 最高优先级 ⇒ 新卡抢槽，本条红）；
 * 2. **释放即补位**：current 已被处理（不在 pending）或本来没卡 ⇒ 取 pending 里
 *    优先级最高的一件（同优先级按传入序，传入序由接线层保证稳定）；
 * 3. **空槽返 null**：什么都没有 ⇒ 不渲染卡（不是渲染一张空卡）。
 */
internal fun decisionSlotCurrent(current: DecisionItem?, pending: List<DecisionItem>): DecisionItem? {
    if (current != null && pending.any { it == current }) return current
    return pending.minByOrNull { it.kind.ordinal }
}

/**
 * 排队数（「还有 N 条」那一行的 N）：pending 除去正在展示的那一件。
 *
 * 展示中的那件不算排队（它在槽里，不在队里）；没有展示 ⇒ 全部 pending 都是排队。
 */
internal fun decisionQueueCount(current: DecisionItem?, pending: List<DecisionItem>): Int {
    val shown = decisionSlotCurrent(current, pending)
    return pending.count { it != shown }
}

/**
 * 顶栏盾牌徽标（设计稿 §3.6b／验收 #24）：待授权＋建议＋提案三页签的待处理总数。
 *
 * 记录页不计入（那是历史，不是待办）；效果卡与建议卡同驻「建议」页签 ⇒ 一起数。
 */
internal fun decisionBadgeCount(authorization: Int, suggestion: Int, proposal: Int): Int =
    authorization + suggestion + proposal

// ─────────────────────────────────────────────────────────────────────────────
// 二、卡片规格（渲染层的唯一输入；文案由接线层按语言填好）
// ─────────────────────────────────────────────────────────────────────────────

/** 按钮角色（设计稿 §2「按钮态」：主按钮/tonal/文字钮/危险主钮）。 */
internal enum class DecisionButtonRole {
    /** 取消/拒绝（文字钮或描边，永远在左）。 */
    NEGATIVE,

    /** 搁置/稍后处理（缓冲档，tonal）。 */
    BUFFER,

    /** 确认位（实心主按钮）。 */
    PRIMARY,

    /** 明确动词的危险动作（危险实心，仅「回滚到采纳前」这类）。 */
    DANGER,
}

/**
 * 卡上的一枚按钮。
 *
 * @param enabled false = 置灰（如 TOOL_RULE 没有落盘通道 ⇒ 采纳置灰并给 [note]）
 * @param note 置灰原因（按钮底下那行小字；enabled 时忽略）
 */
internal data class DecisionButton(
    val id: String,
    val label: String,
    val role: DecisionButtonRole,
    val enabled: Boolean = true,
    val note: String? = null,
)

/**
 * 徽标色调（设计稿 §1：高风险 #B42318 系、中风险 #B54708 系、低风险/已应用 #067647 系；
 * 实现取既有 colorScheme 的 error/tertiary/secondaryContainer，不写死十六进制）。
 */
internal enum class DecisionBadgeTone { NEUTRAL, WARNING, DANGER }

/**
 * 一张决策卡的完整规格（四变体共用骨架，只差内容——设计稿 §2）。
 *
 * @param key 稳定键（同 [DecisionItem.id]；Compose 侧重置局部状态用它）
 * @param badge 徽标文字（「高」/「中」/「低」/「效果回退」；null = 不显示）
 * @param badgeTone 徽标色调（建议卡按风险定；其余变体 NEUTRAL）
 * @param warning 高风险警示带（贴顶危险色一条；null = 没有）
 * @param body 正文（默认 3 行＋展开）
 * @param chips 证据 chips（自检/eval/效果/可回滚）
 * @param footer 卡底一行小字（「只弹一次」等；null = 不显示）
 */
internal data class DecisionCardSpec(
    val variant: DecisionVariant,
    val key: String,
    val title: String,
    val subtitle: String = "",
    val badge: String? = null,
    val badgeTone: DecisionBadgeTone = DecisionBadgeTone.NEUTRAL,
    val warning: String? = null,
    val body: String = "",
    val chips: List<String> = emptyList(),
    val buttons: List<DecisionButton> = emptyList(),
    val footer: String? = null,
)

/**
 * 卡片规格的自检（**纯函数**；负例靶子：把标题判空删掉 ⇒ 空标题卡也能过，本条红）。
 *
 * 只查「这张卡成不成立」，不查好看不好看：
 * 1. 标题不能空（标题＝做什么，空标题的卡用户不知道在批什么）；
 * 2. 按钮至少一枚、id 不重不空（点下去要能对回接线层）；
 * 3. **恰好一个强调色**（PRIMARY/DANGER 二选一，设计稿 §2「一张卡最多一个强调色」）——
 *    零个＝没有确认位；两个＝两个都在抢注意力；
 * 4. 否定位在左（第一个按钮必须是 NEGATIVE——「取消/拒绝在左侧远离误触路径」§4）；
 *    例外是单按钮卡（指路类），不强制。
 */
internal object DecisionSpecRules {

    fun validate(spec: DecisionCardSpec): List<String> {
        val problems = mutableListOf<String>()
        if (spec.title.isBlank()) problems += "决策卡标题不能为空（标题＝做什么）"
        if (spec.buttons.isEmpty()) {
            problems += "决策卡至少要有一枚按钮"
        } else {
            val blankIds = spec.buttons.withIndex()
                .filter { it.value.id.isBlank() }
                .map { "第 ${it.index + 1} 个按钮 id 为空" }
            problems += blankIds
            val dupIds = spec.buttons.map { it.id }
                .groupBy { it }
                .filter { it.key.isNotBlank() && it.value.size > 1 }
                .keys
                .map { "按钮 id 重复：$it" }
            problems += dupIds
            val accent = spec.buttons.count { it.role == DecisionButtonRole.PRIMARY || it.role == DecisionButtonRole.DANGER }
            if (accent != 1) problems += "要恰好一个强调按钮（PRIMARY/DANGER），当前 $accent 个"
            if (spec.buttons.size > 1 && spec.buttons.first().role != DecisionButtonRole.NEGATIVE) {
                problems += "多按钮卡的第一个必须是取消/拒绝（否定位在左）"
            }
        }
        return problems
    }

    fun isValid(spec: DecisionCardSpec): Boolean = validate(spec).isEmpty()
}

/**
 * 按钮行排序（**纯函数**）：否定位在左、缓冲居中、确认位（PRIMARY/DANGER）在右。
 *
 * 为什么需要它：授权/提案的按钮来自引擎选项表（[com.example.zhengdao.baopu.agent.AskUserRequest.options]，
 * 那里推荐项排第一），直接渲染会变成「确认位在最左」——既违 §4 拇指区口径，
 * 也让 [DecisionSpecRules.validate] 的「第一个必须是否定位」必红。
 * 稳定排序（同角色保持传入序）。
 */
internal fun orderedButtons(buttons: List<DecisionButton>): List<DecisionButton> {
    val order = mapOf(
        DecisionButtonRole.NEGATIVE to 0,
        DecisionButtonRole.BUFFER to 1,
        DecisionButtonRole.PRIMARY to 2,
        DecisionButtonRole.DANGER to 3,
    )
    return buttons.sortedBy { order[it.role] ?: 99 }
}

// ─────────────────────────────────────────────────────────────────────────────
// 三、建议卡（99）的内容换算（风险徽标 / 证据 chips / 采纳闸门）
// ─────────────────────────────────────────────────────────────────────────────

/** 风险等级 → 徽标文案键（设计稿 §2 四变体表：建议卡徽标＝风险 高/中/低）。 */
internal fun riskBadgeRes(risk: EvolutionRisk): Int = when (risk) {
    EvolutionRisk.HIGH -> R.string.decision_badge_high
    EvolutionRisk.MEDIUM -> R.string.decision_badge_medium
    EvolutionRisk.LOW -> R.string.decision_badge_low
}

/** 风险等级 → 徽标色调（建议卡徽标色随风险变；中风险＝thirdiary 系）。 */
internal fun riskBadgeTone(risk: EvolutionRisk): DecisionBadgeTone = when (risk) {
    EvolutionRisk.HIGH -> DecisionBadgeTone.DANGER
    EvolutionRisk.MEDIUM -> DecisionBadgeTone.WARNING
    EvolutionRisk.LOW -> DecisionBadgeTone.NEUTRAL
}

/**
 * 决策口给的建议 → 卡上那句「我们的判断」（设计稿 W4：建议（采纳/搁置/拒绝））。
 *
 * 注意它是**建议**不是动作：卡上永远三钮齐全，用户可以不按建议来。
 */
internal fun recommendationRes(r: EvolutionRecommendation): Int = when (r) {
    EvolutionRecommendation.ADOPT -> R.string.decision_rec_adopt
    EvolutionRecommendation.REJECT -> R.string.decision_rec_reject
    EvolutionRecommendation.DEFER -> R.string.decision_rec_defer
}

/**
 * 原因码 → 证据 chip 文案键（设计稿 W4「证据 chips：用了 N 次、失败/被纠正 N 次、
 * eval 是否过线」）。
 *
 * 只映射**用户看得懂**的那几个码；纯流程码（CANDIDATE_EVIDENCE 等）返 null——
 * chip 是给用户一眼扫的，不是审计清单（审计在 `决策审计.jsonl`）。
 */
internal fun reasonChipRes(code: EvolutionReasonCode): Int? = when (code) {
    EvolutionReasonCode.SELF_CHECK_PASSED -> R.string.decision_chip_self_check_passed
    EvolutionReasonCode.SELF_CHECK_FAILED -> R.string.decision_chip_self_check_failed
    EvolutionReasonCode.SELF_CHECK_NOT_RUN -> R.string.decision_chip_self_check_not_run
    EvolutionReasonCode.EVAL_PASSED -> R.string.decision_chip_eval_passed
    EvolutionReasonCode.EVAL_BELOW_FLOOR -> R.string.decision_chip_eval_below
    EvolutionReasonCode.EVAL_NOT_RUN -> R.string.decision_chip_eval_not_run
    EvolutionReasonCode.EFFECT_POSITIVE -> R.string.decision_chip_effect_positive
    EvolutionReasonCode.EFFECT_NEGATIVE -> R.string.decision_chip_effect_negative
    EvolutionReasonCode.EFFECT_INSUFFICIENT -> R.string.decision_chip_effect_insufficient
    EvolutionReasonCode.ROLLBACK_AVAILABLE -> R.string.decision_chip_rollback_available
    EvolutionReasonCode.ROLLBACK_UNAVAILABLE -> R.string.decision_chip_rollback_unavailable
    else -> null
}

/**
 * 高风险采纳要不要走二次确认（设计稿 §2 四变体表「高风险采纳须二次确认＋完整 diff」；
 * 验收 #8：无确认不可采纳）。
 */
internal fun adoptNeedsConfirm(risk: EvolutionRisk): Boolean = risk == EvolutionRisk.HIGH

/**
 * 这条建议能不能「采纳」（99 批报告 §6 未做项逐条对账）：
 * - SKILL_BODY：走现有落盘通道（`SkillStore.plan/apply` 备份＋自检）⇒ 可采纳；
 * - PERSONA_APPEND：走现有落盘通道（`PersonaFiles.applyProposal` 自动备份）⇒ 可采纳；
 * - TOOL_RULE：**没有现成的工具约束落盘通道**，99 批明记录「不新开写盘路径」
 *   ⇒ 置灰（按钮带原因），等后续单设计落点。
 */
internal fun suggestionAdoptEnabled(kind: ProposalKind): Boolean = when (kind) {
    ProposalKind.SKILL_BODY, ProposalKind.PERSONA_APPEND -> true
    ProposalKind.TOOL_RULE -> false
}

// ─────────────────────────────────────────────────────────────────────────────
// 四、决策中心（四页签）
// ─────────────────────────────────────────────────────────────────────────────

/** 决策中心四页签（设计稿 §0 决策 #5）。声明序＝页签序。 */
internal enum class DecisionTab { TO_AUTHORIZE, SUGGESTIONS, PROPOSALS, HISTORY }

/**
 * 决策中心里的一行（四页签共用；便览即可，动作仍回控制器）。
 *
 * @param typeLabel 类型签（「建议」页签里区分 99 建议 / 100 效果；其余页签 null）
 * @param undoable 能不能在这行上撤销（仅记录页的授权行）
 */
internal data class DecisionCenterRow(
    val id: String,
    val variant: DecisionVariant,
    val title: String,
    val subtitle: String = "",
    val badge: String? = null,
    val typeLabel: String? = null,
    val undoable: Boolean = false,
)

/**
 * 「建议」页签的排序（设计稿 W6b／验收 #23：效果行排前，类型签区分，不另开页签）。
 *
 * 稳定分区：效果（POST_ADOPTION）全部排在建议（SUGGGESTION）前面，组内保持传入序。
 */
internal fun suggestionTabRows(rows: List<DecisionCenterRow>): List<DecisionCenterRow> =
    rows.sortedBy { if (it.variant == DecisionVariant.POST_ADOPTION) 0 else 1 }

/**
 * 顶栏徽标该显示几（0 = 不显示徽标，顶栏全净——设计稿 §0 信息层级⑤）。
 */
internal fun badgeVisible(count: Int): Boolean = count > 0
