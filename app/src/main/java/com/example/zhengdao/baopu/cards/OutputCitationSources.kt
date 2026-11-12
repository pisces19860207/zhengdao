// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十五批 · **依据的来源侧**（收集 + 存在性校验 + 本轮搜索结果缓存）。
//
// 与 [OutputCitationGate] 的分工：闸只判「有没有、形状对不对、还在不在」，
// 「还在不在」由这里的 [exists] 注入；收集也只认人设／知识库／搜索三类文件，
// **台账一次都不读**——台账归 TopicSuggest 查重用，两条数据在类型上就不相交。
//
// 616：搜索结果的缓存 [CitationHitCache] 按**来源域**分键（[CitationDomain]）——
// debug 预览页的示例造数只进预览域；出卡侧的收集与存在性都只认生产域，
// 「先开预览、再正式出卡」的示例引据到不了卡上（缺陷现场见 570/569）。
package com.example.zhengdao.baopu.cards

import com.example.zhengdao.core.TimeFormats
import java.io.File

/**
 * 引据的来源域（[CitationHitCache] 的**键型**）：生产检索与预览造数各占一个域，跨域不可命中。
 *
 * 616 的缺陷形态：进程级缓存不分域，debug 预览页灌进来的示例数据（news.example/hot）
 * 会被正式出卡当成真依据。键带来源域之后，「示例引据进生产卡」要先做出一个**显式**
 * 的跨域动作才写得出来，而这一步被出卡侧（[OutputCitationSources] 的收集与存在性，
 * 按视图域 [CitationSourceView.domain]）拦死。
 */
enum class CitationDomain(
    /** 示例/预览域为 true（616③：生产出卡时这样的引据一律拒）；生产域为 false。 */
    val sample: Boolean,
) {
    /** 正式出卡用的检索结果（SearchWebTool 真抓的）。 */
    PRODUCTION(sample = false),

    /** debug 预览页造的示例数据；只进自己的域，生产读不到（616）。 */
    PREVIEW(sample = true),
}

/** 一条联网搜索结果（与工具层 WebHit 同形，但不把判据层绑到 internal 类型上）。 */
data class CitationWebHit(
    val url: String,
    val title: String,
    val snippet: String,
    val fetchedAt: Long,
    /**
     * 来源域。默认＝生产：生产侧（SearchWebTool）零改动；预览造数必须**显式**
     * 点名 PREVIEW——把示例数据写进生产缓存从此是一个写出来的动作（616②）。
     */
    val source: CitationDomain = CitationDomain.PRODUCTION,
)

/** 一份「相对路径 ＋ 文件」；相对路径就是卡面依据里给人看的那一段。 */
data class CitationSourceDoc(val rel: String, val file: File)

/**
 * 收集依据时只读的视图。**故意不含台账**：台账只用于查重，绝不进依据。
 *
 * 为什么不按「root ＋ 相对路径」现场拼文件：全局人设跟 App 走、不在工作区里，
 * 拼出来会是另一个路径；现场拼路径还得防 `..` 穿越。这里只认**登记过的文件**，
 * 收集与存在性校验共用同一张表，天然只有一套答案。
 *
 * @param domain 本视图为哪个域出卡（616③，默认＝生产）：收集与存在性只认**同域**
 *   的搜索结果——生产视图拒示例/预览引据；预览页自己的演示视图则合法地用 PREVIEW 域。
 */
data class CitationSourceView(
    val docs: List<CitationSourceDoc>,
    val personaRel: String?,
    val webHits: List<CitationWebHit>,
    val domain: CitationDomain = CitationDomain.PRODUCTION,
)

/**
 * 本轮联网搜索的结果（进程级；只存纯数据，不存 Context ⇒ 不触发 StaticFieldLeak）。
 *
 * **键＝来源域**（616④）：读写都经 `domain` 参数索引，存储按 [CitationDomain] 分键；
 * 不点名域即生产（默认参数＝生产，生产读写点因此零改动），预览侧的 PREVIEW 由
 * 源码断言钉住（OutputCitationWiringTest）。跨域读写互不可见 ⇒ 先开预览、再正式出卡，
 * 示例引据到不了卡上。
 */
object CitationHitCache {
    @Volatile
    private var hitsByDomain: Map<CitationDomain, List<CitationWebHit>> = emptyMap()

    fun record(items: List<CitationWebHit>, domain: CitationDomain = CitationDomain.PRODUCTION) {
        val next = hitsByDomain.toMutableMap()
        next[domain] = items.toList()
        hitsByDomain = next
    }

    fun snapshot(domain: CitationDomain = CitationDomain.PRODUCTION): List<CitationWebHit> =
        hitsByDomain[domain].orEmpty()
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
            CitationKind.WEB -> webExists(c.ref, view.webHits.ofDomain(view.domain))
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

    /**
     * 本视图域内的搜索结果（616③）：出卡时校验依据来源——别的域（示例/预览）的
     * 引据一律不算数。生产视图里它们进不了卡面、也过不了存在性。
     */
    private fun List<CitationWebHit>.ofDomain(domain: CitationDomain): List<CitationWebHit> =
        filter { it.source == domain }

    /**
     * URL 得是本轮真搜到的那条，日期还得是抓到它的那天（不是随便编的一天）。
     * 域的校验在 [exists] 注入前完成（只把本域的 hits 递进来）。
     */
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

    /** 搜索结果＝本视图域内真抓到的 hits；日期由 fetchedAt 换算，不手写（616③）。 */
    private fun webResults(view: CitationSourceView): List<Citation> =
        view.webHits.ofDomain(view.domain).take(MAX_WEB).map { h ->
            Citation(
                CitationKind.WEB,
                "${h.url} ${TimeFormats.dayStamp(h.fetchedAt)}",
                "搜索 ${h.title.take(18)}",
                h.snippet,
            )
        }
}
