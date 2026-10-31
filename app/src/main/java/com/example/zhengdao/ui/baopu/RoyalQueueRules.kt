// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十八批（派工单 §1–§3）：**一条意见 ⇒ 一张能上御案的卡**的整条流水线（纯函数）。
//
// 顺序写死在这里，别处不许打乱：
//   ① 拟稿（中书舍人）→ 给"要核的事实主张"（[RoyalDrafts.claimsOf]）
//   ② ③b 核验闸（暗卫；未合时用桩）→ 假 ⇒ **打回**，不进御案（[DraftGate]）
//   ③ 拟成人话卡面（[RoyalDrafts.draft]），真假标注由核验给
//   ④ 卡面自检（[RoyalCardGate]）→ 技术字段/超 3 行/无默认 ⇒ **拒渲染**
//
// 四步里任何一步不过，[prepare] 返回 null：**坏卡不画、也不静默**——各步都留一行日志，
// 验收靠 grep 那几句（日志格式本身是判据，所以写成纯函数 `reasonLine`）。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.agent.DraftGate
import com.example.zhengdao.baopu.agent.DraftGateResult
import com.example.zhengdao.baopu.agent.Ruling
import com.example.zhengdao.baopu.agent.StubVerifier
import com.example.zhengdao.baopu.agent.ClaimVerifier

/** 御案页签里的一行（一条可上御案的意见）。 */
internal data class RoyalRow(
    val id: String,
    val card: RoyalCard,
    val atMs: Long,
    /**
     * 这条意见的"类"（298 学习分组的键，与 [com.example.zhengdao.baopu.policy.VerdictDraft]
     * 查表用的 `opinion.gist` 同一个字）。**只随行、不上卡**：卡面出现技术字段是 278
     * `RoyalCardGate` 明令拒渲染的，所以它待在行上，决定按钮按下时交给学习器。
     */
    val gist: String,
)

/**
 * 流水线：意见 → 卡（或"不许上御案"）。
 *
 * 无状态：同一个 ruling＋同一份核验结果永远出同一张卡（页签重组不会"变脸"）。
 */
internal object RoyalQueueRules {

    /**
     * @param verifier 核验方（生产＝暗卫；279 未合时传 [StubVerifier]，它一律回"无法判定"）
     * @return 可渲染的卡；null＝打回/拒渲染（**理由见 [reasonLine]**）
     */
    fun prepare(
        ruling: Ruling,
        s: DecisionStrRes,
        verifier: ClaimVerifier = StubVerifier,
    ): RoyalRow? {
        // ② 核验：假 ⇒ 打回（不拟卡、不进御案）
        val gate = DraftGate.check(RoyalDrafts.claimsOf(ruling), verifier)
        if (gate is DraftGateResult.Rejected) return null
        val checks = (gate as DraftGateResult.Passed).checks
        // ③ 拟稿
        val card = RoyalDrafts.draft(ruling, checks, s) ?: return null
        // ④ 卡面自检
        if (!RoyalCardGate.renderable(card)) return null
        return RoyalRow(id = ruling.id, card = card, atMs = ruling.atMs, gist = ruling.opinion.gist)
    }

    /**
     * 这一条为什么没出现在御案上（**人话 + 机器原因都给**，给日志和验收用）。
     *
     * 返回 null ＝ 它其实能上（没问题）。
     */
    fun reasonLine(
        ruling: Ruling,
        s: DecisionStrRes,
        verifier: ClaimVerifier = StubVerifier,
    ): String? {
        val gate = DraftGate.check(RoyalDrafts.claimsOf(ruling), verifier)
        if (gate is DraftGateResult.Rejected) {
            return "抱朴御案：打回重写（${ruling.id}）：${DraftGate.logLine(gate)}"
        }
        val checks = (gate as DraftGateResult.Passed).checks
        val card = RoyalDrafts.draft(ruling, checks, s)
            ?: return "抱朴御案：拟不成（${ruling.id}）：核验含「假」主张"
        if (!RoyalCardGate.renderable(card)) {
            return RoyalCardGate.rejectLine(card)
        }
        return null
    }

    /**
     * 待批的那些（**只列真的在等你的**）：
     * - 批过/驳过的永不回来；今天搁置的明天回来（见 `RoyalDecisionLedger.suppressedIds`）；
     * - 打回/拒渲染的不进列表。
     */
    fun pending(
        rulings: List<Ruling>,
        suppressed: Set<String>,
        s: DecisionStrRes,
        verifier: ClaimVerifier = StubVerifier,
    ): List<RoyalRow> = rulings
        .filter { it.id !in suppressed }
        .mapNotNull { prepare(it, s, verifier) }
        .sortedByDescending { it.atMs }
}
