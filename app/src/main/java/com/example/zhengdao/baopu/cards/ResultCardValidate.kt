// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十二批 · **校验样式**的判据（派工单 §2.6：红框＋人话、置灰＋小计、可点跳转）。
//
// ## 归属：这里只有「怎么说」，没有「怎么说算错」
// 派工单写得很死——「样式与小组件放 212 的壳里；**校验逻辑仍归各自卡**」。
// 所以本文件不判必答、不判数字、不判上限：它只接受别的卡算好的 [CardFieldError]，
// 把「一句话」「还差几项」「第一处错在哪」翻译出来。谁算错谁负责，壳只负责说人话。
//
// ## 为什么错误要有 [CardErrorKind] 而不是直接给句子
// 「红框＋一句人话」的负例是「只变红不给文案」。如果错误直接带句子，那负例就防不住了：
// 调用方完全可以传一句空串，界面照样画红框。这里把「哪种错」做成枚举，句子由
// [messageKeyOf] 从**闭集**里取——传进来的东西没法是空白，负例在类型层面就红了。
//
// ## 「还差 2 项」为什么要单独算
// 派工单：不用点一次提交才知道。所以提交按钮的置灰与顶部小计用的是**同一个数**
// （[SubmitPlan.needed]），界面上不可能出现「按钮灰着但不说差几项」或「小计 2 项但按钮能点」。
package com.example.zhengdao.baopu.cards

/** 四种错（闭集；新增一种要同时加 strings 两边）。 */
enum class CardErrorKind {
    /** 必填没填。 */
    REQUIRED,

    /** 该填数字没填数字。 */
    NOT_NUMBER,

    /** 超过上限（上限数在 [CardFieldError.detail] 里）。 */
    TOO_MANY,

    /** 卡自己判出来的其它错（句子由卡给，缺省落 [CardValidate.FALLBACK_KEY]）。 */
    CUSTOM,
}

/**
 * 一处错。
 *
 * @param fieldKey 哪一项（= 模板里那一行的 key；跳转与去重都按它）
 * @param kind 哪种错（决定用哪句人话）
 * @param detail 补充参数（`TOO_MANY` 的 3、`CUSTOM` 的一句话）；不许是空串
 */
data class CardFieldError(
    val fieldKey: String,
    val kind: CardErrorKind,
    val detail: String? = null,
)

/** 提交口的状态（按钮置灰与否 ＋ 顶部小计 ＋ 点小计跳到哪）。 */
data class SubmitPlan(
    val enabled: Boolean,
    /** 还差几项（0 ＝ 全填好了）。 */
    val needed: Int,
    /** 小计那句话的 strings 键（`needed > 0` 时非空）。 */
    val summaryKey: String?,
    /** 点小计要滚到的那一项（`needed > 0` 时非空）。 */
    val firstErrorKey: String?,
)

object CardValidate {

    /** 「这项必填」。 */
    const val REQUIRED_KEY = "baopu_card_error_required"

    /** 「请输入数字」。 */
    const val NOT_NUMBER_KEY = "baopu_card_error_number"

    /** 「最多选 %1$d 项」。 */
    const val TOO_MANY_KEY = "baopu_card_error_too_many"

    /** 卡自己判出来、但没给句子的兜底（**永远不为空**：红框必须配一句话）。 */
    const val FALLBACK_KEY = "baopu_card_error_generic"

    /** 「还差 %1$d 项」。 */
    const val SUMMARY_KEY = "baopu_card_error_summary"

    /**
     * 哪种错 → 哪一句人话（**永远非空**：负例「只变红不给文案」在类型层面就过不去）。
     *
     * `TOO_MANY` 把上限数塞进句子（`detail` 是上限，缺省 3——不是猜，是本仓 203 现用的那个数）。
     * `CUSTOM` 用卡自己给的那句话；它没给（null/空白）就落 [FALLBACK_KEY]，绝不画一个哑红框。
     */
    fun messageOf(error: CardFieldError, lookup: (String) -> String): String = when (error.kind) {
        CardErrorKind.REQUIRED -> lookup(REQUIRED_KEY)
        CardErrorKind.NOT_NUMBER -> lookup(NOT_NUMBER_KEY)
        CardErrorKind.TOO_MANY -> lookup(TOO_MANY_KEY).format(error.detail?.toIntOrNull() ?: 3)
        CardErrorKind.CUSTOM -> error.detail?.takeIf { it.isNotBlank() } ?: lookup(FALLBACK_KEY)
    }

    /**
     * 提交口状态。
     *
     * @param errors 各卡算出来的错（顺序无所谓）
     * @param fieldOrder 这一张卡的字段顺序（跳转按它取「第一处」；空表则取 [errors] 第一个）
     */
    fun planOf(errors: List<CardFieldError>, fieldOrder: List<String> = emptyList()): SubmitPlan {
        // 同一项只算一次：一项填错不该在「还差几项」里数两遍（同字段必答＋格式两条同时红是常态）
        val distinct = LinkedHashMap<String, CardFieldError>()
        for (e in errors) distinct.putIfAbsent(e.fieldKey, e)
        val needed = distinct.size
        if (needed == 0) return SubmitPlan(enabled = true, needed = 0, summaryKey = null, firstErrorKey = null)
        return SubmitPlan(
            enabled = false,
            needed = needed,
            summaryKey = SUMMARY_KEY,
            firstErrorKey = jumpTarget(distinct.values.toList(), fieldOrder),
        )
    }

    /**
     * 点「还差 N 项」要滚到哪一项：按**字段顺序**取第一处，而不是按错误发生的先后——
     * 她从上往下填，跳下去的那一处应当是她视线最先经过的那个空。
     */
    fun jumpTarget(errors: List<CardFieldError>, fieldOrder: List<String>): String? {
        if (errors.isEmpty()) return null
        if (fieldOrder.isEmpty()) return errors.first().fieldKey
        val wrong = errors.map { it.fieldKey }.toSet()
        return fieldOrder.firstOrNull { it in wrong } ?: errors.first().fieldKey
    }
}
