// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百零六批 · 调解器的**决策级**三权：`shouldAsk`（弹卡前）／`budgetFor`（耐心阈值）／
// `decide`（只给排序建议）。
//
// 为什么需要这一层（派工单 406 原话口径）：238 的调解器有**档位**与**回灌**（guidance/reframe），
// 但左相弹卡那一刻它没有发言权——用户已经说「不想弄了」、档位已经 LOOP，问卡照样一张张弹出来。
// 本文件把调解器的既有状态翻译成三个**决策级**读数，供左相弹卡闸（RunEngine askUser/askGroup）
// 与问题组排序使用。
//
// 铁律（派工单 406，逐字）：
//   · **不许改三态**——右相（`verify/ChancellorGate` 的 PASS/FAIL/UNVERIFIABLE）一个字不动、
//     仍零模型；本文件在 :policy，结构上就 import 不到它（`PolicyBoundaryGateTest` 的白名单）。
//   · 只「**少问／排序／让步**」——本文件三个函数的权力边界：
//       shouldAsk 只能把**非必问**的问卡压掉（少问），必问档一律放行（让步给法条）；
//       budgetFor 只给**耐心阈值**读数（让步：用户松口、档位回落，预算自动回满——不记仇）；
//       decide 只回**排序建议**（一个下标排列），采纳与否是调用方的事，它不丢卡、不改卡、不答卡。
//   · 调解器**无权让模型多问**：shouldAsk 没有 "必须问" 的出口——必问是左相与法条的事，
//     调解器至多放行（`allowed=true`），从不制造问卡。
//
// 内容不可见（238 的红线在这里兑现成结构）：三个函数**不收任何用户原话**——输入只有
// 档位状态、画像阈值、计数与**问题形状**（kind 映射出的档感重量），结构上想看内容也看不了。
// 输出的 reason/line 一律是类别句，不带原文。
//
// 零 Android / 零 IO / 零网络 / 零时钟：`now` 由调用方传入（与 238 同一口径，缺省 0）。
package com.example.zhengdao.baopu

/** 调解器的决策级判据（纯函数：状态、画像、计数都由调用方给）。 */
object MediatorDecide {

    // ── ② budgetFor：耐心阈值（让步）────────────────────────────────────────

    /** 一次耐心阈值的读数。 */
    data class AskBudget(
        /** 本回合还允许的问卡数上限（画像 askTolerance 按档位收紧后的值）。 */
        val tolerance: Int,
        /** 本回合已经问过几张（调用方的计数；判据不自己数）。 */
        val asked: Int,
        /** 还剩几张：`tolerance - asked`，负数钳回 0。 */
        val remaining: Int,
        /** 依据一句（类别句；落日志/卡面证据区用，不带原文）。 */
        val basis: String,
    ) {
        /** 预算用尽（`remaining == 0`）⇒ 本回合不该再弹非必问卡。 */
        val exhausted: Boolean get() = remaining <= 0
    }

    /**
     * 耐心阈值：画像的 `askTolerance`（样本闸／漂移闸／保守闸都在 [PatienceProfile.effective]
     * 里过完了，这里**不抄第二遍**）按当前档位收紧：
     *
     *   CALM／CAREFUL ⇒ 原值；STRAINED ⇒ −1（钳 ≥0）；LOOP ⇒ 0（熔断本来就不该再问）。
     *
     * **让步的机器形状**：用户说「没事了」（[HarmonyMediator] 里 `isReset` ⇒ 状态整个回
     * CALM）或连成两次成功回落 CALM ⇒ 档位回原位 ⇒ 本函数读到的 tolerance 自动回满——
     * 调解器不记仇，让步是结构性的（跟着档位走），不是一句特判。
     *
     * 口径：计数单位是**一回合**（两次用户输入之间）——问卡是对「这一句」的追问，
     * 跨回合的账归学习器（400）与免打扰（330）管，这里不越界。
     */
    fun budgetFor(
        profile: PatienceProfile,
        state: HarmonyMediator.State,
        askedSoFar: Int,
        now: Long = 0L,
    ): AskBudget {
        val base = profile.effective(now).askTolerance
        val tolerance = when (state.mood) {
            HarmonyMediator.Mood.LOOP -> 0
            HarmonyMediator.Mood.STRAINED -> (base - 1).coerceAtLeast(0)
            else -> base
        }
        val remaining = (tolerance - askedSoFar).coerceAtLeast(0)
        val tighten = when {
            state.mood == HarmonyMediator.Mood.LOOP -> "、LOOP 清零"
            tolerance != base -> "、STRAINED 收一档"
            else -> ""
        }
        return AskBudget(
            tolerance = tolerance,
            asked = askedSoFar,
            remaining = remaining,
            basis = "耐心阈值 $tolerance（画像 askTolerance $base$tighten）—本回合已问 $askedSoFar",
        )
    }

    // ── ① shouldAsk：左相弹卡前（少问）────────────────────────────────────

    /** 一次弹卡咨询的结论。 */
    data class AskAdvice(
        /** true＝照常弹；false＝压（不弹、不进补问队列——少问，不是延后问）。 */
        val allowed: Boolean,
        /** true＝调解器真下了结论（压了）；false＝调解器没参与（行为与接线前一致）。 */
        val effective: Boolean,
        /** 依据一句（类别句；空＝调解器没参与）。 */
        val reason: String,
        /** 这次用掉的耐心阈值读数（对拍/日志用）。 */
        val budget: AskBudget,
    ) {
        /** 落日志的那一行（压下时才有内容；调用方 `if (!allowed) log(advice.line())`）。 */
        fun line(): String = if (allowed) "" else "调解器压卡｜$reason｜${budget.basis}"
    }

    /**
     * 左相弹卡前问调解器：这张（非确认类的）问卡此刻该不该弹。
     *
     * 判定顺序（先让步、再少问）：
     *   1. [mustAsk]（法条必问／用户点名「④告诉我该怎么做」）⇒ **放行**——调解器无权过问，
     *      造 LOOP 再重也压不掉必问（这一格就是铁律的负例锚）；
     *   2. [HarmonyMediator.State.paused] ⇒ 压——暂停的契约是「不再重试，只给存档与下次继续」，
     *      再弹问卡就是违约；
     *   3. [HarmonyMediator.Mood.LOOP] ⇒ 压——熔断的契约是「只给换件事／存档两条路」，
     *      新问卡两条都不是；
     *   4. 预算用尽（[budgetFor] 的 remaining ≤ 0）⇒ 压——一回合追问到耐心阈值了；
     *   5. 其余 ⇒ 放行且 [AskAdvice.effective]=false（CALM/CAREFUL 不改变弹卡行为）。
     *
     * 压＝不弹也不排队（少问）：排队是免打扰（330）的形状——「过会儿再问」；调解器压的
     * 是「这个人现在不想被问」，过会儿（下一回合）预算与档位重新算，模型照常可以再问。
     */
    fun shouldAsk(
        state: HarmonyMediator.State,
        profile: PatienceProfile,
        askedSoFar: Int,
        mustAsk: Boolean = false,
        now: Long = 0L,
    ): AskAdvice {
        val budget = budgetFor(profile, state, askedSoFar, now)
        if (mustAsk) {
            return AskAdvice(true, effective = false, reason = "必问档：调解器无权过问", budget = budget)
        }
        return when {
            state.paused -> AskAdvice(
                false,
                effective = true,
                reason = "用户已暂停：不再追问（契约只给存档与下次继续）",
                budget = budget,
            )
            state.mood == HarmonyMediator.Mood.LOOP -> AskAdvice(
                false,
                effective = true,
                reason = "已熔断：不再弹问卡（只给换件事／存档两条路）",
                budget = budget,
            )
            budget.exhausted -> AskAdvice(
                false,
                effective = true,
                reason = "一回合追问到耐心阈值，这回合不再弹卡",
                budget = budget,
            )
            else -> AskAdvice(true, effective = false, reason = "", budget = budget)
        }
    }

    // ── ③ decide：只给排序建议 ─────────────────────────────────────────────

    /**
     * 一次排序建议：[order] 是**原列表的下标排列**（照它重排即可），空 advice 不改序。
     *
     * 「只给排序建议」的机械含义：产出是一个排列＋一句建议；丢卡／并卡／改卡都不在本权里，
     * 采纳与否归调用方（接线层 adopt 时也只许 `items.map { items[order[it]] }` 这一种用法）。
     */
    data class OrderAdvice(
        /** 建议排列：`order[newIndex] = oldIndex`；与原序一致 ⇒ 照旧。 */
        val order: List<Int>,
        /** true＝建议的序与原序不同（只有这时谈「因调解而变」）。 */
        val changed: Boolean,
        /** 建议一句（类别句；照旧 ⇒ 空串）。 */
        val line: String,
    ) {
        /** 恒等排列（照旧）。 */
        companion object {
            fun keep(size: Int): OrderAdvice = OrderAdvice(List(size) { it }, changed = false, line = "")
        }
    }

    /**
     * 一组问卡的排序建议：**省力的在前**（档感重的靠后）。
     *
     * 输入 [weights] 是调用方按**问题形状**给的档感重量（0=选项/是否这种点一下的，
     * 1=数字，2=要打字的自由文本）——判据只看形状，不看内容（内容不可见的结构保证）。
     *
     * 什么时候真的会变序：
     *   · [HarmonyMediator.Mood.CAREFUL]／[HarmonyMediator.Mood.STRAINED]（用户已经嫌烦，
     *     guidance 原话是「回答更短」／「只说不超过三句」）⇒ 稳定升序（同重保持原序，
     *     最小干预）；已有一个重问题排在轻问题前面时才动，否则照旧。
     *   · CALM ⇒ 照旧（模型自己排的序在松弛档位下不动它）；
     *   · LOOP／暂停 ⇒ 照旧——那种局面归 [shouldAsk] 压卡，排序已经没有意义。
     */
    fun decide(weights: List<Int>, state: HarmonyMediator.State): OrderAdvice {
        if (weights.size <= 1) return OrderAdvice.keep(weights.size)
        val reorder = when (state.mood) {
            HarmonyMediator.Mood.CAREFUL, HarmonyMediator.Mood.STRAINED -> true
            else -> false
        }
        if (!reorder) return OrderAdvice.keep(weights.size)
        val order = weights.indices.sortedBy { weights[it] } // sortedBy 稳定：同重保持原序
        if (order == weights.indices.toList()) return OrderAdvice.keep(weights.size)
        return OrderAdvice(
            order = order,
            changed = true,
            line = "调解器建议：省力的先问（第 ${order.first() + 1} 问提前），要打字的靠后",
        )
    }
}
