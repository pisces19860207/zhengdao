// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百一十批 · 本包自有闸（805 三道闸之外）：
// · 判据② 硬度与尺寸独立：把大小与硬度揉成一个「综合档／大而硬」即红；
// · 判据③ 禁数值：倍数、长度、角度值——805 的 measureHit 管长度/百分比，这里补倍数与角度；
// · 判据⑤ 占位逐字：只收 pending:775／pending:814，自造子区名或截面名即红。
//
// 敏感词与单位全部用拼接写，免得源码门禁扫到自己（沿用 658/805 做法）。
package com.example.zhengdao.baopu.state.erect

/** 本包自有闸。纯字符串判断，无 IO、无正则回溯风险。 */
object ErectGuard {

    const val MERGED_REJECT: String =
        "硬度与尺寸必须分两件记：不给「" + "综合" + "档」，也不把大小说成硬度"
    const val FACTOR_REJECT: String =
        "增长只给档不给" + "倍数" + "：不写倍数、不出尺寸"
    const val ANGLE_VALUE_REJECT: String =
        "角度只给方向档：不出" + "度数" + "或角度数值"
    const val PENDING_REJECT: String =
        "占位只写 pending:<单号>：775 子区、814 截面不自造名字"

    /** 判据②：把尺寸与硬度揉成一档的说法。拼接写，避免自咬。 */
    private val MERGED_TERMS: List<String> = listOf(
        "综合" + "档", "软硬" + "兼", "大小" + "即硬", "大" + "就硬",
        "大" + "而硬", "硬" + "度等" + "于" + "大", "size" + "Hard", "combined" + "Grade",
    )

    /** 判据③：倍数与尺寸说法（805 的长度单位另由 BodyPartGuard 管）。 */
    private val FACTOR_TERMS: List<String> = listOf(
        "倍数", "倍", "增长" + "率", "放大" + "倍",
        "c" + "m", "m" + "m", "厘" + "米", "毫" + "米",
    )

    /**
     * 判据③：角度数值写法。方向档名（上翘／略上／平／略下）和「角度数值」这类描述词
     * 都不算——只拦真正带数值笔意的写法（度数符号、斜率、弧度、仰角/俯角配数）。
     * 不收「度数」这个通用子串：正常的「角度数值」里就含它，收了就是误伤。
     */
    private val ANGLE_VALUE_TERMS: List<String> = listOf(
        "度角", "°", "斜" + "率", "弧" + "度",
        "仰" + "角", "俯" + "角",
    )

    /** 允许的占位字面量。要加新占位必须改这里并补测试（冻结表）。 */
    val ALLOWED_PENDING: Set<String> = setOf(PENDING_SUBZONE, PENDING_SECTION)

    /** 判据②：命中合并说法即返回词条，无命中 null。 */
    fun mergedHit(text: String): String? = MERGED_TERMS.firstOrNull { text.contains(it) }

    fun rejectMerged(text: String) {
        val hit = mergedHit(text)
        require(hit == null) { "$MERGED_REJECT（命中「$hit」）" }
    }

    /** 判据③：倍数与长度。长度按独立词判（缩写后紧跟字母不算）。 */
    fun factorHit(text: String): String? =
        FACTOR_TERMS.firstOrNull { containsUnit(text, it) }

    fun rejectFactor(text: String) {
        val hit = factorHit(text)
        require(hit == null) { "$FACTOR_REJECT（命中「$hit」）" }
    }

    /** 判据③：角度数值。 */
    fun angleValueHit(text: String): String? = ANGLE_VALUE_TERMS.firstOrNull { text.contains(it) }

    fun rejectAngleValue(text: String) {
        val hit = angleValueHit(text)
        require(hit == null) { "$ANGLE_VALUE_REJECT（命中「$hit」）" }
    }

    /**
     * 判据⑤：文本里出现的 `pending:单号` 必须是冻结表里的两个之一；
     * 并拦住截面/子区的自造名（那是 814/775 的账）。
     */
    fun rejectInventedPlaceholder(text: String) {
        val pendingTokens = PENDING_PATTERN.findAll(text).map { it.value }.toList()
        val bad = pendingTokens.filterNot { it in ALLOWED_PENDING }
        require(bad.isEmpty()) { "$PENDING_REJECT（收到「${bad.joinToString("、")}」）" }
        val invented = INVENTED_TERMS.firstOrNull { text.contains(it) }
        require(invented == null) { "$PENDING_REJECT（自造名「$invented」）" }
    }

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

    private val PENDING_PATTERN = Regex("pending:[A-Za-z0-9_]+")

    /** 截面/子区自造名：这些是 814/775 的账，本包不许提前定义（拼接写避免自咬）。 */
    private val INVENTED_TERMS: List<String> = listOf(
        "横" + "截面", "截面" + "图", "壁厚", "子区" + "id", "子区" + "名",
        "海绵体" + "截面",
    )
}
