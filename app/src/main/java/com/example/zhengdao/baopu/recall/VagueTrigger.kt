// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 540 模糊唤回 · **触发与线索判据**（纯函数，零 IO、不读时钟、不调模型）。
//
// ## 触发口径（派工单 ①）
// 两条路：**模糊指代**（「那个」「上次说的」）与**显式要求**（「帮我找一下」）。
// 模糊指代**必须带着求证语气或光杆指代**才触发——「那个方案不错」里顺口一个
// 「那个」不该每次都翻旧账（打扰治理是 499/507 立过的规矩）；而「那个？」
// 这种除了指代什么实词都没有的短句，就是在找东西，照触发。
//
// ## 线索（clues）从哪来
// 把指代词与求证词从原句里剥掉，剩下的**实词**（拉丁词 ＋ CJK 二元组，与
// `DiaryRecall.queryFromInput` 同一套切法，不依赖分词器 ⇒ 确定性可测）就是这句话
// 自己能提供的线索；剥完为空（「那个怎么弄来着」）⇒ 线索只能来自上下文，
// 由调用方把最近几轮的原话再喂进 [contentKeywords]——分组与检索次数的上限
// 归 [RecallEngine.clueGroups] 管，这里只负责一句话切出一把词。
package com.example.zhengdao.baopu.recall

/** 一句话的唤回信号：怎么触发的 ＋ 这句话自己带的实词（可能为空＝光杆指代）。 */
internal data class VagueSignal(
    val kind: Kind,
    val keywords: List<String>,
) {
    /** 触发来路：显式要求优先（用户点名要找，措辞里没有指代词也算）。 */
    enum class Kind { VAGUE_REF, EXPLICIT_FIND }
}

internal object VagueTrigger {

    /** 模糊指代标记（出现其一才算「指代」；「上次那个」两段都含，去重无妨）。 */
    private val VAGUE_MARKERS = listOf(
        "那个", "上次说的", "之前说的", "上次提到", "之前提到",
        "上次那个", "之前那个", "上面说的", "前面说的",
    )

    /**
     * 求证语气词（模糊指代要伴随其一，或整句剥完光杆，见文件头）。
     * 全角问号单列：很多「那个？」就一个字＋问号。
     */
    private val SEEK_CUES = listOf(
        "是什么", "什么", "怎么", "在哪", "哪里", "记得", "来着", "说的啥", "说了啥", "还有印象", "？", "?",
    )

    /** 显式要求（派工单原话「帮我找一下」一族；命中即触发，不要求求证语气）。 */
    private val FIND_CUES = listOf("帮我找", "找一下", "帮我查")

    /**
     * 切词前剥掉的词：标记与求证词自己不能当线索（拿「上次」「那个」去搜旧话，
     * 搜回来的全是废话）；「一下」「帮我」这类语气壳同样剥。
     */
    private val STRIP = VAGUE_MARKERS + SEEK_CUES + FIND_CUES + listOf(
        "一下", "上次", "之前", "帮我", "这个", "就是", "还是", "然后", "聊天", "说说",
    )

    /** 一句话最多给几条线索词（超了按出现序截断——长句不许把线索组吃光）。 */
    const val MAX_KEYWORDS = 8

    /**
     * 触发判定（纯函数）。null ＝ 不触发，这句话当普通聊天。
     *
     * - 显式要求：命中 [FIND_CUES] 即触发（[VagueSignal.Kind.EXPLICIT_FIND]）；
     * - 模糊指代：命中 [VAGUE_MARKERS]，且**求证语气**（[SEEK_CUES]）或**光杆指代**
     *   （剥完标记后一个实词都不剩）二者居其一才触发。
     */
    fun signalOf(text: String): VagueSignal? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val explicit = FIND_CUES.any { t.contains(it) }
        if (explicit) return VagueSignal(VagueSignal.Kind.EXPLICIT_FIND, contentKeywords(t))
        val vague = VAGUE_MARKERS.any { t.contains(it) }
        if (!vague) return null
        val keywords = contentKeywords(t)
        val seeking = SEEK_CUES.any { t.contains(it) }
        val bare = keywords.isEmpty()
        if (!seeking && !bare) return null
        return VagueSignal(VagueSignal.Kind.VAGUE_REF, keywords)
    }

    /**
     * 一句话的实词（线索）：先剥 [STRIP]，再按 `DiaryRecall.queryFromInput` 同款两形态切——
     * ① 拉丁/数字词（≥2 字符、至少一个字母）；② CJK 二元组滑窗。合计截到 [MAX_KEYWORDS]。
     * 输入同序 ⇒ 输出同序（确定性；「要点怎么选」不许漂）。
     */
    fun contentKeywords(text: String, cap: Int = MAX_KEYWORDS): List<String> {
        var s = text
        STRIP.forEach { w -> s = s.replace(w, " ") }
        val keywords = mutableListOf<String>()
        Regex("[A-Za-z0-9][A-Za-z0-9._]*").findAll(s).forEach { m ->
            val w = m.value.trimEnd('.', '_').lowercase()
            if (w.length >= 2 && w.any { it.isLetter() } && w !in keywords) keywords += w
        }
        Regex("[\\u4e00-\\u9fff]+").findAll(s).forEach { run ->
            val t = run.value
            for (i in 0..t.length - 2) {
                val bi = t.substring(i, i + 2)
                if (bi !in keywords) keywords += bi
            }
        }
        return keywords.take(cap.coerceAtLeast(0))
    }
}
