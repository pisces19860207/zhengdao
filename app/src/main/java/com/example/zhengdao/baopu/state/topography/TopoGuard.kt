// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十六批 · 一道本包自有的闸：厚度读数不许借用形状词。
//
// 为什么单独立这一道：848 已经有一套形状词（块、白线、外高内低、倒三角…）。
// 厚度读数最容易顺手写成形状——「胸厚得像倒三角」「背阔直下」——一旦这么写，
// 厚度就在替形状说话，两单的账立刻混成一本（判据④「厚度≠形状」的落点）。
// 所以厚度的备注单独过这一道；凸起／凹陷不设这道闸，它们本来就该用起伏词。
//
// 长度／百分比／诊断词不在这里管：那三道闸是 805 的 BodyPartGuard，全包共用一套口径。
package com.example.zhengdao.baopu.state.topography

/** 厚度备注的形状词闸。纯字符串判断，无 IO、无正则回溯风险。 */
object TopoGuard {

    const val SHAPE_LEAK_REJECT: String =
        "厚度读数不许借用形状词：厚度是前后多深，形状归 848，两本账不许混"

    /**
     * 848 的形状词表。**只收与厚度绝不混用的那些**：
     * 浅／中／深／隐约 是本包自己的档名（凹陷与凸起也在用），不能当形状词拦，否则误伤。
     */
    private val SHAPE_WORDS: List<String> = listOf(
        "界不清", "被脂覆盖", "等高", "略错", "明显错",
        "外高内低", "外低内高", "圆钝", "倒三角", "下缘外翻", "直下",
        "块", "前锯", "背阔", "白线", "胸沟", "窄", "宽",
    )

    /** 命中的形状词，逐个点名。 */
    fun shapeHits(text: String): List<String> = SHAPE_WORDS.filter { text.contains(it) }

    fun rejectShapeWords(text: String) {
        val hits = shapeHits(text)
        require(hits.isEmpty()) { SHAPE_LEAK_REJECT + "（命中「" + hits.joinToString("、") + "」）" }
    }
}
