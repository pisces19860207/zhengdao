// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
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
     */
    fun textSettleNotice(
        findingsCount: Int,
        findingsSummary: String?,
        unfinishedPlanCount: Int,
        truncated: Boolean,
    ): String? = when {
        truncated -> "回复被输出上限截断了（服务端说的），点「继续说完」接着写"
        unfinishedPlanCount > 0 -> "计划里还有 $unfinishedPlanCount 条没勾（进度看顶部计划卡）"
        findingsCount > 0 -> findingsSummary
        else -> null
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
}
