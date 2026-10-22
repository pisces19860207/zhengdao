// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第八十七批 · 运行时「好」R2：**统一失败策略**（§B 91 §2）。
//
// 病灶（本批要治的）：失败处置散在四个地方各写各的——
//   `RoundOutcome.Failed` 走熔断三件套、坏参数走 `shouldRetryBadArgs`、
//   工具失败走 `shouldAskUser`、取消/停止直接 return 一句都不留痕。
// 同一个「这次该怎么办」被回答四遍，任何一处调参都要回去改四处。
//
// 本文件把它收成**一张表 ＋ 一个纯函数**：输入（分类、第几次失败、有没有候选）
// → 输出（重试 / 换通道 / 问人 / 中止）。零 Android、零 IO、零时间，JVM 直测。
//
// ⚠️ 纪律：这里**不新建第二套状态机**——引擎仍然只有 `runLoop` 一条循环，
// 本表只回答「这一个失败该走哪条既有出口」，物理动作全在 RunEngine 里现成的地方。
package com.example.zhengdao.baopu.agent

/**
 * 失败分类（派工单 §2 的六类，一一对应，不多不少）。
 *
 * 为什么分这么细：**「重试有没有用」完全不同**。
 * - [NETWORK]：超时 / 断流 / 5xx / 429 —— 同一条通道再试**可能**好，值得有界重试；
 * - [MODEL_CHANNEL]：401 / 403 / 地区限制 —— 这把 Key 就是不认，重试一百次也一样，
 *   只能换通道或让人来换；
 * - [TOOL_TIMEOUT]：工具自身超时（`GuardPipeline` 的 `future.get(timeout)`）——
 *   与通道无关，**不许记通道失败**（第五十四批 P1-2 的同一条教训）；
 * - [TOOL_ERROR]：工具执行报错 —— 交还模型自己看着办（回灌错误原文）；
 * - [BAD_JSON]：模型给的 tool_call 参数不是合法 JSON —— 回灌原文 ＋ 给一次机会；
 * - [CANCELLED]：用户点了停止 —— **永不重试**（重试等于跟用户对着干）。
 */
internal enum class FailureKind { NETWORK, MODEL_CHANNEL, TOOL_TIMEOUT, TOOL_ERROR, BAD_JSON, CANCELLED }

/**
 * 失败决策（四选一，派工单 §2 原文的四个动词）。
 *
 * [Retry] 与 [Abort] 的区别就在一个 `delayMs`：有界重试要退避，别的都不等。
 * [AskUser] 与 [Abort] 在**当前引擎**里收敛到同一段收尾动作（settle ABORTED ＋ AskUser 事件），
 * 区别是语义标签——「需要人来拍板」（换通道/换说法）还是「这一轮没法继续」。
 * 这层区分留在 trace 的 `decision` 列里，回放的人一眼能看出当时判的是哪一种。
 */
internal sealed interface FailureAction {
    /** 重试同一步：`attempt` = 这是第几次失败（1-based），`delayMs` 是退避时长。 */
    data class Retry(val delayMs: Long, val attempt: Int) : FailureAction

    /** 换通道 / 换 Key（复用第六十四批的通道切换；候选由装配层给，策略不认 Key）。 */
    data object Fallback : FailureAction

    /** 停下问人（换通道 / 换说法 / 修 Key）。 */
    data object AskUser : FailureAction

    /** 中止这一轮（不重试、不换通道）。 */
    data object Abort : FailureAction
}

/** 各类失败的默认重试预算（**有界**；配置能覆盖，但越不过 [FailurePolicy.MAX_RETRY_ATTEMPTS]）。 */
internal val FAILURE_DEFAULT_RETRIES: Map<FailureKind, Int> = mapOf(
    // 网络类：一次退避重试。旧口径（第八十七批之前）一次失败就整轮停 —— 这一条是本批唯一
    // 动到「失败路径」默认值的地方，要恢复旧口径把这里改成 0 即可（配置可覆盖）。
    FailureKind.NETWORK to 1,
    // 通道类：不重试（401/403 重试无效），到熔断线才换通道。
    FailureKind.MODEL_CHANNEL to 0,
    // 工具超时：给一次（工具自己写着「可重试一次」）。
    FailureKind.TOOL_TIMEOUT to 1,
    // 工具报错：交给模型连着试，到 MAX_TOOL_FAILURES（3）才停下问人 —— 与旧行为逐条相等。
    FailureKind.TOOL_ERROR to RunRules.MAX_TOOL_FAILURES - 1,
    // 坏参数：给一次（第五十四批 P1-2 的口径）。
    FailureKind.BAD_JSON to RunRules.MAX_BAD_ARGS_RETRIES,
    // 取消/停止：永不重试。
    FailureKind.CANCELLED to 0,
)

/**
 * 策略表（「配置可覆盖」的唯一入口）。
 *
 * 所有字段都有生产默认值 ⇒ 引擎侧不传参就是生产口径；要复现旧行为/做对照实验，
 * 只换这一份数据，不改任何分支代码（[LEGACY_NO_RETRY] 就是「一律不重试！」那份）。
 */
internal data class FailurePolicyConfig(
    val retries: Map<FailureKind, Int> = FAILURE_DEFAULT_RETRIES,
    /** 退避基数：第 n 次重试等 `base × 2^(n-1)`，封顶 [backoffMaxMs]。 */
    val backoffBaseMs: Long = 500L,
    val backoffMaxMs: Long = 8_000L,
    /** 关掉这一项 ⇒ 通道类失败一律问人，绝不自动换（对照实验用）。 */
    val allowFallback: Boolean = true,
    /** 通道熔断线（默认就是 [RunRules.CHANNEL_BREAK_THRESHOLD]；留成配置项只为可测）。 */
    val channelBreakThreshold: Int = RunRules.CHANNEL_BREAK_THRESHOLD,
    /** 工具失败问人线（默认就是 [RunRules.MAX_TOOL_FAILURES]）。 */
    val toolFailureLimit: Int = RunRules.MAX_TOOL_FAILURES,
) {
    companion object {
        /** 「一律不重试」——与第八十七批之前的引擎行为逐条相等（对照/回滚用）。 */
        val LEGACY_NO_RETRY: FailurePolicyConfig =
            FailurePolicyConfig(retries = FAILURE_DEFAULT_RETRIES.mapValues { 0 })
    }
}

/**
 * 统一失败策略（纯函数；本文件不含任何 Android/IO/线程代码）。
 */
internal object FailurePolicy {

    /**
     * **硬上限**：配置写多大都越不过它。
     *
     * 为什么必须是常量、不放进 [FailurePolicyConfig]：`abort` 被「顺手」改成 `retry`
     * 是这类策略最典型的退化（无限重试＝把一步烧成一百步）。上限写死在这里，
     * 配置再大也只到 2 —— 负例用例钉的就是这一条。
     */
    const val MAX_RETRY_ATTEMPTS = 2

    /** 生产口径（引擎不传 config 时用的那一份）。 */
    val DEFAULT: FailurePolicyConfig = FailurePolicyConfig()

    /**
     * 模型/通道失败的分类（纯函数：拿服务端原文/异常文本判类，不含正文）。
     *
     * 与 `RunEngine.humanizeNetFailure` 互补：那个管「对人怎么说」，这个管「机器怎么办」。
     * 两处都从同一段原文读，所以不会出现「提示说换通道、策略却在重试」这种打架。
     */
    fun classifyModel(raw: String): FailureKind = when {
        raw.isEmpty() -> FailureKind.NETWORK
        // Key/权限/地区：重试无效，只能换通道或换 Key。
        raw.contains("401") || raw.contains("403") ||
            raw.contains("RegionError", ignoreCase = true) ||
            raw.contains("FreeTier", ignoreCase = true) -> FailureKind.MODEL_CHANNEL
        // 余下都是「同一条通道再试可能就好了」：超时、断流、限流、服务端 5xx。
        else -> FailureKind.NETWORK
    }

    /** 工具失败的分类（超时单独一类：它不是通道坏，不许记通道失败）。 */
    fun classifyTool(error: String): FailureKind = when {
        error.contains("超时") || error.contains("timeout", ignoreCase = true) -> FailureKind.TOOL_TIMEOUT
        else -> FailureKind.TOOL_ERROR
    }

    /** 第 [attempt] 次失败的重试退避（毫秒；指数退避，封顶 + 非负）。 */
    fun backoffMs(attempt: Int, config: FailurePolicyConfig = DEFAULT): Long {
        val n = attempt.coerceIn(1, MAX_RETRY_ATTEMPTS)
        var ms = config.backoffBaseMs.coerceAtLeast(0L)
        repeat(n - 1) { ms = (ms * 2).coerceAtMost(config.backoffMaxMs) }
        return ms.coerceIn(0L, config.backoffMaxMs.coerceAtLeast(0L))
    }

    /**
     * 这一轮失败该怎么办（**唯一判据**）。
     *
     * @param kind 失败分类
     * @param attempt 本步**连续**第几次失败（1-based；≤0 按 1 算）
     * @param fallbackAvailable 装配层有没有给出候选通道（策略不认 Key，只看有没有）
     * @param keyRotationAvailable 第一百三十一批 ②：装配层说了"**同一模型还有下一把 Key**"。
     *   默认 false ⇒ 本表与第八十七批至今逐格相等（既有单测一格没动）。
     *   置 true 时**换 Key 优先于退避重试**：429 的语义是"这把 Key 请求太多了"，
     *   退避 500ms 再用同一把只会再吃一次 429——先换一把才是派工单要的动作。
     *   同理 401/403：MODEL_CHANNEL 原本要连坏到熔断线（3 次）才肯换，而池子里明明还有货，
     *   等三次只是把用户的额度烧在明知会失败的同一把上。
     */
    fun decide(
        kind: FailureKind,
        attempt: Int,
        fallbackAvailable: Boolean,
        config: FailurePolicyConfig = DEFAULT,
        keyRotationAvailable: Boolean = false,
    ): FailureAction {
        val n = attempt.coerceAtLeast(1)
        return when (kind) {
            // 用户点停止：重试等于跟用户对着干。
            FailureKind.CANCELLED -> FailureAction.Abort

            // 坏参数：给一次改的机会；第二次还坏就是这轮状态不对，烧 token 没意义。
            FailureKind.BAD_JSON ->
                if (n <= retriesOf(kind, config)) retry(n, config) else FailureAction.Abort

            // 工具类：先让模型自己看着办（Retry＝回灌错误继续），到线必停（先判线再判预算）。
            FailureKind.TOOL_TIMEOUT, FailureKind.TOOL_ERROR -> when {
                n >= config.toolFailureLimit -> FailureAction.AskUser
                n <= retriesOf(kind, config) -> retry(n, config)
                else -> FailureAction.Abort
            }

            // 网络类：先有界重试；重试花完还没通，到熔断线换通道，不到线就停下（旧口径）。
            FailureKind.NETWORK -> when {
                keyRotationAvailable -> FailureAction.Fallback
                n <= retriesOf(kind, config) -> retry(n, config)
                n >= config.channelBreakThreshold -> fallbackOrAsk(fallbackAvailable, config)
                else -> FailureAction.Abort
            }

            // 通道类：不重试；连续到熔断线才换通道，不到线就停下问人（旧口径）。
            FailureKind.MODEL_CHANNEL -> when {
                keyRotationAvailable -> FailureAction.Fallback
                n >= config.channelBreakThreshold -> fallbackOrAsk(fallbackAvailable, config)
                else -> FailureAction.Abort
            }
        }
    }

    /** trace 里 `decision` 列的稳定标签（枚举文字，不含正文；回放按它筛）。 */
    fun decisionLabel(action: FailureAction): String = when (action) {
        is FailureAction.Retry -> "RETRY#${action.attempt}"
        FailureAction.Fallback -> "FALLBACK"
        FailureAction.AskUser -> "ASK_USER"
        FailureAction.Abort -> "ABORT"
    }

    /** 实际可用的重试次数（配置被压到 [MAX_RETRY_ATTEMPTS] 以内，且不为负）。 */
    private fun retriesOf(kind: FailureKind, config: FailurePolicyConfig): Int =
        (config.retries[kind] ?: 0).coerceIn(0, MAX_RETRY_ATTEMPTS)

    private fun retry(attempt: Int, config: FailurePolicyConfig): FailureAction.Retry =
        FailureAction.Retry(backoffMs(attempt, config), attempt)

    private fun fallbackOrAsk(fallbackAvailable: Boolean, config: FailurePolicyConfig): FailureAction =
        if (config.allowFallback && fallbackAvailable) FailureAction.Fallback else FailureAction.AskUser
}
