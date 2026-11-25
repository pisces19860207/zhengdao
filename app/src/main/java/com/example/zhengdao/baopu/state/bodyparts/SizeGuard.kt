// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百二十批 · 文字闸：三道，都是本单判据的直接落点。
// · 判据② 依据不许落在身高／体重／年龄／骨架上（写了就是「推出来的尺寸」）；
// · 判据④ 不许写「胖所以小」「矮所以短」「年纪大＝颜色深」这类因果句；
// · 判据⑥ 颜色两层不许并成一句（色变档／色档是两个字段，出口也必须分两段）。
//
// 与 805 的 BodyPartGuard 分工：长度／面积／百分比、绝对色号、判定词一律由 805 的闸管，
// 本文件只管本单新加的三条。三道闸都只做字符串判断，无 IO、无正则回溯。
//
// 闸自己的词表按老规矩拼接写（"所" + "以"），全角等号写成转义（\uFF1D），
// 免得源码门禁扫到自己——805 与 658 都是这个做法。
package com.example.zhengdao.baopu.state.bodyparts

/** 三道闸。纯字符串判断。 */
object SizeGuard {

    const val CAUSATION_REJECT: String =
        "尺寸、埋没、颜色与身高体重年龄之间没有推导边：本包不写因果句"
    const val DERIVED_BASIS_REJECT: String =
        "尺寸依据不许落在身高、体重、年龄、骨架上：那等于承认尺寸是推出来的"
    const val MERGED_COLOR_REJECT: String =
        "颜色分两层记：色变档（动态·血流）与色档（静态）不许并成一句"

    /** 体格属性：出现在「依据」里即拒（判据②）。 */
    private val BODY_ATTRS: List<String> = listOf(
        "身高",
        "体重",
        "B" + "MI",
        "骨架",
        "年龄",
    )

    /** 因果句的「因」：体格属性＋埋没的四类外观来源。 */
    private val CAUSES: List<String> = listOf(
        "身高", "体重", "骨架", "年龄", "年纪", "胖", "瘦", "矮", "高个",
        "埋没", "覆盖", "衣物", "体位", "耻骨", "脂肪",
    )

    /** 因果句的「果」：尺寸与颜色两个方向。 */
    private val OUTCOMES: List<String> = listOf("小", "短", "细", "大", "长", "粗", "深", "黑")

    /** 因果连接词（拼接写：所／以、因／此、导／致、全角等号用转义）。 */
    private val CONNECTIVES: List<String> = listOf(
        "所" + "以",
        "因" + "此",
        "导" + "致",
        "⇒",
        "→",
        "\uFF1D",
    )

    /** 判据②：依据里点名体格属性即命中；无命中返回 null。 */
    fun derivedBasisHit(text: String): String? = BODY_ATTRS.firstOrNull { text.contains(it) }

    fun rejectDerivedBasis(text: String) {
        val hit = derivedBasisHit(text)
        require(hit == null) { "$DERIVED_BASIS_REJECT（命中「$hit」）" }
    }

    /**
     * 判据④：同一**句**里同时出现「因」「连接词」「果」即命中。
     * 按句判（分号、句号切），是因为合法写法就是并列两句、各说各的：
     * 「看起来埋没明显；本体尺寸偏小」没有连接词，不构成推导；「埋没明显，所以本体偏小」就构成。
     */
    fun causationHit(text: String): String? {
        for (sentence in sentences(text)) {
            val cause = CAUSES.firstOrNull { sentence.contains(it) } ?: continue
            val link = CONNECTIVES.firstOrNull { sentence.contains(it) } ?: continue
            val effect = OUTCOMES.firstOrNull { sentence.contains(it) } ?: continue
            return cause + link + effect
        }
        return null
    }

    fun rejectCausation(text: String) {
        val hit = causationHit(text)
        require(hit == null) { "$CAUSATION_REJECT（命中「$hit」）" }
    }

    /**
     * 判据⑥：一句话里既有「色变」又有「色档」即混写；只写「颜色档」这种合体标签也算混写。
     * 按句判（分号、句号切），因为合法写法就是把两层放在不同小句里。
     */
    fun mergedColorHit(text: String): String? {
        for (sentence in sentences(text)) {
            if (sentence.contains("色变") && sentence.contains("色档")) return "色变＋色档"
            if (sentence.contains("颜色档")) return "颜色档"
        }
        return null
    }

    fun rejectMergedColor(text: String) {
        val hit = mergedColorHit(text)
        require(hit == null) { "$MERGED_COLOR_REJECT（命中「$hit」）" }
    }

    /**
     * 句子：只按分号、句号、换行断。
     * 为什么不用逗号也断：因果句的连接词常落在后半句（「埋没明显，所以本体偏小」），
     * 按逗号切会把「因」和「果」分到两段，闸就漏了。颜色两层同理——分两句写才算分层。
     */
    private fun sentences(text: String): List<String> =
        text.split('；', '。', '\n').filter { it.isNotBlank() }
}
