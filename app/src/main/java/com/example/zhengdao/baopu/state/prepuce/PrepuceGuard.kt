// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百零四批 · 包皮专批：三条文字口径的机器闸门。
//
// 只做三件事，都是判据的直接落点：
// · 判据⑤ 冠／沟合名（「冠沟」「冠与沟」「corona+sulcus」）⇒ 红；
//    唯一例外是解剖固定术语「冠状沟」，它只指沟（与 660 的 g.sulcus 同名）。
// · 判据④ 出百分比／cm² ⇒ 红（覆盖只记子区三态与五档，不给量）。
// · 判据② 「露冠」不得推「露沟」：退至冠后＝corona 露出＋sulcus 仍覆，两区各记一笔。
package com.example.zhengdao.baopu.state.prepuce

/** 文字口径闸门。纯字符串判断，无 IO、无正则回溯风险。 */
object PrepuceGuard {

    const val MERGED_NAME_REJECT: String =
        "冠与沟必须逐项独立：不许用「冠沟」这类合名代替 corona 与 sulcus"
    const val MEASURE_REJECT: String =
        "包皮覆盖不出百分比／cm²：只记子区三态与五档口径"
    const val CORONA_NOT_SULCUS: String =
        "「露冠」不得推「露沟」：退至冠后＝冠露出、沟仍被盖，两区各记一笔，不属五档"

    /** 解剖固定术语：只指沟。放进白名单是因为它天生含「冠」字，不代表冠。 */
    private const val FIXED_SULCUS_TERM = "冠状沟"
    private const val BLANK_TOKEN = "〇"

    private val MEASURE_HINTS = listOf("%", "％", "cm", "厘米", "毫米", "平方")
    private val CORONA_HINTS = listOf("冠", "corona")
    private val SULCUS_HINTS = listOf("沟", "sulcus")

    /**
     * 判据⑤：这个区名是不是把冠与沟合成了一格。
     *
     * 判法：先把白名单术语「冠状沟」替换掉，剩下的字样里若**同时**出现冠与沟的指称 ⇒ 合名；
     * 若区名里出现了「冠状沟」又在别处提到冠（如「冠状沟＋冠」）⇒ 也是合名。
     */
    fun isMergedAreaName(name: String): Boolean {
        val text = name.lowercase().filterNot { it.isWhitespace() }
        if (text.isEmpty()) return false
        val hasFixedSulcus = text.contains(FIXED_SULCUS_TERM)
        val rest = text.replace(FIXED_SULCUS_TERM, BLANK_TOKEN)
        val corona = CORONA_HINTS.any { rest.contains(it) }
        val sulcusElsewhere = SULCUS_HINTS.any { rest.contains(it) }
        return corona && (sulcusElsewhere || hasFixedSulcus)
    }

    /** 合名即抛。 */
    fun requireSeparateAreaName(name: String) {
        require(!isMergedAreaName(name)) { "$MERGED_NAME_REJECT（收到「$name」）" }
    }

    /** 判据④：文字里出现百分比或面积单位即抛。空串与纯文字不抛。 */
    fun rejectMeasures(text: String) {
        val hit = MEASURE_HINTS.firstOrNull { text.contains(it, ignoreCase = true) }
        require(hit == null) { "$MEASURE_REJECT（命中「$hit」）" }
    }

    /** 判据②的判据本体：冠露出而沟仍被盖 ＝ 退至冠后这个中间态。 */
    fun isRetractedBehindCorona(corona: CoverState, sulcus: CoverState): Boolean =
        corona == CoverState.EXPOSED && sulcus != CoverState.EXPOSED
}
