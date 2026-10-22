// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第九十三批 · 模型路由 v1》§1——「每个 provider/model 一行：vision 有无；
// 上下文窗口；工具 schema 支持；思考档位；成本：输入/输出每 1k token（拿不到就标 UNKNOWN，
// 不猜）；延迟：最近 P50/P90（没有就 UNKNOWN）；表放代码/资源，单一来源，不散落在 UI」。
//
// 派工单的两条硬口径在本文件里的落法：
// - 「单一来源」⇒ 只装 `Provider` **没有**的那几列（见下面「刻意不装的两列」），
//   能看图与上下文窗口继续只住在 `Provider` 里；
// - 「拿不到就标 UNKNOWN，不猜」⇒ 成本／延迟／质量档**全表为空**，每行为什么空写在
//   `evidence` 里（报告 §二 逐行对账）。抄一份公开价目表进来就是「没有依据的依据」，本批不干。
package com.example.zhengdao.baopu

/**
 * 模型能力 / 成本 / 延迟表（第九十三批 · 路由判据的**唯一数据源**）。
 *
 * ## 刻意**不装**的两列（第二真相红线，派工单 §2「当前模型仍只有一份真相」）
 * - **能不能看图（vision）**：唯一真相是 [Provider.vision]。第二十四批 V5 已经立死
 *   「组装层不许自己猜模型名，只读这个字段」，本表再存一份 `vision` 就是第二个真相，
 *   而且两份一定会漂（一张表改了、字段没改）。
 * - **上下文窗口 / 输出上限**：唯一真相是 [Provider.contextWindow] / [Provider.maxOutput]，
 *   拿不到的回退只在 `ContextAssembler.ContextBudgets.windowOf` 那一处。
 *
 * ⇒ `ModelRoutingGateTest` 用源码门禁钉死这条边界：本文件出现 `vision` / `contextWindow`
 *   字段就是红（KDoc 里提它们是纪律声明，不算）。
 *
 * ## 表里装的是 `Provider` 表达不了的东西
 * 工具 schema 支持、思考档位清单、每 1k token 成本、P50/P90 延迟、质量档——
 * 这些都不是请求体字段，是「该选谁」的输入。
 *
 * ## UNKNOWN 的口径（派工单 §3「不乱猜」）
 * [Support.UNKNOWN] / `null` 一律表示**没有依据**，不是「不支持」、不是 0。
 * 消费方（[ModelRouter]）对 UNKNOWN 的处理是**不参与排序**，而不是排到最后或排在最前：
 * 排到最后＝用「没数据」当理由淘汰一条通道；排在最前＝拿「没数据」当便宜——都编不出来。
 */
internal object ModelCapabilityTable {

    /** 三态判据：有依据的支持、有依据的不支持、**没依据**。 */
    enum class Support { YES, NO, UNKNOWN }

    /** 「这一行代表该通道的任意模型」的哨兵（自定义通道只有这一种行）。 */
    const val ANY_MODEL: String = "*"

    /**
     * 一个 provider/model 一行。
     *
     * @param toolSchema 协议 A 的原生 `tools` 数组这台模型吃不吃（**不是**「本 App 发没发」）
     * @param thinking 模型侧思考能力有没有依据；档位清单见 [thinkingTiers]
     * @param thinkingTiers 该模型**声明过**的思考档位；空表 = 清单没有依据
     * @param inputPer1k 每 1k 输入 token 单价；null = UNKNOWN（不抄公开价目表）
     * @param outputPer1k 每 1k 输出 token 单价；null = UNKNOWN
     * @param currency 单价币种；成本未知时同样 null（有价没币种等于没价）
     * @param latencyP50Ms 最近 P50 端到端耗时；null = UNKNOWN
     * @param latencyP90Ms 最近 P90；null = UNKNOWN
     * @param qualityTier 质量档（1 最好）；null = UNKNOWN——「模型名看着强」不算依据
     * @param evidence 这一行每一格的依据与「为什么那几格是 UNKNOWN」
     */
    data class Row(
        val channelId: String,
        val model: String,
        val toolSchema: Support,
        val thinking: Support,
        val thinkingTiers: List<String>,
        val inputPer1k: Double?,
        val outputPer1k: Double?,
        val currency: String?,
        val latencyP50Ms: Long?,
        val latencyP90Ms: Long?,
        val qualityTier: Int?,
        val evidence: String,
    ) {
        /** 有价可排：输入＋输出＋币种三件齐（缺一件就不参与成本排序）。 */
        val costKnown: Boolean
            get() = inputPer1k != null && outputPer1k != null && !currency.isNullOrBlank()

        /** 有延迟可排：至少要有 P50（只有 P90 排不出「通常多快」）。 */
        val latencyKnown: Boolean get() = latencyP50Ms != null

        /** 有质量档可排。 */
        val qualityKnown: Boolean get() = qualityTier != null

        /** 人读一行（日志/后续 UI 通吃）；**不含 Key**——本表从来就不存 Key。 */
        fun render(): String = "$channelId/$model 工具=${toolSchema.name} 思考=${thinking.name}" +
            "(${thinkingTiers.joinToString("/")}) 成本=${priceText()} 延迟=${latencyText()}" +
            " 质量=${qualityTier ?: "UNKNOWN"}"

        private fun priceText(): String =
            if (!costKnown) "UNKNOWN" else "$inputPer1k/$outputPer1k per 1k $currency"

        private fun latencyText(): String = when {
            !latencyKnown -> "UNKNOWN"
            latencyP90Ms == null -> "P50=${latencyP50Ms}ms P90=UNKNOWN"
            else -> "P50=${latencyP50Ms}ms P90=${latencyP90Ms}ms"
        }
    }

    /** 查不到任何依据时的空行（全 UNKNOWN；调用方据此「不参与排序」而不是当 0）。 */
    fun unknownRow(channelId: String, model: String): Row = Row(
        channelId = channelId,
        model = model,
        toolSchema = Support.UNKNOWN,
        thinking = Support.UNKNOWN,
        thinkingTiers = emptyList(),
        inputPer1k = null,
        outputPer1k = null,
        currency = null,
        latencyP50Ms = null,
        latencyP90Ms = null,
        qualityTier = null,
        evidence = "表里没有这一行 ⇒ 全列没依据（不猜）",
    )

    // ── 表体 ─────────────────────────────────────────────────────────────
    //
    // 序＝通道 id ＋ 模型；(channel, ANY_MODEL) 是该通道的兜底行。
    // 每条 evidence 里「① 实测／② 元数据或台账／③ 仓库口径未实测」是仓库既有证据等级口径
    // （见 Provider.kt 头部与 docs/FEATURE-LEDGER.md），② 明确不等于线上一定工作。

    val ROWS: List<Row> = listOf(
        Row(
            channelId = "deepseek",
            model = "deepseek-flash",
            toolSchema = Support.YES,
            thinking = Support.YES,
            thinkingTiers = listOf("low", "high", "max"),
            inputPer1k = null,
            outputPer1k = null,
            currency = null,
            latencyP50Ms = null,
            latencyP90Ms = null,
            qualityTier = null,
            evidence = "工具①：协议 A 原生 function calling 真机跑通（docs/FEATURE-LEDGER.md 里 " +
                "48 批 ask_user、D-03 edit_file 两条真机验收都靠它；当期主力通道＝DeepSeek 官方，" +
                "builtin-free 整条 403 RegionError）。思考①：`GET /models` 的 " +
                "effort.supported_levels=[low,high,max]＋未传默认 high（2026-10-17 蓝总管探针 " +
                "D:\\zd-tools\\ds-probe.txt / ds-effort-probe2.txt；服务端枚举另有 8 档，" +
                "档位清单只登记模型自己声明的那三档）。成本/延迟/质量：全仓无价格台账、" +
                "无按通道聚合的延迟读数 ⇒ UNKNOWN。",
        ),
        Row(
            channelId = "openai-compat",
            model = "gpt-4o-mini",
            toolSchema = Support.UNKNOWN,
            thinking = Support.NO,
            thinkingTiers = emptyList(),
            inputPer1k = null,
            outputPer1k = null,
            currency = null,
            latencyP50Ms = null,
            latencyP90Ms = null,
            qualityTier = null,
            evidence = "思考=NO③：依据是仓库口径（ModelParams / LlmWire KDoc：这条通道不认 " +
                "reasoning_effort，瞎发会 400，所以预设从不声明该字段），**本仓未实测**。" +
                "工具 schema：本仓没在这条端点上发过 tools（无 key、无真机）⇒ UNKNOWN，" +
                "不拿 OpenAI 公开文档冒充实测。成本/延迟/质量：无取证 ⇒ UNKNOWN。",
        ),
        Row(
            channelId = "anthropic",
            model = "claude-sonnet-4-5",
            toolSchema = Support.UNKNOWN,
            thinking = Support.UNKNOWN,
            thinkingTiers = emptyList(),
            inputPer1k = null,
            outputPer1k = null,
            currency = null,
            latencyP50Ms = null,
            latencyP90Ms = null,
            qualityTier = null,
            evidence = "形状差异=③仓库口径（LlmWire KDoc：Anthropic 的思考是 `thinking` 参数，" +
                "不是 reasoning_effort 字段；它的预设从不声明 effort）。档位清单与工具 schema：" +
                "本仓无 key 无实测 ⇒ UNKNOWN（请求体形状已实现≠这台模型已验通）。" +
                "成本/延迟/质量：无取证 ⇒ UNKNOWN。",
        ),
        Row(
            channelId = ProviderPrefs.CHANNEL_CUSTOM,
            model = ANY_MODEL,
            toolSchema = Support.UNKNOWN,
            thinking = Support.UNKNOWN,
            thinkingTiers = emptyList(),
            inputPer1k = null,
            outputPer1k = null,
            currency = null,
            latencyP50Ms = null,
            latencyP90Ms = null,
            qualityTier = null,
            evidence = "用户自填 baseUrl/model，仓库零依据 ⇒ 全列 UNKNOWN。这是有意的：给一条不认识的" +
                "端点猜「它应该支持工具、应该便宜」，路由就会把用户的活派给一个未知模型。" +
                "打开条件：真机跑一轮带 tools 的请求并留 usage 读数，或用户自己填数（后续批）。",
        ),
    )

    /**
     * 查行（**纯函数**）：先精确 (channel, model)，再退到该通道的 [ANY_MODEL] 兜底行，
     * 都没有 ⇒ null（调用方用 [unknownRow]，全列没依据）。
     */
    fun rowOf(channelId: String, model: String): Row? =
        ROWS.firstOrNull { it.channelId == channelId && it.model == model }
            ?: ROWS.firstOrNull { it.channelId == channelId && it.model == ANY_MODEL }

    /** 查不到就给了空行——路由拿到的永远是「有形状、可能全 UNKNOWN」的一行，不是 null。 */
    fun rowOrUnknown(channelId: String, model: String): Row =
        rowOf(channelId, model) ?: unknownRow(channelId, model)

    /** 表是否自证：同一 (channel, model) 不许出现两行（两行就是两份真相，必漂）。 */
    fun duplicateKeys(): List<String> =
        ROWS.groupBy { "${it.channelId}/${it.model}" }.filterValues { it.size > 1 }.keys.sorted()
}
