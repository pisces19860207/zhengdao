// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十三批 §3.1/§3.2（真跑一次压缩 ＋ 冷却防死循环）。
// 房型照刀 3 A 段的 `ConfirmFlow.kt`：把「要真 SQLite / 真网络」的部分**注入**进来
// （[CompressionStore] 与 `complete` 闭包），内核自己无 Android/SQLite ⇒ JVM 直测
// （J3 成功落库 / J6 失败不炸 / J7 冷却 三条都靠这个内核，不然只能上真机）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.CompletionResult
import com.example.zhengdao.baopu.CompressionRecord
import com.example.zhengdao.baopu.Compressor
import com.example.zhengdao.baopu.HistoryMessage
import com.example.zhengdao.baopu.LlmMessage
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.baopu.TokenEstimator
import com.example.zhengdao.rootfs.RunLog

/**
 * 压缩落库与留痕的注入接口（实现在 `BaopuDb` 侧，照 [com.example.zhengdao.baopu.PendingStore] 房型）。
 *
 * 为什么整个压缩都要走接口而不是直接调 `BaopuDb`：本仓无 Robolectric ⇒
 * `SQLiteOpenHelper` 在 JVM 单测里实例化不了（刀 0 的老教训），不注入就只能上真机验，
 * 而派工单 §6.1 要的是 JVM 逐条。
 */
internal interface CompressionStore {

    /** 落一条压缩记录（成功才调；失败/跳过都不写这张表）。 */
    fun insert(sessionId: Long, record: CompressionRecord, sourceTokens: Int, summaryTokens: Int)

    /** 本会话最近一条压缩记录（恢复用；没有 ⇒ null）。 */
    fun latest(sessionId: Long): CompressionRecord?

    /** 最近一次压缩失败发生在第几步（冷却判据的锚；没有 ⇒ null）。 */
    fun lastFailedStep(sessionId: Long): Int?

    /** 记一次失败（进 `run_log`，同时成为冷却起点）。 */
    fun markFailed(sessionId: Long, step: Int, code: Int, message: String)

    /** 记一次「该压但没压」（降级通道等，进 `run_log`；**不是**失败，不触发冷却）。 */
    fun markSkipped(sessionId: Long, step: Int, reason: String)
}

/**
 * 压缩内核（第十三批 A 段）——选区 → 判通道 → 调一次模型 → 解析 → 落库。
 *
 * ## 四条不走弯路的口径（派工单 §3.1/§3.2）
 * 1. 选区为空 ⇒ 不发请求（空请求是纯浪费，且摘要无从谈起）；
 * 2. 通道**降级** ⇒ 不压（降级通道压出来的摘要不可信，宁可不压，记一行留痕）；
 * 3. 失败（超时/非 2xx/没文本）⇒ **转成 [Outcome.Failed]，绝不抛**——
 *    抛上去整轮就炸了，压缩只是优化，不配让整轮陪葬（J6 钉的就是这条）；
 * 4. 失败后**冷却 10 步**（[COOLDOWN_STEPS]）：压缩失败通常是通道的问题，
 *    一步一重试等于每步都多烧一次请求，MAX_STEPS=16 拦得住步数、拦不住钱。
 *
 * 成功之后什么时候再压：**不归这里管**——派工单 §3.2 第 2 条说「下次必须确实又超
 * 触发线」，那是 `ContextAssembler.needsCompressing` 的事，内核不主动重压。
 */
internal class CompressionRunner(
    private val complete: (messages: List<LlmMessage>, maxTokens: Int, temperature: Double) -> CompletionResult,
    private val store: CompressionStore,
    private val log: (String) -> Unit = { RunLog.log(it) },
    /**
     * 内置免费通道此刻可不可用（[Compressor.chooseChannel] 的运行时判定）。
     *
     * 必须是**闭包**而不是一个 Boolean：`freeAvailable` 是连通性/额度这种会变的
     * 事，压的那一刻现问才准（构造期问一次，后面十几步都用旧答案 ⇒ 该降级时不降级）。
     * 默认 true（拿不到信息时按可用处理，与 [Compressor.chooseChannel] 同口径）。
     */
    private val freeAvailable: () -> Boolean = { true },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val tokensOf: (String) -> Int = { TokenEstimator.estimate(it) },
) {

    sealed interface Outcome {
        /** 压成了：[record] 已落库，调用方拿 `record.summary` 当摘要替身。 */
        data class Ok(val record: CompressionRecord) : Outcome

        /** 该压但没压（选区为空 / 通道降级）：[reason] 是给人看的一句话。 */
        data class Skipped(val reason: String) : Outcome

        /** 压失败了：整轮照跑，[message] 进 `run_log`。 */
        data class Failed(val code: Int, val message: String) : Outcome

        /** 冷却中（上次失败还没满 10 步）：[untilStep] = 到第几步才允许再试。 */
        data class Cooling(val untilStep: Int) : Outcome
    }

    /**
     * 跑一次压缩。
     *
     * @param step 当前步数（冷却判据用它跟上次失败的那一步比）
     */
    fun runOnce(
        sessionId: Long,
        history: List<HistoryMessage>,
        provider: Provider,
        step: Int,
    ): Outcome {
        val lastFailed = store.lastFailedStep(sessionId)
        if (lastFailed != null && step - lastFailed < COOLDOWN_STEPS) {
            // 冷却期内连请求都不发（J7：失败后第 1 步不再发，第 11 步才允许）
            return Outcome.Cooling(lastFailed + COOLDOWN_STEPS)
        }

        val range = Compressor.selectCompressionRange(history)
        if (range.isEmpty()) {
            return Outcome.Skipped("选区为空：最近 ${Compressor.KEEP_RECENT} 条之外没有可压的历史")
        }

        val choice = Compressor.chooseChannel(provider, freeAvailable())
        if (choice.degraded) {
            // 降级通道上压出来的摘要不可信 ⇒ 不压（派工单 §3.1 第 2 步），但要留痕
            store.markSkipped(sessionId, step, SKIP_REASON_DEGRADED)
            log("抱朴压缩：${Compressor.channelNote(choice)} ⇒ 本次不压（降级通道的摘要不可信）")
            return Outcome.Skipped("压缩通道已降级，本次不压（摘要不可信，原文仍在）")
        }

        val messages = Compressor.buildRequest(range, choice.provider)
        return when (val r = complete(messages, MAX_TOKENS, TEMPERATURE)) {
            is CompletionResult.Ok -> {
                val ids = range.map { it.id }
                val record = Compressor.parseSummary(r.text, ids, now(), r.model)
                val sourceTokens = range.sumOf { tokensOf(it.text) }
                store.insert(sessionId, record, sourceTokens, tokensOf(record.summary))
                log("抱朴压缩：${ids.size} 条压成摘要（模型 ${r.model}），已落库")
                Outcome.Ok(record)
            }

            is CompletionResult.Failed -> {
                // 记 run_log 并成为冷却起点；**不抛**（抛上去整轮就炸，J6）
                store.markFailed(sessionId, step, r.code, r.message)
                log("抱朴压缩：失败（code=${r.code}）${r.message} ⇒ 本轮按没压继续跑")
                Outcome.Failed(r.code, r.message)
            }
        }
    }

    companion object {
        /** 降级跳过的留痕原因码（`run_log` 里就是这个字面量，蓝总管按它认）。 */
        const val SKIP_REASON_DEGRADED = "compression_skipped_degraded"

        /** 失败的留痕原因码（同上）。 */
        const val FAIL_REASON = "compression_failed"

        /** 压缩失败后的冷却步数（派工单 §3.2：至少隔 10 步）。 */
        const val COOLDOWN_STEPS = 10

        /** 摘要 token 上限（派工单 §3.3：≤ 1024）。 */
        const val MAX_TOKENS = 1024

        /** 摘要温度（派工单 §3.3：0.2，要稳定不要花样）。 */
        const val TEMPERATURE = 0.2
    }
}
