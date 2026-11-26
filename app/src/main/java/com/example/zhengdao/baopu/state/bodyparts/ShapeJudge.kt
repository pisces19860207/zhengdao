// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百三十五批 · 判据：纯函数，只回答「这句话有没有把形态当成性评价」。
// 不下生理结论、不进闸、不落库；生产侧要判的时候再调。
//
// 两条判据臂，各管一件事：
// · 结构化臂 [judge]：一条 [Claim] 说「写了什么形态／下了什么结论／凭什么」，
//   拿 [ShapeRules.allowed] 去对——形态不在任何评价维的允许来源里，这一条是数据保证的，不靠自觉；
// · 文字臂 [judgeText]：七类体相传闻、身材羞辱／健身崇拜、评分式说法，按整句普通文本匹配。
//
// 声音分叉（判据⑤）：叙述者 ⇒ 红；角色 ⇒ 须核（＝WARN），且理由里必须写「偏见」。
// 三个恒不可推的维（经验／生育力／品性）**不分声音，一律红**——污名化与医学判断没有「偏见豁免」。
package com.example.zhengdao.baopu.state.bodyparts

/**
 * 一条结构化断言。
 *
 * [shape] 是这句话提到的形态，[judged] 是它下的结论，[sources] 是它自称的依据。
 * 三者分开存，判据才能回答「是不是拿形态当依据」，而不是去猜语义。
 *
 * [preference] 只对性吸引力有意义：谁的口味。缺它就等于叙述者在宣布客观吸引力。
 * [plotValidates] 是剧情层的开关：true＝这一段把传闻演成了真的。
 */
data class Claim(
    val text: String,
    val voice: Voice,
    val shape: Set<ShapeAspect> = emptySet(),
    val judged: Judged? = null,
    val sources: Set<Source> = emptySet(),
    val preference: Preference? = null,
    val plotValidates: Boolean = false,
) {
    init {
        require(text.isNotBlank()) { "断言缺正文" }
    }
}

/** 判据集合。全部是纯函数：同一条断言跑两遍，结论逐字一致。 */
object ShapeJudge {

    /** 身体词：文字臂的兜底（没走结构化断言时用）。 */
    private val BODY_WORDS: List<String> = listOf(
        "胸肌", "腹肌", "肌肉", "下体", "尺寸", "喉结", "阴囊", "睾丸",
        "身材", "块头", "鼻子", "小腿", "体毛", "秃",
    )

    /** 评价词：只有和身体词、推导词三者同现才算形态推导。 */
    private val JUDGMENT_WORDS: List<String> = listOf(
        "性感", "很猛", "能力强", "不行", "持久", "技术好", "有魅力",
    )

    /** 推导词：形态 → 评价那一步就藏在这里。 */
    private val CAUSAL_WORDS: List<String> = listOf("所以", "因此", "可见", "说明", "注定")

    /** 偏见标记：角色说传闻时必须带，否则读不出「这是他的偏见」。 */
    private val BIAS_MARKERS: List<String> = listOf(
        "听说", "都说", "据说", "据传", "偏见", "觉得", "以为", "认为", "老话",
    )

    /** 命理词后面得有个「结论」才算推导；纯记录（他是 A 型血）不判红。 */
    private val CONCLUSION_WORDS: List<String> = listOf(
        "所以", "因此", "说明", "可见", "一定", "必然", "注定", "都", "决定",
    )

    /** 身材羞辱词（胖／瘦不得配贬义）。 */
    private val SHAME_WORDS: List<String> = listOf(
        "恶心", "油腻", "邋遢", "懒", "没自制力", "不自律", "可怜", "失败",
    )

    /** 健身崇拜词（肌肉不得配褒义定性）。 */
    private val WORSHIP_WORDS: List<String> = listOf(
        "自律", "优秀", "男人味", "值得尊敬", "高级", "榜样", "厉害",
    )

    /** 体型词：羞辱／崇拜两侧都要先有它。 */
    private val BODY_TOKENS: List<String> = listOf(
        "胖", "肥", "瘦", "弱", "肌肉", "腹肌", "胸肌", "身材", "块头",
    )

    /** 评分式说法：任何声音都红（判据⑥「不做吸引力评分」）。 */
    private val SCORE_WORDS: List<String> = listOf(
        "评分", "打分", "综合分", "满分", "分值", "吸引力指数",
    )

    /** 汇总：一条断言的全部结论。空列表＝这句话站得住。 */
    fun judge(claim: Claim): List<ShapeFinding> {
        val out = ArrayList<ShapeFinding>()
        claimFinding(claim)?.let { out += it }
        out += judgeText(claim.text, claim.voice, claim.plotValidates)
        // 结构化臂已经说过的事，文字臂不再重复说一遍。
        val lexical = if (claim.judged != null) {
            out.filterNot { it.code == "SHAPE-CAUSAL" || it.code == "SHAPE-CAUSAL-BIAS" }
        } else {
            out
        }
        return lexical.distinctBy { it.code }
    }

    /** 一组断言汇总。 */
    fun all(claims: List<Claim>): List<ShapeFinding> = claims.flatMap { judge(it) }.distinctBy { it.code }

    /** 一行汇总：过／须核／红 各几条。 */
    fun summary(findings: List<ShapeFinding>): String {
        val check = findings.count { it.verdict == Verdict.NEED_CHECK }
        val deny = findings.count { it.verdict == Verdict.DENY }
        return "过 " + (findings.size - check - deny) + "／须核 " + check + "／红 " + deny
    }

    // ── 结构化臂 ──────────────────────────────────────────────────────

    private fun claimFinding(claim: Claim): ShapeFinding? {
        val dim = claim.judged ?: return null
        val fromShape = Source.MORPHOLOGY in claim.sources

        // ① 恒不可推的三维：只要碰了形态（提到或当依据），不分声音一律红。
        if (ShapeRules.isHard(dim)) {
            if (!fromShape && claim.shape.isEmpty()) return null
            return hardFinding(claim, dim)
        }

        // ② 拿形态当依据：这就是那条被剪断的边。
        if (fromShape) {
            return voiceFinding(claim, specialCode(claim, dim) ?: dim.name + "-SHAPE-EDGE", edgeReason(dim))
        }

        // ③ 吸引力没有持有者：叙述者在宣布客观吸引力。
        if (dim == Judged.ATTRACTION && claim.preference == null) {
            return voiceFinding(claim, "ATTRACTION-NO-HOLDER", "吸引力只许写成某个角色的口味：缺 who")
        }

        // ④ 自称有依据，但依据不在这一维的允许来源里（含「一个来源都没给」）。
        val useful = claim.sources.filter { ShapeRules.supports(dim, it) }
        if (useful.isEmpty()) {
            return voiceFinding(claim, specialCode(claim, dim) ?: dim.name + "-NO-SOURCE", noSourceReason(dim))
        }
        return null
    }

    private fun hardFinding(claim: Claim, dim: Judged): ShapeFinding {
        val code = specialCode(claim, dim) ?: dim.name + "-FROM-SHAPE"
        val why = when (dim) {
            Judged.EXPERIENCE -> "性经验是经历，不写在身体上；松紧与大小都不推经验"
            Judged.FERTILITY -> "生育力是医学判断，不住在形态里；大小不推能不能生"
            else -> "品性是行为与处境，不住在身体形状里"
        }
        return ShapeFinding(Verdict.DENY, code, why + "（" + claim.voice.label + "也一样红）")
    }

    private fun voiceFinding(claim: Claim, code: String, why: String): ShapeFinding =
        if (claim.voice == Voice.NARRATOR) {
            ShapeFinding(Verdict.DENY, code, why)
        } else {
            ShapeFinding(Verdict.NEED_CHECK, code, why + "；角色看法须读成偏见，可被推翻")
        }

    /** 三处最常被当符号的部位各给一个可点名的代码；其余走通用代码。 */
    private fun specialCode(claim: Claim, dim: Judged): String? = when {
        ShapeAspect.SCROTUM_LAXITY in claim.shape &&
            (dim == Judged.EXPERIENCE || dim == Judged.MORAL) -> "SCROTUM-STIGMA"
        ShapeAspect.TESTIS_SIZE in claim.shape && dim == Judged.FERTILITY -> "TESTIS-FERTILITY"
        ShapeAspect.TESTIS_SIZE in claim.shape -> "TESTIS-CAPACITY"
        ShapeAspect.LARYNX in claim.shape -> "LARYNX-MASCULINITY"
        else -> null
    }

    private fun edgeReason(dim: Judged): String = when (dim) {
        Judged.TENSION -> "性张力只许来自观测／设定／实测，形态不是来源"
        Judged.ATTRACTION -> "性吸引力只许写成某个角色的口味，形态不是来源"
        Judged.ABILITY -> "性能力只许来自硬度实测／起维持消退／活动频率／具体行为"
        else -> "这一维不由形态推出"
    }

    private fun noSourceReason(dim: Judged): String = when (dim) {
        Judged.TENSION -> "张力没给来源：观测／设定／实测三选一"
        Judged.ATTRACTION -> "吸引力没给持有者：写清是谁的口味"
        Judged.ABILITY -> "能力没给来源：先有实测档、过程、频率或具体行为"
        else -> "这一维没有可用来源"
    }

    // ── 文字臂 ────────────────────────────────────────────────────────

    /** 只看文字：七类传闻、命理、羞辱／崇拜、评分式说法。[rumors] 可替换，供删行变异自检。 */
    fun judgeText(
        text: String,
        voice: Voice,
        plotValidates: Boolean = false,
        rumors: List<Rumor> = ShapeRumorCatalog.entries,
    ): List<ShapeFinding> {
        val out = ArrayList<ShapeFinding>()
        val hits = rumors.filter { ShapeRumorCatalog.templateMatches(it.template, text) }
        hits.forEach { rumor -> out += rumorFinding(rumor, text, voice, plotValidates) }

        // 清单外新造不了：命理词 + 结论词同句也算（E 类直接红）。
        val fortune = ShapeRumorCatalog.fortuneHits(text)
        if (hits.none { it.klass == RumorClass.FORTUNE } && fortune.isNotEmpty() && hasConclusion(text)) {
            out += fortuneFinding(fortune.joinToString("、"), voice, plotValidates)
        }

        shameFinding(text, voice)?.let { out += it }
        if (SCORE_WORDS.any { text.contains(it) }) {
            out += ShapeFinding(Verdict.DENY, "ATTRACTION-SCORE", "吸引力不并成一个数：只写谁的口味")
        }
        if (claimShapedCausal(text) && hits.isEmpty()) {
            out += voiceFindingText(voice)
        }
        return out
    }

    private fun rumorFinding(rumor: Rumor, text: String, voice: Voice, plotValidates: Boolean): ShapeFinding {
        if (plotValidates) {
            return ShapeFinding(
                Verdict.DENY, "RUMOR-PLOT-VALIDATES",
                "剧情把传闻验证成真（" + rumor.id + "）：等于变相背书",
            )
        }
        if (rumor.klass == RumorClass.FORTUNE && voice == Voice.NARRATOR) {
            return ShapeFinding(
                Verdict.DENY, "FORTUNE-DIRECT",
                "命理不能当依据（" + rumor.id + "）：叙述者层面直接红",
            )
        }
        if (voice == Voice.NARRATOR) {
            return ShapeFinding(
                Verdict.DENY, "RUMOR-NARRATOR-" + rumor.id,
                "叙述者复述体相传闻（" + rumor.klass.label + "）：" + rumor.template,
            )
        }
        if (!hasBias(text)) {
            return ShapeFinding(
                Verdict.DENY, "RUMOR-UNMARKED-" + rumor.id,
                "角色说传闻但读不出是偏见（" + rumor.id + "）：须带听说／都说一类标记",
            )
        }
        return ShapeFinding(
            Verdict.NEED_CHECK, "RUMOR-BIAS-" + rumor.id,
            "角色相信体相传闻（" + rumor.klass.label + "）：须读成偏见，可被推翻",
        )
    }

    private fun fortuneFinding(tokens: String, voice: Voice, plotValidates: Boolean): ShapeFinding =
        if (voice == Voice.NARRATOR || plotValidates) {
            ShapeFinding(Verdict.DENY, "FORTUNE-DIRECT", "命理不能当依据（" + tokens + "）：叙述者层面直接红")
        } else {
            ShapeFinding(Verdict.NEED_CHECK, "FORTUNE-BIAS", "角色拿命理当依据（" + tokens + "）：须读成偏见")
        }

    private fun shameFinding(text: String, voice: Voice): ShapeFinding? {
        val body = BODY_TOKENS.firstOrNull { text.contains(it) } ?: return null
        val shame = SHAME_WORDS.firstOrNull { text.contains(it) }
        val worship = WORSHIP_WORDS.firstOrNull { text.contains(it) }
        if (shame == null && worship == null) return null
        val code = if (shame != null) "BODY-SHAME" else "FITNESS-WORSHIP"
        val why = if (shame != null) {
            "身材不配贬义词（" + body + "／" + shame + "）：只写形态不写价值"
        } else {
            "肌肉不配褒义定性（" + body + "／" + worship + "）：只写形态不写价值"
        }
        return if (voice == Voice.NARRATOR) {
            ShapeFinding(Verdict.DENY, code, why)
        } else {
            ShapeFinding(Verdict.NEED_CHECK, code, why + "；角色看法须读成偏见")
        }
    }

    /** 文字臂的兜底：身体词＋评价词＋推导词三者同现，且没命中任何传闻。 */
    private fun claimShapedCausal(text: String): Boolean =
        BODY_WORDS.any { text.contains(it) } &&
            JUDGMENT_WORDS.any { text.contains(it) } &&
            CAUSAL_WORDS.any { text.contains(it) }

    private fun voiceFindingText(voice: Voice): ShapeFinding =
        if (voice == Voice.NARRATOR) {
            ShapeFinding(Verdict.DENY, "SHAPE-CAUSAL", "拿身体形状推出性评价：这一步不许有")
        } else {
            ShapeFinding(Verdict.NEED_CHECK, "SHAPE-CAUSAL-BIAS", "角色拿身体形状推出性评价：须读成偏见")
        }

    private fun hasConclusion(text: String): Boolean = CONCLUSION_WORDS.any { text.contains(it) }

    private fun hasBias(text: String): Boolean = BIAS_MARKERS.any { text.contains(it) }
}
