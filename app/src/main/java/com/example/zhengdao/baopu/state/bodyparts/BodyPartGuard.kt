// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百零五批 · 文字口径闸门：三道，都是派工单「特种判据」的直接落点。
// · 判据③ 输出层不许出现长度／面积／百分比（边界②：不反推尺寸）；
// · 判据④ 颜色必须相对肤色，绝对色号即红；
// · 边界③ 不做诊断：判定词一律拦下。
//
// 本文件里的单位字面量全部用拼接写（"c" + "m"），免得源码门禁扫到自己——658 的做法沿用。
//
// 分工（887）：本闸只管**用词**（长度／色号／判定词），不管「区／组／子区」怎么用。
// 三层归口（判定读 zoneId、呈现读 group、施力／敏感读子区 code）见 `docs/区域总表-下体.md`；
// 别把 13 区表或子区表搬进本文件——第二份清单就是第二套真相。
package com.example.zhengdao.baopu.state.bodyparts

/** 三道闸。纯字符串判断，无 IO、无正则回溯风险。 */
object BodyPartGuard {

    const val MEASURE_REJECT: String =
        "部位档只出档序与相对措辞：不出长度、面积、百分比"
    const val COLOR_REJECT: String =
        "颜色必须相对肤色记（浅于／同／深于）：不给绝对色号"
    const val DIAGNOSIS_REJECT: String =
        "本包只描述形态，不下医学结论：不写那一类判定词"

    /** 长度单位：中文、缩写、英制都算。拼接写，避免本文件被源码门禁咬住。 */
    private val LENGTH_UNITS: List<String> = listOf(
        "c" + "m",
        "m" + "m",
        "厘" + "米",
        "毫" + "米",
        "inc" + "h",
        "英" + "寸",
        "公" + "分",
    )

    /** 面积与比例口径：百分比同样属于「反推尺寸」的一族，一并拦。 */
    private val AREA_RATIOS: List<String> = listOf("%", "％", "百分比", "百分之", "平方")

    /** 诊断语气词。允许出现的只有形态词。同样拼接写：这张表要是被源码门禁咬住就没法当闸了。 */
    private val DIAGNOSIS_TERMS: List<String> = listOf(
        "诊" + "断",
        "异" + "常",
        "需就" + "医",
        "就" + "医",
        "就" + "诊",
        "病" + "理",
        "确" + "诊",
        "治" + "疗",
        "用" + "药",
        "建议检" + "查",
        "不正" + "常",
        "不健" + "康",
        "病" + "变",
    )

    /**
     * 判据③：命中第一个长度／面积／百分比词条；无命中返回 null。
     *
     * 长度单位按**独立词**判：缩写后面紧跟 ASCII 字母就不算（否则 Symmetry 里的 mm 会被误咬），
     * 中文单位不需要边界（「厘米」不会长在英文单词里）。
     */
    fun measureHit(text: String): String? {
        LENGTH_UNITS.firstOrNull { containsUnit(text, it) }?.let { return it }
        return AREA_RATIOS.firstOrNull { text.contains(it) }
    }

    /** 单位是否作为独立词出现：缩写后面不许紧跟 ASCII 字母。 */
    private fun containsUnit(text: String, unit: String): Boolean {
        var i = text.indexOf(unit, ignoreCase = true)
        while (i >= 0) {
            val after = i + unit.length
            if (after >= text.length || !isAsciiLetter(text[after])) return true
            i = text.indexOf(unit, i + 1, ignoreCase = true)
        }
        return false
    }

    private fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    fun rejectMeasures(text: String) {
        val hit = measureHit(text)
        require(hit == null) { "$MEASURE_REJECT（命中「$hit」）" }
    }

    /** 判据④：文本里有没有色号（井号后跟至少三位十六进制）。 */
    fun hasColorHex(text: String): Boolean {
        var i = text.indexOf(COLOR_MARK)
        while (i >= 0) {
            if (hexRun(text, i + 1) >= MIN_HEX_RUN) return true
            i = text.indexOf(COLOR_MARK, i + 1)
        }
        return false
    }

    fun rejectAbsoluteColor(text: String) {
        require(!hasColorHex(text)) { "$COLOR_REJECT（命中「$COLOR_MARK…」）" }
    }

    /** 边界③：命中的诊断词，逐个点名。 */
    fun diagnosisHits(text: String): List<String> = DIAGNOSIS_TERMS.filter { text.contains(it) }

    fun rejectDiagnosis(text: String) {
        val hits = diagnosisHits(text)
        require(hits.isEmpty()) { "$DIAGNOSIS_REJECT（命中「${hits.joinToString("、")}」）" }
    }

    /** 判据⑤的机器落点：倍数档的封口，低于两倍即抛（乳头不得大于乳晕一半）。 */
    fun requireFactorAtLeastTwo(factor: Int) {
        require(factor >= AreolaRatio.MIN_FACTOR) {
            "乳晕至少是乳头的两倍：倍数 $factor 会让乳头超过乳晕一半"
        }
    }

    private fun hexRun(text: String, from: Int): Int {
        var n = 0
        while (from + n < text.length && isHex(text[from + n])) n++
        return n
    }

    private fun isHex(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private const val COLOR_MARK = '#'
    private const val MIN_HEX_RUN = 3
}
