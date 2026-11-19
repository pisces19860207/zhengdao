// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百三十九批 · 三化抑制：输出侧风格病灶判据（尺寸通胀 / 医学免责腔 / 模板腔）。
// 与 669 分工：669 管"写错了什么"（事实误写），本单管"写成什么腔"（风格病灶）。
// 阈值与词表一律来自 AntiSlopBaseline（带 source/asOf），本文件不写死任何阈值数字或词表。
// 纯函数：零模型、零联网、零随机、零时钟、零写盘；只报不改写，原文一字不动。
// 严重度：BLOCK＝拦（只用于 A 无来源夸大与 B 建议词，派工单 §二⑤）；WARN＝提示。
package com.example.zhengdao.baopu.antislop

/** 处置档：BLOCK 判红且拦；WARN 判红但只提示、不拦、不改写。 */
enum class SlopSeverity { BLOCK, WARN }

/** 一条风格病灶。span＝原文字符偏移 [start,end)；code 形如 A-NO-SOURCE-EXAGGERATION。 */
data class SlopFinding(
    val rule: String,
    val code: String,
    val severity: SlopSeverity,
    val start: Int,
    val end: Int,
    val reason: String,
)

/** 三项可机检读数（派工单 §二④）：黑名单每 N 字命中、段尾总结率、四字词每 N 字密度。 */
data class SlopMetrics(
    val hedgePerWindow: Double,
    val blacklistPerWindow: Double,
    val summaryRate: Double,
    val fourCharPerWindow: Double,
)

data class SlopReport(val findings: List<SlopFinding>, val metrics: SlopMetrics)

/** 三化检测器。构造吃一份基线；三条 scan 与 metrics 都是纯函数。 */
class AntiSlopGuard(private val cfg: AntiSlopBaseline) {

    init {
        require(cfg.a.inflationMarkers.isNotEmpty() && cfg.b.hedges.isNotEmpty() && cfg.c.blacklist.isNotEmpty()) {
            "三条规则词表不得为空，拒绝装配（fail-closed）"
        }
    }

    /** 全文判定：固定规则序 A→B→C，findings 按偏移稳定排序。 */
    fun analyze(text: String): SlopReport {
        val paras = paragraphsOf(text)
        val findings = (scanA(text) + scanB(text) + scanC(text))
            .sortedWith(compareBy({ it.start }, { it.end }, { it.code }))
        return SlopReport(findings, metricsOf(text, paras, charCount(text)))
    }

    // ── A 尺寸通胀 ────────────────────────────────────────────────────────

    fun scanA(text: String): List<SlopFinding> = noSourceFindings(text) + defaultFindings(text)

    /** 夸大词命中须在同一子句内同时带稀有度标记与来源 id；否则 BLOCK。 */
    private fun noSourceFindings(text: String): List<SlopFinding> {
        val out = mutableListOf<SlopFinding>()
        for (cl in clausesOf(text)) {
            val hit = cfg.a.inflationMarkers.firstOrNull { it in cl.body } ?: continue
            if (cfg.a.rarityMarkers.any { it in cl.body } && cfg.a.sourceMarkers.any { it in cl.body }) continue
            val at = cl.body.indexOf(hit)
            out += SlopFinding(
                "A", "A-NO-SOURCE-EXAGGERATION", SlopSeverity.BLOCK,
                cl.start + at, cl.start + at + hit.length,
                "夸大表述「$hit」须同时带稀有度标记与来源 id",
            )
        }
        return out
    }

    /** 默认值必须来自 665 区间（P50 附近）；越界或写成夸大词只提示。 */
    private fun defaultFindings(text: String): List<SlopFinding> {
        val out = mutableListOf<SlopFinding>().also { it += rangeFindings(text) }
        for (cl in clausesOf(text)) {
            val cue = cfg.a.defaultCues.firstOrNull { it in cl.body } ?: continue
            if (cfg.a.inflationMarkers.none { it in cl.body }) continue
            val at = cl.body.indexOf(cue)
            out += SlopFinding(
                "A", "A-DEFAULT-INFLATION", SlopSeverity.WARN,
                cl.start + at, cl.start + at + cue.length,
                "默认值不许写夸大词，须来自 665 区间（P50 附近）",
            )
        }
        return out
    }

    private fun rangeFindings(text: String): List<SlopFinding> {
        val out = mutableListOf<SlopFinding>()
        val re = Regex(cfg.a.defaultValuePattern)
        for (m in re.findAll(text)) {
            val value = m.groupValues.getOrNull(1)?.toDoubleOrNull() ?: continue
            val unit = m.destructured.component2()
            val mm = value * unitFactor(unit)
            if (mm in cfg.a.defaultBandLowMm..cfg.a.defaultBandHighMm) continue
            out += SlopFinding(
                "A", "A-DEFAULT-OUT-OF-BAND", SlopSeverity.WARN,
                m.range.first, m.range.last + 1,
                "默认值 ${m.value} 不在 665 区间（P50 附近）",
            )
        }
        return out
    }

    private fun unitFactor(unit: String): Double = when (unit) {
        "mm", "毫米" -> cfg.a.mmPerMm
        else -> cfg.a.mmPerCm
    }

    // ── B 医学免责腔 ────────────────────────────────────────────────────────

    fun scanB(text: String): List<SlopFinding> =
        adviceFindings(text) + densityFinding(text) + consecutiveFinding(text) + disclaimerFinding(text)

    /** 建议禁词（治疗/预防/卫生建议，走 728 禁词）⇒ BLOCK。 */
    private fun adviceFindings(text: String): List<SlopFinding> {
        val out = mutableListOf<SlopFinding>()
        for (w in cfg.b.adviceBanWords) {
            val at = text.indexOf(w)
            if (at >= 0) out += SlopFinding(
                "B", "B-ADVICE-BAN", SlopSeverity.BLOCK, at, at + w.length,
                "建议禁词「$w」命中（走禁词组）",
            )
        }
        return out
    }

    private fun densityFinding(text: String): List<SlopFinding> {
        val chars = charCount(text)
        if (chars == 0) return emptyList()
        val hits = cfg.b.hedges.sumOf { countOccurrences(text, it) }
        val per = hits.toDouble() * cfg.window.hedgeChars / chars
        if (per <= cfg.b.maxHedgePer100) return emptyList()
        return listOf(SlopFinding("B", "B-HEDGE-DENSITY", SlopSeverity.WARN, 0, 0, "hedge 密度 $per 超上限"))
    }

    /** 连续若干段都出现 hedge ⇒ 判红（只提示）。 */
    private fun consecutiveFinding(text: String): List<SlopFinding> {
        val paras = paragraphsOf(text)
        var run = 0
        for (p in paras) {
            run = if (cfg.b.hedges.any { it in p.body }) run + 1 else 0
            if (run < cfg.b.consecutiveHedgeParagraphs) continue
            return listOf(SlopFinding("B", "B-CONSECUTIVE-HEDGE", SlopSeverity.WARN, p.start, p.end, "连续多段出现 hedge"))
        }
        return emptyList()
    }

    private fun disclaimerFinding(text: String): List<SlopFinding> {
        val hits = countOccurrences(text, cfg.b.globalDisclaimer)
        if (hits <= cfg.b.maxGlobalDisclaimer) return emptyList()
        return listOf(SlopFinding("B", "B-DISCLAIMER-REPEAT", SlopSeverity.WARN, 0, 0, "全局免责句只允许一句"))
    }

    // ── C 模板腔 ────────────────────────────────────────────────────────────

    fun scanC(text: String): List<SlopFinding> {
        val m = metricsOf(text, paragraphsOf(text), charCount(text))
        val out = mutableListOf<SlopFinding>()
        if (m.blacklistPerWindow > cfg.c.maxBlacklistPer500) out += findingC("C-BLACKLIST-RATE", "黑名单词组命中率超限")
        if (m.summaryRate > cfg.c.maxSummaryRate) out += findingC("C-SUMMARY-RATE", "段尾总结率超限")
        if (m.fourCharPerWindow > cfg.c.fourCharMaxPer100) out += findingC("C-FOUR-CHAR-DENSITY", "四字词密度超限（只提示）")
        return out
    }

    private fun findingC(code: String, reason: String) = SlopFinding("C", code, SlopSeverity.WARN, 0, 0, reason)

    /** 三项读数＋hedge 读数，恒计算，不受阈值影响（供判据④取证）。 */
    fun metrics(text: String): SlopMetrics = metricsOf(text, paragraphsOf(text), charCount(text))

    private fun metricsOf(text: String, paras: List<Para>, chars: Int): SlopMetrics {
        if (chars == 0) return SlopMetrics(0.0, 0.0, 0.0, 0.0)
        return SlopMetrics(
            hedgePerWindow = per(text, cfg.b.hedges, cfg.window.hedgeChars, chars),
            blacklistPerWindow = per(text, cfg.c.blacklist, cfg.window.blacklistChars, chars),
            summaryRate = summaryRate(paras),
            fourCharPerWindow = per(text, cfg.c.fourCharLexicon, cfg.window.fourCharChars, chars),
        )
    }

    private fun per(text: String, terms: List<String>, window: Int, chars: Int): Double =
        terms.sumOf { countOccurrences(text, it) }.toDouble() * window / chars

    private fun summaryRate(paras: List<Para>): Double {
        if (paras.isEmpty()) return 0.0
        val tails = paras.count { p ->
            val clean = TRAIL.replace(p.body.trimEnd(), "")
            cfg.c.summaryMarkers.any { clean.endsWith(it) }
        }
        return tails.toDouble() / paras.size
    }

    private fun charCount(text: String) = text.count { !it.isWhitespace() }

    private fun countOccurrences(text: String, term: String): Int {
        if (term.isEmpty()) return 0
        var n = 0
        var at = text.indexOf(term)
        while (at >= 0) {
            n++
            at = text.indexOf(term, at + term.length)
        }
        return n
    }

    private fun clausesOf(text: String): List<Clause> {
        val out = mutableListOf<Clause>()
        var start = 0
        for (i in text.indices) {
            if (text[i] !in BREAKS) continue
            if (i > start) out += Clause(text.substring(start, i), start, i)
            start = i + 1
        }
        if (start < text.length) out += Clause(text.substring(start), start, text.length)
        return out
    }

    private fun paragraphsOf(text: String): List<Para> {
        val out = mutableListOf<Para>()
        var cursor = 0
        for (line in text.lines()) {
            if (line.isNotBlank()) out += Para(line, cursor, cursor + line.length)
            cursor += line.length + 1
        }
        return out
    }

    private data class Clause(val body: String, val start: Int, val end: Int)
    private data class Para(val body: String, val start: Int, val end: Int)

    private companion object {
        val BREAKS = charArrayOf('。', '！', '？', '；', '\n')
        val TRAIL = Regex("[。！？；，,.!?;]+$")
    }
}
