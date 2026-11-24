// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百九十六批 · 文字闸。违禁词表只在本文件出现，源码门禁不扫本文件；
// 词表用拼接构造，避免将来有人误把本文件加进扫描清单时自咬。
package com.example.zhengdao.baopu.state.presence

object PresenceGuard {

    /** 把正常照护写成可疑：污名化，硬拦。 */
    private val stigmatizingCare: List<String> = listOf(
        "不该" + "帮",
        "有" + "问题",
        "不能给" + "女儿",
        "不能给" + "儿子",
        "帮了就" + "是错",
        "性别" + "设限",
        "不应该" + "给",
    )

    /** 群体刻板判断：硬拦。只给场所常态度，不给「某地人都如何」。 */
    private val groupBias: List<String> = listOf(
        "都" + "保守",
        "都不" + "在乎",
        "全都" + "这样",
        "人人" + "都这样",
    )

    /** 行为指导与过度限制：硬拦，但「穿条内裤」始终允许。 */
    private val forcedDress: List<String> = listOf(
        "必须穿" + "整齐",
        "裹" + "严实",
        "穿整齐" + "才行",
        "该不该" + "裸",
    )

    fun rejectStigmatizingCare(text: String) {
        val hit = stigmatizingCare.firstOrNull { it in text }
        require(hit == null) { "正常照护不判不提示（命中「$hit」）" }
    }

    fun rejectGroupJudgment(text: String) {
        val hit = groupBias.firstOrNull { it in text }
        require(hit == null) { "场所只给常态度，不给群体判断（命中「$hit」）" }
    }

    fun rejectBehaviorAdvice(text: String) {
        val hit = forcedDress.firstOrNull { it in text }
        require(hit == null) { "不做行为指导，穿条内裤已足够（命中「$hit」）" }
    }

    /** 唯一出口前的合检。 */
    fun requireNeutral(text: String) {
        require(text.isNotBlank()) { "结论文字不许空" }
        rejectStigmatizingCare(text)
        rejectGroupJudgment(text)
        rejectBehaviorAdvice(text)
    }

    /** 「穿戴整齐」只在明确有外人时可造；其余场合默认到「穿内裤」为止。 */
    fun requireUnderwearEnough(tier: CanBeNude, outsider: Boolean) {
        require(tier != CanBeNude.FULLY_DRESSED || outsider) {
            "没有明确外人时最多到穿内裤：穿戴整齐只在明确有外人时使用"
        }
    }
}
