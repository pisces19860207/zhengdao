// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第一百四十批 §范围（软档执行约束）＋ 设计 `抱朴设置重构方案-2026-10-22.md`
// §5「执行约束三档（已拍板：软档＋确认卡）」。
//
// 软档的三条判据都放在这个**零 Android / 零 IO / 零时钟**的文件里（JVM 直测）：
// 1. **计划有变必须过卡**：`PlanChangeRules.classify` 只认文字序列——同一批文字只改
//    status（todo/doing/done）＝进度更新，不需要卡；文字增删＝结构变更，必须过卡。
//    只换顺序不算变更（避免模型重排一下就被迫打扰用户）。
// 2. **连续 3 步未推进且未更新 ⇒ 弹御批卡**：计数在 [PlanController]（它知道当前计划），
//    本文件只给阈值与三选一的映射。
// 3. **同一份被拒的改动不重复打卡**（161 降噪）：[PlanChangeMemory] 只记**用户真点过的按钮**
//    （批准／拒），判据一律不猜语义——实测「同义改写」的相似度（0.44–0.71）与
//    「换成另一件事」的相似度（0.35–0.44）区间重叠，任何阈值都会放过某次真替换。
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

    /**
     * 这份改动的指纹：同一版计划被原样重发 ⇒ 同一个串（161 决定登记的键）。
     *
     * 条目按「长度＃原文」编码后再拼，所以文字里出现 `/`、`#`、`]` 都不会串号。
     *
     * 2026-10-23 真机补正：原来**逐字比较**，但模型每次重写都会变排版
     * （「1. list files」→「1 list files」），于是同一件事反复弹卡。现在只做
     * **排版归一化**——去掉开头编号/项目符号、折叠空白、去尾标点、忽略大小写；
     * 不碰词义（「删除备份」与「归档备份」仍是两份改动，该问的照问）。
     */
    val signature: String
        get() = "A[" + encode(added) + "]R[" + encode(removed) + "]"

    private fun encode(items: List<String>) = items.joinToString("/") {
        val normalized = normalizeForSignature(it)
        "${normalized.length}#$normalized"
    }

    /** 排版归一化（不改变语义）：编号/项目符号 → 空白折叠 → 去尾标点 → 小写。 */
    private fun normalizeForSignature(raw: String): String {
        var s = raw.trim()
        // 「1.」「1、」「1)」「- 」「* 」「• 」「一、」这类前缀，反复剥（模型会叠两层）
        while (true) {
            val stripped = s.replace(
                Regex("^(?:\\d+\\s*[.、)∶:：]?|[-*•·]\\s*|[一二三四五六七八九十]+\\s*[、.])\\s*"),
                "",
            ).trim()
            if (stripped == s) break
            s = stripped
        }
        s = s.replace(Regex("\\s+"), " ")
            .trimEnd('.', '。', ';', '；', ',', '，', ':', '：', '!', '！', '?', '？')
        return s.lowercase()
    }
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

/** 用户对某一次结构变更表过的态（161：只有真点过的按钮才算数）。 */
internal enum class PlanChangeDecision {
    /**
     * 点过「更新计划」。
     *
     * **它不额外放行任何一次改动**——放行照旧只走 [PlanController.authorizeNextChange]
     * 那一张一次性的授权（用户红线：点一次只放行下一次）。记它只为覆盖此前的拒录：
     * 她改主意批了同一版改动，旧的那次「拒」不该继续挡后面。
     */
    APPROVED,

    /** 点过「不改，按原计划」⇒ 同一份改动本轮重发不再打扰，只回灌模型。 */
    REJECTED,
}

/**
 * 最近若干次「用户已经表过态」的结构变更（161 降噪的核心零件；零 Android / 零 IO / 零时钟）。
 *
 * ## 为什么要它（真机实据）
 * 被拒的改动**不落库**，模型原样再发 ⇒ `classify` 算出同一版 diff ⇒ 同一张卡再弹一次。
 * 2026-10-22 真机一次 6 步任务里「结构变更待确认」连弹 5 次（22:37:50／22:38:14／22:40:34／
 * 22:41:31／22:44:57）：前三次都是「新增 6 条／删去 6 条」的整表重发，最后一次只给第 6 步
 * 补了半句（「…再读回核对」⇒「…再读回核对，报总行数」）；她全程点了 3 次「更新计划」才把这一轮
 * 跑完（报告 `真机验收-2026-10-22-合验.md` UI-D17）。这不是判据判错，是**没记住人的决定**。
 *
 * ## 为什么不做「同义改写自动放行」
 * 见文件头第 3 条：相似度区间重叠 ⇒ 阈值必然放过某次真替换，而那正是 140 摆在
 * `classify` 注释里的负例靶心（「改成条数相同就算没变 ⇒ 换一条内容也能绕卡，同样会红」）。
 *
 * ## 什么不算「表过态」
 * **超时／没点不记**——`changeAccepted` 把超时当不同意，但「不同意」不等于「用户答过」；
 * 记下去等于替用户做决定，下次同一份改动用户就没机会改主意了。
 *
 * ## 和「记忆能力」不是一回事
 * `baopu/Memory.kt`／`MemoryStore.kt` 那条线是**落盘**的情景记忆（候选→待确认→没确认不落盘）。
 * 本类只是进程内的一张最近裁决表：不落盘、不跨进程、不喂模型，换一轮就清。
 *
 * @param maxEntries LRU 上限：留太久的决定会把新改动误认成老决定
 */
internal class PlanChangeMemory(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    private val decided = LinkedHashMap<String, PlanChangeDecision>()

    /** 记下用户的决定；同一份改动再记 ⇒ 覆盖并前移到最新。 */
    @Synchronized
    fun record(change: PlanChange, decision: PlanChangeDecision) {
        decided.remove(change.signature)
        decided[change.signature] = decision
        while (decided.size > maxEntries) decided.remove(decided.keys.first())
    }

    /** 这份改动表过态吗；没见过 ⇒ null（照常弹卡，一次都不许多放）。 */
    @Synchronized
    fun lookup(change: PlanChange): PlanChangeDecision? = decided[change.signature]

    /** 换会话／全新任务时清空：决定属于那一版计划，不该跟到下一场。 */
    @Synchronized
    fun clear() = decided.clear()

    companion object { const val DEFAULT_MAX_ENTRIES = 8 }
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

    /**
     * 同一份改动用户已经明确拒过、模型又原样发过来时的回灌（这一次**不弹卡**）。
     *
     * 三条缺一不可：没生效、为什么这次没再问（用户已经答过）、下一步怎么走。
     * [changeBlocked] 里「不要换一种写法绕过确认」防的是改写，防不住原样重发。
     */
    fun repeatRejected(change: PlanChange): String = buildString {
        append("计划改动**没有生效**：这份改动（")
        append(describeChange(change))
        append("）用户刚才已经明确拒过，这次不再重复弹确认卡。原计划保持不变。")
        append("要么按原计划继续，要么先在正文里说明为什么要改、请用户重新同意之后再调 update_plan；")
        append("原样重发同一版不会有第二次确认机会。")
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
