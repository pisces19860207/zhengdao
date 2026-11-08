// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十一批 · 子智能体 v2 ①「上下文隔离」：子 Agent 只拿
// **子任务目标＋相关片段＋约束**，不含父全量历史；预算按「父的 1/3」硬算。
//
// 为什么单开一件而不是继续写在 SubAgentRules 里：隔离是**可量的判据**
// （token 比与原文泄漏），不是提示词措辞。这里只放「怎么算、怎么裁」，
// 措辞仍在 SubAgentRules.subTaskMessages（唯一定义处），本件只做预算与裁剪。
package com.example.zhengdao.baopu.agent.multiagent

import com.example.zhengdao.baopu.LlmMessage
import com.example.zhengdao.baopu.TokenEstimator

internal object SubAgentIsolation {

    /** 子上下文相对父的份额：父子比 ≤ 1/SHARE（派工单判据①「≤ 父 1/3」）。 */
    const val SHARE = 3

    /**
     * 子上下文的**地板**（token）。
     *
     * 为什么要有地板：极短会话（父上下文只有一两百 token）按 1/3 算会低于
     * 「系统提示＋目标」本身的长度，子 Agent 连任务都读不全。地板只在父 <
     * [SHARE]×地板 时生效；判据①的场景（50 轮父会话）仍由 1/3 主导。
     */
    const val MIN_CHILD_TOKENS = 512

    /** 裁剪的最大轮数（每轮砍 1/4；8 轮足够把 2000 字 input 砍到几十字）。 */
    const val TRIM_ROUNDS = 8

    /** 一组消息的 token（全仓唯一一把尺子：[TokenEstimator]）。 */
    fun tokensOf(messages: List<LlmMessage>): Int =
        messages.sumOf { TokenEstimator.estimate(it.content) }

    /** 子上下文预算 ＝ max(父/3, 地板)。 */
    fun childCap(parentTokens: Int): Int =
        maxOf(parentTokens.coerceAtLeast(0) / SHARE, MIN_CHILD_TOKENS)

    /** 隔离判据：子 ≤ 预算（父未知时用 0 ⇒ 只吃地板，宁紧不松）。 */
    fun withinShare(childTokens: Int, parentTokens: Int): Boolean =
        childTokens <= childCap(parentTokens)

    /**
     * 子 Agent 的开局两条消息（system＋user），**按父预算裁 input**。
     *
     * 只裁 [SubTask.input]（材料/背景），不裁 goal 与 acceptance——后者是
     * 「要做什么、怎样算完成」，裁掉子任务就失去意义；input 超预算时宁可少给材料
     * 让子 Agent 自己用只读工具去查（它本来就有工具）。
     *
     * @param parentTokens 父客户端此刻的上下文估算（生产由最近一步的
     *   `run_log.tokens_in` 给；拿不到传 0 ⇒ 只吃地板）
     */
    fun seed(task: SubTask, parentTokens: Int): List<LlmMessage> {
        val cap = childCap(parentTokens)
        var current = task
        repeat(TRIM_ROUNDS) {
            val messages = SubAgentRules.subTaskMessages(current)
            if (tokensOf(messages) <= cap) return messages
            if (current.input.isEmpty()) return messages // 已无可裁：系统提示本身撑着
            current = current.copy(input = current.input.take(current.input.length * 3 / 4))
        }
        return SubAgentRules.subTaskMessages(current)
    }
}
