// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百八十二批 · 前置②（自进化：**只交出去、不收进来**）。
//
// 目标收口时，把「达成/失败 ＋ 判据逐条结果 ＋ 预算用量」按**既有账本的形状**写一行——
// 落点就是 495 已经在读的那本触发账（`filesDir/evolution/trigger-ledger.jsonl`，
// `TriggerLedgerStore.read()` / `TriggerRowCodec.parse` 本批一个字没改）。
//
// 三条约束（用户令，逐条落地）：
//   1. **不新增 kind**：`type` 沿用既有的 [TriggerType.MANUAL]，不新增枚举值；
//   2. **不新增文件格式**：行仍是 [TriggerRow] 的 13 个键，编码仍走 [TriggerRowCodec]；
//   3. **不实现 495 的聚合口径**：本行 `triggered=false` ＋ `evalPassRate=null` ⇒
//      `EvolutionInputs.triggerEvalOf` 只认「真触发且有读数」的行，这一行天然不进它的分数。
//      换句话说：**数据交出去了，怎么用是 495/517 自己的判据**，本批一个字不越界。
//
// 为什么 `triggered=false` 而不是造一条"真触发"：真触发行会吃掉 24h 同类限频额度
// （`EvalTriggerBudget`），把一个目标收口伪装成一次评测触发，等于替 495 花它的预算。
// 被抑制行不消耗预算（那条口径写在 `EvalTriggerBudget` 的文件头），也不会被读成读数。
package com.example.zhengdao.baopu.evolution.trigger

import com.example.zhengdao.baopu.plan.GoalRules
import java.io.File

internal object GoalCloseout {

    /** 这一行的抑制原因：**明说它不是评测触发**（读账的人一眼看得出为什么没被聚合）。 */
    const val NOT_A_TRIGGER: String = "不是评测触发：目标收尾的一行事实"

    /**
     * 事实 ⇒ 既有账行（纯函数：零 IO、零时钟）。
     *
     * 键的用法（都在既有 13 键里，没有新键）：
     * · `ref` ＝ `goal:<目标 id>`；
     * · `intent` ＝ 人话：状态 ＋ 目标句 ＋ 判据几条过几条 ＋ 预算用了几步；
     * · `evalRan/evalPassed/evalTotal` ＝ 验了几条／过了几条／共几条（这里装的是**判据**，不是评测用例；
     *   因为 `evalPassRate` 留空，495 的聚合不会把这几个数当评测读数）；
     * · `evalFailedCases` ＝ 逐条结果，一行一条（`过|判据原文|路径`）；
     * · `suppressReason` ＝ [NOT_A_TRIGGER]。
     */
    fun row(fact: GoalRules.CloseoutFact): TriggerRow = TriggerRow(
        atMs = fact.atMs,
        type = TriggerType.MANUAL,
        triggered = false,
        ref = "goal:${fact.goalId}",
        intent = intentOf(fact),
        evalRan = fact.ran,
        evalPassed = fact.passed,
        evalTotal = fact.total,
        evalPassRate = null,
        evalFailedCases = fact.cases,
        suppressReason = NOT_A_TRIGGER,
    )

    /** 落到既有账（[TriggerLedgerStore] 的追加口，写失败返 false——记账失败不许挡收口）。 */
    fun append(file: File, fact: GoalRules.CloseoutFact): Boolean =
        TriggerLedgerStore(file).append(row(fact))

    /** 那一行人话（读账不用解 JSON 也看得懂）。 */
    fun intentOf(fact: GoalRules.CloseoutFact): String =
        "目标${fact.stateCn}：${fact.sentence}｜判据 ${fact.passed}/${fact.total}｜" +
            "预算 ${fact.budgetUsed}/${fact.budgetMax}（${fact.budgetPct}）"
}
