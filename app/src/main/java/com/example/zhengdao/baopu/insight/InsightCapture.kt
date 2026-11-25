// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十四批 · 534 灵感捕获与关联唤醒 ① capture —— 从当轮对话里抓
// 「正在成形但没落地」的念头，**逐字留原文 + 必带原文锚点**（哪一句/哪条账）。
//
// 依据：`派工单-2026-11-08-第五百三十四批-灵感捕获与关联唤醒.md` §待发提示词 ①：
//   「必须带原文锚点（哪一句/哪条账）；无锚点 ⇒ 红」。
//
// 三条硬纪律：
//   1. **不代笔**：body 是从消息里切出的原文整句，一个字不改；判据⑤禁词只管
//      系统自己生成的一行文案（见 InsightWake.lineOf），不管用户原话。
//   2. **无锚点不成念头**：[CapturedThought] 构造期就 require 锚点非空且指回原文——
//      缺引用的念头根本造不出来（结构保证，不是运行时检查）。
//   3. **零 Android、零时钟、零模型**：纯 JVM 函数，时刻/消息全由调用方注入。
package com.example.zhengdao.baopu.insight

/**
 * 原文锚点：这条念头到底从哪一句/哪条账来的。
 * [ref] 是稳定指针（关联唤醒时据此指回原文，也是共用去重表的键）。
 */
internal sealed interface InsightAnchor {
    /** 锚点指向的那句原文（逐字）。 */
    val quote: String

    /** 稳定指针（`msg:<messageId>` / `ledger:<ledgerId>`）。 */
    val ref: String

    /** 锚在会话里的某一句消息。 */
    data class Message(val messageId: Long, val sessionId: Long, override val quote: String) : InsightAnchor {
        override val ref: String get() = "msg:$messageId"
    }

    /** 锚在项目账的某一条。 */
    data class Ledger(val ledgerId: String, override val quote: String) : InsightAnchor {
        override val ref: String get() = "ledger:$ledgerId"
    }
}

/** 一条已捕获的念头（原文逐字，锚点不可缺）。 */
internal data class CapturedThought(
    val id: String,
    /** 原文整句，一字不改。 */
    val body: String,
    val anchor: InsightAnchor,
    val atMs: Long,
    val workspace: String,
) {
    init {
        require(body.isNotBlank()) { "念头必须有原文（空白不是念头）" }
        require(anchor.quote.isNotBlank()) { "锚点必须指回一句原文（无锚点 ⇒ 红，判据①）" }
        require(body.contains(anchor.quote)) { "原文必须含锚点那句（锚点与正文对不上 ⇒ 红）" }
        require(id.isNotBlank()) { "念头必须有稳定 id" }
    }
}

/** 一轮捕获的输入：一条消息（只认用户的原话，机器自己说的话不抓）。 */
internal data class TurnMessage(
    val messageId: Long,
    val sessionId: Long,
    val role: String,
    val text: String,
    val atMs: Long,
)

/** 一轮捕获的产出：抓到的念头 + 没抓的原因（失败兜底：不许静默丢）。 */
internal data class CaptureOutcome(
    val thoughts: List<CapturedThought>,
    val skipped: List<String>,
)

/**
 * 意向词（闭集）——出现它才算「正在成形」。
 * 复用词面判断，不引模型、不建向量（判据②）。
 */
internal object InsightCaptureRules {

    val INTENT_WORDS = listOf(
        "想", "打算", "要不", "要不要", "也许", "可能", "还没", "要是",
        "不如", "回头", "以后", "突然想到", "考虑", "计划", "希望", "可惜", "试试",
    )

    /** 完成词：带了它说明这事已经落地，不是「没落地」的念头。 */
    val DONE_WORDS = listOf("已完成", "做完了", "搞定了", "已经做了", "已经完成", "已经定了")

    /** 系统自己生成的一行文案禁词（判据⑤：不许替人下结论）。 */
    val PREACH_WORDS = listOf("你应该", "建议你", "记得要", "最好", "必须")

    /** 单句上限（念头是一句话，不是文章；与 677 的 120 字同源）。 */
    const val SENTENCE_LIMIT = 120

    /** 一句话切分（保留原文，不 trim 内容以外的字）。 */
    fun sentences(text: String): List<String> = text
        .split(Regex("(?<=[。！？!?；;\\n])"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    /** 这句是否「正在成形但没落地」。 */
    fun isForming(sentence: String): Boolean {
        if (sentence.length > SENTENCE_LIMIT) return false
        if (DONE_WORDS.any { sentence.contains(it) }) return false
        return INTENT_WORDS.any { sentence.contains(it) }
    }

    /** 一行文案是否越界替人下结论（判据⑤的判据本体）。 */
    fun preaches(line: String): Boolean = PREACH_WORDS.any { line.contains(it) }
}

/**
 * 从当轮消息里抓念头（纯函数）。
 *
 * 每条抓到的念头 [CapturedThought.body] 都是原句逐字；锚点取 `msg:<messageId>`。
 * messageId <= 0 的消息没有锚点 ⇒ 跳过并记原因（无锚点绝不成念头）。
 */
internal fun captureTurn(
    messages: List<TurnMessage>,
    nowMs: Long,
    workspace: String,
): CaptureOutcome {
    val thoughts = mutableListOf<CapturedThought>()
    val skipped = mutableListOf<String>()
    messages.forEach { m ->
        if (m.role != "user") return@forEach
        if (m.messageId <= 0L) {
            skipped.add("消息没有 messageId（无锚点）→ 不抓：${m.text.take(20)}")
            return@forEach
        }
        InsightCaptureRules.sentences(m.text).filter(InsightCaptureRules::isForming).forEach { sentence ->
            thoughts.add(
                CapturedThought(
                    id = "insight-${m.messageId}-${thoughts.size}",
                    body = sentence,
                    anchor = InsightAnchor.Message(m.messageId, m.sessionId, sentence),
                    atMs = if (m.atMs > 0L) m.atMs else nowMs,
                    workspace = workspace,
                ),
            )
        }
    }
    return CaptureOutcome(thoughts.toList(), skipped.toList())
}
