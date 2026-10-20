// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第九十三批 · 模型路由 v1》§2——「输入：任务所需能力（vision/tools/context/
// thinking）＋偏好（省钱/低延迟/质量）；输出：选中的 provider/model＋理由＋备选链；
// 默认行为不变：不显式开 AUTO_ROUTE 时仍走当前通道；开启后才按能力/成本/延迟排序；
// 没有满足能力的模型 ⇒ 明确报错/提示，不静默降级；回退：复用 64 的切换/能力差异，
// 不新建第二套状态；『当前模型』仍只有一份真相」。
//
// 零 Android 依赖（不碰 Context / SharedPreferences / 数据库）：判据全在这一个文件里 JVM 直测，
// 存储与读 Key 的活留在 ProviderPrefs（它已经把「Context-free 内核 ＋ SP 薄壳」分成两半，
// 本文件比它更干净——连 keyFor 都不接，候选由调用方备好交进来）。
package com.example.zhengdao.baopu

import java.util.Locale

/**
 * 模型路由 v1 的**判据本体**（派工单 §2）。
 *
 * ## 三句话概括
 * 1. **能力过滤**先跑：这条通道做不到这件事，就根本不进排序（做不到还不说 ⇒ 就是「静默降级」）；
 * 2. **排序只在有依据的维度上跑**：成本/延迟/质量任一维在某个候选上是 UNKNOWN ⇒ 那个候选
 *    **不参与这条维度的排序**，进 `unranked` 并写在理由里；不排最后（拿「没数据」当淘汰理由）、
 *    不排最前（拿「没数据」当便宜）；
 * 3. **选不到就明说**：[Plan.NoMatch] 带缺什么、为什么；调用方走既有出口（ask_user / 留在原通道），
 *    本文件不抛、不静默回退、不新建状态。
 *
 * ## 与第六十四批的分工（派工单 §2「回退复用 64，不新建第二套状态」）
 * 本文件只回答**「下一个该选谁」**；**「什么时候切、怎么切、切失败怎么办」**全是 64 的
 * `ChannelSwitchFsm` / `ChannelSwitchCoordinator`（三个安全点、单飞、FALLBACK 回退）。
 * 这里没有任何状态、不写任何存储、不调度任何切换——所以「当前模型」的真相仍然只有一份
 * （`ProviderPrefs` 现值，写只经 `selectChannel`/`save`）。门禁见 `ModelRoutingGateTest`。
 *
 * ## Key 的纪律（派工单 §5 打回第四条「打印密钥」）
 * [Plan] 的 `reason` / `notice()` 只允许出现通道 id、模型名、label、表里的数字。
 * ⚠️ [Provider] 是 data class，`toString()` **会把 apiKey 原文打出来** ⇒ 本文件任何字符串
 * 都不许内插一个 Provider 对象（只取 `.id` / `.model` / `.label`）。用例把哨兵 key 塞进
 * 候选再核对输出，源码门禁再把「内插 Provider」这条路钉死。
 */
internal object ModelRouter {

    // ── 输入：任务所需能力 ＋ 偏好 ────────────────────────────────────────

    /**
     * 这一件事需要什么能力（**没有一项是"猜出来的"**：调用方不知道就别传，缺省 false/null =
     * 「这项没要求」，与「要求不满足」是两码事）。
     *
     * @param vision 本轮要发图（第二十四批：能不能看图只看 `Provider.vision`）
     * @param tools 本轮要带原生 `tools` 数组（协议 A）
     * @param contextTokens 这一步至少要塞进窗口的 token 数；null = 没算过
     * @param thinking 想要的思考档位（`ModelParams` 的界面四档之一）；null = 不挑
     */
    data class Need(
        val vision: Boolean = false,
        val tools: Boolean = false,
        val contextTokens: Int? = null,
        val thinking: String? = null,
    )

    /** 偏好三档（派工单 §2「省钱/低延迟/质量」）。 */
    enum class Preference { CHEAP, FAST, QUALITY }

    /** SP 里存的是小写英文 token（与 ToolGroup.key 同思路：不存枚举 ordinal，枚举顺序将来会变）。 */
    fun keyOf(p: Preference): String = p.name.lowercase(Locale.ROOT)

    /** 存的偏好字符串 → 枚举；脏值/空/没存过 ⇒ null（**不猜默认偏好**——派工单「不乱猜」）。 */
    fun preferenceOf(raw: String?): Preference? {
        val v = raw?.trim()?.lowercase(Locale.ROOT)?.ifEmpty { return null } ?: return null
        return Preference.entries.firstOrNull { keyOf(it) == v }
    }

    // ── 候选：Provider 真相 ＋ 表里那几列 ─────────────────────────────────

    /**
     * 一条候选的完整视图。
     *
     * `vision` / `windowTokens` **读的是 [Provider]**（唯一真相，[ModelCapabilityTable] 里没有
     * 这两列）；`row` 只补 Provider 表达不了的列。窗口的「没声明怎么算」也复用既有那一处
     * [ContextAssembler.ContextBudgets.windowOf]，路由不自带第二套回退。
     */
    data class Candidate(val provider: Provider, val row: ModelCapabilityTable.Row) {
        val vision: Boolean get() = provider.vision
        val windowTokens: Int get() = ContextAssembler.ContextBudgets.windowOf(provider)

        /** 成本维能不能参与排序（表里三件齐才行）。 */
        val costKnown: Boolean get() = row.costKnown

        /** 每 1k token 合计价（输入＋输出，同币种）；无依据 ⇒ null。 */
        val pricePer1k: Double?
            get() = if (!costKnown) null else row.inputPer1k!! + row.outputPer1k!!
    }

    /**
     * 把一个 [Provider] 配上它那行能力数据（**纯函数**；查不到行 ⇒ 全 UNKNOWN 的空行）。
     * 能力行只从 [ModelCapabilityTable] 来，vision/窗口只从 [Provider] 来 —— 两处的分工见那张表的 KDoc。
     */
    fun candidateOf(p: Provider): Candidate =
        Candidate(p, ModelCapabilityTable.rowOrUnknown(p.id, p.model))

    // ── 能力过滤 ─────────────────────────────────────────────────────────

    /**
     * 缺什么（返回人类可读的缺项清单；**空表 = 满足**）。
     *
     * 三种"做不到"要分开说，混成一句就是编：
     * - 「不支持」＝有依据地说 NO；
     * - 「无依据」＝表里 UNKNOWN，没查过 ⇒ 同样不许选（宁可不选，不乱猜）；
     * - 「档位清单无依据」＝知道它会思考，但不知道它认哪几档 ⇒ 挑特定档位时不算满足。
     */
    fun missingOf(c: Candidate, need: Need): List<String> {
        val out = mutableListOf<String>()
        if (need.vision && !c.vision) {
            out += "看图（${c.provider.id}/${c.provider.model} 的 Provider.vision=false）"
        }
        if (need.tools && c.row.toolSchema != ModelCapabilityTable.Support.YES) {
            out += "工具 schema（${explain(c.row.toolSchema)}）"
        }
        need.contextTokens?.let { want ->
            if (c.windowTokens < want) {
                out += "上下文窗口（要 $want，这条只有 ${c.windowTokens}）"
            }
        }
        need.thinking?.let { tier ->
            when {
                c.row.thinking == ModelCapabilityTable.Support.NO ->
                    out += "思考档位 $tier（这条通道不认思考字段：${c.row.channelId}）"

                c.row.thinking == ModelCapabilityTable.Support.UNKNOWN ->
                    out += "思考档位 $tier（${explain(c.row.thinking)}）"

                c.row.thinkingTiers.isEmpty() ->
                    out += "思考档位 $tier（知道它会思考，但档位清单没有依据 ⇒ 不猜）"

                tier !in c.row.thinkingTiers ->
                    out += "思考档位 $tier（清单只有 ${c.row.thinkingTiers.joinToString("/")}）"
            }
        }
        return out
    }

    fun satisfies(c: Candidate, need: Need): Boolean = missingOf(c, need).isEmpty()

    private fun explain(s: ModelCapabilityTable.Support): String = when (s) {
        ModelCapabilityTable.Support.UNKNOWN -> "没有依据，不猜"
        ModelCapabilityTable.Support.NO -> "有依据地不支持"
        ModelCapabilityTable.Support.YES -> "有依据地支持"
    }

    // ── 排序（只在有依据的维度上比） ──────────────────────────────────────

    /**
     * 按偏好排序 ⇒ (排好序的, 不参与排序的)。
     *
     * 「不参与排序」不是被淘汰：调用方拿到的 [Plan.Routed.unranked] 会带着原因出现在理由里，
     * 用户看得见「这三条没报价格，所以没参与比较」。
     */
    fun rank(eligible: List<Candidate>, preference: Preference): Pair<List<Candidate>, List<Candidate>> {
        val withBasis: List<Candidate>
        val withoutBasis: List<Candidate>
        when (preference) {
            Preference.CHEAP -> {
                withBasis = eligible.filter { it.costKnown }
                withoutBasis = eligible.filterNot { it.costKnown }
            }

            Preference.FAST -> {
                withBasis = eligible.filter { it.row.latencyKnown }
                withoutBasis = eligible.filterNot { it.row.latencyKnown }
            }

            Preference.QUALITY -> {
                withBasis = eligible.filter { it.row.qualityKnown }
                withoutBasis = eligible.filterNot { it.row.qualityKnown }
            }
        }
        val sorted = withBasis.sortedWith(comparatorFor(preference))
        // 同分（比较键相等）时按候选序（＝调用方给的序，ProviderPrefs 里就是 Providers.all() 序）落定，
        // 不引入「名字字典序」这种看起来像依据其实不是的东西。
        return sorted to withoutBasis
    }

    /**
     * 各偏好的比较器（越靠前越好；**每一档只比它自己有依据的那一列**）。
     *
     * 刻意不带"次级排序键"：把 P90 缺失当 0、把没测到延迟当最慢，都是拿「没数据」冒充依据
     * （派工单 §3「UNKNOWN 不参与排序、不乱猜」）。同分就落回候选序 ——
     * [rank] 用的是稳定排序，所以同价时谁在前就是调用方名单里谁在前，不引入字典序这种假依据。
     */
    private fun comparatorFor(preference: Preference): Comparator<Candidate> = when (preference) {
        Preference.CHEAP -> compareBy { it.pricePer1k }
        Preference.FAST -> compareBy { it.row.latencyP50Ms }
        Preference.QUALITY -> compareBy { it.row.qualityTier }
    }

    // ── 总入口 ───────────────────────────────────────────────────────────

    /** 一次路由的结果（派工单 §2「输出：选中的 provider/model＋理由＋备选链」）。 */
    sealed interface Plan {

        /** 给人看的一句话（只含 id/model/label 与表里的数字，**永不含 Key**）。 */
        fun notice(): String

        /** AUTO_ROUTE 没开 ⇒ 走当前通道，一个字节都不变（派工单 §2「默认行为不变」）。 */
        data class Passthrough(val current: Provider, val reason: String) : Plan {
            override fun notice(): String = reason
        }

        /**
         * 选中了。
         *
         * @param alternates 备选链（排序后的剩余，**序即建议序**；真正切过去还是走 64）
         * @param unranked 因数据未知而没参与比较的候选（理由里必须说清）
         */
        data class Routed(
            val picked: Provider,
            val reason: String,
            val alternates: List<Provider>,
            val unranked: List<Provider>,
        ) : Plan {
            override fun notice(): String = reason
        }

        /**
         * 没有满足能力的模型 / 排序维度全无依据 ⇒ **明确报错**（派工单 §2「不静默降级」）。
         *
         * @param missing 缺项清单（空表时它是"为什么排不出来"的一句话）
         */
        data class NoMatch(val reason: String, val missing: List<String>) : Plan {
            override fun notice(): String =
                if (missing.isEmpty()) reason else "$reason（缺：${missing.joinToString("；")}）"
        }
    }

    /**
     * 选谁（**纯函数**，不读写任何存储、不碰状态机）。
     *
     * @param autoEnabled AUTO_ROUTE 是否被**显式**打开；false ⇒ 结果恒等于当前通道
     * @param preference 排序偏好；auto 开着但偏好没选 ⇒ [Plan.NoMatch]（不许替用户猜一个偏好）
     * @param need 这一件事需要的能力
     * @param current 当前通道（用于 [Plan.Passthrough] 与理由）
     * @param candidates 候选（调用方备好：谁有 Key、排除谁，是 ProviderPrefs 那一处的规矩，
     *   本文件不自己找候选，免得跟 64 的 `fallbackFrom` 长成两套规则）
     * @param rowFor 取能力行的接缝，默认＝查 [ModelCapabilityTable]（**表是唯一数据源**）。
     *   它是判据参数不是入口：生产调用方（`ProviderPrefs.fallbackFrom`）一个都不传，
     *   只有单测用它塞夹具行——本仓全表成本/延迟/质量都是 UNKNOWN，
     *   「排得出序」这条路只能靠夹具证明判据本身对，生产数据今天就该走 [Plan.NoMatch]。
     */
    fun plan(
        autoEnabled: Boolean,
        preference: Preference?,
        need: Need,
        current: Provider,
        candidates: List<Provider>,
        rowFor: (Provider) -> ModelCapabilityTable.Row = { candidateOf(it).row },
    ): Plan {
        if (!autoEnabled) {
            return Plan.Passthrough(
                current,
                "AUTO_ROUTE 未开启：仍走当前通道 ${current.id}/${current.model}",
            )
        }
        if (preference == null) {
            return Plan.NoMatch(
                "AUTO_ROUTE 已开启但没有选偏好（省钱/低延迟/质量三选一）⇒ 不猜，交回调用方",
                emptyList(),
            )
        }
        if (candidates.isEmpty()) {
            return Plan.NoMatch("没有可选通道（候选为空：该填的 Key 没填，或被排除规则排掉了）", emptyList())
        }
        val all = candidates.map { Candidate(it, rowFor(it)) }
        val eligible = all.filter { satisfies(it, need) }
        val missing = if (eligible.isEmpty()) all.flatMap { missingOf(it, need) }.distinct() else emptyList()
        if (eligible.isEmpty()) {
            return Plan.NoMatch("没有满足这次能力要求的模型：${all.size} 条候选全部不合格", missing)
        }
        val (ranked, unranked) = rank(eligible, preference)
        if (ranked.isEmpty()) {
            // 能力都满足，但这条偏好要的那一维**全表无依据** ⇒ 一样不硬选（派工单：不乱猜）。
            return Plan.NoMatch(
                "候选都满足能力，但「${cnName(preference)}」这一维没有任何数据（成本/延迟/质量全表 UNKNOWN）" +
                    "⇒ 排不出序，不硬选",
                listOf("${cnName(preference)}数据缺失：${unranked.size} 条候选全部 UNKNOWN"),
            )
        }
        val head = ranked.first()
        val chain = ranked.drop(1)
        return Plan.Routed(
            picked = head.provider,
            reason = "按${cnName(preference)}排序选中 ${head.provider.id}/${head.provider.model}" +
                "（${head.row.render()}）" +
                (if (chain.isEmpty()) "" else "；备选链：${chain.joinToString(" → ") { "${it.provider.id}/${it.provider.model}" }}") +
                (if (unranked.isEmpty()) "" else "；未参与排序（${cnName(preference)}无依据）：" +
                    unranked.joinToString("、") { "${it.provider.id}/${it.provider.model}" }) +
                "；当前通道：${current.id}/${current.model}",
            alternates = chain.map { it.provider },
            unranked = unranked.map { it.provider },
        )
    }

    /** 偏好的中文名（进理由与后续 UI；只有这三个词，没有第四种）。 */
    fun cnName(preference: Preference): String = when (preference) {
        Preference.CHEAP -> "成本（每 1k token 合计价）"
        Preference.FAST -> "延迟（P50）"
        Preference.QUALITY -> "质量档"
    }
}
