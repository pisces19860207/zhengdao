// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十五批 · 文字口径闸：禁数值与禁评价。
// 长度／面积／百分比／诊断词直接复用 805 的 BodyPartGuard，本包不造第二套。
// 词表用拼接写，免得源码门禁扫到自己（805/815/866 的做法沿用）。
package com.example.zhengdao.baopu.state.frame

import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard

/** 骨点只描述显露与触及，不做身材评价。 */
object FrameGuard {

    const val VALUE_REJECT: String =
        "骨点只记显露与触及，不下身材评价：不写" + "理" + "想" + "这类价值词"

    private val VALUE_WORDS: List<String> = listOf(
        "理" + "想", "完" + "美", "标" + "准" + "型", "运" + "动" + "型",
        "健" + "美" + "型", "男" + "神", "女" + "神", "好" + "身" + "材",
        "差" + "身" + "材", "极" + "品",
    )

    fun valueHits(text: String): List<String> = VALUE_WORDS.filter { text.contains(it) }

    fun rejectValueWords(text: String) {
        val hits = valueHits(text)
        require(hits.isEmpty()) { VALUE_REJECT + "（命中「" + hits.joinToString("、") + "」）" }
    }

    /** 出口全闸：本包价值词 + 805 的长度/比例/诊断闸。 */
    fun rejectAll(text: String) {
        rejectValueWords(text)
        BodyPartGuard.rejectMeasures(text)
        BodyPartGuard.rejectDiagnosis(text)
    }
}
