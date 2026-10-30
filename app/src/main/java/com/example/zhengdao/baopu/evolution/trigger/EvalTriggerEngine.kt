// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百零四批 · 触发后的**执行链**（派工单 §2：跑 eval → 产证据 → 进「未办成单」或
// 进化提案，**不弹卡**）。
//
// 流程（每条信号独立走一遍，互不挡）：
//   预算判定 → 抑制则记一行"为什么没评"收场；
//            → 放行则跑评测（**只跑用例/规则**——生产实现是 163 批的离线机械评测，
//              假策略＋真管线，零网络零模型；"不许让模型自评"在这里是结构性的：
//              本文件连模型客户端的依赖都没有，源级门禁钉死）→ 读数进账行 → 路由。
//
// 路由（谁进哪本账，写死在 [destinationOf]，别处不许再分一遍）：
//   ① RUN_FAILED      → 「未办成单」（这件事没办成，评测读数进 reasonDetail）；
//   ②③ 纠错/成功率    → 进化提案（PENDING 候选，payload 是规则拼的事实，人点头才生效）；
//   ④ MANUAL          → 只落账本（预留——证据先攒着，进哪本账等有界面那批再定）。
package com.example.zhengdao.baopu.evolution.trigger

/** 一次评测的读数（跑不出来时 [error] 说明原因，**不许拿 0.0 冒充"全挂"**）。 */
internal data class TriggerEvalReading(
    val ran: Int,
    val passed: Int,
    val total: Int,
    val passRate: Double?,
    val failedCases: List<String>,
    val elapsedMs: Long,
    val error: String = "",
) {
    companion object {
        fun ofError(t: Throwable): TriggerEvalReading = TriggerEvalReading(
            ran = 0, passed = 0, total = 0, passRate = null, failedCases = emptyList(),
            elapsedMs = 0, error = "${t.javaClass.simpleName}: ${t.message ?: ""}",
        )
    }
}

/** 路由目的地（[EvalTriggerEngine.destinationOf] 的输出）。 */
internal enum class TriggerDestination { UNFINISHED, PROPOSAL }

/** 引擎的一次输出（Triggered 行里的读数就是"证据落账"的账；NoSignal＝四条件全不满足）。 */
internal sealed interface EvalTriggerOutcome {
    data class Triggered(val row: TriggerRow) : EvalTriggerOutcome
    data class Suppressed(val type: TriggerType, val reason: String) : EvalTriggerOutcome
    data object NoSignal : EvalTriggerOutcome
}

/**
 * 触发引擎（判定与记账在 JVM 里可直测；生产依赖由 [EvalTriggerWiring] 注入）。
 *
 * 全部依赖注入成函数，与 168 批生产评测壳同一条纪律：把"关掉时零调用"这类性质
 * **量出来**而不是说出来——测试里用计数器假件，评测没跑就是零次。
 *
 * @param readRows 读限频账（全量；预算自己筛）
 * @param appendRow 追加账行（false＝记账失败；照常返回结果，不许把收尾挡住）
 * @param runEval 跑一次评测（生产＝163 批离线机械评测的基线臂；测试＝假件）
 * @param recordUnfinished 进「未办成单」，返回路由引用（`unfinished:<key>`；失败返回 ""）
 * @param recordProposal 进化提案（PENDING），返回 `proposal:<id>`（失败返回 ""）
 * @param now 时钟（注入，便于 JVM 直测限频边界）
 */
internal class EvalTriggerEngine(
    private val readRows: () -> List<TriggerRow>,
    private val appendRow: (TriggerRow) -> Boolean,
    private val runEval: () -> TriggerEvalReading,
    private val recordUnfinished: (EvalTriggerSignal, TriggerEvalReading, Long) -> String,
    private val recordProposal: (EvalTriggerSignal, TriggerEvalReading, Long) -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {

    /** 会话收尾入口：四条件判定 → 逐信号处理。 */
    fun onRoundSettled(inputs: EvalTriggerInputs): List<EvalTriggerOutcome> {
        val signals = EvalTriggerRules.signalsOf(inputs)
        if (signals.isEmpty()) return listOf(EvalTriggerOutcome.NoSignal)
        return signals.map { process(it) }
    }

    /** ④ 预留：手动触发（同样过限频——手动是入口，不是旁路）。 */
    fun manual(note: String): EvalTriggerOutcome = process(
        EvalTriggerSignal(type = TriggerType.MANUAL, ref = MANUAL_REF, intent = note),
    )

    private fun process(signal: EvalTriggerSignal): EvalTriggerOutcome {
        val at = now()
        val reason = EvalTriggerBudget.suppressReason(signal.type, readRows(), at)
        if (reason != null) {
            // 抑制也要落一行：验收②「同日再 FAILED ⇒ 不触发」的"不触发"要能对账。
            appendRow(
                TriggerRow(
                    atMs = at, type = signal.type, triggered = false,
                    ref = signal.ref, intent = signal.intent, suppressReason = reason,
                ),
            )
            log("[eval-trigger] ${signal.type.cn}被限频：$reason（ref=${signal.ref}）")
            return EvalTriggerOutcome.Suppressed(signal.type, reason)
        }
        // 评测只跑用例/规则；崩了收成带 error 的读数，不许把异常冒回收尾路径。
        val reading = runCatching { runEval() }.getOrElse { TriggerEvalReading.ofError(it) }
        val routeRef = when (destinationOf(signal.type)) {
            TriggerDestination.UNFINISHED ->
                runCatching { recordUnfinished(signal, reading, at) }.getOrDefault("")
            TriggerDestination.PROPOSAL ->
                runCatching { recordProposal(signal, reading, at) }.getOrDefault("")
            null -> ""
        }
        val row = TriggerRow(
            atMs = at,
            type = signal.type,
            triggered = true,
            ref = signal.ref,
            intent = signal.intent,
            evalRan = reading.ran,
            evalPassed = reading.passed,
            evalTotal = reading.total,
            evalPassRate = reading.passRate,
            evalFailedCases = reading.failedCases,
            evalError = reading.error,
            route = routeRef,
        )
        appendRow(row)
        log(
            "[eval-trigger] ${signal.type.cn}触发评测：${reading.passed}/${reading.total} 过" +
                (if (reading.error.isBlank()) "" else "（评测异常：${reading.error}）") +
                if (routeRef.isBlank()) "" else "，已落 $routeRef",
        )
        return EvalTriggerOutcome.Triggered(row)
    }

    /**
     * 谁进哪本账（**唯一写死的地方**；改路由只许改这里，别在调用方另分一遍）。
     *
     * MANUAL 返回 null：证据先落账本，进不进面向用户的账等有界面那批再定（预留语义）。
     */
    internal fun destinationOf(type: TriggerType): TriggerDestination? = when (type) {
        TriggerType.RUN_FAILED -> TriggerDestination.UNFINISHED
        TriggerType.USER_CORRECTION, TriggerType.SKILL_RATE -> TriggerDestination.PROPOSAL
        TriggerType.MANUAL -> null
    }

    companion object {
        /** 手动信号的证据引用（没有 run_id／skill_event 可指，固定字面量）。 */
        const val MANUAL_REF = "manual"
    }
}
