package com.example.zhengdao.baopu.state.persona

internal enum class ClinicalCategory(val label: String) {
    PERSONALITY_DISORDER("人格障碍"),
    ATTACHMENT_TYPE("依恋类型"),
    PSYCHIATRIC_DIAGNOSIS("精神科诊断"),
    PSEUDO_PSYCH_LABEL("伪心理标签"),
}

internal enum class ClinicalAllowedPosition { DIALOGUE }
internal enum class ClinicalExemption { MEDICAL_UNCERTAIN_NARRATION }
internal enum class ClinicalPosition { AUTO, DIALOGUE, NARRATOR }
internal enum class ClinicalSubjectRole { NONE, DOCTOR, MENTAL_HEALTH_PROFESSIONAL }

internal data class ClinicalTermRule(
    val phrase: String,
    val category: ClinicalCategory,
    val allowedPosition: ClinicalAllowedPosition,
    val exemption: ClinicalExemption,
)

internal object PersonaLanguage {
    val STIGMA_TERMS = listOf("直男癌", "舔狗", "精神病", "神经病", "娘炮", "废物", "太监")
    val SURFACE_TAGS = listOf("傲娇", "天然呆", "毒舌", "腹黑", "三无", "元气", "弱气", "大小姐")
    val CLINICAL_TERMS = listOf(
        rule("人格障碍", ClinicalCategory.PERSONALITY_DISORDER),
        rule("边缘型人格", ClinicalCategory.PERSONALITY_DISORDER),
        rule("自恋型人格", ClinicalCategory.PERSONALITY_DISORDER),
        rule("表演型人格", ClinicalCategory.PERSONALITY_DISORDER),
        rule("偏执型人格", ClinicalCategory.PERSONALITY_DISORDER),
        rule("分裂样人格", ClinicalCategory.PERSONALITY_DISORDER),
        rule("依赖型人格", ClinicalCategory.PERSONALITY_DISORDER),
        rule("强迫型人格", ClinicalCategory.PERSONALITY_DISORDER),
        rule("回避型人格", ClinicalCategory.PERSONALITY_DISORDER),
        rule("分裂型人格", ClinicalCategory.PERSONALITY_DISORDER),
        rule("依恋类型", ClinicalCategory.ATTACHMENT_TYPE),
        rule("安全依恋", ClinicalCategory.ATTACHMENT_TYPE),
        rule("焦虑依恋", ClinicalCategory.ATTACHMENT_TYPE),
        rule("回避依恋", ClinicalCategory.ATTACHMENT_TYPE),
        rule("回避型依恋", ClinicalCategory.ATTACHMENT_TYPE),
        rule("混乱依恋", ClinicalCategory.ATTACHMENT_TYPE),
        rule("痴迷型依恋", ClinicalCategory.ATTACHMENT_TYPE),
        rule("恐惧型依恋", ClinicalCategory.ATTACHMENT_TYPE),
        rule("attachment", ClinicalCategory.ATTACHMENT_TYPE),
        rule("NPD", ClinicalCategory.PSYCHIATRIC_DIAGNOSIS),
        rule("PTSD", ClinicalCategory.PSYCHIATRIC_DIAGNOSIS),
        rule("BPD", ClinicalCategory.PSYCHIATRIC_DIAGNOSIS),
        rule("抑郁症", ClinicalCategory.PSYCHIATRIC_DIAGNOSIS),
        rule("双相障碍", ClinicalCategory.PSYCHIATRIC_DIAGNOSIS),
        rule("精神分裂", ClinicalCategory.PSYCHIATRIC_DIAGNOSIS),
        rule("多动症", ClinicalCategory.PSYCHIATRIC_DIAGNOSIS),
        rule("创伤后应激障碍", ClinicalCategory.PSYCHIATRIC_DIAGNOSIS),
        rule("人格量表", ClinicalCategory.PSEUDO_PSYCH_LABEL),
        rule("心理量表", ClinicalCategory.PSEUDO_PSYCH_LABEL),
        rule("心理测量", ClinicalCategory.PSEUDO_PSYCH_LABEL),
        rule("人格测验", ClinicalCategory.PSEUDO_PSYCH_LABEL),
        rule("MBTI", ClinicalCategory.PSEUDO_PSYCH_LABEL),
        rule("色彩性格", ClinicalCategory.PSEUDO_PSYCH_LABEL),
        rule("九型人格", ClinicalCategory.PSEUDO_PSYCH_LABEL),
    )

    private val UNCERTAINTY_TERMS = listOf("可能", "也许", "看起来像")
    private val DETERMINISM_TERMS = listOf("人格决定命运", "性格决定命运")
    private val QUOTED = Regex("\"([^\"]*)\"|「([^」]*)」|“([^”]*)”")
    private val DIRECT_SPEECH = Regex("(?:(?:他|她)?(?:说|问|答)|低声说)\\s*[：:]\\s*")
    private val SENTENCE_BREAKS = charArrayOf('。', '！', '？', '!', '?', '；', ';', '\n')

    private fun rule(phrase: String, category: ClinicalCategory) = ClinicalTermRule(
        phrase,
        category,
        ClinicalAllowedPosition.DIALOGUE,
        ClinicalExemption.MEDICAL_UNCERTAIN_NARRATION,
    )

    fun clinical(
        text: String,
        position: ClinicalPosition = ClinicalPosition.AUTO,
        subjectRole: ClinicalSubjectRole = ClinicalSubjectRole.NONE,
    ): List<CheckItem> {
        val findings = CLINICAL_TERMS.mapNotNull { rule ->
            val narratorHits = occurrences(text, rule.phrase)
                .filter { positionAt(text, it, position) == ClinicalPosition.NARRATOR }
            if (narratorHits.isEmpty()) return@mapNotNull null
            if (narratorHits.all { medicalUncertain(text, it, subjectRole) }) {
                CheckItem(Severity.WARNING, "CLINICAL_MEDICAL_UNCERTAIN", "医疗角色不确定引述：${rule.phrase}")
            } else {
                CheckItem(Severity.ERROR, "DIAGNOSIS", "禁心理诊断，命中：${rule.phrase}")
            }
        }
        return if (findings.any { it.severity == Severity.ERROR }) {
            findings.filter { it.severity == Severity.ERROR }
        } else {
            findings
        }
    }

    fun stigma(text: String, position: ClinicalPosition = ClinicalPosition.AUTO): List<CheckItem> =
        STIGMA_TERMS.mapNotNull { term ->
            val hits = occurrences(text, term)
            if (hits.isEmpty()) return@mapNotNull null
            if (hits.all { positionAt(text, it, position) == ClinicalPosition.DIALOGUE }) {
                CheckItem(Severity.WARNING, "STIGMA_DIALOGUE", "人物语言偏激：$term")
            } else {
                CheckItem(Severity.ERROR, "STIGMA_NARRATOR", "叙述者定型蔑称：$term")
            }
        }

    fun determinism(text: String): List<CheckItem> = DETERMINISM_TERMS
        .filter { it in text }
        .map { CheckItem(Severity.ERROR, "DETERMINISM_FORBIDDEN", "禁人格决定论：$it") }

    fun offSurfaceTags(texts: List<String>): List<CheckItem> = SURFACE_TAGS
        .filter { tag -> texts.any { tag in it } }
        .map { CheckItem(Severity.ERROR, "ANIME_TAG_OFF_SURFACE", "二次元标签只落 surface：$it") }

    fun surfaceTags(tags: List<String>): List<CheckItem> {
        val out = mutableListOf<CheckItem>()
        tags.filter { it !in SURFACE_TAGS }.forEach {
            out += CheckItem(Severity.ERROR, "ANIME_TAG_UNKNOWN", "未知 surface 标签：$it")
        }
        tags.groupingBy { it }.eachCount().filterValues { it > 1 }.forEach { (tag, count) ->
            out += CheckItem(Severity.ERROR, "ANIME_TAG_DUP", "surface 标签重复：$tag×$count")
        }
        tags.forEach { out += clinical(it, ClinicalPosition.NARRATOR) + stigma(it, ClinicalPosition.NARRATOR) }
        return out
    }

    private fun occurrences(text: String, phrase: String): List<Int> {
        val source = text.lowercase()
        val needle = phrase.lowercase()
        val out = mutableListOf<Int>()
        var from = 0
        while (from <= source.length - needle.length) {
            val hit = source.indexOf(needle, from)
            if (hit < 0) break
            if (exactBoundary(source, hit, hit + needle.length, phrase)) out += hit
            from = hit + needle.length
        }
        return out
    }

    private fun exactBoundary(text: String, start: Int, end: Int, phrase: String): Boolean {
        if (phrase.any { it.code > 127 }) return true
        val before = text.getOrNull(start - 1)
        val after = text.getOrNull(end)
        return !isAsciiLetterOrDigit(before) && !isAsciiLetterOrDigit(after)
    }

    private fun isAsciiLetterOrDigit(ch: Char?): Boolean =
        ch != null && ch.code <= 127 && ch.isLetterOrDigit()

    private fun positionAt(text: String, index: Int, forced: ClinicalPosition): ClinicalPosition {
        if (forced != ClinicalPosition.AUTO) return forced
        val quoted = QUOTED.findAll(text).any { match ->
            val range = match.groups[1]?.range ?: match.groups[2]?.range ?: match.groups[3]?.range
            range != null && index in range
        }
        val introduced = DIRECT_SPEECH.findAll(text).any { match ->
            val start = match.range.last + 1
            val end = SENTENCE_BREAKS.asIterable()
                .mapNotNull { text.indexOf(it, start).takeIf { index -> index >= 0 } }
                .minOrNull() ?: text.length
            index in start until end && completeIntroducedSpeech(text.substring(start, end))
        }
        return if (quoted || introduced) ClinicalPosition.DIALOGUE else ClinicalPosition.NARRATOR
    }

    private fun completeIntroducedSpeech(speech: String): Boolean = when {
        speech.startsWith("「") -> "」" in speech
        speech.startsWith("“") -> "”" in speech
        speech.startsWith("\"") -> "\"" in speech.substring(1)
        else -> true
    }

    private fun medicalUncertain(text: String, index: Int, role: ClinicalSubjectRole): Boolean =
        role != ClinicalSubjectRole.NONE && UNCERTAINTY_TERMS.any { it in sentenceAt(text, index) }

    private fun sentenceAt(text: String, index: Int): String {
        var start = 0
        var end = text.length
        SENTENCE_BREAKS.forEach { ch ->
            val before = text.lastIndexOf(ch, index - 1)
            if (before >= start) start = before + 1
            val after = text.indexOf(ch, index)
            if (after >= 0 && after < end) end = after
        }
        return text.substring(start, end)
    }
}
