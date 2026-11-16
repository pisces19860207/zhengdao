// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百五十四批 · 模型提交口的两件工具（624 阶段二挂账的那道口）。
//
//   · learn_draft_submit —— 模型把自己归纳的技能草案**交给结构门**（goal/params/skeleton/
//     variableSteps/evidence＋轨迹引用）。过门 ⇒ 进草稿区（不转正）；不过 ⇒ 拒收，原因分四类
//     （缺定位／缺证据／结构不合法／含不可逆动作）。
//   · learn_trace_summary —— 把最近一次教学轨迹压成 ≤300 字（步骤序号＋动作＋定位种类），
//     给模型当归纳的原料；只给「做了什么」，不给页面正文，敏感字段整条丢弃。
//
// 三条纪律（派工单）：
//   ① **不直接写盘**：本文件里没有一处文件写（唯一写口是 SkillDraftStore 的草稿区）；
//   ② **不转正**：转正仍走人批（ObserveCenter.confirmDraft → 643 候选链，与 643 一致）；
//   ③ **不另立判据**：两条归纳路（脚本侧 DraftInducer／模型侧 LearnSubmit）都过同一个
//      SkillDraftRules.validate——本文件只负责把话说清、把结果说人话。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.learn.LearnSubmit
import com.example.zhengdao.baopu.learn.ObserveCenter

/** 学习板块的两件工具（表在 [BaopuToolCatalog]，组归 SKILL：与 write_skill 同类「把学到的东西写下来」）。 */
internal object LearnTools {

    /** 拿不到观察中枢时的统一话术。 */
    const val NO_TRACE = "还没有观察到的教学轨迹：先在玄览里操作几步（学习板块开着观察），再让我归纳。"

    const val ARG_DRAFT = "draft"

    // ── ① 提交草案（交给结构门，不写盘、不转正）────────────────────────────────

    fun draftSubmitSpec(): ToolSpec = ToolSpec(
        name = "learn_draft_submit",
        description = "把你归纳的技能草案交给结构门。草案形状 zl-draft/1：" +
            "goal（这次要办成什么）、params（[{name,from}]）、" +
            "skeleton（步骤数组：i 步号／intent 意图／a 动作 click|type|select／url／" +
            "el 定位面（tag/role/name/aria/testid/id/inputName/placeholder/formLabel/xp）／" +
            "param 参数步名／opt 是否偶发／cands 冲突候选／expect 校验点／ev 证据数组）、" +
            "variableSteps（参数步号）、sessions（轨迹引用：最近一次观察会话 id，" +
            "如「观察/会话-<id>.jsonl」）。" +
            "过门 ⇒ 进草稿区等你自己确认（**不转正**）；不过 ⇒ 拒收并说清欠哪一样：" +
            "缺定位／缺证据／结构不合法／含不可逆动作。提交本身不写盘、不进技能库。" +
            "归纳前先用 learn_trace_summary 看轨迹，证据要指回真跑过的那一段。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_DRAFT to jsonObjectOf(
                    "type" to "object",
                    "description" to "草案本体（zl-draft/1）：goal／params／skeleton／variableSteps／sessions",
                ),
            ),
            "required" to jsonArrayOf(ARG_DRAFT),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE, // 只进草稿区（人待办），不转正、不进技能库；每次一张卡会把归纳变成打扰
        riskTier = "R1",
        readOnly = false,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args ->
        val draft = args.optJSONObject(ARG_DRAFT)
            ?: return@ToolSpec ToolOutcome.Rejected(
                "${LearnSubmit.REASON_SHAPE}：draft 必须是对象（goal／params／skeleton／variableSteps／sessions）",
            )
        when (val out = ObserveCenter.submitDraft(draft)) {
            is LearnSubmit.Outcome.Accepted -> ToolOutcome.Ok(acceptedText(out))
            is LearnSubmit.Outcome.Rejected -> ToolOutcome.Rejected(out.reasons.joinToString("\n"))
        }
    }

    /** 收下时的人话（说清三件事：进了哪儿、没转正、怎么转正）。 */
    private fun acceptedText(out: LearnSubmit.Outcome.Accepted): String {
        val merged = if (out.merged) "，与同意图草案合并成一条（第 ${out.withN} 次观察）" else ""
        return "已收进草稿区（**没有转正**）：${out.draft.goal}｜${out.draft.skeleton.size} 步｜" +
            "参数 ${out.draft.params.size} 个$merged。\n" +
            "要我把它练成技能，得你自己在治理中心「学习板块」里确认——我不自己决定转正。"
    }

    // ── ② 轨迹摘要（只读；≤300 字；敏感整条丢弃）──────────────────────────────

    fun traceSummarySpec(): ToolSpec = ToolSpec(
        name = "learn_trace_summary",
        description = "看最近一次教学轨迹的摘要（一次观察会话压成 300 字以内）：" +
            "只有步骤序号、动作（点击/输入/选择）和定位种类（testid/role/name/xp…），" +
            "**没有页面正文、没有填过的内容**；密码/验证码这类敏感操作整条不出现。" +
            "归纳草案前先看它，schema 见 learn_draft_submit；证据要指回这段轨迹。" +
            "只读，不改任何东西。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(),
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        riskTier = "R0",
        readOnly = true,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { _ ->
        val summary = ObserveCenter.traceSummary()
        if (summary.isBlank()) ToolOutcome.Failed(NO_TRACE) else ToolOutcome.Ok(summary)
    }
}
