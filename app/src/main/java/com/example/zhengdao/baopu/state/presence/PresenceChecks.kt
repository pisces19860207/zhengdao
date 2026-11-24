// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百九十六批 · 判据（纯函数）。
// 场景级判红只有两条，红规则表恰两项；DENY 只能由 red() 这一处产生。
// 六类日常身体场景与亲子照护场景抑制全部避讳类提示；失真提示独立保留，不判红。
// 参考矩阵只展示不参与判定；kinAvoidance 只写进 why 的倾向段，不拦截事件。
package com.example.zhengdao.baopu.state.presence

object PresenceChecks {

    /** 两条硬红线，冻结。源码门禁断言表长恒为 2。 */
    enum class RedRule(val code: String, val text: String) {
        CHILD_SEXUAL("RED-CHILD-SEXUAL", "有小孩在场且写了性化描写：这条红线不商量"),
        UNDERAGE_SEXUAL("RED-UNDERAGE-SEXUAL", "主角是未成年且写了性化描写：这条红线不商量"),
        ;

        companion object {
            const val COUNT: Int = 2
        }
    }

    const val HINT_MISSING_PRESENCE: String = "HINT-PRESENCE-UNSET"
    const val HINT_NAKED_SEX: String = "HINT-NAKED-IS-NOT-SEX"
    const val HINT_OVER_DRESS: String = "HINT-UNDERWEAR-ENOUGH"
    const val HINT_DISTORT_WRAP: String = "DISTORT-WRAPPED-EVERYONE"
    const val HINT_DISTORT_NURSE: String = "DISTORT-NURSING-HIDDEN"

    data class PresenceFinding(
        val kind: FindingKind,
        val verdict: PresenceVerdict,
        val code: String,
        val why: String,
    ) {
        init {
            require(code.isNotBlank() && why.isNotBlank()) { "结论缺代码或缺理由" }
            PresenceGuard.requireNeutral(why)
        }

        fun line(): String = "[" + verdict.label + "/" + kind.label + "] " + code + "：" + why
    }

    /** 两条红线的命中集合。全包只有这里能产出 DENY。 */
    fun redRules(s: PresenceScene): List<RedRule> {
        val hits = mutableListOf<RedRule>()
        if (s.minor == MinorPresence.ACCOMPANIED && s.sexual != SexualSignal.NONE) {
            hits.add(RedRule.CHILD_SEXUAL)
        }
        if (s.minor == MinorPresence.UNDERAGE_LEAD && s.sexual != SexualSignal.NONE) {
            hits.add(RedRule.UNDERAGE_SEXUAL)
        }
        return hits
    }

    /** 六类场所的日常身体场景，或照护动作、有小孩在场，均不判不提示。 */
    private fun noAvoidance(s: PresenceScene): Boolean =
        (s.sceneKind == SceneKind.DAILY_BODY && PresenceCatalog.isRoutine(s.venue)) ||
            s.care != CareAction.NONE ||
            s.minor == MinorPresence.ACCOMPANIED ||
            s.age?.order == AgeTier.ZERO_AVOID_ORDER

    /** 避讳类提示。日常与照护场景一律抑制；过度限制只提示，应对自动降到穿内裤。 */
    private fun avoidance(s: PresenceScene): List<PresenceFinding> {
        if (noAvoidance(s)) return emptyList()
        val out = mutableListOf<PresenceFinding>()
        if (s.presence == null) {
            out.add(hint(HINT_MISSING_PRESENCE, "没写在场者：先按有他人处理，可后补"))
        }
        if (s.sexual == SexualSignal.FROM_NAKED) {
            out.add(hint(HINT_NAKED_SEX, "仅因裸体推断性意味，设定未明写：按生活层记"))
        }
        if (s.requestedNude == CanBeNude.FULLY_DRESSED && !s.outsider) {
            out.add(hint(HINT_OVER_DRESS, "没有明确外人却要穿戴整齐：穿条内裤已经够用"))
        }
        return out
    }

    /** 失真提示独立保留：它说的是把常态写歪，不是避讳，所以日常场所也要出。 */
    private fun distortion(s: PresenceScene): List<PresenceFinding> = when (s.distortion) {
        DistortionFlag.NONE -> emptyList()
        DistortionFlag.WRAPPED_EVERYONE -> listOf(
            PresenceFinding(
                FindingKind.DISTORTION, PresenceVerdict.HINT, HINT_DISTORT_WRAP,
                "写成人人裹着毛巾：与这类场所的现场常态不符",
            ),
        )
        DistortionFlag.NURSING_HIDDEN -> listOf(
            PresenceFinding(
                FindingKind.DISTORTION, PresenceVerdict.HINT, HINT_DISTORT_NURSE,
                "把哺乳写成背过身遮掩：与育儿常态不符",
            ),
        )
    }

    private fun hint(code: String, why: String): PresenceFinding =
        PresenceFinding(FindingKind.AVOIDANCE, PresenceVerdict.HINT, code, why)

    /** DENY 的唯一产地。 */
    private fun red(rule: RedRule): PresenceFinding =
        PresenceFinding(FindingKind.RED, PresenceVerdict.DENY, rule.code, rule.text)

    private fun pass(): PresenceFinding =
        PresenceFinding(
            FindingKind.INFO, PresenceVerdict.PASS, "PRESENCE-OK",
            "按在场者与场所记档：生活层照写，参考档见应对",
        )

    /** 全部结论，顺序固定，可逐字回放。 */
    fun all(s: PresenceScene): List<PresenceFinding> {
        PresenceGuard.rejectStigmatizingCare(s.narrativeNote)
        PresenceGuard.rejectGroupJudgment(s.narrativeNote)
        val out = mutableListOf<PresenceFinding>()
        redRules(s).forEach { out.add(red(it)) }
        out.addAll(distortion(s))
        out.addAll(avoidance(s))
        out.add(pass())
        return out
    }

    /** 问题清单：通过项之外的全部条目。 */
    fun issues(s: PresenceScene): List<PresenceFinding> =
        all(s).filter { it.verdict != PresenceVerdict.PASS }

    /** 避讳类问题清单，专门供「三岁前零避讳」断言使用。 */
    fun avoidanceIssues(s: PresenceScene): List<PresenceFinding> =
        issues(s).filter { it.kind == FindingKind.AVOIDANCE }

    fun worst(s: PresenceScene): PresenceVerdict =
        all(s).fold(PresenceVerdict.PASS) { acc, f -> PresenceVerdict.worst(acc, f.verdict) }

    /** 汇总计数。这里出现第二处 DENY 字面量，只用于计数，不产结论。 */
    fun summary(s: PresenceScene): String {
        val findings = all(s)
        val passCount = findings.count { it.verdict == PresenceVerdict.PASS }
        val hintCount = findings.count { it.verdict == PresenceVerdict.HINT }
        val denyCount = findings.count { it.verdict == PresenceVerdict.DENY }
        return "通过 " + passCount + "／提示 " + hintCount + "／红 " + denyCount
    }

    /** 结构自检：三岁前照护若出现避讳提示，视为实现错误，直接抛。 */
    fun requireInfantCareClean(
        s: PresenceScene,
        findings: List<PresenceFinding> = all(s),
    ) {
        if (s.age == AgeTier.UNDER_THREE && s.care != CareAction.NONE) {
            val bad = findings.filter { it.kind == FindingKind.AVOIDANCE }
            require(bad.isEmpty()) { "三岁前照护不得出避讳提示" }
        }
    }

    /** 应对档。明确外人才到穿戴整齐；没写在场者与矩阵不符都不影响结论。 */
    private fun chooseNude(s: PresenceScene): CanBeNude {
        val requested = s.requestedNude
        if (requested == CanBeNude.FULLY_DRESSED && !s.outsider) return CanBeNude.UNDERWEAR
        if (requested != null) return requested
        if (s.outsider) return CanBeNude.FULLY_DRESSED
        if (noAvoidance(s)) return CanBeNude.NATURAL
        if (s.presence == PresenceKind.OPPOSITE_SEX || s.presence == PresenceKind.PUBLIC) {
            if (s.kin == KinAvoidance.ALWAYS && s.age != null &&
                s.age != AgeTier.UNDER_THREE
            ) {
                return CanBeNude.WRAPPED
            }
            return CanBeNude.UNDERWEAR
        }
        return CanBeNude.NATURAL
    }

    /** 输出三件。红线成立时敏感层整层不写，生活层照写不受影响。 */
    fun output(s: PresenceScene): PresenceOutput {
        val canDescribe = if (redRules(s).isEmpty()) CanDescribe.DAILY else CanDescribe.SENSITIVE_SKIP
        val who = s.presence?.label ?: "未写（按有他人处理）"
        val carePart = if (s.care == CareAction.NONE) "" else "/照护=" + s.care.label
        val where = s.venue.label + "/" + s.sceneKind.label + carePart
        val lean = if (s.kin != KinAvoidance.NONE && s.age != null &&
            s.age != AgeTier.UNDER_THREE &&
            (s.oppositeSexKin || s.presence == PresenceKind.OPPOSITE_SEX)
        ) {
            "｜倾向=" + s.kin.label + "（只影响描写倾向，不拦截）"
        } else {
            ""
        }
        val why = "在场者=" + who + "｜期待=" + s.privacy.line() + "｜场景=" + where + lean
        return PresenceOutput(chooseNude(s), canDescribe, why, s.outsider)
    }

    /** 一张单的唯一出口：三件输出 + 汇总 + 逐条结论。 */
    fun sceneLine(s: PresenceScene): String {
        val findings = all(s)
        val text = output(s).line() + "\n" + summary(s) + "\n" +
            findings.joinToString("\n") { it.line() }
        PresenceGuard.requireNeutral(text)
        return text
    }
}
