// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴-方案-2026-10-16.md §9b（老对话压成结构化摘要；压缩前不删原文；
// 压缩点可见不静默；摘要用便宜模型）+ 派工单第八批（压缩不许动前缀；
// 压掉了哪几条要留在记录里，不许无声丢历史）。
package com.example.zhengdao.baopu

/**
 * 压缩记录（判据 5：压缩不丢痕）。
 *
 * [compressedIds] 是「哪几条被压掉了」的账——原文仍在 SQLite（L1 会话层），
 * 摘要只是上下文里的替身；用户点开压缩标记能回看原文。
 *
 * [channel] / [degraded] / [degradeReason]（追加二第 4 条）：本次压缩用了哪个
 * 通道、是否降级、为什么——**用户知情**是降级的前提，降级不写理由等于偷换。
 */
data class CompressionRecord(
    val summary: String,
    val compressedIds: List<String>,
    val createdAt: Long,
    val model: String,
    val channel: String = Compressor.DEFAULT_CHANNEL_ID,
    val degraded: Boolean = false,
    val degradeReason: String = "",
)

/** 压缩通道选择结果（纯数据；降级必须带理由）。 */
data class ChannelChoice(
    val provider: Provider,
    val degraded: Boolean,
    val reason: String,
)

/**
 * 上下文压缩器（刀 0b · 2026-10-16）。
 *
 * **提示词构造与结果解析是纯函数**（可 JVM 单测）；真调用走 [LlmClient]
 * （薄壳一层，刀 1 接线；摘要建议走免费通道，不占主对话的钱）。
 *
 * 结构化摘要字段（方案 §9b 定版，不是散文）：目标 / 已定的决定 / 已排除的路 /
 * 未决 / 涉及的文件路径。
 */
internal object Compressor {

    /** 摘要字段（顺序即提示词里的列举顺序）。 */
    val SUMMARY_FIELDS = listOf("目标", "已定的决定", "已排除的路", "未决", "涉及的文件路径")

    /** 最近 N 条原文不压（方案 §9b「必须留住的四样」之一）。 */
    const val KEEP_RECENT = 4

    /** 默认压缩车道 id（免费车道；追加二第 4 条 + 第一百三十一批 ① 换道）。 */
    val DEFAULT_CHANNEL_ID: String get() = Providers.OPENROUTER_FREE.id

    /** 压缩专用系统提示（便宜模型也能照做的短提示）。 */
    fun systemPrompt(): String = (
        "你是对话压缩器。把下面这段旧对话压成结构化摘要，只输出这些字段：\n" +
            SUMMARY_FIELDS.joinToString(" / ") { it } + "。\n" +
            "规则：每行一个字段，格式「字段：内容」；没有内容的字段写「无」；\n" +
            "不许编造对话里没有的信息；保留具体路径、模型名、已定的口径原样。"
        )

    /**
     * 压缩模型选择（追加二第 4 条 · 纯函数）。
     *
     * 默认走 **免费车道**（方案 §9b 第 5 条：摘要用便宜模型，不占主对话的钱）；
     * 第一百三十一批 ① 之后这条车道是 OpenRouter 免费档——**它要用户自己的 Key**，
     * 没填 Key 或额度打满就算「不可用」。
     * 不可用 ⇒ **降级到当前会话的 provider**，且 [ChannelChoice.degraded]
     * 置位、[ChannelChoice.reason] 写清为什么——降级必须让用户知情。
     *
     * 「可用与否」是运行时判定（连通性/额度），由引擎探针喂进来
     * （[freeAvailable]）；没有信息时按可用处理（默认即免费）。
     */
    fun chooseChannel(sessionProvider: Provider, freeAvailable: Boolean = true): ChannelChoice {
        val free = Providers.OPENROUTER_FREE
        if (freeAvailable) {
            return ChannelChoice(provider = free, degraded = false, reason = "")
        }
        return ChannelChoice(
            provider = sessionProvider,
            degraded = true,
            reason = "免费模型当前不可用（没填 Key 或额度已用完），" +
                "本次压缩改用当前会话的模型 ${sessionProvider.model}（${sessionProvider.label}）",
        )
    }

    /** 模型留痕一行（进压缩记录给用户看：用了谁、降没降、为什么）。 */
    fun channelNote(choice: ChannelChoice): String = if (choice.degraded) {
        "压缩模型：${choice.provider.label}（降级）——${choice.reason}"
    } else {
        "压缩模型：${choice.provider.label}（默认免费模型）"
    }

    /**
     * 构造压缩请求（纯函数）。
     *
     * @param toCompress 待压消息（已由 [selectCompressionRange] 选区：中段与旧段）
     * @param provider 压缩通道（[chooseChannel] 的产出；默认免费通道）
     */
    fun buildRequest(toCompress: List<HistoryMessage>, provider: Provider): List<LlmMessage> {
        val body = buildString {
            append("待压缩的对话（").append(toCompress.size).append(" 条）：\n")
            for (m in toCompress) {
                val who = if (m.role == HistoryMessage.ROLE_USER) "用户" else "抱朴"
                append("【").append(who).append("】").append(m.text).append('\n')
            }
        }
        return listOf(
            LlmMessage(LlmMessage.ROLE_SYSTEM, systemPrompt()),
            LlmMessage(LlmMessage.ROLE_USER, body.toString()),
        )
    }

    /**
     * 解析摘要响应（纯函数，**容忍坏格式**：缺字段不抛，按「无」处理；
     * 完全不像字段表的，整段当「目标」下面的原文——压坏了也要能看见）。
     */
    fun parseSummary(response: String, compressedIds: List<String>, createdAt: Long, model: String): CompressionRecord {
        val fields = LinkedHashMap<String, String>()
        var lastKey: String? = null
        for (raw in response.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val idx = line.indexOf('：').let { if (it >= 0) it else line.indexOf(':') }
            val key = if (idx > 0) line.substring(0, idx).trim() else null
            if (key != null && key in SUMMARY_FIELDS) {
                fields[key] = line.substring(idx + 1).trim()
                lastKey = key
            } else if (lastKey != null) {
                // 续行：拼回上一个字段（模型爱把一条决定拆多行）
                fields[lastKey] = fields[lastKey].orEmpty() + "\n" + line
            } else {
                fields["目标"] = (fields["目标"].orEmpty() + "\n" + line).trim()
                lastKey = "目标"
            }
        }
        val summary = SUMMARY_FIELDS.joinToString("\n") { k -> "$k：${fields[k] ?: "无"}" }
        return CompressionRecord(
            summary = summary,
            compressedIds = compressedIds,
            createdAt = createdAt,
            model = model,
        )
    }

    /**
     * 选区：只压**中段与旧段**（[ContextAssembler.selectCompressionRange] 的判据
     * 在这里复用）；人设与记忆段由 [AssembledPrompt.prefixText] 兜住，永远进不来。
     */
    fun selectCompressionRange(history: List<HistoryMessage>, keepRecent: Int = KEEP_RECENT): List<HistoryMessage> =
        ContextAssembler.selectCompressionRange(history, keepRecent)

    /**
     * 摘要增量合并（第十六批第二件 · **乙案** · 纯函数）。
     *
     * 口径（派工单 §2B 第 4 条）：不把旧摘要拼进请求（请求体只含本轮新段——
     * 与改前的请求构造逐字节同形，J11 靠这条），产出后按「旧 + 新」顺序拼接；
     * 拼接天然保住旧摘要的实质内容（J10）。总长度受 [maxTokens]（即压缩的
     * 1024 输出上限）约束：超限先丢**最旧的段**并记日志「摘要超限，丢弃最旧段」；
     * 只剩一段时不再丢（丢光等于白压，最后一段必须留着）。
     *
     * 选乙案不选甲案的理由（交付报告同步）：甲案要改请求构造（旧摘要＋新对话一起
     * 发给模型合并），输出质量略好，但请求体从此与改前不同形，J11（老调用字节
     * 不变）得靠参数开关绕；乙案请求构造**零改动**，代价是摘要从「一张字段表」
     * 变成「两张字段表叠放」——[parseSummary] 对叠放表同样宽容，消费侧（模型）
     * 拿到的仍是完整字段文本。
     */
    fun mergeSummaries(
        previous: String?,
        fresh: String,
        maxTokens: Int,
        tokensOf: (String) -> Int = { TokenEstimator.estimate(it) },
        log: (String) -> Unit = {},
    ): String {
        if (previous.isNullOrBlank()) return fresh
        var segs = listOf(previous, fresh) // 旧在前、新在后
        var merged = segs.joinToString("\n\n")
        while (segs.size > 1 && tokensOf(merged) > maxTokens) {
            segs = segs.drop(1) // 超限先丢最旧的段
            merged = segs.joinToString("\n\n")
            log("抱朴压缩：摘要超限，丢弃最旧段（剩 ${segs.size} 段）")
        }
        return merged
    }

    /** 哪些段永不被压缩（追加判据 3：A/B/C 人设与记忆段永远不进压缩范围）。 */
    fun neverCompressible(): Set<SegmentKind> = setOf(
        SegmentKind.PERSONA_A, SegmentKind.PERSONA_B, SegmentKind.PERSONA_C,
        SegmentKind.HARD_FACTS, SegmentKind.MEMORY,
    )
}
