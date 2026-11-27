// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百三十九批 · 闸与唯一出口。
//
// 出口只有一处：[PubertyFinding.line]。构造结论时就过四道闸，写坏的话根本造不出来：
// · 长度／面积／百分比 ⇒ 引 805 [BodyPartGuard]（同一套口径，不另立第二套）；
// · 部位判定词（805 的那张表）⇒ 同上；
// · 发育结论词（本单）⇒ 医学结论不许由描写代替；
// · 发育推断（本单）⇒ 由发育推能力与品性这一步不许有。
//
// 禁词表一律拼接写（"性" + "早熟"），免得源码门禁扫到自己——805／935 的做法沿用。
// 注意：年龄数值、身体变化细节、既往史细节**不在**任何一张表里，它们本来就允许。
package com.example.zhengdao.baopu.state.bodyparts

/** 四道闸 + 两种行的唯一写法。纯字符串判断，无 IO、无正则回溯风险。 */
object PubertyGuard {

    const val DIAGNOSIS_REJECT: String =
        "发育只写过程，不下医学结论：那一类判定词一个都不写"
    const val INFERENCE_REJECT: String =
        "由发育不推能力与品性：只写变化，不写结论"
    const val BLOCKED_LINE: String =
        "该行触到本单禁项，已拦下（原文不外显）"

    /** 发育结论词（医学判断）。拼接写：本文件自己被门的源码闸扫到时不许自咬。 */
    private val DIAGNOSIS_TERMS: List<String> = listOf(
        "性" + "早熟",
        "性" + "晚熟",
        "发育" + "迟",
        "骨" + "龄",
        "内分" + "泌",
        "激素水" + "平",
    )

    /** 发育词：推断句的第一环。 */
    private val DEV_WORDS: List<String> = listOf(
        "发育", "变声", "喉结", "体毛", "突增", "肩宽",
    )

    /** 推导词：第二环。三环缺一不判红，免得把正常因果句杀掉。 */
    private val CAUSAL_WORDS: List<String> = listOf("所以", "因此", "说明", "可见", "注定", "决定")

    /** 结论词：第三环。只收「能力／品性」那一族，不收「高／壮」这类纯形态词。 */
    private val CONCLUSION_WORDS: List<String> = listOf(
        "能力强", "性能力", "品性", "品行", "男人味", "更男人", "靠得住", "可靠",
    )

    /** 命中的发育结论词；无命中返回 null。 */
    fun diagnosisHit(text: String): String? = DIAGNOSIS_TERMS.firstOrNull { text.contains(it) }

    fun rejectDiagnosisConclusion(text: String) {
        val hit = diagnosisHit(text)
        require(hit == null) { "$DIAGNOSIS_REJECT（命中一条结论词）" }
    }

    /** 推断句：三环同现才返回结论词；否则 null。 */
    fun inferenceHit(text: String): String? {
        if (DEV_WORDS.none { text.contains(it) }) return null
        if (CAUSAL_WORDS.none { text.contains(it) }) return null
        return CONCLUSION_WORDS.firstOrNull { text.contains(it) }
    }

    fun rejectInference(text: String) {
        val hit = inferenceHit(text)
        require(hit == null) { "$INFERENCE_REJECT（命中「$hit」）" }
    }

    /** 四道闸一起过：长度／百分比、805 部位判定词、发育结论词、发育推断。 */
    fun rejectAll(text: String) {
        BodyPartGuard.rejectMeasures(text)
        BodyPartGuard.rejectDiagnosis(text)
        rejectDiagnosisConclusion(text)
        rejectInference(text)
    }

    /**
     * 一行细节能不能出线：返回 null＝可以；否则返回哪一类禁项（不回显词条本身）。
     *
     * 为什么要这个而不是直接 rejectAll：细节行是**读进来的数据**，写坏不该让整张单崩掉，
     * 该让那一行被拦下、其余照常读。判据层另跑一遍，红仍然出红。
     */
    fun noteHit(text: String): String? {
        if (BodyPartGuard.measureHit(text) != null) return "长度或比例"
        if (BodyPartGuard.diagnosisHits(text).isNotEmpty()) return "部位判定词"
        if (diagnosisHit(text) != null) return "发育结论词"
        if (inferenceHit(text) != null) return "发育推断"
        return null
    }

    /** 五维档位行：部位＋档名。档名里没有判词，也没有年龄。 */
    fun tierLine(dim: PubertyDim, tier: PubertyTier): String {
        require(tier.label.isNotBlank()) { "档位行缺档名：" + dim.label }
        rejectAll(tier.label)
        return dim.label + "：" + tier.label
    }
}

/**
 * 一条结论。构造即守：有代码、有理由，理由里没有长度单位、没有诊断结论、没有推断。
 *
 * 为什么不复用 805 的 [Finding]：那个类型的 part/dim 是八部位与二十三维修正案，
 * 本单判的是「目的」与两条禁令；另立同形类型可以多加两道本单专属的闸。
 */
data class PubertyFinding(
    val verdict: Verdict,
    val code: String,
    val why: String,
    val source: String = PUBERTY_SOURCE,
    val asOf: String = PUBERTY_AS_OF,
) {
    init {
        require(code.isNotBlank()) { "结论缺代码：$code" }
        require(why.isNotBlank()) { "结论缺理由：$code" }
        PubertyGuard.rejectAll(why)
    }

    /** 唯一出口：判定＋代码＋理由（来源 日期）。 */
    fun line(): String =
        "[" + verdict.label + "] " + code + "：" + why + "（" + source + " " + asOf + "）"

    companion object {
        const val COUNT_VERDICTS: Int = 3
    }
}
