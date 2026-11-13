// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七十九批 · 结构拆分第八阶段（派工单 `docs/派工单-2026-10-20-第七十九批-RunEngine切片.md`）：
// 从 `RunEngine.kt` 的 runLoop 及周边抽出的**值进值出**判据。房型照同包 `RunRules.kt`：
// internal object、零 Android/Compose/SQLite/网络依赖，JVM 直测。
//
// 抽了五片（派工单 §1 候选清单的落地）：
//   ① Text 轮收尾的 notice 裁决（修复提醒 + 三级覆盖）；
//   ② 网络失败人话化（错误号口径：英文原文只进日志，UI 拿人话）；
//   ③ 停下来问人的两处文案判据（熔断问人 / 坏参数重试）；
//   ④ 两个结果分类小映射（服务端截断标记 / run_log 的 ok 标记）。
//
// ⚠️ 与派工单候选清单的两处偏差（已申报，理由见交付报告 §2）：
//   ① 「停止/继续/收尾」的**流判定**（`if (stopRequested)` 与收尾闸重修条件）留在
//      RunEngine 原地——P1EngineGateTest（第五十四批）把这两行字面钉死在引擎里，
//      本单红线不许改断言；本文件接管的是同一条决策链上的 **notice 裁决**。
//   ② usage 耗时口径（`if (stepStartedAtMs == 0L) -1L …`）未抽——BaopuCostWiringTest
//      的 A2 要求该字面留在 `onUsage?.invoke(` 块内。
package com.example.zhengdao.baopu.agent

/**
 * runLoop 的纯判据外挂（第七十九批）。
 *
 * 纪律同 [RunRules]：只放「输入普通值、输出普通值/数据类」的判定；
 * 不碰 db、Llm、工具执行、Compose、IO——那些仍留在引擎壳里。
 */
internal object RunLoopRules {

    // ── ① Text 轮收尾：notice 裁决 ───────────────────────────────────────

    /**
     * 收尾闸让模型「按自检报告修一轮」时贴的那句 notice（纯值）。
     *
     * 判据来源：RunEngine Text 分支的收尾闸重修臂（改前行 982）。
     * 只有「本轮真写过产物且自检有未过项且还没修满」才会走到这里，
     * 所以 [findingsCount] 恒 ≥ 1；0 是纯函数兜底，不额外分支。
     */
    fun verifyRepairNotice(findingsCount: Int): String =
        "自检发现 $findingsCount 项未过，已让抱朴修一轮"

    /**
     * Text 轮正常收尾时**用户最终看到的那句 notice**（三级覆盖，纯值）。
     *
     * 覆盖顺序（改前源码的顺序，一级压一级，后写的赢）：
     * 1. 截断（第四十批：「看着写完了其实没写完」必须让人看见）；
     * 2. 计划未勾（2026-10-19：不许装成按计划走完了）；
     * 3. 收尾闸未过摘要（[ArtifactVerifier.summarize] 的结果，由调用方算好传入）。
     * 都不命中 ⇒ 返回 null = **不动 notice**（保留本轮此前可能已贴的提示，
     * 例如换通道成功那句——清零只发生在开轮处）。
     *
     * @param findingsCount 收尾闸未过项数（0 = 没有未过项）
     * @param findingsSummary 未过摘要；[findingsCount] > 0 时调用方传非空
     *   （count 才是判据，summary 只是载荷——传 null 会安全降级成「不动」）
     * @param unfinishedPlanCount 计划里还没勾的条数
     * @param truncated 本轮回复是否被服务端截在输出上限
     * @param answerNotice 227 §0.5 的答案自检结果（「这轮有 N 条可疑：…」；null = 无）。
     *   它是**补充**，不改变上面三级覆盖的优先级：已有更高一级的提示时用「；」接在同一行；
     *   三级都不命中时，它就是唯一那句。
     * @param contractRecon 第二百四十二批 D 段的**勘验对账**读数（「计划与执行对不上账：…」；
     *   null = 这一轮没有契约，或对得上——对上时不多说，没契约时这一层完全不存在）。
     *   与 [answerNotice] 同一级：都是补充，不改上面三级的优先级。
     */
    fun textSettleNotice(
        findingsCount: Int,
        findingsSummary: String?,
        unfinishedPlanCount: Int,
        truncated: Boolean,
        answerNotice: String? = null,
        contractRecon: String? = null,
    ): String? {
        val base = when {
            truncated -> "回复被输出上限截断了（服务端说的），点「继续说完」接着写"
            unfinishedPlanCount > 0 -> "计划里还有 $unfinishedPlanCount 条没勾（进度看顶部计划卡）"
            findingsCount > 0 -> findingsSummary
            else -> null
        }
        val tail = listOfNotNull(answerNotice, contractRecon).joinToString("；")
        return when {
            base == null -> tail.ifBlank { null }
            tail.isBlank() -> base
            else -> "$base；$tail"
        }
    }

    // ── ② 失败人话化 ─────────────────────────────────────────────────────

    /**
     * 网络失败人话化（错误号口径：英文原文只进日志，UI 拿人话）。
     *
     * 判据来源：RunEngine 的私有 `humanizeNetFailure`（改前行 1368-1376），
     * 八个分支**逐字搬移**——`when` 的先后就是优先级：一条原文同时含 403 与 429
     * 时按 403 报（先命中先返回）。都不中 ⇒ 兜底那句（原文已记日志）。
     *
     * 为什么值得单测：这是「用户唯一能看懂的那句」，改一个号段 silently 换文案，
     * 行为用例覆盖不到（要真网络），JVM 直测正好补上。
     */
    fun humanizeNetFailure(raw: String): String = when {
        raw.contains("403") -> "该通道拒绝访问（403）：Key 不对、没权限，或这条通道在你的网络里不通——换通道"
        raw.contains("401") -> "该通道不认这把 Key（401）：检查 Key 是否填对，或换通道"
        raw.contains("429") -> "模型端限流（429）：稍等再试，或换一个 provider"
        raw.contains("HTTP 5") -> "模型端服务错误（5xx）：稍等再试"
        raw.contains("截断") -> "响应流中断：该轮作废重发（已花的 token 记在账里）"
        raw.contains("timeout", ignoreCase = true) -> "连接超时：检查网络或换一个 provider"
        else -> "模型调用失败（原文已记日志）"
    }

    // ── ③ 停下来问人的文案 ───────────────────────────────────────────────

    /**
     * 通道熔断、没有候选可切时贴的那句 notice（二选一，纯值）。
     *
     * 判据来源：RunEngine Failed 分支的 BrokenAskUser 臂（改前行 1161-1167）。
     * 同一轮自动换通道的次数到顶（[RunRules.MAX_CHANNEL_SWITCHES]）⇒ 说「换过 N 次
     * 都不通」并带上停在第几步；没到顶 ⇒ 说「连续失败 N 次（已熔断）」。
     * 为什么值得单测：两个分支都带计数，计数取错（如把 channelSwitches 与
     * consecutiveChannelFailures 写反）文案会一本正经地胡说。
     */
    fun channelBrokenNotice(
        channelSwitches: Int,
        consecutiveChannelFailures: Int,
        step: Int,
    ): String =
        if (channelSwitches >= RunRules.MAX_CHANNEL_SWITCHES) {
            "同一轮已自动换过 ${RunRules.MAX_CHANNEL_SWITCHES} 次通道都不通：" +
                "换个通道或稍后再试（已停在第 $step 步）"
        } else {
            "当前通道连续失败 $consecutiveChannelFailures 次（已熔断）：换个通道或稍后再试"
        }

    /**
     * 坏参数（tool_call 参数不是合法 JSON）重试时贴的 notice（纯值）。
     *
     * 判据来源：RunEngine BadArgs 分支（改前行 1127）。调用点在
     * `badArgsRetries++` **之后**——句子里「第 N 次」是本次重试的序号
     * （第一次坏 ⇒ 第 1 次），不是已用掉的机会数。
     */
    fun badArgsRetryNotice(attempt: Int): String =
        "模型这轮给的参数不对，已让它重试（第 $attempt 次）"

    /**
     * 坏参数**到线**时贴的那句 notice（第一百八十八批 §1.6）。
     *
     * 与 [badArgsRetryNotice] 的分界：重试时说的是「已让它重试」；到线时说的是
     * 「不试了，原因在这」。人话里带工具名与原文摘要（哪家/哪字段/服务端原话的
     * 完整版在 run_log.err，见 [RunRules.badArgsStopSummary]）——旧文案
     * 「（错误已记日志）」等于什么都没说，用户不知道该换个什么说法。
     *
     * @param toolName 出错的工具名（空 = 语法层拿不到名字，不说）
     * @param attempts 同一轮第几次仍不合法（1-based）
     * @param brief 原文摘要（≤60 字，调用方截好传进来）
     */
    fun badArgsStopNotice(toolName: String, attempts: Int, brief: String): String {
        val head = "模型连续 $attempts 次给的参数都不合法，这轮停下来"
        val who = if (toolName.isBlank()) "" else "（$toolName：$brief）"
        return head + who + "。换个说法或换个文件再试（不许硬试）"
    }

    // ── ④ 结果分类小映射 ─────────────────────────────────────────────────

    /**
     * 服务端把回复截在输出上限了吗（第四十批：只认 finish_reason，不猜）。
     *
     * 判据来源：RunEngine streamRound 收尾（改前行 1363）。
     * OpenAI 系说 `length`、Anthropic 系说 `max_tokens`；**大小写敏感、不含糊匹配**——
     * 别的 stop reason（`stop`／`tool_calls`／null）都算没截断。
     * 为什么值得单测：这两个字面量任何一个写错，截断检测静默失效，
     * 用户看到的就是「话说一半但没有继续说完按钮」。
     */
    fun isTruncatedStopReason(stopReason: String?): Boolean =
        stopReason == "length" || stopReason == "max_tokens"

    /**
     * 工具交换落 run_log 的 ok 标记（纯值，只看出参文本的前缀）。
     *
     * 判据来源：RunEngine persistToolExchange（改前行 1783）。
     * 「失败：…」与「被拒：…」两种前缀 ⇒ ok=0（运维侧能直接按 ok 过滤失败）；
     * 其余（含空串）⇒ ok=1。判的是**前缀**，不是包含——
     * 正文中间出现「失败」二字不算失败。
     */
    fun toolExchangeOk(wrapped: String): Boolean =
        !wrapped.startsWith("失败") && !wrapped.startsWith("被拒")

    // ── ⑤ 失败线索落 err（第六百三十一批）────────────────────────────────

    /**
     * 工具失败时写进 `run_log.err` 的那**一句**（纯值；成功恒空串）。
     *
     * 病灶：六百二十批（[com.example.zhengdao.baopu.ToolFailureAttribution]）已经按
     * `ok=0` 的 `err` 做归因、反思器（[com.example.zhengdao.baopu.ReflectionRunner]）
     * 已经拿 `err` 首行当失败分类——可 `persistToolExchange` 一直写 `err = ""`，
     * 于是「有线索」的那两侧在生产上永远读到空串：归因恒「未归因」、分类恒空。
     * 本函数就是补上那个源头，**只决定 err 这一个列写什么**，不改任何判定。
     *
     * 三道闸，按序（前三档都不可能带出正文，第四档才是原文，但仍过闸）：
     * 1. **成功不写**：`toolExchangeOk` 判真 ⇒ 空串（负例：成功也写 ⇒ 判据②红）。
     * 2. **606 的 12 键优先**：线索命中 [PeerFailureCode] 闭集里的某一类 ⇒
     *    只写 `<wire>: <该类固定的那句人话>`，正文一个字都不带（结构上不含路径/正文）。
     * 3. **否则首行截断**：只取错误首行，**切在第一个分句边界**（`：`/`:`/`，`/`；`/`。`/`、`）——
     *    本项目错误文案恒为「短因：细节」形，细节才是路径与正文的所在。
     * 4. **抹路径 token**：`C:\…`、`/sdcard/…`、`file://…` 一律抹掉（宁可把「备份/回滚点」
     *    这种斜杠词也当路径抹了，也不放过一条真路径）。
     *
     * 硬上限 [ERR_MAX] 字符：超过就截断加省略号。为什么值得单测：这一列是
     * 「不读代码也能验收」的那本账，写脏了（带路径、带正文、超长）等于把私密内容
     * 抄进运维账；写空等于 620/反思器两处继续空转。
     *
     * @param wrapped 工具出参原文（与 [toolExchangeOk] 同一个入参，成败同一把尺）
     * @return 成功 ⇒ `""`；失败 ⇒ 非空且长度 ≤ [ERR_MAX]
     */
    fun runLogErr(wrapped: String): String {
        if (toolExchangeOk(wrapped)) return ""
        val line = PREFIX_TOKEN.replace(wrapped, "").lineSequence().firstOrNull()?.trim().orEmpty()
        // 620 收口：按共享表的特征词认码，命中就写它的固定短句（正文一字不带）。
        ToolErrCodes.ALL
            .firstOrNull { c -> c.patterns.any { it.containsMatchIn(line) } }
            ?.let { return it.line }
        val cleaned = scrubPathTokens(cutAtClause(line)).trim()
        return if (cleaned.isEmpty()) ERR_NO_CLUE else clipErr(cleaned)
    }

    /** err 列硬上限（字符）。**只能改小**——这是「别把正文抄进账」的最后一道。 */
    const val ERR_MAX: Int = 200

    /** 账上连一个字都读不出来时的固定一句（非空：判据①要求失败必有原因）。 */
    const val ERR_NO_CLUE: String = "工具失败（账上无可读线索）"

    /** 「失败：」「被拒：」前缀（有冒号无冒号都认——[toolExchangeOk] 判的就是前缀二字）。 */
    private val PREFIX_TOKEN = Regex("^(?:失败|被拒)[：:]?")

    /** 分句边界：切在第一个，后面的细节（路径/参数值/正文）整个不要。 */
    private val CLAUSE_BREAK: Set<Char> = setOf('：', ':', '，', ',', '；', ';', '。', '、', '\t')

    /**
     * 路径样式 token：盘符路径 / `file://` / 以 `/` 打头的串。
     *
     * 为什么连 `备份/回滚点` 这种斜杠词也抹：抹错的代价是多读两个字，
     * 漏过的代价是绝对路径明文进了运维账——两害取其轻。
     */
    private val PATH_TOKEN = Regex("""(?:[A-Za-z]:[\\/]|file://|/)[^\s，。；、：（）()【】「」"']{2,}""")

    /**
     * 12 键优先表（键 → 固定的一行；606 的 wire ＋ [com.example.zhengdao.baopu.peer.PeerFailureText]
     * 那套人话的短句）。**特征词宁窄不宽**：认错键会把环境的错记到别处，
     * 认不出就退回第 3 档截断——不硬猜。
     */
    // 第六百二十批收口：码表**不在这里**了，统一从 [ToolErrCodes.ALL] 取（唯一一份）。
    // 631 写侧只负责「按特征词认出码、写它的固定短句」；认不出的走第 3 档截断。

    /** 切在第一个分句边界（`http://` 里的冒号不算边界——那是地址不是分句）。 */
    private fun cutAtClause(line: String): String {
        line.forEachIndexed { i, c ->
            if (c !in CLAUSE_BREAK) return@forEachIndexed
            val inScheme = c == ':' && line.startsWith("//", i + 1)
            if (!inScheme) return line.substring(0, i)
        }
        return line
    }

    private fun scrubPathTokens(text: String): String = PATH_TOKEN.replace(text, "").trim()

    private fun clipErr(text: String): String =
        if (text.length <= ERR_MAX) text else text.take(ERR_MAX - 1) + "…"
}
