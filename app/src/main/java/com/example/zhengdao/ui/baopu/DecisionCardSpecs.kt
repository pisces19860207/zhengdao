// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百零四批：决策卡四变体的**规格工厂**（从 BaopuChatController 抽出）。
//
// 为什么抽：派工单 §2 要求「四变体回填/按钮/状态各至少一例」——规格构建若留在
// 控制器里就要连 Context／DB 一起测（本仓无 Robolectric，测不动）。抽成
// 「纯映射＋文案解析器注入」后，JVM 直测（见 DecisionCardSpecsTest）。
//
// 纪律：本文件只做「已有数据 → 规格」的映射，**不读盘、不碰引擎、不判优先级**；
// 字符串 id 全部走 R（中英双份在 strings.xml），文案内容不在这里发明。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.R
// EvolutionSuggestion 与本文件同包（ui.baopu，声明在 BaopuChatController.kt）⇒ 不 import
import com.example.zhengdao.baopu.PostAdoptionNotice
import com.example.zhengdao.baopu.PostAdoptionVerdict
import com.example.zhengdao.baopu.ProposalKind
import com.example.zhengdao.baopu.agent.AskKind
import com.example.zhengdao.baopu.agent.AskUserRequest
import com.example.zhengdao.baopu.agent.GroupGrantScope
import com.example.zhengdao.baopu.agent.PersonaApprovalCard
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.eval.EvalEvidenceSource
import com.example.zhengdao.util.TimeFormats

/** 文案解析器（生产传 `appCtx.getString`；测试传假实现或读 strings.xml）。 */
internal fun interface DecisionStrRes {
    fun get(id: Int, vararg args: Any): String
}

/** 四变体规格工厂（无状态；同入参同出参）。 */
internal object DecisionSpecs {

    // ── 授权卡（97） ────────────────────────────────────────────────────────

    fun authorization(req: AskUserRequest, s: DecisionStrRes): DecisionCardSpec {
        val group = ToolGroup.fromKey(req.subject)
        val title = if (group != null) {
            s.get(R.string.decision_authorize_title, s.get(baopuGroupTitle(group)))
        } else {
            req.question
        }
        val subtitle = group?.let { s.get(baopuGroupLine(it)) }
            ?: s.get(R.string.decision_authorize_subtitle)
        val buttons = orderedButtons(
            req.options.map { opt ->
                when (opt.id) {
                    GroupGrantScope.SESSION.key ->
                        DecisionButton(opt.id, s.get(R.string.decision_authorize_session), DecisionButtonRole.PRIMARY)

                    GroupGrantScope.ALWAYS.key ->
                        DecisionButton(opt.id, s.get(R.string.decision_authorize_always), DecisionButtonRole.BUFFER)

                    else ->
                        DecisionButton(opt.id, s.get(R.string.decision_authorize_cancel), DecisionButtonRole.NEGATIVE)
                }
            },
        )
        return DecisionCardSpec(
            variant = DecisionVariant.AUTHORIZATION,
            key = "auth:${req.subject}",
            title = title,
            subtitle = subtitle,
            warning = if (group == ToolGroup.SKILL) {
                s.get(R.string.decision_authorize_warning_skill)
            } else {
                null
            },
            buttons = buttons,
            footer = s.get(R.string.decision_authorize_once_note),
        )
    }

    // ── 提案卡（96） ────────────────────────────────────────────────────────

    fun persona(req: AskUserRequest, s: DecisionStrRes): DecisionCardSpec {
        val scopeRes = if (req.subject == "B") R.string.decision_scope_global else R.string.decision_scope_workspace
        val buttons = orderedButtons(
            req.options.map { opt ->
                when (opt.id) {
                    PersonaApprovalCard.ID_WORKSPACE ->
                        DecisionButton(opt.id, s.get(R.string.decision_persona_workspace), DecisionButtonRole.PRIMARY)

                    PersonaApprovalCard.ID_GLOBAL ->
                        DecisionButton(opt.id, s.get(R.string.decision_persona_global), DecisionButtonRole.BUFFER)

                    else ->
                        DecisionButton(opt.id, s.get(R.string.decision_persona_cancel), DecisionButtonRole.NEGATIVE)
                }
            },
        )
        return DecisionCardSpec(
            variant = DecisionVariant.PERSONA,
            key = "persona:${req.subject}",
            title = s.get(R.string.decision_persona_title),
            subtitle = s.get(scopeRes) + " · " + s.get(R.string.decision_persona_subtitle),
            body = req.detailText.ifBlank { req.question },
            buttons = buttons,
        )
    }

    // ── 建议卡（99） ────────────────────────────────────────────────────────

    fun suggestion(sug: EvolutionSuggestion, s: DecisionStrRes): DecisionCardSpec {
        val adoptEnabled = suggestionAdoptEnabled(sug.kind)
        return DecisionCardSpec(
            variant = DecisionVariant.SUGGESTION,
            key = "suggestion:${sug.candidateId}",
            title = suggestionTitle(sug, s),
            subtitle = s.get(
                R.string.decision_suggest_judge,
                s.get(recommendationRes(sug.recommendation)),
            ),
            badge = s.get(riskBadgeRes(sug.riskClass)),
            badgeTone = riskBadgeTone(sug.riskClass),
            warning = if (sug.riskClass == com.example.zhengdao.baopu.EvolutionRisk.HIGH) {
                s.get(R.string.decision_suggest_warning_high)
            } else {
                null
            },
            body = sug.payload,
            chips = sug.reasons.mapNotNull { reasonChipRes(it.code) }.distinct()
                .map { s.get(it) },
            // 第一百六十八批：证据来源与日期（派工单 §2「UI 必须标个人生产证据/发版证据」）。
            footer = evidenceFooter(sug, s),
            buttons = orderedButtons(
                buildList {
                    add(DecisionButton("reject", s.get(R.string.decision_suggest_reject), DecisionButtonRole.NEGATIVE))
                    add(DecisionButton("defer", s.get(R.string.decision_suggest_defer), DecisionButtonRole.BUFFER))
                    add(
                        DecisionButton(
                            id = "adopt",
                            label = s.get(R.string.decision_suggest_adopt),
                            role = DecisionButtonRole.PRIMARY,
                            enabled = adoptEnabled,
                            note = if (adoptEnabled) null else s.get(R.string.decision_suggest_adopt_blocked_tool_rule),
                        ),
                    )
                },
            ),
        )
    }

    /** 建议卡标题（按候选类型；决策中心列表行复用同一个函数，不写第二遍）。 */
    fun suggestionTitle(sug: EvolutionSuggestion, s: DecisionStrRes): String = when (sug.kind) {
        ProposalKind.SKILL_BODY -> s.get(R.string.decision_suggest_title_skill, sug.target)
        ProposalKind.PERSONA_APPEND -> s.get(R.string.decision_suggest_title_persona)
        ProposalKind.TOOL_RULE -> s.get(R.string.decision_suggest_title_tool, sug.target)
    }

    /**
     * 第一百六十八批：证据来源行（派工单 §2「UI 必须标个人生产证据/发版证据」）。
     *
     * 只回答「这次判定看的是谁产的证据、什么时候产的」：没有证据时明说没有
     * （缺/过期 ⇒ DEFER 是决策口的结论，那句话由原因链说，这一行不越位）。
     * 日期走 [TimeFormats]（B-补-24：主源码不许裸 SimpleDateFormat）。
     */
    private fun evidenceFooter(sug: EvolutionSuggestion, s: DecisionStrRes): String {
        val src = sug.evalSource ?: return s.get(R.string.decision_suggest_evidence_none)
        val label = s.get(
            if (src == EvalEvidenceSource.PERSONAL_PROD) {
                R.string.decision_evidence_personal
            } else {
                R.string.decision_evidence_release
            },
        )
        val date = sug.evalRanAt?.let { TimeFormats.minuteStamp(it) }
            ?: s.get(R.string.decision_evidence_date_unknown)
        return s.get(R.string.decision_suggest_evidence, label, date)
    }

    // ── 效果卡（100） ───────────────────────────────────────────────────────

    fun effect(n: PostAdoptionNotice, s: DecisionStrRes): DecisionCardSpec {
        val retire = n.verdict == PostAdoptionVerdict.RETIRE
        val verdictText = s.get(
            if (retire) R.string.post_adopt_verdict_retire else R.string.post_adopt_verdict_rollback,
        )
        val body = s.get(
            R.string.post_adopt_counts,
            n.before.successes, n.before.failures, n.before.corrections,
            n.delta.successes, n.delta.failures, n.delta.corrections,
        ) + "\n" + verdictText
        val buttons = orderedButtons(
            buildList {
                add(DecisionButton("keep", s.get(R.string.post_adopt_keep), DecisionButtonRole.NEGATIVE))
                add(DecisionButton("later", s.get(R.string.post_adopt_later), DecisionButtonRole.BUFFER))
                if (retire) {
                    // 没有备份可退 ⇒ 主按钮只指路设置页，不新造删除通道
                    add(DecisionButton("tosettings", s.get(R.string.decision_effect_to_settings), DecisionButtonRole.PRIMARY))
                } else {
                    add(DecisionButton("rollback", s.get(R.string.post_adopt_rollback), DecisionButtonRole.DANGER))
                }
            },
        )
        return DecisionCardSpec(
            variant = DecisionVariant.POST_ADOPTION,
            key = "effect:${n.eventId}",
            title = s.get(R.string.post_adopt_title, n.skillName),
            badge = s.get(R.string.decision_badge_effect),
            badgeTone = DecisionBadgeTone.DANGER,
            body = body,
            chips = if (n.backupIds.isNotEmpty()) {
                listOf(s.get(R.string.decision_chip_rollback_available))
            } else {
                emptyList()
            },
            buttons = buttons,
            footer = s.get(R.string.decision_effect_once_note),
        )
    }
}

/**
 * 待办条目的纯投影（从控制器的可观察状态 → [DecisionItem] 列表）。
 *
 * 抽出来为了两件事：① 槽位判据（[decisionSlotCurrent]）只吃这个列表，接线层
 * 换成别的状态源也不用改判据；② JVM 能直测「哪类事进槽、哪类不进」——
 * **普通 `ask_user` 提问不进决策槽**（那是对话不是决策；KEEP/DEFER 同理：
 * 后端从不把它们变成待办条目）。
 */
internal fun decisionPendingItems(
    choiceKind: AskKind?,
    choiceSubject: String,
    effectIds: List<String>,
    suggestionIds: List<String>,
): List<DecisionItem> = buildList {
    when (choiceKind) {
        AskKind.GROUP_AUTHORIZATION -> add(DecisionItem(DecisionVariant.AUTHORIZATION, "auth:$choiceSubject"))
        AskKind.PERSONA_PROPOSAL -> add(DecisionItem(DecisionVariant.PERSONA, "persona:$choiceSubject"))
        AskKind.QUESTION, null -> Unit
    }
    effectIds.forEach { add(DecisionItem(DecisionVariant.POST_ADOPTION, "effect:$it")) }
    suggestionIds.forEach { add(DecisionItem(DecisionVariant.SUGGESTION, "suggestion:$it")) }
}
