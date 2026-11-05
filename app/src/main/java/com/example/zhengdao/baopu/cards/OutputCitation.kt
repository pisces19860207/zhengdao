// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十五批 · **产出引依据**（通用机制；不认任何具体技能、不认任何具体人格）。
//
// ## 为什么是「出卡前的闸」而不是「渲染时补一行」
// 派工单两条硬判据：依据为空 ⇒ 不许出卡；指向的条目不存在 ⇒ 拒并说明。
// 这两条都要求「那张卡根本没被放出来」；渲染层补一行只能做到「显示得好看」，
// 拦不住一张没依据的卡。所以判据放在出卡之前，界面只认 [CitationVerdict.Pass]。
//
// ## 为什么台账不在闭集里
// 台账是「已经发过什么」的清单，只用于查重。若允许它当依据，就会留下
// 「引了台账第 N 行、内容却是从旧台账里长出来的」这条老路——本批要堵的就是它。
// 除了闭集不含它，[CitationRefs.shapeProblem] 再按字面拦一次：换个 kind 把台账
// 塞进 PERSONA 也过不去。
package com.example.zhengdao.baopu.cards

import java.util.Locale

/** 依据的种类（**闭集**，三类）。台账故意不在其中：台账只用于查重，不是内容来源。 */
enum class CitationKind {
    /** 人设条目：来源是「文件:行」。 */
    PERSONA,

    /** 知识库文档：来源是「文件:行」。 */
    KNOWLEDGE,

    /** 联网搜索结果：来源是「URL 日期」。 */
    WEB,
}

/**
 * 一条依据。
 *
 * @param ref 来源：人设/知识库写 `相对/路径.md:12`；搜索写 `https://… 2026-11-05`
 * @param display 卡面顶部那一行里的短标签（如「人设第 3 条（语气）」）；由产出方给，机制不造人格名
 * @param excerpt 点开看的原文（人设那一行／文档那一行／搜索摘要）
 */
data class Citation(
    val kind: CitationKind,
    val ref: String,
    val display: String,
    val excerpt: String = "",
)

/** 拒的理由（闭集；不许出现「说不清为什么就拒了」）。 */
enum class CitationReject {
    /** 一条依据都没有。 */
    EMPTY,

    /** 把台账当依据（无论挂在哪个 kind 下）。 */
    LEDGER_SOURCE,

    /** 来源不是「文件:行」或「URL 日期」。 */
    BAD_REF,

    /** 指向的条目不存在（被删／过期／行号越界）。 */
    DANGLING,
}

/** 闸的结论：通过时带上卡面那一行，拒绝时带上人话。 */
sealed interface CitationVerdict {
    data class Pass(val line: String, val citations: List<Citation>) : CitationVerdict
    data class Reject(val reason: CitationReject, val detail: String) : CitationVerdict
}

/** 界面注入的文案（纯判据层不写中文字面量；strings.xml 是唯一出处）。 */
data class CitationMessages(
    val prefix: String,
    val emptyHint: String,
    val ledgerHint: String,
    val badRefHint: String,
    val danglingHint: String,
)

/** 引用形状与禁源（纯函数，JVM 直测）。 */
object CitationRefs {

    /** 台账类来源标记；命中即拒（大小写不敏感）。 */
    val LEDGER_MARKERS: List<String> = listOf("台账", "ledger")

    private val FILE_LINE = Regex("""^(.+):(\d+)$""")
    private val WEB_REF = Regex("""^(https?://\S+)\s+(\d{4}-\d{2}-\d{2})$""")

    /** `相对/路径.md:12` → (路径, 行号)；行号必须 ≥1。 */
    fun fileLine(ref: String): Pair<String, Int>? {
        val m = FILE_LINE.find(ref.trim()) ?: return null
        val line = m.groupValues[2].toIntOrNull() ?: return null
        return if (line >= 1) m.groupValues[1].trim() to line else null
    }

    /** `https://… 2026-11-05` → (URL, 日期)。 */
    fun webRef(ref: String): Pair<String, String>? {
        val m = WEB_REF.find(ref.trim()) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    /** 这条是不是在拿台账当依据（ref 与 display 都扫，换个说法也拦得住）。 */
    fun isLedger(c: Citation): Boolean {
        val text = (c.ref + " " + c.display).lowercase(Locale.ROOT)
        return LEDGER_MARKERS.any { text.contains(it.lowercase(Locale.ROOT)) }
    }

    /** 形状问题；null ＝ 形状没问题。返回 (理由, 那一条)，拒的时候要指名道姓。 */
    fun shapeProblem(c: Citation): Pair<CitationReject, Citation>? {
        if (isLedger(c)) return CitationReject.LEDGER_SOURCE to c
        val ok = when (c.kind) {
            CitationKind.PERSONA, CitationKind.KNOWLEDGE -> fileLine(c.ref) != null
            CitationKind.WEB -> webRef(c.ref) != null
        }
        return if (ok) null else CitationReject.BAD_REF to c
    }
}

/** 出卡前的依据闸（唯一判据入口；不查盘——查盘由 [OutputCitationSources] 注入 exists）。 */
object OutputCitationGate {

    /** 卡面顶部那一行：`依据：人设第 3 条（…）、知识库 …`。 */
    fun line(citations: List<Citation>, prefix: String): String =
        prefix + citations.joinToString("、") { it.display }

    fun check(
        citations: List<Citation>,
        exists: (Citation) -> Boolean,
        messages: CitationMessages,
    ): CitationVerdict {
        if (citations.isEmpty()) {
            return CitationVerdict.Reject(CitationReject.EMPTY, messages.emptyHint)
        }
        val shape = citations.mapNotNull { CitationRefs.shapeProblem(it) }.firstOrNull()
        if (shape != null) return rejectOf(shape, messages)
        val dangling = citations.firstOrNull { !exists(it) }
        if (dangling != null) {
            return CitationVerdict.Reject(
                CitationReject.DANGLING,
                messages.danglingHint.format(dangling.ref),
            )
        }
        return CitationVerdict.Pass(line(citations, messages.prefix), citations)
    }

    private fun rejectOf(
        shape: Pair<CitationReject, Citation>,
        messages: CitationMessages,
    ): CitationVerdict {
        val (reason, citation) = shape
        val hint = if (reason == CitationReject.LEDGER_SOURCE) messages.ledgerHint else messages.badRefHint
        return CitationVerdict.Reject(reason, "$hint（${citation.ref}）")
    }
}
