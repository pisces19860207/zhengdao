// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百二十七批 · 判据检查器：齐件、下体来源、时间/基线与文案红线。
package com.example.zhengdao.baopu.state.smell

import com.example.zhengdao.baopu.state.fluid.DrynessGrade

internal object OdorChecks {

    private val DISEASE_NAME = Regex("""[一-龥]{1,4}病|症状""")
    private const val ODOR_TERM =
        "(?:气味|汗味|体味|下体味|烟味|油味|酸味|异味)"
    private const val WORD_END = "(?=$|[^一-龥]|啊|呀|呢|吧|吗|了|一点|一些|的)"
    private const val INTENSITY_LINK = "(?:约为|约等于|约|等于|达到|≈|＝|=|为|是|:|：)?"
    private const val INTENSITY_ADVERB =
        "(?:已经|现已|目前|现在|大概|大约|估计|约略|实际|确实|明显|略微|比较|较为|相当|十分|非常|很|极|更|最|偏)*"
    private val INTENSITY_CLAIM = Regex(
        """(?:气味)?强度\s*""" + INTENSITY_ADVERB + """\s*""" + INTENSITY_LINK + """\s*(?:第)?""" +
            """(?:[0-9一二三四五六七八九十]+|十分|很|极|明显|隐约|重|轻|""" +
            """较[大小低高强弱轻重]|偏[高低强轻重])""" +
            "|" + ODOR_TERM + """\s*""" + INTENSITY_ADVERB + """\s*""" + INTENSITY_LINK +
            """\s*(?:第)?[0-9一二三四五六七八九十]+""" +
            "|" + ODOR_TERM +
            """[^。！？；;\n]{0,4}(?:特别重|很重|极重|较重|偏重|很浓|极浓|浓烈|强烈|重|浓|强|""" +
            """很轻|极轻|较轻|偏轻|很淡|极淡|较淡|偏淡|淡)""" + WORD_END,
    )
    private val INTENSITY_FORMULA =
        Regex("""(?:气味|强度|汗味|体味|下体味|烟味|油味|酸味|异味)[^。！？\n]{0,16}[+\-*/][^。！？\n]{0,16}""")
    private val PERFUME_TERM = Regex("""(?:香水|香味|香气|香体喷雾)""")
    private val PERFUME_ADVICE = Regex(
        """(?:建议|推荐|可以|应该|应当|需要|不妨|请|叫|让|要|想要|想|打算|准备|最好|试试|尝试|提醒|叮嘱|要求|命令|禁止|不许|(?<![没无未不有])(?:买|喷|用|抹|洒|拿|借助|靠|使用|利用)(?![了过]))"""
    )
    private val PERFUME_INTENT = Regex(
        """(?:建议|推荐|可以|应该|应当|需要|不妨|请|叫|让|要|想要|想|打算|准备|最好|试试|尝试)"""
    )
    private val PERFUME_HISTORY = Regex("""(?:上次|之前|以前|刚才|昨天|曾经|当时|已经|用了|喷了|抹了|洒了)""")
    private val PERFUME_REPORT = Regex("""(?:说|称|告诉|提醒|叮嘱|要求|命令|禁止|不许)""")
    private val PERFUME_FUTURE_ADVICE = Regex(
        """(?:今天|现在|此时|此刻|当下|目前|这会儿|以后|之后|下次|今后|往后|应当|应该|要|需|请|让|叫|建议|推荐|可以|最好|试试|尝试)"""
    )
    private val PERFUME_REPORTED_ADVICE = Regex(
        PERFUME_REPORT.pattern + """[，,]?""" + """[^，,。！？；;\n]{0,16}""" +
            PERFUME_FUTURE_ADVICE.pattern + """[^，,。！？；;\n]{0,12}""" + PERFUME_ADVICE.pattern +
            """[^，,。！？；;\n]{0,12}""" + """(?:香水|香味|香气|香体喷雾)"""
    )
    private val PERFUME_PURCHASE_ADVICE = Regex(
        "(?:" + PERFUME_FUTURE_ADVICE.pattern +
            """[^，,。！？；;\n]{0,12}|(?:叮嘱|提醒|要求|命令|禁止|不许)[^，,。！？；;\n]{0,12})""" +
            """(?<![没无未不有])买(?![了过])[^，,。！？；;\n]{0,12}(?:香水|香味|香气|香体喷雾)"""
    )
    private val PERFUME_INTENT_BEFORE = Regex(
        PERFUME_INTENT.pattern + """[^，,。！？；;\n]{0,12}(?:香水|香味|香气|香体喷雾)"""
    )
    private val PERFUME_ACTION = Regex(
        """(?:喷|用|抹|洒|拿|借助|靠|使用|利用)[^，,。！？；;\n]{0,12}(?:香水|香味|香气|香体喷雾)""" +
            """|(?:香水|香味|香气|香体喷雾)[^，,。！？；;\n]{0,12}(?:喷|用|抹|洒|拿|借助|靠|使用|利用)"""
    )
    private val PERFUME_MASK = Regex(
        """(?:香水|香味|香气|香体喷雾)[^。！？；;\n]{0,12}(?:掩盖|遮蔽|遮|盖|压住|压过)"""
    )
    private val VALUE_DIRTY = Regex(
        """(?:你|他|她|对方|本人|自己|这个人|身上)(?:今天|现在|总是|明明|确实|果然)?""" +
            """(?:显得|看着|闻着)?(?:真|很|太|挺|特别|明显|有点)?脏""" + WORD_END +
            """|(?:体味|汗味|气味)\s*(?:=|说明|代表|意味着)\s*(?:你|他|她|对方|本人|自己|此人|这个人)?""" +
            """\s*(?:很|真|太|特别)?脏""" + WORD_END,
    )
    private const val NON_OCCUPATION_ROLE =
        "(?!(?:[^，,。！？；;\n]{0,8}(?:的)?)?" +
            "(?:丈夫|妻子|男友|女友|老公|老婆|先生|女士|家长|父亲|母亲|爸爸|妈妈|(?:男|女)?学生))"
    private const val OCCUPATION_ROLE =
        "(?:" + NON_OCCUPATION_ROLE + """[^，,。！？；;\n]{1,12}""" +
            "(?:家长|主板|保安|保洁|译者|记者|师|员|工|民|兵|生|士|机|领|手|家|长|主|板|理|售|服|" +
            "夫|户|匠|警|医|护|厨|贩)|警察|记者|商人)"
    private const val OCCUPATION_NOUNS =
        "(?:职业|工人|农民|士兵|当兵|医生|护士|厨师|司机|程序员|白领|教师|警察|记者|商人)"
    private const val OCCUPATION_SUBJECT =
        """(?:(?:他|她|对方|本人|这位|此人)(?:的)?(?:职业|工作)?(?:是|为|当|干)""" + OCCUPATION_ROLE +
            """|(?:他|她|对方|本人|这位|此人)\s*做""" + OCCUPATION_ROLE +
            """|(?:职业|工作)(?:是|为)""" + OCCUPATION_ROLE +
            """|(?:身为|作为)""" + OCCUPATION_ROLE + ")"
    private val OCCUPATION = Regex("(?:" + OCCUPATION_SUBJECT + "|" + OCCUPATION_NOUNS + ")")
    private val CURRENT_OCCUPATION_ODOR = Regex(
        OCCUPATION_SUBJECT +
            """(?:，|,)?(?:所以|因此|就|于是|导致|使得|现在|此时|此刻|当下|今天|一定|必然|可能)?""" +
            """(?:，|,)?""" +
            """[^，,。！？；;\n]{0,12}(?:有|带|沾|没|无)[^，,。！？；;\n]{0,6}(?:汗味|体味|烟味|油味|气味|异味)""" +
            "|" + OCCUPATION_NOUNS +
            """(?:所以|因此|就|现在|此时|此刻|当下|今天|一定|必然|可能)[^，,。！？；;\n]{0,12}""" +
            """(?:有|带|沾|没|无)[^，,。！？；;\n]{0,6}(?:汗味|体味|烟味|油味|气味|异味)""",
    )
    private val OCCUPATION_CAUSAL_ODOR = Regex(
        """(?:因为|由于)[^。！？；;\n]{0,20}(?:""" + OCCUPATION_SUBJECT + "|" + OCCUPATION_NOUNS + ")" +
            """[^。！？；;\n]{0,24}(?:有|带|沾|没|无)[^。！？；;\n]{0,6}(?:汗味|体味|烟味|油味|气味|异味)""",
    )
    private val HISTORICAL_ODOR = Regex(
        """(?:上次|之前|以前|刚才|昨天|上周|上个月|去年|前几天|日前|曾经|当时)""" +
            """[^。！？；;\n]{0,20}(?:有|带|沾|没|无)[^。！？；;\n]{0,6}(?:汗味|体味|烟味|油味|气味|异味)""",
    )
    private val POSTPOSED_HISTORICAL_ODOR = Regex(
        """(?:有|带|沾|没|无)[^。！？；;\n]{0,6}(?:汗味|体味|烟味|油味|气味|异味)""" +
            """[^。！？；;\n]{0,8}(?:上次|之前|以前|刚才|昨天|上周|上个月|去年|前几天|日前|曾经|当时)""",
    )
    private val CONTINUOUS_HISTORY = Regex(
        """(?:(?:自|从)(?:上次|之前|以前|刚才|昨天|上周|上个月|去年|前几天|日前|曾经|当时)""" +
            """(?:\s*(?:[一二三四五六七八九十\d]+[日号]?|周[一二三四五六日天]|""" +
            """星期[一二三四五六日天]|礼拜[一二三四五六日天]))?\s*(?:一直|始终)|""" +
            """(?:自|从)?(?:上次|之前|以前|刚才|昨天|上周|上个月|去年|前几天|日前|曾经|当时)""" +
            """[^。！？；;\n]{0,8}(?:以来|起|至今|到现在|到今日|到今天))"""
    )
    private val CONTINUOUS_NEGATED_ODOR = Regex(
        CONTINUOUS_HISTORY.pattern +
            """(?:一直|始终|至今|仍然|仍|还|都|从来|历来)*\s*""" +
            """(?:并)?(?:没有|没|无|未(?:有)?)\s*""" +
            """(?:明显|很|太|特别|有点|略微|轻微|一点|一些|淡淡|什么)*""" +
            """(?:汗味|体味|烟味|油味|气味|异味)(?:了|啊|呀|呢|吧|吗)?"""
    )
    private fun historicalOdorStated(text: String): Boolean =
        HISTORICAL_ODOR.containsMatchIn(text) || POSTPOSED_HISTORICAL_ODOR.containsMatchIn(text)

    private val CURRENT_TIME = Regex("""(?:今天|现在|此时|此刻|当下|目前|这会儿)""")
    private val NEGATED_ODOR = Regex(
        """(?:他|她|对方|本人|自己)?(?:今天|现在|此时|此刻|当下|目前|这会儿)?""" +
            """\s*(?:并)?(?:没有|没|无|未(?:有)?)\s*""" +
            """(?:明显|很|太|特别|有点|略微|轻微|一点|一些|淡淡|什么)*""" +
            """(?:汗味|体味|烟味|油味|气味|异味)(?:了|啊|呀|呢|吧|吗)?"""
    )
    private val FOOD_ODOR = Regex(
        "(?:菜|饭|食物|厨房)[^。！？；;\\n]{0,10}(?:有|带|沾)" +
            "[^。！？；;\\n]{0,4}(?:油味|烟味|气味|异味)",
    )
    private val BODY_ODOR = Regex(
        "(?:身上|体味|汗味|衣物|本人|自己)[^。！？；;\\n]{0,8}(?:汗味|体味|油味|烟味|气味|异味)",
    )
    private val PERIANAL_TERM = Regex("""(?:肛周|肛门)""")
    private val PERINEUM_TERM = Regex("""会阴""")
    private const val LOWER_BODY_TERM =
        "(?:下体|阴部|会阴|生殖器|外阴|阴茎|包皮|龟头|阴囊|股间|腹股沟)"
    private val LOWER_BODY_TERM_REGEX = Regex(LOWER_BODY_TERM)
    private const val NOTE_ODOR_TERM =
        "(?:气味|汗味|体味|烟味|油味|酸味|异味|臭味|味道|味)"
    private val OTHER_BODY_REGION = Regex("""(?:腋下|腋窝|头皮|口腔|牙齿|脚|足部|衣物|衣服)""")
    private val LOWER_CLAUSE_BOUNDARY = Regex("""(?:但是|但|不过|然而|而且|并且|同时|另外|只是|，|,)""")
    private val LOWER_PREDICATE_START = Regex(
        """^(?:却|也|还|仍|并且|而且|同时|另外)?(?:有|带|沾|出现|发出|散|留|存在|没|无|未|不)"""
    )
    private val LOWER_NOTE_ODOR = Regex(
        LOWER_BODY_TERM + """[^。！？；;\n]{0,10}(?:有|带|沾|出现|发出|散|留|存在)""" +
            """[^。！？；;\n]{0,4}""" + NOTE_ODOR_TERM +
            "|" + NOTE_ODOR_TERM + """[^。！？；;\n]{0,8}""" + LOWER_BODY_TERM,
    )
    private val LOWER_NOTE_NO_ODOR = Regex(
        LOWER_BODY_TERM + """[^。！？；;\n]{0,8}""" +
            """(?:并没有|并未|没有|没|无|未|不存在|不带有|不带|未发现|没发现|没有发现)""" +
            """[^。！？；;\n]{0,4}""" + NOTE_ODOR_TERM,
    )
    private val LOWER_NOTE_DOUBLE_NEGATED = Regex(
        LOWER_BODY_TERM + """[^。！？；;\n]{0,8}(?:不是|并非|并不是)""" +
            """(?:完全|全|全然|丝毫|一点|有些|有点|真的|确实|明显|很|太|特别)*""" +
            """(?:没有|没|无|未|不)[^。！？；;\n]{0,4}""" + NOTE_ODOR_TERM,
    )

    private fun lowerClauseOdorStated(clause: String, inheritedLower: Boolean): Boolean {
        val candidate = if (
            inheritedLower && !LOWER_BODY_TERM_REGEX.containsMatchIn(clause) &&
            !OTHER_BODY_REGION.containsMatchIn(clause)
        ) {
            "下体$clause"
        } else {
            clause
        }
        return (LOWER_NOTE_ODOR.containsMatchIn(candidate) &&
            (!LOWER_NOTE_NO_ODOR.containsMatchIn(candidate) ||
                LOWER_NOTE_DOUBLE_NEGATED.containsMatchIn(candidate))) ||
            LOWER_NOTE_DOUBLE_NEGATED.containsMatchIn(candidate)
    }

    private fun lowerBodyOdorStated(text: String): Boolean {
        for (sentence in text.split(Regex("""[。！？；;\n]"""))) {
            var lowerSubject = false
            var inheritLower = false
            var start = 0
            val boundaries = LOWER_CLAUSE_BOUNDARY.findAll(sentence).toList()
            for (index in boundaries.indices) {
                val boundary = boundaries[index]
                val clause = sentence.substring(start, boundary.range.first).trim()
                if (lowerClauseOdorStated(clause, inheritLower)) return true
                if (LOWER_BODY_TERM_REGEX.containsMatchIn(clause)) lowerSubject = true
                val nextStart = boundary.range.last + 1
                val nextEnd = boundaries.getOrNull(index + 1)?.range?.first ?: sentence.length
                val nextClause = sentence.substring(nextStart, nextEnd).trim()
                inheritLower = lowerSubject && !LOWER_BODY_TERM_REGEX.containsMatchIn(nextClause) &&
                    !OTHER_BODY_REGION.containsMatchIn(nextClause) &&
                    ((boundary.value != "，" && boundary.value != ",") ||
                        LOWER_PREDICATE_START.containsMatchIn(nextClause))
                start = nextStart
            }
            if (lowerClauseOdorStated(sentence.substring(start).trim(), inheritLower)) return true
        }
        return false
    }

    fun claimIssues(claim: OdorClaim): List<OdorIssue> = buildList {
        addAll(coreIssues(claim))
        addAll(presenceIssues(claim))
        addAll(sourceIssues(claim))
        addAll(textIssues(claim.note))
        addAll(noteRegionIssues(claim))
        if (OdorRegion.PERIANAL in claim.odorRegion) add(regionIssue())
        OdorRules.temporalIssue(claim)?.let { add(it) }
        OdorRules.temporalTierIssue(claim)?.let { add(it) }
        OdorRules.temporalSourceIssue(claim)?.let { add(it) }
        OdorRules.seasonIssue(claim)?.let { add(it) }
        OdorRules.freshSweatIssue(claim)?.let { add(it) }
        OdorRules.baselineIssue(claim)?.let { add(it) }
        dryMarkIssue(claim)?.let { add(it) }
    }

    private fun coreIssues(claim: OdorClaim): List<OdorIssue> = buildList {
        claim.odorPresent ?: add(issue(OdorCodes.CORE_PRESENT, "存在档 odorPresent 必填"))
        if (claim.odorRegion.isEmpty()) add(issue(OdorCodes.CORE_REGION, "分区 odorRegion 至少一区"))
        claim.odorTemporal ?: add(issue(OdorCodes.CORE_TEMPORAL, "时间档 odorTemporal 必填"))
        claim.odorSeasonal ?: add(issue(OdorCodes.CORE_SEASONAL, "季节档 odorSeasonal 必填"))
        claim.climateEvidence ?: add(issue(OdorCodes.CORE_CLIMATE, "气候依据必须引用 657 或 730"))
        claim.odorClimateTrend ?: add(issue(OdorCodes.CORE_CLIMATE_TREND, "热湿/干冷方向断言必填"))
    }

    private fun presenceIssues(claim: OdorClaim): List<OdorIssue> {
        val present = claim.odorPresent ?: return emptyList()
        val noOdor = present == OdorPresence.NONE
        if (noOdor && (claim.odorSource.isNotEmpty() || claim.odorWord.isNotEmpty())) {
            return listOf(issue(OdorCodes.ZERO_WITH_DETAIL, "存在档为无，却仍填来源或描述词"))
        }
        if (noOdor && claim.smellIntensity != null && claim.smellIntensity.smellIntensity > 0) {
            return listOf(issue(OdorCodes.ZERO_WITH_INTENSITY, "存在档为无，883 强度却为非零档"))
        }
        if (!noOdor && claim.smellIntensity?.smellIntensity == 0) {
            return listOf(issue(OdorCodes.INTENSITY_PRESENCE_CONFLICT, "存在档有味，883 强度却为零档"))
        }
        if (!noOdor && claim.odorWord.isEmpty()) {
            return listOf(issue(OdorCodes.CORE_WORD, "有气味却缺中性描述词 odorWord"))
        }
        return emptyList()
    }

    private fun sourceIssues(claim: OdorClaim): List<OdorIssue> {
        val present = claim.odorPresent ?: return emptyList()
        if (present == OdorPresence.NONE) return emptyList()
        val lower = OdorRules.hasLowerBody(claim.odorRegion)
        val validOrigin = OdorRules.hasValidLowerBodyOrigin(claim.lowerBodyOrigins)
        if (lower && !validOrigin) {
            return listOf(issue(OdorCodes.LOWER_NO_SOURCE, "下体有味却无 883 垢/780 三液/806 汗来源"))
        }
        val lowerOnly = lower && claim.odorRegion.all { it.lowerBody }
        if (claim.odorSource.isEmpty() && !(lowerOnly && validOrigin)) {
            return listOf(issue(OdorCodes.CORE_SOURCE, "有气味却缺来源 odorSource 或下体来源"))
        }
        return emptyList()
    }

    private fun regionIssue(): OdorIssue = issue(
        OdorCodes.REGION_REDLINE,
        "肛周默认不写：边界归 807，本批不展开",
    )

    private fun noteRegionIssues(claim: OdorClaim): List<OdorIssue> = buildList {
        if (PERIANAL_TERM.containsMatchIn(claim.note)) add(regionIssue())
        if (PERINEUM_TERM.containsMatchIn(claim.note)) {
            add(issue(OdorCodes.REGION_REDLINE, "会阴待 e1：本批不展开"))
        }
        val present = claim.odorPresent
        val lowerConfirmed = OdorRules.hasLowerBody(claim.odorRegion) &&
            OdorRules.hasValidLowerBodyOrigin(claim.lowerBodyOrigins)
        if (
            lowerBodyOdorStated(claim.note) &&
            (!lowerConfirmed || present == null || present == OdorPresence.NONE)
        ) {
            add(issue(OdorCodes.LOWER_NO_SOURCE, "文案指下体有味，但存在档与结构化分区/正来源不一致"))
        }
    }

    private fun dryMarkIssue(claim: OdorClaim): OdorIssue? {
        val dry = claim.residueDryness ?: return null
        val silent = dry.level >= DrynessGrade.DRY_MARK_LEVEL && claim.odorPresent == OdorPresence.NONE
        return if (silent) {
            OdorIssue(
                OdorCodes.DRY_MARK_SILENT,
                IssueSeverity.PROMPT,
                "干痕/干涸仍可能留淡味：可以写淡，不要写完全无味",
            )
        } else {
            null
        }
    }

    private fun textIssues(text: String): List<OdorIssue> = buildList {
        val perfumeGuidance = perfumeGuidanceStated(text)
        addAll(diagnosisIssues(text))
        addAll(wordIssues(OdorWords.VALUE_JUDGMENT, OdorCodes.VALUE_JUDGMENT, "人格/地位判断", text))
        if (VALUE_DIRTY.containsMatchIn(text)) {
            add(issue(OdorCodes.VALUE_JUDGMENT, "不得用脏给人或体味作价值判断"))
        }
        addAll(
            wordIssues(
                behaviorGuidanceWords(text, perfumeGuidance),
                OdorCodes.BEHAVIOR_GUIDANCE,
                "行为指导",
                text,
            )
        )
        addAll(
            wordIssues(OdorWords.OCCUPATION_INFERENCE, OdorCodes.OCCUPATION_INFERENCE, "职业直接推当下气味", text)
        )
        addAll(wordIssues(OdorWords.POINT_VALUE, OdorCodes.POINT_VALUE, "点值/化验", text))
        addAll(wordIssues(OdorWords.FRESH_SWEAT_SOUR, OdorCodes.FRESH_SWEAT_SOUR, "新汗即酸臭", text))
        DISEASE_NAME.findAll(text)
            .map { it.value }
            .filter { candidate -> OdorWords.DIAGNOSIS.none { candidate.contains(it) } }
            .distinct()
            .forEach { add(issue(OdorCodes.DIAGNOSIS, "出现病名词「" + it + "」")) }
        if (INTENSITY_CLAIM.containsMatchIn(text) || INTENSITY_FORMULA.containsMatchIn(text)) {
            add(issue(OdorCodes.INTENSITY_UNSOURCED, "强度只能引用 883 结构化档，不得在文案自报或自算"))
        }
        if (perfumeGuidance) {
            add(issue(OdorCodes.BEHAVIOR_GUIDANCE, "不得给出香水相关的行为指导"))
        }
        addAll(occupationIssues(text))
        addAll(washedTextIssues(text))
    }

    private fun perfumeGuidanceStated(text: String): Boolean =
        text.split(Regex("""[。！？；;\n]""")).any { sentence ->
            PERFUME_REPORTED_ADVICE.containsMatchIn(sentence) ||
                sentence.split(Regex("""[,，]""")).any { clause ->
                    PERFUME_TERM.containsMatchIn(clause) &&
                        ((PERFUME_MASK.containsMatchIn(clause) &&
                            !PERFUME_HISTORY.containsMatchIn(clause)) ||
                            (PERFUME_ADVICE.containsMatchIn(clause) &&
                                (PERFUME_INTENT_BEFORE.containsMatchIn(clause) ||
                                    PERFUME_PURCHASE_ADVICE.containsMatchIn(clause) ||
                                    (!PERFUME_HISTORY.containsMatchIn(clause) &&
                                        PERFUME_ACTION.containsMatchIn(clause)))))
                }
        }

    private fun behaviorGuidanceWords(text: String, guidanceStated: Boolean): List<String> =
        if (!guidanceStated && historicalPerfumeMaskStated(text)) {
            OdorWords.BEHAVIOR_GUIDANCE.filterNot { it.endsWith("掩盖") }
        } else {
            OdorWords.BEHAVIOR_GUIDANCE
        }

    private fun historicalPerfumeMaskStated(text: String): Boolean =
        text.split(Regex("""[。！？；;\n]""")).any { sentence ->
            sentence.split(Regex("""[,，]""")).any { clause ->
                PERFUME_HISTORY.containsMatchIn(clause) &&
                    PERFUME_MASK.containsMatchIn(clause) &&
                    !PERFUME_INTENT.containsMatchIn(clause)
            }
        }

    private fun occupationIssues(text: String): List<OdorIssue> =
        text.split(Regex("""[。！？；;\n]"""))
            .map { sentence ->
                val clauses = sentence.split(Regex("""[,，]"""))
                val occupationStated = clauses.any { OCCUPATION.containsMatchIn(it) }
                clauses
                    .filterNot { clause ->
                        FOOD_ODOR.containsMatchIn(clause) && !BODY_ODOR.containsMatchIn(clause)
                    }
                    .filterNot { clause ->
                        historicalOdorStated(clause) &&
                            !CURRENT_TIME.containsMatchIn(clause) &&
                            !CONTINUOUS_HISTORY.containsMatchIn(clause)
                    }
                    .filterNot { clause ->
                        occupationStated && !OCCUPATION.containsMatchIn(clause) &&
                            NEGATED_ODOR.matches(clause.trim())
                    }
                    .filterNot { clause -> CONTINUOUS_NEGATED_ODOR.matches(clause.trim()) }
                    .joinToString("，")
            }
            .filter { candidate ->
                OCCUPATION.containsMatchIn(candidate) &&
                    (CURRENT_OCCUPATION_ODOR.containsMatchIn(candidate) ||
                        OCCUPATION_CAUSAL_ODOR.containsMatchIn(candidate))
            }
            .map { issue(OdorCodes.OCCUPATION_INFERENCE, "职业只能提示可能暴露，不能直推当下气味") }

    private fun washedTextIssues(text: String): List<OdorIssue> {
        if (OdorRules.incompleteWashStated(text)) return emptyList()
        return wordIssues(OdorWords.WASHED_STILL_ODOR, OdorCodes.WASHED_STILL_ODOR, "洗后仍味", text)
    }

    private fun diagnosisIssues(text: String): List<OdorIssue> {
        val normalized = text.lowercase()
        return OdorWords.DIAGNOSIS
            .filter { word ->
                val suffix = if (word in OdorWords.DIAGNOSIS_ODOR_COLLISIONS) "(?!味)" else ""
                Regex(Regex.escape(word.lowercase()) + suffix).containsMatchIn(normalized)
            }
            .map { issue(OdorCodes.DIAGNOSIS, "出现病名/诊断词「" + it + "」") }
    }

    private fun wordIssues(
        words: List<String>,
        code: String,
        kind: String,
        text: String = "",
    ): List<OdorIssue> {
        val normalized = text.lowercase()
        return words.filter { word ->
            word.isNotEmpty() && normalized.contains(word.lowercase())
        }.map { issue(code, "出现" + kind + "词「" + it + "」") }
    }

    private fun issue(code: String, detail: String): OdorIssue =
        OdorIssue(code, IssueSeverity.RED, detail)
}
