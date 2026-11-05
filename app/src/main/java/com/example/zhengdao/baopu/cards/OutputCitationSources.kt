// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十五批 · **依据的来源侧**（收集 + 存在性校验 + 本轮搜索结果缓存）。
//
// 与 [OutputCitationGate] 的分工：闸只判「有没有、形状对不对、还在不在」，
// 「还在不在」由这里的 [exists] 注入；收集也只认人设／知识库／搜索三类文件，
// **台账一次都不读**——台账归 TopicSuggest 查重用，两条数据在类型上就不相交。
package com.example.zhengdao.baopu.cards

import com.example.zhengdao.core.TimeFormats
import java.io.File

/** 一条联网搜索结果（与工具层 WebHit 同形，但不把判据层绑到 internal 类型上）。 */
data class CitationWebHit(
    val url: String,
    val title: String,
    val snippet: String,
    val fetchedAt: Long,
)

/** 一份「相对路径 ＋ 文件」；相对路径就是卡面依据里给人看的那一段。 */
data class CitationSourceDoc(val rel: String, val file: File)

/**
 * 收集依据时只读的视图。**故意不含台账**：台账只用于查重，绝不进依据。
 *
 * 为什么不按「root ＋ 相对路径」现场拼文件：全局人设跟 App 走、不在工作区里，
 * 拼出来会是另一个路径；现场拼路径还得防 `..` 穿越。这里只认**登记过的文件**，
 * 收集与存在性校验共用同一张表，天然只有一套答案。
 */
data class CitationSourceView(
    val docs: List<CitationSourceDoc>,
    val personaRel: String?,
    val webHits: List<CitationWebHit>,
)

/** 本轮联网搜索的结果（进程级；只存纯数据，不存 Context ⇒ 不触发 StaticFieldLeak）。 */
object CitationHitCache {
    @Volatile
    private var hits: List<CitationWebHit> = emptyList()

    fun record(items: List<CitationWebHit>) {
        hits = items.toList()
    }

    fun snapshot(): List<CitationWebHit> = hits
}

object OutputCitationSources {

    const val MAX_PERSONA = 3
    const val MAX_KNOWLEDGE = 2
    const val MAX_WEB = 2

    private val ITEM_LINE = Regex("""^\s*[-*]\s+(.+)$""")
    private val SECTION_LINE = Regex("""^#{2,}\s+(.+)$""")

    fun collect(view: CitationSourceView): List<Citation> =
        personaClauses(view) + knowledgeDocs(view) + webResults(view)

    /** 把闸接到盘上：三类各走自己的「还在不在」判据。 */
    fun exists(view: CitationSourceView): (Citation) -> Boolean = { c ->
        when (c.kind) {
            CitationKind.PERSONA, CitationKind.KNOWLEDGE -> fileLineExists(c.ref, view.docs)
            CitationKind.WEB -> webExists(c.ref, view.webHits)
        }
    }

    /** 「文件:行」还打不打得开：这份文件登记过、还在、行号没越界、那一行不是空的。 */
    fun fileLineExists(ref: String, docs: List<CitationSourceDoc>): Boolean {
        val (rel, line) = CitationRefs.fileLine(ref) ?: return false
        val f = docs.firstOrNull { it.rel == rel }?.file ?: return false
        if (!f.isFile) return false
        val lines = runCatching { f.readLines(Charsets.UTF_8) }.getOrNull() ?: return false
        return line in 1..lines.size && lines[line - 1].isNotBlank()
    }

    /** URL 得是本轮真搜到的那条，日期还得是抓到它的那天（不是随便编的一天）。 */
    fun webExists(ref: String, hits: List<CitationWebHit>): Boolean {
        val (url, date) = CitationRefs.webRef(ref) ?: return false
        return hits.any { it.url == url && TimeFormats.dayStamp(it.fetchedAt) == date }
    }

    /** 人设条目＝文件里 `- ` 开头的列表项，按出现顺序编号；括号里的小节名从文件读，不硬编人格。 */
    private fun personaClauses(view: CitationSourceView): List<Citation> {
        val doc = view.docs.firstOrNull { it.rel == view.personaRel } ?: return emptyList()
        val lines = runCatching { doc.file.readLines(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Citation>(MAX_PERSONA)
        var section = ""
        var n = 0
        lines.forEachIndexed { i, raw ->
            val heading = SECTION_LINE.find(raw)?.groupValues?.get(1)?.trim()
            if (heading != null) section = heading
            val item = ITEM_LINE.find(raw)?.groupValues?.get(1)?.trim() ?: return@forEachIndexed
            n += 1
            if (out.size < MAX_PERSONA) {
                val label = if (section.isEmpty()) "人设第 $n 条" else "人设第 $n 条（$section）"
                out += Citation(CitationKind.PERSONA, "${doc.rel}:${i + 1}", label, item)
            }
        }
        return out
    }

    /** 知识库文档＝每份 md 的第一个非空行；来源同样是「文件:行」。 */
    private fun knowledgeDocs(view: CitationSourceView): List<Citation> =
        view.docs.filter { it.rel != view.personaRel }.take(MAX_KNOWLEDGE).mapNotNull { doc ->
            val lines = runCatching { doc.file.readLines(Charsets.UTF_8) }.getOrNull()
                ?: return@mapNotNull null
            val at = lines.indexOfFirst { it.isNotBlank() }
            if (at < 0) return@mapNotNull null
            Citation(
                CitationKind.KNOWLEDGE,
                "${doc.rel}:${at + 1}",
                "知识库 ${doc.rel}",
                lines[at].trim(),
            )
        }

    /** 搜索结果＝本轮真抓到的 hits；日期由 fetchedAt 换算，不手写。 */
    private fun webResults(view: CitationSourceView): List<Citation> =
        view.webHits.take(MAX_WEB).map { h ->
            Citation(
                CitationKind.WEB,
                "${h.url} ${TimeFormats.dayStamp(h.fetchedAt)}",
                "搜索 ${h.title.take(18)}",
                h.snippet,
            )
        }
}
