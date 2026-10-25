// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十一批 · 接线刀二（审计 §2.5 剩余）：本批**只接已有能力，不新造功能**。
// 各缺口的执行侧改动散在既有文件里（每处一两行，见交付报告 §接线 的文件:行号），
// 本文件集中放四件**纯判据**（无 Android、无 IO，JVM 直测）：
//   ① recallBudgetOf   —— 记忆召回预算按模型窗口取（#2），封顶仍是既有 2k 常量；
//   ② MemoryQualityBadge —— 记忆质量分成文（#3）：分数/等级/未评估三态；
//   ③ toolRepairRequestText —— 工具失败「修一下」要预填的那句修复请求（#6），
//      归因本身来自既有 [com.example.zhengdao.baopu.agent.ToolFailureAdvisor]；
//   ④ subAgentUsageLine —— 子 Agent token 汇总行（#5），给 run_log 留痕用。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.ToolFailureAdvisor
import com.example.zhengdao.baopu.agent.ToolFailureEvidence
import com.example.zhengdao.baopu.agent.multiagent.SubTaskResult

// ── ① 记忆召回预算按窗口（#2） ────────────────────────────────────────────

/**
 * 召回预算占窗口的份额（**倒数**：窗口 ÷ [RECALL_WINDOW_DIVISOR]）。
 *
 * 口径：记忆段至多吃窗口的 1/64——128k 起步的当代模型按这条落在 2k 封顶上
 * （与旧常量行为一致）；只有小窗口（32k 以下）才会看到预算收缩。
 */
const val RECALL_WINDOW_DIVISOR = 64

/**
 * 召回预算下限：窗口再小也给这么多，避免「窗口小 ⇒ 一条记忆都召不回」
 * （0 预算等于不召回，那是把记忆功能静默关掉，不是省 token）。
 */
const val RECALL_BUDGET_FLOOR = 256

/**
 * 记忆召回预算（#2 接法：**按模型窗口取，保留上限**）：
 * `clamp(窗口 ÷ 64, 256, RECALL_TOKEN_BUDGET)`。
 *
 * 判据对应：「换模型窗口预算变化」——同一台设备换到小窗口通道，预算跟着缩；
 * 「不超上限」——1M 窗口也只给 [ContextAssembler.RECALL_TOKEN_BUDGET]（2k），
 * 旧路径（一律 2k）在大窗口上的行为一个字不变。
 */
internal fun recallBudgetOf(windowTokens: Int): Int =
    (windowTokens / RECALL_WINDOW_DIVISOR)
        .coerceIn(RECALL_BUDGET_FLOOR, ContextAssembler.RECALL_TOKEN_BUDGET)

// ── ② 记忆质量分成文（#3） ────────────────────────────────────────────────

/** 质量等级（阈值只借 [MemoryQuality] 的既有常量，不另立第二套线）。 */
internal enum class MemoryQualityLevel {
    /** 优：80 分及以上。 */
    GOOD,

    /** 良：[MemoryQuality.STALE_BELOW] 到 79 分。 */
    FAIR,

    /** 低：低于 [MemoryQuality.STALE_BELOW]（与打 STALE 标记同一条线）。 */
    POOR,

    /** 未评估：从没跑过质量打分（scoredAt = 0）。 */
    UNASSESSED,
}

/** 一条记忆在卡上的质量读数（纯数据；UI 只管摆，不自己算分）。 */
internal data class MemoryQualityBadge(
    val level: MemoryQualityLevel,
    /** 分数；[MemoryQualityLevel.UNASSESSED] 时无意义（UI 显示「未评估」）。 */
    val score: Int,
)

/**
 * 记忆 → 卡上那行质量读数（#3）。
 *
 * 分两个来源，绝不混：
 * - `scoredAt > 0`：质量层真跑过 ⇒ 显示**落账的分数**（`qualityScore` 列）；
 * - `scoredAt = 0`：没跑过 ⇒ [MemoryQualityLevel.UNASSESSED]（「未评估」），
 *   **不许**拿 [MemoryQuality.scoreOf] 现算一个冒充——那是把「评估过」和
 *   「能算出来」混成一回事（判据的负例正是这条）。
 */
internal fun memoryQualityBadgeOf(e: MemoryEntry): MemoryQualityBadge {
    if (e.scoredAt <= 0L) return MemoryQualityBadge(MemoryQualityLevel.UNASSESSED, MemoryQuality.DEFAULT_SCORE)
    val s = e.qualityScore
    val level = when {
        s >= 80 -> MemoryQualityLevel.GOOD
        s >= MemoryQuality.STALE_BELOW -> MemoryQualityLevel.FAIR
        else -> MemoryQualityLevel.POOR
    }
    return MemoryQualityBadge(level, s)
}

// ── ③ 「修一下」的修复请求文案（#6） ─────────────────────────────────────

/** 修复请求里错误原文的截断长度（预填进输入框的文本不该被一条长堆栈淹没）。 */
const val TOOL_REPAIR_ERR_CAP = 160

/**
 * 失败行「修一下」⇒ 预填进输入框的那句话（#6；**只预填，不发送**）。
 *
 * 归因与建议来自既有 [ToolFailureAdvisor.advise]（115 批，纯函数、只出建议）；
 * 顾问判「不建议重试」（[com.example.zhengdao.baopu.agent.ToolRepairSuggestion.NONE]）
 * ⇒ 返回 null——那种失败修不了，给个「修一下」按钮只会把用户点进死胡同。
 */
internal fun toolRepairRequestText(toolName: String, errText: String): String? {
    val advice = ToolFailureAdvisor.advise(ToolFailureEvidence(toolName = toolName, errorText = errText))
    if (advice.suggestion == com.example.zhengdao.baopu.agent.ToolRepairSuggestion.NONE) return null
    val err = errText.trim().replace('\n', ' ').replace('\r', ' ')
    val capped = if (err.length > TOOL_REPAIR_ERR_CAP) err.take(TOOL_REPAIR_ERR_CAP) + "…" else err
    return "请修一下刚才失败的操作：工具 $toolName 失败（$capped）。" +
        "归因：${advice.type.label}／${advice.cause.label}。" +
        "建议：${advice.action}。按建议处理后重试；改不了就说明原因。"
}

// ── ④ 子 Agent token 汇总行（#5） ─────────────────────────────────────────

/**
 * 一笔分解的子任务 token 合计（#5）。两边都 null ⇒ null（一步读数都没有，
 * 不写 0 假装知道——与 `LlmUsage` 的口径一致）。
 */
internal data class SubAgentUsage(val tokensIn: Long, val tokensOut: Long, val readings: Int)

/** 汇总（纯函数）：只加真有读数的条目；重复计账在这层天然挡住（每条只加一次）。 */
internal fun subAgentUsageOf(results: List<SubTaskResult>): SubAgentUsage? {
    var hasReading = false
    var readings = 0
    var inSum = 0L
    var outSum = 0L
    for (r in results) {
        val i = r.tokensIn
        val o = r.tokensOut
        if (i != null || o != null) {
            hasReading = true
            readings++
            inSum += i ?: 0L
            outSum += o ?: 0L
        }
    }
    return if (hasReading) SubAgentUsage(inSum, outSum, readings) else null
}

/** run_log 那行汇总账（`[baopu-subtask]` 可 grep；null ⇒ 一条读数都没有，不出声）。 */
internal fun subAgentUsageLine(parentId: String, usage: SubAgentUsage?): String? =
    usage?.let {
        "[baopu-subtask] 分解 $parentId：子任务 token 入 ${it.tokensIn} · 出 ${it.tokensOut}" +
            "（${it.readings} 条有读数；父会话各步用量另计，无重复）"
    }
