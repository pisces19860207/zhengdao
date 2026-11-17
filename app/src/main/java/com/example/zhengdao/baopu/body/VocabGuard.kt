// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十九批 · 形轨.部位词表误写检测（vocab_guard）＋宾转主写法提示。
// 依据＝docs/facts/anatomy-zones.json（13 区，与 660 共用）＋事实底盘 v0.7 §十五。
// 十条规则按子句做确定性词面共现：闭集词表、零模型、零联网、零随机、零时钟。
// **只报不改写**：scan 返回 findings（rule_id/severity/span/正确说法/zone_id），原文一字不动。
// suggest 是写法提示（§15.3 宾语→主语），不拦、不进 findings。
package com.example.zhengdao.baopu.body

/** 误写严重度：error＝解剖事实错；warn＝口径缺失/状态简化。 */
enum class GuardSeverity(val label: String) { ERROR("error"), WARN("warn") }

/** 一条误写发现。span＝原文字符偏移 [start, end)；correction＝正确说法；zoneId＝依据分区。 */
data class GuardFinding(
    val ruleId: String,
    val severity: GuardSeverity,
    val start: Int,
    val end: Int,
    val correction: String,
    val zoneId: String,
)

/** 一条写法提示（不拦）：某部位词全部出现都在宾语位 ⇒ 建议挪到主语位。 */
data class WritingSuggestion(
    val zoneId: String,
    val term: String,
    val start: Int,
    val end: Int,
    val advice: String,
)

/**
 * 词表误写检测器。构造时吃同一份 zone json（规则依据的 zone_id 必须在表里，
 * 否则抛错——检测器与口径表不许漂）。scan/suggest 都是纯函数。
 */
class VocabGuard(private val zones: List<AnatomyZone>) {

    init {
        require(zones.isNotEmpty()) { "分区表为空，拒绝装配（fail-closed）" }
        val ids = zones.map { it.zoneId }.toSet()
        for (c in CHECKS) {
            require(c.zoneId in ids) { "规则 ${c.ruleId} 的依据分区不在表里：${c.zoneId}" }
        }
    }

    /** 十条规则扫全文：固定规则序×子句序，结果按 (start, end, ruleId) 稳定排序。 */
    fun scan(text: String): List<GuardFinding> {
        val clauses = clausesOf(text)
        val out = mutableListOf<GuardFinding>()
        for (c in CHECKS) {
            for (cl in clauses) {
                if (c.hits(cl.body)) {
                    out += GuardFinding(c.ruleId, c.severity, cl.start, cl.end, c.correction, c.zoneId)
                }
            }
        }
        return out.sortedWith(compareBy({ it.start }, { it.end }, { it.ruleId }))
    }

    /** ③ 写法提示：部位词只作宾语出现 ⇒ 提示挪到主语（不拦）。单字别名不进提示层。 */
    fun suggest(text: String): List<WritingSuggestion> {
        val out = mutableListOf<WritingSuggestion>()
        for ((term, zone) in termOwners()) {
            val occ = occurrencesOf(text, term)
            if (occ.isEmpty() || !occ.all { isObjectAt(text, it) }) continue
            val first = occ.first()
            out += WritingSuggestion(
                zoneId = zone.zoneId,
                term = term,
                start = first,
                end = first + term.length,
                advice = "把「$term」从宾语挪到主语：让${zone.name}作描述对象（部位＋特征/变化），" +
                    "结构自然出现（事实底盘 §15.3）。",
            )
        }
        return out
    }

    /** 词→区归属：name＋aliases，长度≥2，按表序首区独占（「耻骨」撞车归 radix）。 */
    private fun termOwners(): List<Pair<String, AnatomyZone>> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Pair<String, AnatomyZone>>()
        for (z in zones) {
            for (t in (listOf(z.name) + z.aliases)) {
                if (t.length >= 2 && seen.add(t)) out += t to z
            }
        }
        return out
    }

    private fun occurrencesOf(text: String, term: String): List<Int> {
        val out = mutableListOf<Int>()
        var i = text.indexOf(term)
        while (i >= 0) {
            out += i
            i = text.indexOf(term, i + term.length)
        }
        return out
    }

    /** 宾语位：及物动词闭集里某词恰好结束于该出现之前。 */
    private fun isObjectAt(text: String, idx: Int): Boolean =
        OBJECT_VERBS.any { v -> idx >= v.length && text.startsWith(v, idx - v.length) }

    /** 子句切分：。！？；换行；span 不含分隔符。 */
    private fun clausesOf(text: String): List<Clause> {
        val out = mutableListOf<Clause>()
        var start = 0
        for (i in text.indices) {
            if (text[i] in "。！？；\n") {
                if (i > start) out += Clause(text.substring(start, i), start, i)
                start = i + 1
            }
        }
        if (start < text.length) out += Clause(text.substring(start), start, text.length)
        return out
    }

    private data class Clause(val body: String, val start: Int, val end: Int)

    private data class Check(
        val ruleId: String,
        val severity: GuardSeverity,
        val zoneId: String,
        val correction: String,
        val hits: (String) -> Boolean,
    )

    private companion object {

        fun has(vararg words: String): (String) -> Boolean = { s -> words.any { it in s } }

        fun lacks(vararg words: String): (String) -> Boolean = { s -> words.none { it in s } }

        fun both(a: (String) -> Boolean, b: (String) -> Boolean): (String) -> Boolean =
            { s -> a(s) && b(s) }

        fun triple(a: (String) -> Boolean, b: (String) -> Boolean, c: (String) -> Boolean): (String) -> Boolean =
            { s -> a(s) && b(s) && c(s) }

        /** 宾语位动词闭集（suggest 用；检测层不用）。 */
        val OBJECT_VERBS = listOf(
            "握住", "握", "抵住", "抵", "含住", "含", "舔", "抚摸", "抚", "摸",
            "捏", "揉", "套弄", "撸", "咬", "吻", "碰", "触", "抓", "托",
            "掐", "捻", "蹭", "顶住", "顶", "吞", "衔", "包裹", "裹住", "夹住",
            "夹", "揉捏", "抚弄", "拨弄", "挑逗", "刺激", "触碰", "摩挲", "搓", "压",
        )

        /** 十条规则（VG-08 按三个分区各一条检查，共用 rule_id）。顺序即输出序的一部分。 */
        val CHECKS = listOf(
            Check(
                "VG-01", GuardSeverity.ERROR, "frenulum",
                "系带位于腹侧，是连接包皮与龟头的小褶，不在背面。",
                both(both(has("系带"), has("背侧", "背面")), lacks("不在背侧", "不是背侧", "非背侧", "不在背面", "不是背面")),
            ),
            Check(
                "VG-02", GuardSeverity.ERROR, "meatus",
                "尿道口是龟头尖端的纵裂，通常略偏腹侧，不是正中心的圆孔。",
                both(
                    both(has("尿道口", "马眼", "尿道外口"), has("圆孔", "正中心", "正中")),
                    lacks("不是正中心", "不在正中", "不是圆孔", "并非圆孔", "非圆孔"),
                ),
            ),
            Check(
                "VG-03", GuardSeverity.ERROR, "glans",
                "龟头是黏膜样表面，比柱身更光滑更薄，颜色通常深一档，不与柱身同质同色。",
                both(has("龟头"), has("同质同色", "同质", "同色", "质地相同", "质地一样", "颜色相同", "颜色一样")),
            ),
            Check(
                "VG-04", GuardSeverity.WARN, "scrotum",
                "阴囊松紧随温度与提睾反射持续变化：冷时收缩上提、热时松弛下垂，不是恒定状态。",
                triple(
                    has("阴囊", "囊袋"),
                    has("永远", "始终", "恒定", "一直", "总是", "从不"),
                    has("紧缩", "紧绷", "沉甸甸", "松弛", "下垂", "固定"),
                ),
            ),
            Check(
                "VG-05", GuardSeverity.ERROR, "testis",
                "左右睾丸通常不等高，左侧一般略低，附睾贴在后方。",
                both(has("睾丸"), has("同高", "等高", "对称", "一样高", "高度相同", "完全相同")),
            ),
            Check(
                "VG-06", GuardSeverity.ERROR, "corona",
                "龟头冠是龟头近端的隆起边缘、最宽的一圈；凸起球状是犬科结构，人类没有。",
                both(has("龟头冠", "冠状", "冠部"), has("球状", "球形", "球结", "圆球", "凸起球")),
            ),
            Check(
                "VG-07", GuardSeverity.ERROR, "shaft",
                "柱身背侧两条阴茎海绵体偏硬、腹侧尿道海绵体偏软，横截面偏椭圆，有背腹差异。",
                both(
                    has("柱身", "阴茎体"),
                    has("均匀圆柱", "均匀的圆柱", "单一均匀", "无背腹", "没有背腹", "背腹一致", "背腹相同", "背腹没有差别"),
                ),
            ),
            Check(
                "VG-08", GuardSeverity.WARN, "raphe",
                "阴茎缝是腹侧正中一条色略深的线，从会阴延续至阴囊中缝——提到它请带结构说明。",
                both(has("阴茎缝", "中缝"), lacks("腹侧", "色略深", "颜色略深", "线", "会阴", "阴囊", "延续")),
            ),
            Check(
                "VG-08", GuardSeverity.WARN, "cord",
                "精索是从腹股沟延伸至睾丸后上方的条索，可触及——提到它请带结构说明。",
                both(has("精索"), lacks("条索", "腹股沟", "触及", "可触", "睾丸", "粗细", "后上方")),
            ),
            Check(
                "VG-08", GuardSeverity.WARN, "prepuce",
                "包皮分内板（黏膜、薄、湿润）与外板（皮肤、有毛囊），环切与否状态不同——提到它请带结构说明。",
                both(has("包皮内板", "包皮外板", "内板", "外板"), lacks("黏膜", "皮肤", "毛囊", "湿润", "环切", "薄")),
            ),
            Check(
                "VG-09", GuardSeverity.ERROR, "radix",
                "根部埋于会阴，体表不可见；可见长度受耻骨前脂肪厚度影响。",
                both(
                    both(has("根部", "阴茎根"), has("可见", "露出", "看到", "看见", "眼前", "挺立", "显现", "暴露")),
                    lacks("不可见", "埋于", "看不见", "看不到", "体表不"),
                ),
            ),
            Check(
                "VG-10", GuardSeverity.ERROR, "shaft",
                "周长是绕一圈的长度，直径≈周长÷π，两者不可互换。",
                both(
                    both(has("周长"), has("直径", "粗度", "粗细", "般粗", "一样粗")),
                    lacks("除以", "换算", "不等于", "不能混", "不是同一", "区别"),
                ),
            ),
        )
    }
}