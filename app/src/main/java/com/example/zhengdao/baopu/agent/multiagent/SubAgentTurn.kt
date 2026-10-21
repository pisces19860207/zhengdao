// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百二十七批 · 多 Agent 协作 v1》§1「子 Agent：独立上下文/
// 工具白名单/超时」与开工闸门《多Agent协作v1-协作模型-2026-10-21.md》§3/§8。
//
// 房型说明：子 Agent 的「一轮对话」抽成接口（照 [com.example.zhengdao.baopu.agent]
// 的 shrinkImage/batchRunner 注入房型）——引擎本身要能在 JVM 里跑，单测喂假件
// 就能验全部语义；生产实现 [LlmSubAgentTurn] 包 LlmClient，只负责 IO 与解码，
// 判定逻辑在 [settle]（纯函数）。
package com.example.zhengdao.baopu.agent.multiagent

import com.example.zhengdao.baopu.LlmClient
import com.example.zhengdao.baopu.LlmEvent
import com.example.zhengdao.baopu.LlmMessage
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.baopu.RawToolCall
import com.example.zhengdao.baopu.agent.RunRules
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolCallCodec
import com.example.zhengdao.baopu.agent.ToolRegistry
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 子 Agent 的一轮（给消息与工具数组 ⇒ 文本/工具调用/失败）。 */
internal fun interface SubAgentTurn {
    fun round(messages: List<LlmMessage>, tools: JSONArray?): TurnOutcome
}

/** 一轮的结果（三分支，与父引擎的 RoundOutcome 同构但更小：没有坏参数重试态）。 */
internal sealed interface TurnOutcome {
    /** 模型说完了：正文即子任务结论。 */
    data class Text(val text: String) : TurnOutcome

    /** 模型要调工具：[preamble] 是本轮顺带说的正文（可为空）。 */
    data class Calls(val calls: List<ToolCall>, val preamble: String) : TurnOutcome

    /** 这一轮没成（网络/通道/解码崩）：带人话理由。 */
    data class Failed(val reason: String) : TurnOutcome
}

/**
 * 一轮结果的**判定**（纯函数）：把流式累积的原料变成 [TurnOutcome]。
 *
 * 为什么拆出来：IO（等流、解码）与判定（这堆原料算 Text 还是 Calls 还是 Failed）
 * 分开，判定才能被 JVM 直测——负例「解码失败要报 Failed 不是崩掉」在这层验。
 */
internal object SubAgentTurnOutcomes {

    /**
     * @param text 累计的正文
     * @param rawCalls 原始工具调用（arguments 还是 JSON 字符串）
     * @param failure 流式失败理由（null = 没失败）
     * @param decode 原始调用 ⇒ 校验后的调用（生产传 ToolCallCodec；测试传假件）
     */
    fun settle(
        text: String,
        rawCalls: List<RawToolCall>,
        failure: String?,
        decode: (List<RawToolCall>) -> ToolCallCodec.DecodeResult,
    ): TurnOutcome {
        failure?.let { return TurnOutcome.Failed(it) }
        if (rawCalls.isNotEmpty()) {
            return when (val decoded = decode(rawCalls)) {
                is ToolCallCodec.DecodeResult.Ok -> TurnOutcome.Calls(decoded.calls, text)
                // 坏 JSON/坏参数：按失败回灌（子任务没有父引擎的 BadArgs 重试态——
                // 它的步数预算本来就小，重试一次的机会由协调器的 shouldRetry 给）
                is ToolCallCodec.DecodeResult.Bad -> TurnOutcome.Failed(decoded.error)
            }
        }
        return TurnOutcome.Text(text)
    }
}

/**
 * 生产实现的「一轮」：包 [LlmClient] 的流式调用。
 *
 * 与父引擎 [com.example.zhengdao.baopu.agent.RunEngine.streamRound] 的差别：
 * - 没有步数/落库/事件（那些归协调器与审计存储）；
 * - 等流上限用 [RunRules.STREAM_WAIT_TIMEOUT_MS]（同一口径，不另立数值）；
 * - 解码用的注册表是**父会话全量注册表**（schema 校验用），白名单在执行层拦
 *   （双层防御：模型只看得见白名单内的 tools 数组；真调了外面的，执行层拒）。
 */
internal class LlmSubAgentTurn(
    private val provider: Provider,
    private val registry: ToolRegistry,
    private val log: (String) -> Unit = {},
) : SubAgentTurn {

    override fun round(messages: List<LlmMessage>, tools: JSONArray?): TurnOutcome {
        val textBuf = StringBuilder()
        val latch = CountDownLatch(1)
        var failure: String? = null
        var rawCalls: List<RawToolCall> = emptyList()
        val client = LlmClient(provider)
        val call = client.streamAsync(messages, tools) { ev ->
            when (ev) {
                is LlmEvent.Delta -> textBuf.append(ev.text)
                is LlmEvent.Finished -> rawCalls = ev.toolCalls
                is LlmEvent.Failed -> {
                    failure = ev.error.message ?: ev.error.javaClass.simpleName
                }
            }
            // usage 只透传不落库（子任务用量并进父会话读数归壳层；v1 不接）
            if (ev is LlmEvent.Finished || ev is LlmEvent.Failed) latch.countDown()
        }
        val arrived = runCatching {
            latch.await(RunRules.STREAM_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!arrived) {
            runCatching { call.cancel() }
            log("子 Agent：等模型流超过 ${RunRules.STREAM_WAIT_TIMEOUT_MS / 1000}s，本轮作废")
            return TurnOutcome.Failed("等模型回复超时（${RunRules.STREAM_WAIT_TIMEOUT_MS / 1000}s）")
        }
        return SubAgentTurnOutcomes.settle(
            text = textBuf.toString(),
            rawCalls = rawCalls,
            failure = failure,
        ) { raws ->
            ToolCallCodec.decode(raws, registry)
        }
    }
}
