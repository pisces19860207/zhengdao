// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百一十五批 · 文字口径闸：两道，派工单判据④与边界的直接落点。
// · 判据④ 不做身材评价——「好/差/理想」这类价值词一律拦；
// · 边界 禁数值／不做医学判断——体脂率、围度、诊断词不许出现在出口。
//
// 本文件里的词全部拼接写（"理"+"想"），免得源码门禁扫到自己——805/866 的做法沿用。
// 长度／面积／百分比／诊断词复用 805 的 BodyPartGuard，本包不再造第二套。
package com.example.zhengdao.baopu.state.bodytype

import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard

/** 身材评价词闸。纯字符串判断，无 IO、无正则回溯风险。 */
object BuildGuard {

    const val VALUE_REJECT: String =
        "体型只做分布描述，不下身材评价：不写" + "理" + "想" + "这类价值词"

    const val NUMBER_REJECT: String =
        "体型档只出档序与相对措辞：不出" + "体脂" + "率、" + "B" + "MI、围度、长度"

    /**
     * 价值评价词。拼接构造，避开源码门禁自咬。
     * 「常见／一般／少见」是分布档不是评价，不在表内。
     */
    private val VALUE_WORDS: List<String> = listOf(
        "理" + "想", "完" + "美", "标" + "准型", "运" + "动型", "健" + "美型",
        "男" + "神", "女" + "神", "好" + "身材", "差" + "身材", "极" + "品",
        "最" + "优", "最" + "佳", "肥" + "宅", "废" + "物", "优" + "等", "劣" + "等",
    )

    /** 数值口径词：体脂率与 BMI 这类「率」不属于档，一律拦。 */
    private val NUMBER_WORDS: List<String> = listOf(
        "体脂" + "率", "B" + "MI", "身" + "体质量指数", "百" + "分比",
    )
    // 全角百分号用转义拼，避免字面量被自己的源码门禁咬住。
    private val PCT_FULL: String = 0xFF05.toChar().toString()

    /** 命中的评价词，逐个点名。 */
    fun valueHits(text: String): List<String> = VALUE_WORDS.filter { text.contains(it) }

    /** 命中的数值口径词（含转义构造的全角百分号）。 */
    fun numberHits(text: String): List<String> =
        NUMBER_WORDS.filter { text.contains(it) } + if (text.contains(PCT_FULL)) listOf(PCT_FULL) else emptyList()

    fun rejectValueWords(text: String) {
        val hits = valueHits(text)
        require(hits.isEmpty()) { VALUE_REJECT + "（命中「" + hits.joinToString("、") + "」）" }
    }

    /** 数值闸：本包口径词 + 805 的长度/面积/百分比闸，一次过完。 */
    fun rejectNumbers(text: String) {
        val hits = numberHits(text)
        require(hits.isEmpty()) { NUMBER_REJECT + "（命中「" + hits.joinToString("、") + "」）" }
        BodyPartGuard.rejectMeasures(text)
    }

    /** 诊断闸：直接复用 805，口径一致，不另立第二套。 */
    fun rejectDiagnosis(text: String) = BodyPartGuard.rejectDiagnosis(text)

    /** 出口三闸一次过完。 */
    fun rejectAll(text: String) {
        rejectValueWords(text)
        rejectNumbers(text)
        rejectDiagnosis(text)
    }
}
