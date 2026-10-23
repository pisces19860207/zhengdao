// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第一百四十批 §范围（软档执行约束）＋ 设计 `抱朴设置重构方案-2026-10-22.md`
// §5「执行约束三档（已拍板：软档＋确认卡）」。
//
// 软档的两条判据都放在这个**零 Android / 零 IO / 零时钟**的文件里（JVM 直测）：
// 1. **计划有变必须过卡**：`PlanChangeRules.classify` 只认文字序列——同一批文字只改
//    status（todo/doing/done）＝进度更新，不需要卡；文字增删＝结构变更，必须过卡。
//    只换顺序不算变更（避免模型重排一下就被迫打扰用户）。
// 2. **连续 3 步未推进且未更新 ⇒ 弹御批卡**：计数在 [PlanController]（它知道当前计划），
//    本文件只给阈值与三选一的映射。
//
// 不绑死一步一勾：所有判据只**提示**，不拒绝工具调用、不终止本轮；用户选「继续」
// 后本段偏离不再弹卡，直到计划真的被更新/推进或新开一轮。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanRules.PlanItem

/** 连续几步工具跑完还没推进计划 ⇒ 弹偏离卡（派工单：3 步）。 */
internal const val PLAN_STALE_STEPS = 3

/** 一次 update_plan 相对上一版计划是什么性质。 */
internal enum class PlanChangeKind {
    /** 本会话第一份计划（上一版为空）——不是「变更」，不弹卡。 */
    NEW,

    /** 文字一条没动（只改 status，或只换顺序）——进度更新，不弹卡。 */
    PROGRESS,

    /** 文字有增删——计划结构变了，必须过卡。 */
    STRUCTURE,
}

/**
 * 一次 update_plan 的变更描述（纯数据；卡面文案与拒绝回灌都用它）。
 *
 * @param added 新增的条目原文（按新计划顺序）
 * @param removed 删去的条目原文（按旧计划顺序）
 */
internal data class PlanChange(
    val kind: PlanChangeKind,
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
) {
    /** 要不要过卡：只有文字增删才算「悄悄改计划」。 */
    val needsCard: Boolean get() = kind == PlanChangeKind.STRUCTURE
}

/** 计划结构变更的纯判据（零依赖，JVM 直测）。 */
internal object PlanChangeRules {

    /**
     * 比较上一版与这一版计划。
     *
     * 负例靶心（派工单 §测试）：
     * - 把 return 改成「永远 PROGRESS」⇒「计划悄悄改」用例必红（submit 不再拦）；
     * - 把文字比较改成「条数相同就算没变」⇒ 换一条内容也能绕卡，同样会红。
     */
    fun classify(previous: List<PlanItem>, incoming: List<PlanItem>): PlanChange {
        if (previous.isEmpty()) return PlanChange(PlanChangeKind.NEW)
        val oldTexts = previous.map { it.text }
        val newTexts = incoming.map { it.text }
        if (oldTexts == newTexts) return PlanChange(PlanChangeKind.PROGRESS)
        // 只换顺序 / 重复不变：不算结构变更（status 的 sticky done 由 PlanRules 管）
        if (oldTexts.sorted() == newTexts.sorted()) return PlanChange(PlanChangeKind.PROGRESS)
        val oldSet = oldTexts.toSet()
        val newSet = newTexts.toSet()
        return PlanChange(
            kind = PlanChangeKind.STRUCTURE,
            added = newTexts.filter { it !in oldSet }.distinct(),
            removed = oldTexts.filter { it !in newSet }.distinct(),
        )
    }
}

/** 偏离卡用户点完后，引擎怎么走。 */
internal enum class PlanDeviationAction {
    /** 回计划：往模型历史里塞一条「回到计划」的提示，下一步按原计划推进。 */
    BACK_TO_PLAN,

    /** 更新计划：批准下一次结构变更，并提示模型整表重发新计划。 */
    UPDATE_PLAN,

    /** 继续：不打断、不批准任何改动；本段偏离不再弹卡。 */
    CONTINUE,
}

/**
 * 软档的纯判据与文案（全部 JVM 直测）。
 *
 * 选项 id 是稳定契约：UI 只回传 id，引擎只按 id 判定；改文案不动 id。
 */
internal object PlanGuardRules {

    const val OPT_BACK = "plan-deviation-back"
    const val OPT_UPDATE = "plan-deviation-update"
    const val OPT_CONTINUE = "plan-deviation-continue"
    const val OPT_CHANGE_ACCEPT = "plan-change-accept"
    const val OPT_CHANGE_REJECT = "plan-change-reject"

    /** 连续多少步没推进 ⇒ 弹卡（负例：改成 1 ⇒ 一跑就弹，硬绑一步一勾的变体）。 */
    fun shouldPromptDeviation(stepsWithoutPlanUpdate: Int): Boolean =
        stepsWithoutPlanUpdate >= PLAN_STALE_STEPS

    /** 用户同意计划变更（只有明确点接受才算；超时/拒绝都按不同意）。 */
    fun changeAccepted(pickedId: String?): Boolean = pickedId == OPT_CHANGE_ACCEPT

    /** 偏离卡三选一；没点/超时 ⇒ 继续（不阻断，也不偷偷批准改动）。 */
    fun deviationAction(pickedId: String?): PlanDeviationAction = when (pickedId) {
        OPT_BACK -> PlanDeviationAction.BACK_TO_PLAN
        OPT_UPDATE -> PlanDeviationAction.UPDATE_PLAN
        else -> PlanDeviationAction.CONTINUE
    }

    /** 一条计划的短摘要（卡面/回灌共用；最多 [max] 条，超出加省略号）。 */
    fun brief(items: List<PlanItem>, max: Int = 2): String {
        val names = items.take(max).joinToString("；") { it.text }
        return if (items.size > max) "$names…" else names
    }

    /**
     * 未经确认的结构变更拒绝文案（回灌给模型）。
     *
     * 必须说清三件事：没生效、原计划不变、下一步怎么走——只说「被拒」模型会换个写法再试。
     */
    fun changeBlocked(change: PlanChange): String = buildString {
        append("计划改动**没有生效**：用户没有同意这次改动（")
        append(describeChange(change))
        append("）。原计划保持不变。不要换一种写法绕过确认；")
        append("要么按原计划继续，要么先在正文里说明为什么要改、等用户明确同意后再调 update_plan。")
    }

    /** 变更的人话摘要（卡面与回灌共用）。 */
    fun describeChange(change: PlanChange): String {
        val parts = buildList {
            if (change.added.isNotEmpty()) add("新增「${brief(change.added.map { PlanItem(it, PlanRules.STATUS_TODO) })}」")
            if (change.removed.isNotEmpty()) add("删去「${brief(change.removed.map { PlanItem(it, PlanRules.STATUS_TODO) })}」")
        }
        return if (parts.isEmpty()) "计划文字有调整" else parts.joinToString("；")
    }

    /**
     * 偏离卡选完之后塞回模型历史的提示；null ⇒ 什么都不塞（选「继续」＝不阻断）。
     *
     * 只进内存历史（下一步看得到），不落库、不进正文——这不是「回答」，是引擎的提醒。
     */
    fun deviationFeed(action: PlanDeviationAction, items: List<PlanItem>): String? {
        val remaining = items.filter { it.status != PlanRules.STATUS_DONE }
        return when (action) {
            PlanDeviationAction.BACK_TO_PLAN -> {
                val next = remaining.firstOrNull()?.text ?: "计划里还没勾的条目"
                "【回到计划】用户要你回到当前计划：下一步先按「$next」推进，完成后立刻用 " +
                    "update_plan 打勾；不要新增计划外步骤。计划里还有 ${remaining.size} 条没勾。" +
                    "这不要求一步一勾，但每一步都要能对回计划里的某一条。"
            }

            PlanDeviationAction.UPDATE_PLAN ->
                "【更新计划】用户同意你调整计划：现在调用 update_plan 整表重发新计划（这次改动直接生效）；" +
                    "之后严格按新计划推进，并在完成时打勾。改完之前不要继续跑计划外的步骤。"

            PlanDeviationAction.CONTINUE -> null
        }
    }

    /** 偏离卡的问题（卡面第一行）。 */
    fun deviationQuestion(items: List<PlanItem>, staleSteps: Int): String {
        val remaining = items.filter { it.status != PlanRules.STATUS_DONE }
        return "计划连续 $staleSteps 步没推进，也没更新（还有 ${remaining.size} 条没勾：" +
            "${brief(remaining)}）。要它怎么做？"
    }
}

/** 两张御批卡的组装（纯函数；文案定版在这里，引擎只负责弹与收）。 */
internal object PlanGuardCards {

    /** 连续 3 步偏离卡：回计划 / 更新计划 / 继续。 */
    fun deviationRequest(items: List<PlanItem>, staleSteps: Int = PLAN_STALE_STEPS): AskUserRequest =
        AskUserRequest(
            question = PlanGuardRules.deviationQuestion(items, staleSteps),
            options = listOf(
                AskUserOption(
                    id = PlanGuardRules.OPT_BACK,
                    label = "回计划",
                    detail = "让它回到当前计划：下一步先说明在做哪一条，按计划推进；不额外加步骤",
                    recommended = true,
                ),
                AskUserOption(
                    id = PlanGuardRules.OPT_UPDATE,
                    label = "更新计划",
                    detail = "允许它改计划：下一次 update_plan 直接生效，之后按新计划走",
                ),
                AskUserOption(
                    id = PlanGuardRules.OPT_CONTINUE,
                    label = "继续",
                    detail = "不打断它：按现在的做法跑下去，这次不再提醒",
                ),
            ),
        )

    /** 计划结构变更卡：更新计划 / 不改，按原计划。 */
    fun changeRequest(change: PlanChange): AskUserRequest = AskUserRequest(
        question = "抱朴要调整计划：${PlanGuardRules.describeChange(change)}。改完后续步骤按新计划走，允许吗？",
        options = listOf(
            AskUserOption(
                id = PlanGuardRules.OPT_CHANGE_ACCEPT,
                label = "更新计划",
                detail = "这次改动生效；之后它按新计划推进并打勾",
                recommended = true,
            ),
            AskUserOption(
                id = PlanGuardRules.OPT_CHANGE_REJECT,
                label = "不改，按原计划",
                detail = "计划保持原样；让它回到原计划继续（它不能偷偷改）",
            ),
        ),
    )
}
