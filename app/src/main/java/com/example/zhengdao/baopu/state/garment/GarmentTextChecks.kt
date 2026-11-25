// GZAG · 抱朴 · :policy · 第881批（2026-11-24）· 原创纯判定。
package com.example.zhengdao.baopu.state.garment

internal object GarmentTextChecks {
    const val FORBIDDEN_RELEASE = "garment.text.forbiddenRelease"
    const val SEVERE_CONSEQUENCE = "garment.text.severeConsequence"
    const val EVALUATION = "garment.text.evaluation"
    const val GARMENT_RISK = "garment.text.garmentRisk"
    const val MEASUREMENT = "garment.text.measurement"
    const val ADVICE = "garment.text.advice"
    const val WRONG_PHASE = "garment.text.wrongPhase"
    const val PROTECTED_OPENING_PINCH = "garment.text.protectedOpeningPinch"
    const val HOLE_AUTO_PINCH = "garment.text.holeAutoPinch"
    const val MOCKED_ABSENCE = "garment.text.mockedAbsence"
    const val ROUTINE_INJURY = "garment.text.routineInjury"

    private data class Rule(val code: String, val pattern: Regex)

    private val alwaysRules = listOf(
        Rule(EVALUATION, Regex("邋遢|可怜|贫穷|贫困|穷人|不讲卫生|(?<!无)穷(?!尽)")),
        Rule(
            MEASUREMENT,
            Regex(
                "(?:(?:齿距|牙齿角度|拉链角度|门襟宽度|门襟宽|拉链宽度|裤门宽度)(?:\\s*(?:为|是))?\\s*(?:\\d+(?:\\.\\d+)?|[零〇一二两三四五六七八九十百]+)" +
                    "(?:\\s*(?:mm|毫米|cm|厘米|°|度))?" +
                    "|(?:\\d+(?:\\.\\d+)?|[零〇一二两三四五六七八九十百]+)\\s*(?:mm|毫米|cm|厘米|°|度))",
            ),
        ),
        Rule(
            ADVICE,
            Regex("(?:建议|应该|应当|避免|防止|小心(?!翼翼)|务必|如何).{0,12}(?:拉拉链|拉链|门襟|夹|裤)"),
        ),
        Rule(
            ADVICE,
            Regex("注意(?:不要|别|角度|避免|防止|拉链|裤门|门襟)"),
        ),
        Rule(
            ADVICE,
            Regex("(?:拉拉链|拉链|门襟|夹|裤).{0,12}(?:建议|应该|应当|避免|防止|注意|小心(?!翼翼)|务必|如何)"),
        ),
        Rule(ADVICE, Regex("(?:拉拉链|拉链).{0,8}(?:慢慢来|稳一点|轻一点|不要急)")),
    )

    private val claimRules = listOf(
        Rule(
            FORBIDDEN_RELEASE,
            Regex("硬拽|强拽|强拉|猛拉|剪开|剪断|剪破|撕裂|撕开|扯开|拔出滑块|滑块拔出|把滑块拔|割开|扯出|拽出"),
        ),
        Rule(
            SEVERE_CONSEQUENCE,
            Regex(
                "撕裂|受伤|出血|流血|感染|就医|送医|医院|缝针|肿胀|肿起来|淤青|破皮|" +
                    "大出血|鲜血直流|血淋淋|剧痛|很疼|疼痛难忍|疼得厉害|痛得厉害|" +
                    "持续疼|一直疼|疼了很久|痛了很久|久久不消|皮开肉绽|疼得受不了|痛得受不了",
            ),
        ),
        Rule(
            GARMENT_RISK,
            Regex("(?:穿|穿了|穿着).{0,8}(?:这种|这条|该).{0,2}裤子.{0,16}(?:导致|造成|所以|因此|容易|会|风险|夹)"),
        ),
        Rule(
            GARMENT_RISK,
            Regex("(?:裤型|裤子形态|这种裤子|这条裤子|那条裤子|这款裤子).{0,12}(?:导致|造成|所以|因此|容易|风险)"),
        ),
        Rule(
            GARMENT_RISK,
            Regex("(?:因为|由于).{0,6}(?:穿|穿了|穿着).{0,8}(?:这种|这条).{0,2}裤子"),
        ),
        Rule(
            GARMENT_RISK,
            Regex("(?:因为|由于).{0,6}(?:穿)?(?:牛仔裤|紧身裤|工装裤|运动裤).{0,6}而.{0,4}夹"),
        ),
        Rule(
            GARMENT_RISK,
            Regex(
                "(?:穿)?(?:牛仔裤|紧身裤|工装裤|运动裤).{0,12}(?:会|容易|导致|造成|因此|所以)" +
                    "$NON_CLAUSE{0,12}(?:$BODY_PINCH_WORDS|夹到|被夹|卷入)",
            ),
        ),
        Rule(
            HOLE_AUTO_PINCH,
            Regex(
                "(?:$HOLE_WORDS)$NON_CLAUSE{0,12}[，,；;：:]?$NON_CLAUSE{0,12}" +
                    "(?:自动|就会|必定|一定|导致|造成|因此|所以|会|容易)$NON_CLAUSE{0,6}" +
                    "(?:$PINCH_WORDS|$INJURY_WORDS)",
            ),
        ),
    )

    private val pinchTerms = Regex(PINCH_WORDS)
    private val routineInjuryTerms = Regex(
        "$BODY_PINCH_WORDS|" +
            "(?:$BODY_GARMENT_CONTEXT)$NON_CLAUSE{0,8}(?:$BARE_PINCH_WORDS)|" +
            "(?:$BARE_PINCH_WORDS)$NON_CLAUSE{0,8}(?:$BODY_GARMENT_CONTEXT)|" +
            "(?:$BARE_PINCH_WORDS)$NON_CLAUSE{0,8}(?:$INJURY_WORDS)|" +
            "(?:$INJURY_WORDS)$NON_CLAUSE{0,8}(?:$BARE_PINCH_WORDS)|" +
            "(?:$BODY_GARMENT_CONTEXT)$NON_CLAUSE{0,8}(?:$INJURY_WORDS)|" +
            "(?:$INJURY_WORDS)$NON_CLAUSE{0,8}(?:$BODY_GARMENT_CONTEXT)",
    )
    private val backSliderTerms = Regex("回拉滑块|滑块回拉|把滑块往回拉|反向退回滑块")
    private val backSliderProhibitions = Regex(
        "(?:^|[，。；！？,;!?：:\\s]|他|她|你|我|可|一定|请|麻烦)(?:千万)?$PROHIBITION_WORDS" +
            "$NON_CLAUSE{0,12}(?:回拉滑块|滑块回拉|把滑块往回拉|反向退回滑块)",
    )
    private val underwearTerms = Regex("穿(?:上|着|有|了)?内裤")
    private val absentUnderwearTerms = Regex("无内裤|(?:没|没有|不|未)穿内裤|(?:没|没有)内裤")
    private val removalTerms = Regex(
        "(?:脱|扒)(?:掉|下|了)?内裤|" +
            "内裤(?:后|之后|再|然后|接着|随后)?(?:脱|扒)(?:掉|下|了)|" +
            "(?:脱|扒)(?:掉|下|了)(?=后|之后|再|然后|接着|随后|[，。；！？,;!?：:\\s]|$)",
    )
    private val openingPinch = Regex(
        "(?:$OPENING_WORDS)(?:拉链)?(?:[，,；;：:](?:$RESULT_WORDS)?|$RESULT_WORDS)" +
            "(?:他|她|你|我)?(?:还是|又|便)?(?:$EVENT_PINCH_WORDS)|" +
            "(?:$OPENING_WORDS)$NON_CLAUSE{0,10}(?:$PHASE_WORDS)$EVENT_GAP(?:$EVENT_PINCH_WORDS)|" +
            "(?:$EVENT_PINCH_WORDS)$NON_CLAUSE{0,10}(?:$PHASE_WORDS)$NON_CLAUSE{0,10}(?:$OPENING_WORDS)|" +
            "(?:$EVENT_PINCH_WORDS)$NON_CLAUSE{0,10}(?:$OPENING_WORDS)$NON_CLAUSE{0,10}(?:$PHASE_WORDS)",
    )
    private val absenceTerms = Regex(
        "${absentUnderwearTerms.pattern}|破洞内裤|内裤破洞|" +
            "内裤$NON_CLAUSE{0,4}(?:$HOLE_WORDS|有个洞|有洞)",
    )
    private val laughterTerms = Regex("嘲笑|取笑|当笑点|逗乐|逗笑|哈哈|笑料|乐了|(?<!苦)笑(?!不出来|不出|话|脸|星|声|意|容)")
    private val explicitMockeryTerms = Regex("嘲笑|取笑|当笑点|逗乐|逗笑|笑料")
    private val mockeryContextTerms = Regex(MOCKERY_CONTEXT)
    private val directMockeryTarget = Regex("[他她你我]?")
    private val sentenceBoundary = Regex("[。！？!?\\r\\n]+")
    private val stateChangeProhibitions = Regex(
        "(?:^|[，。；！？,;!?：:\\s]|他|她|你|我|可|一定|请|麻烦)(?:千万)?$PROHIBITION_WORDS" +
            "$NON_CLAUSE{0,6}$",
    )
    private val prospectiveMarker = Regex("^(?:以前|之前|前)(?=$|[，。；！？,;!?：:\\s])")
    private val clausePunctuation = (CLAUSE_PUNCTUATION + "\r\n").toCharArray()
    private const val CLAUSE_PUNCTUATION = "，。；！？,;!?：:"
    private const val PROHIBITION_WORDS = "(?:不要|别|不准|不可|不得|不许|无法|禁止|不允许|不应|不用|不)"
    private const val NON_CLAUSE = "[^$CLAUSE_PUNCTUATION\\r\\n]"
    private const val EVENT_GAP = "$NON_CLAUSE{0,10}[，,；;：:]?$NON_CLAUSE{0,10}"
    private const val OPENING_WORDS = "拉开|下拉|解拉链|打开拉链|解开拉链"
    private const val PHASE_WORDS = "时|过程中|当中"
    private const val RESULT_WORDS = "结果|于是|所以|却|反而|反倒|没想到|不慎|一下子"
    private const val BODY_GARMENT_CONTEXT = "包皮|皮肤|阴茎|龟头|下体|拉链|门襟|裤门|内裤|上拉|下拉|齿列"
    private const val MOCKERY_CONTEXT = "内裤|裤腰|裤边|裤门|门襟|破洞|下体|包皮|皮肤"
    private const val INJURY_WORDS = "生疼|发疼|擦伤|受伤|痛了一下|疼了一下|刺痛|很疼|发红|红肿|红了一下|留痕"
    private const val PINCH_BODY = "包皮|皮肤|阴茎|龟头|下体|肉|内裤|内裤前缘|裤边|布边"
    private const val BARE_PINCH_WORDS =
        "夹到|被夹|卷入|" +
            "夹住(?:了)?(?=时|后|过程中|当中|[，,；;：:。！？!?\\s]|$)|" +
            "夹(?:了|着)(?=时|后|过程中|当中|[，,；;：:。！？!?\\s]|$)"
    private const val BODY_PINCH_WORDS =
        "咬(?:住|到|了)(?:了)?(?:[他她你我]的?|其)?(?:$PINCH_BODY)|" +
            "夹住(?:了)?(?:[他她你我]的?|其)?(?:$PINCH_BODY)|" +
            "夹(?:了|着)(?:一下(?:子)?|一会(?:儿)?|一阵)?(?:[他她你我]的?|其)?(?:$PINCH_BODY)|" +
            "卡住(?:了)?(?:[他她你我]的?|其)?(?:$PINCH_BODY)|" +
            "(?:把|将)(?:[他她你我]的?|其)?(?:$PINCH_BODY)(?:夹|卡|咬)(?:住|到|了)(?:了)?|" +
            "(?:[他她你我]的?|其)?(?:$PINCH_BODY)被(?:夹|卡|咬)(?:住|到|了)(?:了)?|" +
            "夹疼|夹破皮|夹伤"
    private const val EVENT_PINCH_WORDS = "$BARE_PINCH_WORDS|$BODY_PINCH_WORDS"
    private const val PINCH_WORDS = EVENT_PINCH_WORDS
    private const val HOLE_WORDS = "破(?:了)?(?:一个|个)?洞|有(?:一个|个)?洞|窟窿"
    private const val NEGATION_WINDOW = 12

    fun claimsPinch(text: String): Boolean = hasAffirmative(text, pinchTerms)

    fun claimsBackSlider(text: String): Boolean =
        hasAffirmative(text, backSliderTerms) && !backSliderProhibitions.containsMatchIn(text)

    fun audit(
        text: String,
        mode: ActionMode,
        domain: NarrativeDomain = NarrativeDomain.REALISTIC,
    ): List<GarmentIssue> = buildList {
        if (text.isBlank()) return emptyList()
        alwaysRules.forEach { rule ->
            val hit = if (rule.code == EVALUATION) {
                hasAffirmative(text, rule.pattern)
            } else {
                rule.pattern.containsMatchIn(text)
            }
            if (hit) add(GarmentIssue(rule.code, "文本含禁用表达：${rule.code}"))
        }
        claimRules.forEach { rule ->
            if (hasAffirmative(text, rule.pattern)) {
                add(GarmentIssue(rule.code, "文本把不存在的机制写成事实：${rule.code}"))
            }
        }
        if (hasAffirmative(text, openingPinch)) {
            add(GarmentIssue(WRONG_PHASE, "卷入只发生在上拉闭合，不是拉开"))
        }
        if (protectedOpeningPinch(text)) {
            add(GarmentIssue(PROTECTED_OPENING_PINCH, "穿着内裤还在拉开时被夹，机制不成立"))
        }
        if (domain == NarrativeDomain.REALISTIC && mocksAbsence(text)) {
            add(GarmentIssue(MOCKED_ABSENCE, "无内裤或破洞是描述，不是默认域里的笑点"))
        }
        if (mode == ActionMode.ROUTINE && hasAffirmative(text, routineInjuryTerms)) {
            add(GarmentIssue(ROUTINE_INJURY, "routine 场景默认顺利、无痛、无伤"))
        }
    }

    private fun protectedOpeningPinch(text: String): Boolean {
        val stateEvents = underwearEvents(text)
        val pinchPositions = openingPinch.findAll(text)
            .filter { affirmative(text, it) }
            .map { it.range.first }
            .toList()
        val positions = (stateEvents.map { it.first } + pinchPositions).distinct().sorted()
        var wearingUnderwear = true
        positions.forEach { position ->
            stateEvents.filter { it.first == position }.forEach { wearingUnderwear = it.second }
            if (position in pinchPositions && wearingUnderwear) return true
        }
        return false
    }

    private fun underwearEvents(text: String): List<Pair<Int, Boolean>> = buildList {
        val absences = absentUnderwearTerms.findAll(text)
            .filter { !prefixNegated(text, it) && !doubleNegatedPrefix(text, it) }
            .toList()
        underwearTerms.findAll(text)
            .filter { match -> absences.none { it.range.contains(match.range.first) } }
            .forEach { match ->
                if (affirmative(text, match) && !imperativeNegated(text, match)) {
                    add(match.range.first to stateAfter(text, match, true))
                }
            }
        absences.forEach { match ->
            add(match.range.first to stateAfter(text, match, false))
        }
        removalTerms.findAll(text).forEach { match ->
            if (affirmative(text, match) && !imperativeNegated(text, match)) {
                add(match.range.first to stateAfter(text, match, false))
            }
        }
    }

    private fun mocksAbsence(text: String): Boolean {
        val absences = absenceTerms.findAll(text)
            .filter { match ->
                !prefixNegated(text, match) && !doubleNegatedPrefix(text, match) &&
                    (absentUnderwearTerms.matches(match.value) || affirmative(text, match))
            }
            .toList()
        if (absences.isEmpty()) return false
        return laughterTerms.findAll(text).any { laughter ->
            affirmative(text, laughter) && absences.any { absence ->
                val gap = mockeryGap(text, absence, laughter)
                val target = mockeryTarget(text, laughter)
                val boundaries = sentenceBoundary.findAll(gap).count()
                val explicit = explicitMockeryTerms.containsMatchIn(laughter.value)
                val related = directMockeryTarget.matches(target) ||
                    mockeryContextTerms.containsMatchIn(gap + target) ||
                    (boundaries == 0 && target.isBlank())
                if (explicit) {
                    related && gap.length <= 160 && boundaries <= 3
                } else {
                    gap.length <= 80 && boundaries <= 1
                }
            }
        }
    }

    private fun mockeryGap(text: String, left: MatchResult, right: MatchResult): String = when {
        left.range.last < right.range.first -> text.substring(left.range.last + 1, right.range.first)
        right.range.last < left.range.first -> text.substring(right.range.last + 1, left.range.first)
        else -> ""
    }

    private fun mockeryTarget(text: String, laughter: MatchResult): String =
        text.substring(laughter.range.last + 1)
            .takeWhile { it !in clausePunctuation && it != '\r' && it != '\n' }
            .trim()

    private fun imperativeNegated(text: String, match: MatchResult): Boolean {
        val before = text.substring(0, match.range.first)
        return stateChangeProhibitions.containsMatchIn(before.substring(scopeBoundary(before) + 1))
    }

    private fun stateAfter(text: String, match: MatchResult, state: Boolean): Boolean {
        val suffix = text.substring(match.range.last + 1)
        val past = prospectiveMarker.containsMatchIn(suffix)
        return if (past) !state else state
    }

    private fun hasAffirmative(text: String, pattern: Regex): Boolean =
        pattern.findAll(text).any { match -> affirmative(text, match) }

    private fun affirmative(text: String, match: MatchResult): Boolean =
        !prefixNegated(text, match) && !containsNegation(scopeTail(match.value))

    private fun prefixNegated(text: String, match: MatchResult): Boolean {
        val before = text.substring(0, match.range.first)
        val prefix = before.substring(scopeBoundary(before) + 1).takeLast(NEGATION_WINDOW)
        return containsNegation(prefix)
    }

    private fun doubleNegatedPrefix(text: String, match: MatchResult): Boolean {
        val before = text.substring(0, match.range.first)
        val prefix = before.substring(scopeBoundary(before) + 1).takeLast(NEGATION_WINDOW)
        return doubleNegation.containsMatchIn(prefix)
    }

    private fun containsNegation(value: String): Boolean {
        val withoutDoubleNegation = doubleNegation.replace(value, "")
        val relevant = nonNegatingMisses.replace(withoutDoubleNegation, "")
        return NEGATIONS.any { relevant.contains(it) }
    }

    private fun scopeTail(value: String): String = value.substring(scopeBoundary(value) + 1)

    private fun scopeBoundary(value: String): Int {
        val boundary = maxOf(
            value.lastIndexOfAny(clausePunctuation),
            scopeBoundaries.maxOf { value.lastIndexOf(it) },
        )
        if (boundary >= 0 && value.substring(boundary, boundary + 1) == "并" && value.getOrNull(boundary + 1) == '非') {
            return maxOf(
                value.lastIndexOfAny(clausePunctuation),
                scopeBoundaries.filterNot { it == "并" }.maxOf { value.lastIndexOf(it) },
            )
        }
        return boundary
    }

    private val scopeBoundaries = listOf(
        "但是", "但", "可是", "却", "不过", "而是", "就", "反而", "反倒",
        "然后", "后来", "接着", "随后", "而且", "并且", "同时", "且", "而", "并",
    )
    private val nonNegatingMisses = Regex("(?:没|未)(?:留意|注意|发现|意识到|想到|留神)")
    private val doubleNegation = Regex("(?:并非|并不|并未|不是|并不是)\\s*(?:没有|没|未|无)")
    private val NEGATIONS = listOf(
        "不需要", "无需", "无须", "不必", "不会", "不能", "不是", "没有", "没", "未",
        "并非", "并不", "并未", "不等于", "无关", "从不", "不容易", "不要", "不允许", "不应", "不用",
        "不导致", "不造成", "不自动", "不一定会", "不一定",
    )
}
