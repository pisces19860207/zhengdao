// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十七批（L2 轮）· 能力路由漏斗 L2 的 **IO 壳**：一次便宜调用＋每轮一行账。
//
// 三条边界（本轮工单⑤⑥＋判据⑥）：
//   1. **只在 L1 未命中时开这一枪**：L1 命中（含确定性执行）⇒ 账上记「跳过(L1命中)」直接返回，
//      模型调用次数为 0——漏斗的意义是便宜的一层先走；L1 判定在这里用同一把纯函数重算
//      （输入同、判据同，结果必同），**不改 FunnelRead 一个字**（L1 在验收，逻辑冻结）。
//   2. **超时/非法/调用失败 ⇒ 回退 L1 并打 warning，整轮不许崩**：枪打歪了就当没打，
//      L1 的未命中结论照旧交给 L3——本壳没有能让一轮崩掉的出口（异常全兜，见 askOnce）。
//   3. **只记账不装配**：L2 选出的组只写进读数行，不写会话格、不解锁组、不碰 tools 数组
//      （源级门禁 `FunnelL2GateTest` G3 扫写口字样）。
//
// 模型出口房型照 610 的 LlmExtractionPort：一轮无工具对话收满文本；测试经 [askOverride]
// 缝换假件（数调用次数、给回灌文、演超时）。零新依赖：LlmClient/org.json/coroutines 都是现成的。
package com.example.zhengdao.baopu.funnel

import com.example.zhengdao.baopu.LlmClient
import com.example.zhengdao.baopu.LlmEvent
import com.example.zhengdao.baopu.LlmMessage
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.core.RunLog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** L2 的模型出口缝（一轮问答；生产实现 [L2LlmPort]，测试换假件）。 */
internal fun interface L2AskPort {
    /** 发一次便宜调用，收原始文本；失败/超时**抛异常**（由壳兜住走回退）。 */
    fun ask(prompt: String): String
}

/** 生产出口：一轮无工具对话收满文本（房型照 610 LlmExtractionPort，纯文本）。 */
internal class L2LlmPort(
    private val provider: Provider,
    private val awaitMs: Long = FunnelL2Read.TIMEOUT_MS,
) : L2AskPort {
    override fun ask(prompt: String): String {
        val textBuf = StringBuilder()
        val latch = CountDownLatch(1)
        var failure: String? = null
        val call = LlmClient(provider).streamAsync(
            listOf(LlmMessage(LlmMessage.ROLE_USER, prompt)),
            tools = null,
        ) { ev ->
            when (ev) {
                is LlmEvent.Delta -> textBuf.append(ev.text)
                is LlmEvent.Finished -> latch.countDown()
                is LlmEvent.Failed -> {
                    failure = ev.error.message ?: ev.error.javaClass.simpleName
                    latch.countDown()
                }
            }
        }
        val arrived = runCatching { latch.await(awaitMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!arrived) {
            runCatching { call.cancel() }
            throw IllegalStateException("等模型超过 ${awaitMs}ms，本次作废")
        }
        failure?.let { throw IllegalStateException("模型请求失败：$it") }
        return textBuf.toString()
    }
}

/** L2 每轮的壳：判要不要开枪 → 开枪（带超时）→ 判（纯）→ 记一行账。 */
internal object FunnelL2Read {

    /** 账行前缀（与 L1 的「抱朴漏斗L1：」同一张表——同 RunLog、同解析式字段）。 */
    const val LOG_PREFIX = "抱朴漏斗L2："

    /** L2 一枪的时间预算：超时即回退 L1（本轮工单⑤；比主循环短得多）。 */
    const val TIMEOUT_MS = 8_000L

    /** 测试缝：非 null 时用它代替真通道（假件数调用、给回灌文、演超时）。 */
    internal var askOverride: L2AskPort? = null

    /**
     * 每轮一次（在 L1 读数之后、同一协程里顺序调——previousRoute 取 L1 更新**前**的值，
     * 重算的 L1 判定与 L1 壳那行必同：纯函数同输入同输出）。
     * [provider] null ⇒ 没有可用通道（不硬试，直接记回退账）。
     */
    suspend fun onUserTurn(
        sessionId: String,
        text: String,
        hasImage: Boolean,
        workspaceRoot: File,
        previousRoute: String?,
        provider: Provider?,
        timeoutMs: Long = TIMEOUT_MS,
    ) {
        val scripts = withContext(Dispatchers.IO) {
            runCatching { FunnelRead.scriptsOf(workspaceRoot) }
                .onFailure { RunLog.log("${LOG_PREFIX}脚本表读不动：${it.javaClass.simpleName}: ${it.message}（L2 按无脚本判）") }
                .getOrDefault(emptyList())
        }
        val l1 = FunnelL1Rules.judge(
            FunnelTurnInput(text, hasImage, scripts, previousRoute),
            validGroupKeys = FunnelRead.validGroupKeys(),
        )
        if (l1.route != FunnelL1Rules.ROUTE_MISS) {
            RunLog.log(logLineOf(sessionId, l1.route, "跳过(L1命中)", "—", 0L))
            return
        }
        val port = askOverride ?: provider?.let { L2LlmPort(it, timeoutMs) }
        if (port == null) {
            RunLog.log("${LOG_PREFIX}warning：没有可用通道，本轮 L2 不开枪")
            RunLog.log(logLineOf(sessionId, l1.route, "回退L1", "没有可用通道", 0L))
            return
        }
        val started = System.currentTimeMillis()
        val outcome = guard {
            withContext(Dispatchers.IO) { withTimeout(timeoutMs) { port.ask(FunnelL2Judge.promptOf(text)) } }
        }
        val cost = System.currentTimeMillis() - started
        val raw = outcome.getOrElse { err ->
            val why = "${err.javaClass.simpleName}: ${err.message ?: "无消息"}"
            RunLog.log("${LOG_PREFIX}warning：L2 调用失败（$why）——回退 L1 结果，整轮不崩")
            RunLog.log(logLineOf(sessionId, l1.route, "回退L1", why, cost))
            return
        }
        val d = FunnelL2Judge.judge(raw)
        RunLog.log(logLineOf(sessionId, l1.route, d.group, if (d.undecided) d.reason else "—", cost))
    }

    /** 账行（判据⑥四字段＋会话；纯函数，单测直接钉字段齐）。 */
    fun logLineOf(sessionId: String, l1Route: String, l2Result: String, undecidedReason: String, costMs: Long): String =
        "${LOG_PREFIX}会话=$sessionId L1-route=$l1Route L2结果=$l2Result 不确定原因=$undecidedReason 耗时ms=$costMs"

    /** 工单⑤的总闸：一枪怎么飞都不许把整轮带崩——异常全兜成 Result（负例 N3 拆这一格必须红）。 */
    private suspend fun <T> guard(block: suspend () -> T): Result<T> = runCatching { block() }
}
