// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九十一批 §2（R3 压缩质量：记录压缩前后「关键事实/决定/待办」标签、
// 量化保留率；每次压缩记 token 数、保留率、触发原因；低保留率只告警不拦对话）。
// 标签口径取自 `eval/cases.json` 里每例的可核对字符串（final_contains /
// file_contains / 路径）以及提示词里的「不要动/改成/写进」这类约束句——
// 本文件只做**词面级**保留率，不假装语义理解；读数用于相对比较，不用于自动决策。
package com.example.zhengdao.baopu

/** 关键标签的三类（派工单 §2 原文）。 */
internal enum class LabelKind(val label: String) {
    FACT("事实"),
    DECISION("决定"),
    TODO("待办"),
}

/** 一条关键标签（压缩前从原文抽取，压缩后在摘要里找对应）。 */
internal data class KeyLabel(val kind: LabelKind, val text: String)

/**
 * 一次压缩的质量读数。
 *
 * 全部是**度量值**：没有任何字段会反过来改阈值、删消息或阻断对话。
 * [retention] 为 1.0 有两种情况：全都保住了，或原文根本没有可测标签
 * （后者看 [labelsTotal] == 0 区分，报告必须分开写）。
 */
internal data class CompressionMetrics(
    val sourceTokens: Int,
    val summaryTokens: Int,
    /** 摘要/原文的 token 比（越小压得越狠；原文为 0 记 0.0）。 */
    val compressionRatio: Double,
    val labelsBefore: Map<LabelKind, Int>,
    val labelsRetained: Map<LabelKind, Int>,
    val labelsTotal: Int,
    val labelsKept: Int,
    /** 三类合计保留率（0.0–1.0；无标签记 1.0，见上）。 */
    val retention: Double,
    /** 低于 [CompressionQuality.LOW_RETENTION_THRESHOLD]（只告警）。 */
    val lowRetention: Boolean,
    /** 触发原因（压缩线/窗口读数）。 */
    val triggerReason: String,
) {
    /** 一行日志（字段固定，便于从 `zhengdao-log.txt` 里 grep）。 */
    fun summaryLine(): String = buildString {
        append("tokens ").append(sourceTokens).append("→").append(summaryTokens)
        append("（比 ").append(fmt(compressionRatio)).append("）；")
        append("标签 ").append(labelsTotal).append("→").append(labelsKept)
        append("（保留率 ").append(fmt(retention)).append("；")
        append(
            LabelKind.entries.joinToString("，") { k ->
                "${k.label} ${labelsRetained[k] ?: 0}/${labelsBefore[k] ?: 0}"
            },
        )
        append("）；触发：").append(triggerReason)
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.2f", v)
}

/**
 * 压缩质量的**纯判据**（无 Android、无 I/O、无网络，可 JVM 直测）。
 *
 * 与 [Compressor] 的分工：Compressor 负责「怎么压」，本对象只回答
 * 「压完以后，原文里可核对的东西还剩多少」。不参与提示词构造，
 * 因此不会改变压缩请求的任何一个字节。
 */
internal object CompressionQuality {

    /**
     * 低保留率告警线。**只告警**：低于它也只写日志，不重压、不换通道、
     * 不改 [ContextAssembler] 的压缩阈值。
     */
    const val LOW_RETENTION_THRESHOLD = 0.60

    /** 一条标签被摘要「保住」的词面重合度门槛。 */
    const val LABEL_MATCH_THRESHOLD = 0.50

    /** 单次最多抽多少条标签（工具输出可能很长，避免度量本身变成负担）。 */
    const val MAX_LABELS = 200

    /** 太短的行不当标签（「好」「无」这种没有可核对信息）。 */
    const val MIN_LABEL_CHARS = 2

    /** 决定类标记（「已定/不许/不要/必须」等都是 eval 提示词里出现过的约束句）。 */
    private val DECISION_MARKERS = listOf(
        "已定", "决定", "定为", "采用", "口径", "不要", "不许", "不能", "必须", "只许", "统一",
    )

    /** 待办类标记（eval 提示词里的「改成/写进/改名为/贴回来」等动作句）。 */
    private val TODO_MARKERS = listOf(
        "待办", "TODO", "下一步", "待", "需要", "记得", "稍后", "写进", "改成", "改名为",
        "贴回来", "告诉我", "做完", "完成后", "再",
    )

    /**
     * 从一段原文抽标签。
     *
     * 口径（刻意简单、可解释）：
     * - 去列表符号后短于 [MIN_LABEL_CHARS] 的行丢掉；
     * - 含决定标记 ⇒ [LabelKind.DECISION]；否则含待办标记 ⇒ [LabelKind.TODO]；
     *   其余**都算事实**（宁可多抽，也不漏掉可核对的那句）；
     * - 同文去重、保序，最多 [MAX_LABELS] 条。
     */
    fun extractLabels(text: String): List<KeyLabel> {
        val out = LinkedHashSet<KeyLabel>()
        for (raw in text.lines()) {
            val line = raw.trim().trimStart('-', '*', '•', '·').trim()
            if (line.length < MIN_LABEL_CHARS) continue
            val kind = when {
                DECISION_MARKERS.any { line.contains(it, ignoreCase = true) } -> LabelKind.DECISION
                TODO_MARKERS.any { line.contains(it, ignoreCase = true) } -> LabelKind.TODO
                else -> LabelKind.FACT
            }
            out += KeyLabel(kind, line)
            if (out.size >= MAX_LABELS) break
        }
        return out.toList()
    }

    /** 多条消息合并抽取（每条各抽各的，再全局去重）。 */
    fun extractLabels(texts: Iterable<String>): List<KeyLabel> {
        val out = LinkedHashSet<KeyLabel>()
        for (t in texts) {
            for (label in extractLabels(t)) {
                out += label
                if (out.size >= MAX_LABELS) return out.toList()
            }
        }
        return out.toList()
    }

    /**
     * 一条标签是否被摘要保住：标签词集在摘要词集里的覆盖率 ≥ [LABEL_MATCH_THRESHOLD]。
     *
     * 为什么用词集而不是原文包含：摘要是抽象式的，逐字包含几乎必为 0；
     * 词面覆盖能容忍改写与语序变化，同时仍可解释（覆盖率可复核）。
     */
    fun isRetained(label: KeyLabel, summary: String): Boolean {
        val want = MemoryRules.tokensOf(label.text)
        if (want.isEmpty()) return false
        val got = MemoryRules.tokensOf(summary)
        if (got.isEmpty()) return false
        val hit = want.count { it in got }
        return hit.toDouble() / want.size.toDouble() >= LABEL_MATCH_THRESHOLD
    }

    /**
     * 算一次压缩的完整读数（纯函数）。
     *
     * @param sourceTexts 被压的原文（选区里的消息正文）
     * @param summary 压缩后的摘要（增量合并后的最终文本）
     * @param triggerReason 调用方给的触发原因（见 [triggerReason]）
     */
    fun measure(
        sourceTexts: List<String>,
        summary: String,
        triggerReason: String,
        summaryTokens: Int,
        sourceTokens: Int,
    ): CompressionMetrics {
        val labels = extractLabels(sourceTexts)
        val before = LabelKind.entries.associateWith { k -> labels.count { it.kind == k } }
        val keptLabels = labels.filter { isRetained(it, summary) }
        val after = LabelKind.entries.associateWith { k -> keptLabels.count { it.kind == k } }
        val total = labels.size
        val kept = keptLabels.size
        val retention = if (total == 0) 1.0 else kept.toDouble() / total.toDouble()
        return CompressionMetrics(
            sourceTokens = sourceTokens,
            summaryTokens = summaryTokens,
            compressionRatio = if (sourceTokens <= 0) 0.0 else summaryTokens.toDouble() / sourceTokens.toDouble(),
            labelsBefore = before,
            labelsRetained = after,
            labelsTotal = total,
            labelsKept = kept,
            retention = retention,
            lowRetention = total > 0 && retention < LOW_RETENTION_THRESHOLD,
            triggerReason = triggerReason,
        )
    }

    /**
     * 触发原因一行（纯读数）：说明这次为什么压——超过触发线，还是调用方主动要求。
     *
     * @param sourceTokens 被压原文的估算 token（不是整轮输入；整轮读数由引擎持有）
     */
    fun triggerReason(sourceTokens: Int, provider: Provider): String {
        val budget = BudgetTable.forProvider(provider)
        val threshold = TokenBudget.thresholdOf(budget)
        val why = if (sourceTokens > threshold) "超过触发线" else "调用方要求（未超线）"
        return "$why：被压原文 $sourceTokens tokens，触发线 ${Math.round(threshold)}，" +
            "窗口 ${budget.contextWindow}（${budget.source}）"
    }
}
