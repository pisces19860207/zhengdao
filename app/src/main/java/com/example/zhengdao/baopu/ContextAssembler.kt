// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴-Agent设计-程枢版 §7（上下文组装唯一出口；组装顺序；token 估算器定版；
// 预算判据 窗口×0.85）+ 派工单第八批追加节（面向提示词缓存：顺序固定、记忆只增不改、
// 压缩不动前缀、前缀字节级相同可断言）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.baopu.agent.RunRules

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
    /**
     * 本轮要随这条消息一起发出去的图片（data URL 列表，第二十四批 A 段）。
     *
     * 空 = 不发图（纯文本通道、或本轮没有图片、或全被裁掉了）。
     * **这里永远是 data URL，不会是路径**——路径只活到调用方读文件那一刻（V6）。
     * 谁填的：[VisionRules.select] 的产出，依据 `Provider.vision`（V5）。
     */
    val images: List<String> = emptyList(),
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

    /**
     * 压缩红线：这些段永不进压缩范围（26-B 起**唯一来源在内核**，这里只做委托）。
     * 别在本文件里再写一份集合 —— `KernelGuardTest` 的 K7 会红。
     */
    private val NEVER_COMPRESS: Set<SegmentKind> = BaopuKernel.Budget.NEVER_COMPRESS

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
    const val COMPRESS_THRESHOLD = BaopuKernel.Budget.COMPRESS_THRESHOLD

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

    /**
     * 触发线（2026-10-19 用户口径：**窗口的 70%**）。纯函数，便于把边界钉死在单测里。
     *
     * 演进：v1 是 `min(窗口 × 0.85, 120k)`（第十五批给"条数太多"兜底）——1M 窗口上
     * 那条 120k 的封顶等于 **11% 就压**，用户 2026-10-19 真机反馈「压得太早」；
     * 现在 = `min(窗口 × 0.70, 900k)`，1M 窗口 ⇒ 734,003 token 才压（约 70%）。
     * 常量仍在 [RunRules.COMPRESS_ABS_MAX_TOKENS]（判据数值统一放纯判据内核）。
     */
    fun compressionThreshold(windowTokens: Int): Double =
        minOf(windowTokens * COMPRESS_THRESHOLD, RunRules.COMPRESS_ABS_MAX_TOKENS.toDouble())

    /**
     * 是否需要先压缩（2026-10-19 口径：**只看 token 占窗口的比例**）。
     *
     * 🛑 旧口径里还有一条「可压旧消息 ≥ 60 条也压」，2026-10-19 撤掉：
     * 用户在 1M 窗口下要求「到 70% 左右才压」，而 60 条消息在一万 token 时就到了 ⇒
     * 一条条数线能把 70% 的口径彻底架空（真机上就是它提前压的）。
     * 条数现在只决定「**能不能**压」（[pendingOldMessageCount] > 0），不再决定「该不该」。
     *
     * @param oldMessageCount 保留形参（调用方仍在传），**不再参与判定**——留着是为了
     *   不惊动引擎接线，且以后若要恢复类似刹车有现成的读数。
     */
    fun needsCompressing(estimatedTokens: Int, windowTokens: Int, oldMessageCount: Int = 0): Boolean {
        @Suppress("UNUSED_VARIABLE") val ignored = oldMessageCount
        return estimatedTokens > compressionThreshold(windowTokens)
    }

    /**
     * 「可压旧消息」条数（第十五批 B 段）——**直接复用选区函数**，不另写口径：
     * `selectCompressionRange` 保留的最近 KEEP_RECENT 条之外全是候选，
     * 再扣除已压进摘要的消息（compressedIds）——它们已由摘要代言，再算成
     * 「可压」会让条数触发线压完仍满足 ⇒ 每轮重压（C 段明令禁止的抖动）。
     */
    fun pendingOldMessageCount(
        history: List<HistoryMessage>,
        compressedIds: Set<String> = emptySet(),
    ): Int = selectCompressionRange(history, keepRecent = Compressor.KEEP_RECENT)
        .count { it.id !in compressedIds }

    /**
     * 窗口与预算推导（追加二第 1 条 · 2026-10-16）。
     *
     * 全部从 [Provider] 的两个可空字段算：
     * - 窗口：`contextWindow`（元数据 ctx）优先，**拿不到回退 1M**
     *   （刀 3 B 段：128k 旧假设已废，deepseek-flash 实测 1048576）；
     * - 输出上限：`maxOutput` 优先，拿不到回退 **32k**；
     * - 单轮预算：`min(窗口 × 0.6, 输出上限 × 0.9)`（只按窗口算会撞输出上限被截断）；
     * - 触发线：`min(窗口 × 0.70, 900k)`（[compressionThreshold]；2026-10-19 用户口径）。
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

        /** 压缩触发线（min(窗口 × 0.85, 120k)——第十五批 A 段封顶）。 */
        fun thresholdOf(p: Provider): Double = compressionThreshold(windowOf(p))
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
     * @param skipIds **不进 prompt** 的消息 id（第十三批 B 段：压过的旧消息由摘要代言）
     * @param oldMessageCount 可压旧消息条数（第十五批 B 段：[pendingOldMessageCount]
     *   的产出，由调用方传；默认 0 ⇒ 老调用行为不变）
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
        skipIds: Set<String> = emptySet(),
        oldMessageCount: Int = 0,
        // ── 视觉通道（第二十四批 A 段）──
        /** 本轮附件里**是图片**的那些（已由调用方读成 data URL；[VisionRules.ImagePayload] 不含路径）。 */
        images: List<VisionRules.ImagePayload> = emptyList(),
        /**
         * 当前通道能不能看图——**必须来自 `Provider.vision`**（V5：这里不许自己判断
         * 模型名，否则"哪个模型能看图"就有第二份真相，且必然漂移）。
         */
        visionSupported: Boolean = false,
        /**
         * 调用方要拼在本轮输入末尾的额外说明（第二十四批 C 段追加）。
         *
         * 第一个用处：图片**没发出去**时的逐张原因（`VisionFiles.Loaded.note()`）。
         * 为什么要一路传到这里：说明必须跟在「用户这条消息」后面才有意义，
         * 而 `current` 是每步重算的（工具循环里会变），不能在引擎侧一次性拼死。
         */
        extraNotes: List<String> = emptyList(),
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
        // 会话历史：**跳过** [skipIds]（第十三批 B 段）——被压进摘要的旧消息由摘要
        // 代言，**不是删消息**：原文仍在 SQLite 里，用户点开「已压缩」标记能回看
        // （派工单 §4.2「可追溯 + 不删」）。前缀段（NEVER_COMPRESS）压根不经过这里。
        val visibleHistory = if (skipIds.isEmpty()) history else history.filterNot { it.id in skipIds }
        if (visibleHistory.isNotEmpty()) {
            segments += PromptSegment(SegmentKind.HISTORY, renderHistory(visibleHistory))
        }
        // ── 本轮输入 ＋ 图片（第二十四批 A 段）──
        // 裁图判据全在 VisionRules.select（纯函数）：能力不够就一张不发、超限就截断，
        // 两种情况都会给一句 note。note **拼在本轮输入末尾**而不是单独开一段：
        // 开新段会让 SegmentKind 枚举顺序里多一个位置，压缩与前缀判据都要跟着改；
        // 而这句话本来就是"关于本轮输入"的，跟在后面最自然。
        val vision = VisionRules.select(images, visionSupported)
        val notes = buildList {
            if (vision.note.isNotEmpty()) add(vision.note)
            extraNotes.forEach { if (it.isNotBlank()) add(it) }
        }
        val currentText = if (notes.isEmpty()) {
            current
        } else if (current.isBlank()) {
            notes.joinToString("\n")          // 只有图、没打字（也可能一张没发）
        } else {
            current + "\n" + notes.joinToString("\n")
        }
        if (currentText.isNotBlank()) {
            segments += PromptSegment(SegmentKind.CURRENT, currentText)
        }

        val prompt = AssembledPrompt(
            segments = segments,
            needsCompressing = false,
            windowTokens = windowTokens,
            estimatedTokens = 0,
            recallTruncated = recallTruncated,
            recallNote = recallNote,
            compressedIds = compressedIds,
            images = vision.images,
        )
        val estimated = tokensOf(prompt.render())
        return prompt.copy(
            needsCompressing = needsCompressing(estimated, windowTokens, oldMessageCount),
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
