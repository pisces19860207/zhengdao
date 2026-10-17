// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴-Agent设计-程枢版 §7（上下文组装唯一出口；组装顺序；token 估算器定版；
// 预算判据 窗口×0.85）+ 派工单第八批追加节（面向提示词缓存：顺序固定、记忆只增不改、
// 压缩不动前缀、前缀字节级相同可断言）。
package com.example.zhengdao.baopu

/** 一条会话历史消息（id 用于压缩留痕：哪几条被压成摘要）。 */
data class HistoryMessage(val id: String, val role: String, val text: String) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

/** 硬事实卡（永压）：当前工作区路径 / 模型名 / 已定口径 / 格式守卫规则。 */
data class HardFacts(
    val workspacePath: String,
    val model: String,
    val decisions: List<String> = emptyList(),
    val formatRules: List<String> = emptyList(),
)

/** 组装段类型（顺序即枚举序——**顺序固定是缓存判据，不是风格**）。 */
enum class SegmentKind {
    PERSONA_A,   // A 内核（永不进压缩）
    PERSONA_B,   // B 全局人设（永不进压缩）
    PERSONA_C,   // C 项目人设（永不进压缩）
    HARD_FACTS,  // 硬事实卡（永不进压缩）
    MEMORY,      // 记忆召回（永不进压缩；同会话内只增不改）
    COMPRESSION, // 压缩摘要替身（唯一会随压缩事件变化的段）
    HISTORY,     // 会话历史（只增不改）
    CURRENT,     // 本轮输入
}

data class PromptSegment(val kind: SegmentKind, val text: String)

/**
 * 组装结果。判据 5（压缩不丢痕）：[compressedIds] 记录哪几条消息被压进了摘要 ——
 * 原文仍在 SQLite（L1），用户点开能看。
 */
data class AssembledPrompt(
    val segments: List<PromptSegment>,
    val needsCompressing: Boolean,
    val windowTokens: Int,
    val estimatedTokens: Int,
    val recallTruncated: Boolean,
    val recallNote: String,
    val compressedIds: Set<String> = emptySet(),
) {
    /**
     * 渲染成最终提示词文本。
     *
     * **前缀稳定拼接**（追加判据 4 的地基）：分隔符排在**每一段之前**，
     * 没有任何「包住整表」的包裹结构 ⇒ 尾部新增一段只新增字节，
     * 已渲染部分逐字节不动。渲染里也**不出现序号/计数**（「第 N 条」会随追加漂移）。
     */
    fun render(): String = buildString {
        for (s in segments) {
            if (s.text.isEmpty()) continue
            if (isNotEmpty()) append(SEP)
            append(s.text)
        }
    }

    /**
     * 「缓存友好前缀」= 人设三层 ＋ 硬事实 ＋ 记忆段。
     * 这些段**永不进压缩范围**（追加判据 3）；同会话内注入后只增不改（判据 2）。
     */
    fun prefixText(): String = segments
        .filter { it.kind in NEVER_COMPRESS }
        .joinToString(SEP) { it.text }

    private val NEVER_COMPRESS = setOf(
        SegmentKind.PERSONA_A, SegmentKind.PERSONA_B, SegmentKind.PERSONA_C,
        SegmentKind.HARD_FACTS, SegmentKind.MEMORY,
    )

    companion object {
        const val SEP = "\n\n"
    }
}

/**
 * 上下文组装器（刀 0b · 2026-10-16）——**全系统唯一拼 prompt 的地方**（程枢版 §7）。
 *
 * 组装顺序（上→下，追加判据 1 定版）：
 * `A 内核 → B 全局 → C 项目 → 硬事实卡 → 记忆召回 → 压缩摘要替身 → 会话历史 → 本轮输入`
 *
 * 三条缓存硬判据的落地方式：
 * 1. **顺序固定**：段序由 [SegmentKind] 枚举序钉死，调用方无法重排；
 * 2. **记忆只增不改**：[stickyMemories]（本会话已注入）恒在新召回之前，
 *    渲染分隔符在每段之前 ⇒ 新记忆只往记忆段尾部追加；
 * 3. **压缩不动前缀**：[AssembledPrompt.prefixText] 覆盖的段永远不进压缩范围，
 *    压缩只替换 [SegmentKind.COMPRESSION] 段与历史中段。
 */
internal object ContextAssembler {

    /** 压缩触发阈值（程枢版 §7：组装后 > 窗口 × 0.85 ⇒ 先 Compressing 再 Thinking）。 */
    const val COMPRESS_THRESHOLD = 0.85

    /** 记忆召回 token 上界（程枢版 §7：≤ 2k，不是全量灌）。 */
    const val RECALL_TOKEN_BUDGET = MemoryRules.RECALL_TOKEN_BUDGET

    /**
     * 窗口默认值（刀 3 B 段 · 2026-10-17 改）：优先用元数据里的 ctx，**拿不到才回退 1M**
     * （原 128k 是「老模型时代」的假设——deepseek-flash 实测 context_window=1048576，
     * 见 Provider.DEEPSEEK 的取证注记；用户口径「现在的模型大都 1M 了」。
     * 按 128k 配会把 1M 的模型当小模型用：压缩来得早、白压）。
     *
     * 阈值推导见 [ContextBudgets]：窗口值从 [Provider.contextWindow] /
     * [Provider.maxOutput] 两个可空字段算，**不写死常量**（追加二第 1 条）。
     */
    const val DEFAULT_WINDOW_TOKENS = 1_048_576

    /**
     * token 估算器（程枢版 §7 定版）：**中文按字符数 × 1.0，ASCII 按字节 ÷ 4，
     * 取大者，再 × 1.15 安全系数**。Kotlin 侧没有 tokenizer，provider 的 usage
     * 只对已发生的请求有效 ⇒ 组装前只能估。估算值与实际 usage 记 run_log
     * 对拍（`*_est` 两列），跑约 20 次可校准系数。
     *
     * 实现已抽到 [TokenEstimator]（追加二第 2 条「一把尺子」）——
     * Memory 的 ≤ 2k 裁剪与本处的窗口判定共用它。
     */

    /** 触发线（窗口 × 0.85）。纯函数，便于把边界三点钉死在单测里。 */
    fun compressionThreshold(windowTokens: Int): Double = windowTokens * COMPRESS_THRESHOLD

    /**
     * 是否需要先压缩。**严格大于**：恰好压线（== 阈值）不触发。
     * 边界三点（0.849 / 0.850 / 0.851）由 [ContextAssemblerTest] 钉死，
     * 且三点必须从 [ContextBudgets.windowOf] 算出的窗口推导，不许写死常量。
     */
    fun needsCompressing(estimatedTokens: Int, windowTokens: Int): Boolean =
        estimatedTokens > compressionThreshold(windowTokens)

    /**
     * 窗口与预算推导（追加二第 1 条 · 2026-10-16）。
     *
     * 全部从 [Provider] 的两个可空字段算：
     * - 窗口：`contextWindow`（元数据 ctx）优先，**拿不到回退 1M**
     *   （刀 3 B 段：128k 旧假设已废，deepseek-flash 实测 1048576）；
     * - 输出上限：`maxOutput` 优先，拿不到回退 **32k**；
     * - 单轮预算：`min(窗口 × 0.6, 输出上限 × 0.9)`（只按窗口算会撞输出上限被截断）；
     * - 触发线：`窗口 × 0.85`（[compressionThreshold]）。
     */
    internal object ContextBudgets {

        /** 输出上限回退值（拿不到元数据时）。 */
        const val DEFAULT_OUTPUT_TOKENS = 32_000

        /** 单轮预算占窗口比例（程枢版 §7 定版）。 */
        const val WINDOW_BUDGET_RATIO = 0.6

        /** 单轮预算占输出上限比例（程枢版 §7 定版）。 */
        const val OUTPUT_BUDGET_RATIO = 0.9

        /** 窗口：元数据 ctx 优先，拿不到回退 [DEFAULT_WINDOW_TOKENS]。非正数当没给。 */
        fun windowOf(p: Provider): Int =
            p.contextWindow?.takeIf { it > 0 } ?: DEFAULT_WINDOW_TOKENS

        /** 输出上限：元数据优先，拿不到回退 [DEFAULT_OUTPUT_TOKENS]。非正数当没给。 */
        fun outputOf(p: Provider): Int =
            p.maxOutput?.takeIf { it > 0 } ?: DEFAULT_OUTPUT_TOKENS

        /** 单轮 token 预算 = min(窗口 × 0.6, 输出上限 × 0.9)。 */
        fun budgetOf(p: Provider): Int = minOf(
            Math.floor(windowOf(p) * WINDOW_BUDGET_RATIO).toInt(),
            Math.floor(outputOf(p) * OUTPUT_BUDGET_RATIO).toInt(),
        )

        /** 压缩触发线（窗口 × 0.85）。 */
        fun thresholdOf(p: Provider): Double = windowOf(p) * COMPRESS_THRESHOLD
    }

    /**
     * 组装。
     *
     * @param persona PersonaRules.assemble 产出的人设段（A→B→C，已裁剪）
     * @param stickyMemories 本会话**已注入**的记忆（只增不改；恒在新召回之前）
     * @param newMemories 本轮新召回（按剩余预算裁剪后**追加**在 sticky 之后）
     * @param history 会话历史（只增不改；渲染无序号）
     * @param current 本轮输入（空串 = 没有，段整体省略）
     * @param compressionSummary 压缩摘要替身（null = 还没压过）
     * @param compressedIds 被压进摘要的消息 id（留痕，判据 5）
     */
    @Suppress("LongParameterList")
    fun assemble(
        persona: List<PersonaSegment>,
        facts: HardFacts,
        stickyMemories: List<MemoryEntry>,
        newMemories: List<MemoryEntry>,
        history: List<HistoryMessage>,
        current: String,
        windowTokens: Int = DEFAULT_WINDOW_TOKENS,
        compressionSummary: String? = null,
        compressedIds: Set<String> = emptySet(),
        tokensOf: (String) -> Int = { TokenEstimator.estimate(it) },
    ): AssembledPrompt {
        // ── 记忆段：sticky 恒前，new 追加；预算 = 2k − sticky 已占 ──
        val stickyRendered = stickyMemories.map { MemoryRules.renderForContext(it) }
        val stickyCost = stickyRendered.sumOf { tokensOf(it) }
        val remaining = (RECALL_TOKEN_BUDGET - stickyCost).coerceAtLeast(0)
        val pickedNew: List<MemoryEntry>
        val recallTruncated: Boolean
        val recallNote: String
        if (remaining == 0) {
            pickedNew = emptyList()
            recallTruncated = newMemories.isNotEmpty()
            recallNote = if (newMemories.isNotEmpty()) {
                "记忆召回预算已被本会话已注入条目占满（${RECALL_TOKEN_BUDGET} tokens），本轮新召回 ${newMemories.size} 条全部未注入"
            } else {
                ""
            }
        } else {
            val r = MemoryRules.trimToBudget(newMemories, remaining, tokensOf)
            pickedNew = r.picked
            recallTruncated = r.truncated
            recallNote = r.note
        }
        val memoryLines = stickyRendered + pickedNew.map { MemoryRules.renderForContext(it) }

        // ── 段装配（顺序 = 枚举序） ──
        val segments = mutableListOf<PromptSegment>()
        for (p in persona) segments += PromptSegment(personaKind(p.layer), p.text)
        segments += PromptSegment(SegmentKind.HARD_FACTS, renderFacts(facts))
        if (memoryLines.isNotEmpty()) {
            val note = if (recallTruncated) "\n$recallNote" else ""
            segments += PromptSegment(SegmentKind.MEMORY, memoryLines.joinToString("\n") + note)
        } else if (recallNote.isNotEmpty()) {
            segments += PromptSegment(SegmentKind.MEMORY, recallNote)
        }
        if (!compressionSummary.isNullOrBlank()) {
            val mark = buildString {
                append("【此处已压缩")
                if (compressedIds.isNotEmpty()) append("：${compressedIds.size} 条旧消息压成摘要，原文仍在记录里可回看")
                append("】\n")
                append(compressionSummary)
            }
            segments += PromptSegment(SegmentKind.COMPRESSION, mark)
        }
        if (history.isNotEmpty()) {
            segments += PromptSegment(SegmentKind.HISTORY, renderHistory(history))
        }
        if (current.isNotBlank()) segments += PromptSegment(SegmentKind.CURRENT, current)

        val prompt = AssembledPrompt(
            segments = segments,
            needsCompressing = false,
            windowTokens = windowTokens,
            estimatedTokens = 0,
            recallTruncated = recallTruncated,
            recallNote = recallNote,
            compressedIds = compressedIds,
        )
        val estimated = tokensOf(prompt.render())
        return prompt.copy(
            needsCompressing = needsCompressing(estimated, windowTokens),
            estimatedTokens = estimated,
        )
    }

    private fun personaKind(layer: PersonaLayer): SegmentKind = when (layer) {
        PersonaLayer.A_KERNEL -> SegmentKind.PERSONA_A
        PersonaLayer.B_GLOBAL -> SegmentKind.PERSONA_B
        PersonaLayer.C_PROJECT -> SegmentKind.PERSONA_C
    }

    /** 硬事实卡渲染（字段固定，无序号）。 */
    fun renderFacts(f: HardFacts): String = buildString {
        append("【硬事实】\n")
        append("工作区：").append(f.workspacePath).append('\n')
        append("模型：").append(f.model).append('\n')
        if (f.decisions.isNotEmpty()) {
            append("已定口径：\n")
            f.decisions.forEach { append("- ").append(it).append('\n') }
        }
        if (f.formatRules.isNotEmpty()) {
            append("格式守卫规则：\n")
            f.formatRules.forEach { append("- ").append(it).append('\n') }
        }
    }.trimEnd()

    /**
     * 历史渲染：**每条独立、无序号**（「第 N 条」会随追加漂移，废掉缓存）。
     * 分隔符由 [AssembledPrompt.render] 统一排在每段前 ⇒ 追加一条只新增字节。
     */
    fun renderHistory(history: List<HistoryMessage>): String =
        history.joinToString("\n") { m ->
            val who = if (m.role == HistoryMessage.ROLE_USER) "用户" else "抱朴"
            "【$who】${m.text}"
        }

    /**
     * 压缩选区（判据：只压中段与旧段，前缀与最近 N 条不动）。
     * @param keepRecent 最近 N 条原文不压（方案 §9b「必须留住的四样」之一）
     * @return 可压的消息列表（保持原序）；不足 keepRecent+1 条时返回空（没压的价值）
     */
    fun selectCompressionRange(history: List<HistoryMessage>, keepRecent: Int): List<HistoryMessage> {
        if (history.size <= keepRecent) return emptyList()
        return history.subList(0, history.size - keepRecent).toList()
    }
}
