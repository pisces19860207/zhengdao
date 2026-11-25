// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十四批 · 534 灵感捕获与关联唤醒 ③ wake —— 被关联到的念头浮一行。
//
// 与 625 **共用**执行器与去重表（待发提示词 ④，判据③④）：
//   · 执行器＝[SubconsciousRunner.run]（676/625 的那一枚，本包零 Runner 类）；
//   · 去重表＝625 的 `suggestedAtByKey(root)` 读回的同一张表——同一念头 3 天冷却
//     （≥24h），两次关联只浮一次；账也写回同一张 `回响.jsonl`（格式与 625 相同，
//     why 前缀同为 `676 回响`，让 625 的去重表读得见本包的念头）。
//
// 判据落点：
//   ③ 每天 ≤1：runner 天闸（lastRunDayKey）＋本层 take(1)；不点不进上下文＝独立
//      [InsightMailbox]（点开那一次才 offer，引擎侧一行 drain；空表 ⇒ 逐字节一致）；
//   ④ 与 625 去重共享：候选 ledgerId＝念头锚点 ref，冷却判定全交 runner；
//   ⑤ 不许替人下结论：一行文案经 [InsightCaptureRules.preaches] 过滤，命中「你应该/"
//      "建议你/记得要/最好/必须」的模板整条不浮（判据本体在 capture 包，同一把尺）。
package com.example.zhengdao.baopu.insight

import com.example.zhengdao.baopu.agent.QuietGrant
import com.example.zhengdao.baopu.agent.SessionShape
import com.example.zhengdao.baopu.safety.DiaryEvent
import com.example.zhengdao.baopu.subconscious.EchoKind
import com.example.zhengdao.baopu.subconscious.SubconsciousCandidate
import com.example.zhengdao.baopu.subconscious.SubconsciousRunner
import com.example.zhengdao.baopu.subconscious.SubconsciousSignal

/** 一轮唤醒的产出（rows 至多 1 行；note 说明为什么没浮——失败兜底）。 */
internal data class InsightWakeRound(
    val signals: List<SubconsciousSignal>,
    val note: String,
    val ran: Boolean,
    /** 起居注同形 ACTION 行（runner 逐条映自 signals；落账由装配层做，本层只产出）。 */
    val actionRows: List<DiaryEvent> = emptyList(),
)

/**
 * 把关联到的念头转成共享执行器的候选（判据④：ledgerId 必带锚点，缺引用活不到这里）。
 *
 * 用 [EchoKind.MENTIONED_NOT_DONE]（用户提过没做）：语义最贴「正在成形但没落地」，
 * 也顺带继承它的 30 天回看窗——太老的念头不浮。
 */
internal fun candidatesOf(
    associations: List<InsightAssociation>,
): List<SubconsciousCandidate> = associations
    .asSequence()
    .distinctBy { it.thought.anchor.ref }
    .map { a ->
        SubconsciousCandidate(
            // id 用 echo:<锚点 ref>：runner 的账行 why 带这个 id ⇒ 625 的
            // suggestedAtByKey 去 `echo:` 前缀后正好是锚点 ref（同一念头去重共享）。
            id = "echo:${a.thought.anchor.ref}",
            kind = EchoKind.MENTIONED_NOT_DONE,
            title = a.thought.body.replace('\n', ' ').take(InspirationRowTitleLimit.CHARS),
            ledgerId = a.thought.anchor.ref,
            atMs = a.thought.atMs,
            verdictOfLedger = "",
        )
    }
    .toList()

/** 标题上限（与 625 候选摘要 30 字同源；避免把整句原文塞进一行）。 */
internal object InspirationRowTitleLimit {
    const val CHARS = 30
}

/**
 * 浮一行的文案模板（**只陈述事实＋询问**，「要不要看」是问句不是替人下结论）。
 * 模板本身过禁词门禁：一旦有人把它改成「你应该看」，判据⑤立刻红。
 */
internal object InsightWakeLine {
    const val TEMPLATE = "之前记下的一个念头：%s（依据：%s）· 要看吗？"

    fun of(signal: SubconsciousSignal): String {
        val line = TEMPLATE.format(signal.title, signal.ledgerId)
        require(!InsightCaptureRules.preaches(line)) { "一行文案不许替人下结论（判据⑤）" }
        return line
    }
}

/**
 * wake 的纯逻辑：关联结果 + 共享去重表 + 天闸 ⇒ 至多一条信号。
 *
 * 所有时刻/免打扰/去重状态由调用方注入（JVM 直测；本层零 IO、零时钟、零调度）。
 * `picker` 传 null ⇒ runner 走规则版（本单不引模型，边界「不做自动执行」）。
 */
internal fun runInsightWake(
    associations: List<InsightAssociation>,
    nowMs: Long,
    grant: QuietGrant?,
    shape: SessionShape,
    lastRunDayKey: Long?,
    suggestedAtByKey: Map<String, Long>,
): InsightWakeRound {
    val outcome = SubconsciousRunner.run(
        candidates = candidatesOf(associations),
        nowMs = nowMs,
        grant = grant,
        shape = shape,
        lastRunDayKey = lastRunDayKey,
        suggestedAtByKey = suggestedAtByKey,
        picker = null,
    )
    if (!outcome.ran) {
        return InsightWakeRound(emptyList(), outcome.skipWhy, false)
    }
    // 每天 ≤1：runner 一轮最多 3 枚；本层只浮最优 1 枚（rulesPick 已排好序）。
    val floats = outcome.signals.take(1)
    return InsightWakeRound(
        signals = floats,
        note = if (floats.isEmpty()) "这轮没挑到可浮的念头" else "浮出 1 行（依据 ${floats.first().ledgerId}）",
        ran = true,
        // 账行与「浮出的那一行」一一对应（浮 1 行 ⇒ 1 行账；0 行 ⇒ 0 行）。
        actionRows = outcome.actionRows.filter { row ->
            floats.any { s -> row.why.endsWith(s.id) }
        },
    )
}

/**
 * 点开才进上下文的信箱（与 677 的灵感信箱同房型，但各自独立——不改 677）。
 * offer 全仓唯一调用点＝点开 534 那一行；引擎侧恰好一处 drain，空表 ⇒ 逐字节一致。
 */
internal object InsightMailbox {
    private var pending: String? = null

    fun offer(text: String) = synchronized(this) { pending = text }

    fun drain(): List<String> = synchronized(this) {
        val t = pending
        pending = null
        if (t.isNullOrBlank()) emptyList() else listOf(t)
    }

    fun clear() = synchronized(this) { pending = null }
}
