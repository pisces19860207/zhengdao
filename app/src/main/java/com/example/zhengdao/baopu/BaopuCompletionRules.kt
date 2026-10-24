// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单《第一百六十九批 · 接线刀》§3——「长任务/子 Agent 结束时，若 App 不在前台 ⇒
// 发一条完成通知（成功/失败/被中止）；重复通知合并，同一任务不刷屏；拒绝权限不崩；
// 通知内容不含 Key/敏感正文：只给标题＋状态＋可读摘要」。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.RunState

/** 一轮结束的三种可见结局（派工单原文：成功/失败/被中止）。 */
internal enum class CompletionOutcome { SUCCESS, FAILED, ABORTED }

/** 完成通知的两种来源：整轮结束、或本轮里的子 Agent 批结束。 */
internal enum class CompletionKind { RUN, SUB_AGENTS }

/**
 * 完成通知的**纯判据**（不碰 Context / 不发通知，JVM 直测）。
 *
 * 三条口径：
 * 1. **只在后台发**：前台完成时用户已经看得见会话，通知只会是噪音；
 * 2. **权限拒绝不崩也不硬弹**：返回 false，调用方什么都不做（会话本身早已更新）；
 * 3. **同一任务合并**：同一会话在 [MERGE_WINDOW_MS] 内只发一条——子 Agent 刚完成的
 *    一秒后整轮又结束，不该连响两次；不同会话不受影响。
 */
internal object BaopuCompletionRules {

    /** 同一会话两条完成通知的最小间隔；窗口内后到的那条不再响。 */
    const val MERGE_WINDOW_MS = 5_000L

    /**
     * 从引擎终态与人话 notice 判结局。
     *
     * `RunState.ABORTED` 同时承担「用户点停止」和「出错收轮」：前者引擎会写
     * 「已停止（这轮的回答到这儿为止）」，后者写的是错误原因/「已取消」。
     * 只有前者算「被中止」，其余都算失败——不把错误说成中止。
     */
    fun outcomeOf(state: RunState, notice: String?): CompletionOutcome = when (state) {
        RunState.DONE -> CompletionOutcome.SUCCESS
        else -> if (notice != null && notice.startsWith("已停止")) {
            CompletionOutcome.ABORTED
        } else {
            CompletionOutcome.FAILED
        }
    }

    /** 子 Agent 批结束：工具报告 ok ⇒ 成功，否则失败。 */
    fun subAgentOutcome(ok: Boolean): CompletionOutcome =
        if (ok) CompletionOutcome.SUCCESS else CompletionOutcome.FAILED

    /**
     * 这条完成事件该不该真的发通知。
     *
     * @param foreground 任一 Activity 可见（[com.example.zhengdao.AppForeground.isForeground]）
     * @param permissionGranted POST_NOTIFICATIONS 已授予；拒绝 ⇒ 只更新会话、不弹
     * @param nowMs 单调时钟（`SystemClock.elapsedRealtime`）
     * @param lastPostedAtMs 上一条完成通知的时刻（0 = 从未发过）
     * @param lastSessionId 上一条完成通知指向的会话
     */
    fun shouldPost(
        foreground: Boolean,
        permissionGranted: Boolean,
        nowMs: Long,
        lastPostedAtMs: Long,
        lastSessionId: Long,
        sessionId: Long,
    ): Boolean {
        if (foreground || !permissionGranted) return false
        val withinMergeWindow =
            lastSessionId == sessionId && lastPostedAtMs > 0 && nowMs - lastPostedAtMs in 0 until MERGE_WINDOW_MS
        return !withinMergeWindow
    }

    /** 通知标题：来源＋结局（**不含会话正文**）。 */
    fun titleOf(kind: CompletionKind, outcome: CompletionOutcome): String = when (kind) {
        CompletionKind.RUN -> when (outcome) {
            CompletionOutcome.SUCCESS -> "抱朴 · 任务完成"
            CompletionOutcome.FAILED -> "抱朴 · 任务失败"
            CompletionOutcome.ABORTED -> "抱朴 · 任务已中止"
        }

        CompletionKind.SUB_AGENTS -> when (outcome) {
            CompletionOutcome.SUCCESS -> "抱朴 · 子 Agent 完成"
            CompletionOutcome.FAILED -> "抱朴 · 子 Agent 失败"
            // 子 Agent 批只有 ok/不 ok 两态；中止由整轮那条通知负责
            CompletionOutcome.ABORTED -> "抱朴 · 子 Agent 已中止"
        }
    }

    /**
     * 通知正文：只给「下一步做什么」，**不带模型回复、工具输出、会话标题或 Key**。
     * 用户点进来才看得到正文——锁屏上不该出现聊天内容。
     */
    fun textOf(kind: CompletionKind, outcome: CompletionOutcome): String = when (kind) {
        CompletionKind.RUN -> when (outcome) {
            CompletionOutcome.SUCCESS -> "后台跑完了，点按回到会话查看结果"
            CompletionOutcome.FAILED -> "后台这一轮没跑完，点按回到会话查看原因"
            CompletionOutcome.ABORTED -> "这一轮已停止，点按回到会话"
        }

        CompletionKind.SUB_AGENTS -> when (outcome) {
            CompletionOutcome.SUCCESS -> "子 Agent 已完成，点按回到会话查看结果"
            CompletionOutcome.FAILED -> "子 Agent 没跑成，点按回到会话查看原因"
            CompletionOutcome.ABORTED -> "子 Agent 已中止，点按回到会话"
        }
    }
}
