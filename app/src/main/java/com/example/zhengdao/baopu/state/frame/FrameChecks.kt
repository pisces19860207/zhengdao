// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十五批 · 判据：纯函数，只回答「这张骨点单自洽吗」。
// 不下生理结论、不进闸、不落库、不装配（源码门禁钉）。
//
// 六条判据：
// ① 点数 10–14 且三项齐（构造期保证，这里出账）；
// ② 可见度必须由 815 真枚举 bodyFatTier 推——体脂高却写分明 ⇒ 红（补丁三，负例进单测）；
// ③ 每点可作参照锚，目录给 ≥3 条「距某点 N 档」示例；
// ④ 禁数值、禁评价，出口过文字闸；
// ⑤ 与 660 截面正交：换遍骨架三档与截面宽三档，可见度指纹逐字不变（宽骨架≠骨点更明显）；
// ⑥ 补丁五/六：每点 zoneId/zoneName 齐、人读名限三档、subzoneId 只认 775 十四个 code；
// ⑦ detekt≤189／lint 0/0／水位≤315／单测 0 失败（工具真读数，不在此函数内）。
//
// 补丁一/七：骨段与关节归 900；本包没有骨段表与关节档，判据也不替 900 说话。
package com.example.zhengdao.baopu.state.frame

import com.example.zhengdao.baopu.state.bodytype.BodyFatTier
import com.example.zhengdao.baopu.state.bodytype.SectionGrade
import com.example.zhengdao.baopu.state.bodytype.SectionSpec
import com.example.zhengdao.baopu.state.bodytype.SkeletonTier

/** 一条结论。理由带角色与档名，出门前过文字闸。 */
data class FrameFinding(
    val verdict: FrameVerdict,
    val code: String,
    val why: String,
    val source: String = FRAME_SOURCE,
    val asOf: String = FRAME_AS_OF,
) {
    init {
        require(code.isNotBlank()) { "结论缺代码：无来源" }
        require(why.isNotBlank()) { "结论缺理由：无来源" }
        FrameGuard.rejectAll(why)
    }

    fun line(): String =
        "[" + verdict.label + "] " + code + "：" + why + "（" + source + " " + asOf + "）"
}

/** 判据集合。全部纯函数：同一张单跑两遍，结论逐字一致。 */
object FramePointChecks {

    /** 体脂到达这一档即启用「高体脂盖住骨点」红线。 */
    private val FAT_HIGH: Int = BodyFatTier.HIGH.level

    /** ① 点数与三项齐：结构保证，这里出账。 */
    fun completeness(p: FramePerson): FrameFinding = FrameFinding(
        FrameVerdict.PASS, "BONE-COMPLETE",
        p.roleId + "：骨点 " + p.frame.size + " 处齐（" + FrameCatalog.MIN_POINTS + "–" +
            FrameCatalog.MAX_POINTS + " 区间内），每点 visible／relative／palpable 三项齐，禁数值",
    )

    /**
     * ② 补丁二/三：visible 直接读 815 真枚举推。
     * 体脂高却写分明单独判红并点名；其余不符也判红，不许「差不多」。
     */
    fun fatVisible(p: FramePerson): FrameFinding {
        val fat = p.profile.bodyFatTier
        val wrong = p.frame.all().filter {
            it.visible != FrameCatalog.visibleOf(it.point, fat)
        }
        val red = wrong.filter { fat.level >= FAT_HIGH && it.visible.level >= VisibleGrade.LAST }
        return when {
            red.isNotEmpty() -> FrameFinding(
                FrameVerdict.DENY, "FAT-HIGH-VISIBLE",
                p.roleId + "：体脂 " + fat.label + " 却把 " + names(red) +
                    " 写成" + VisibleGrade.PRONOUNCED.label + "——脂层先盖住骨点，分明不成立",
            )
            wrong.isNotEmpty() -> FrameFinding(
                FrameVerdict.DENY, "VISIBLE-NOT-DERIVED",
                p.roleId + "：体脂 " + fat.label + "，" + names(wrong) +
                    " 的可见档不是从 815 体脂真枚举推出来的——不许手填",
            )
            else -> FrameFinding(
                FrameVerdict.PASS, "VISIBLE-DERIVED",
                p.roleId + "：" + p.frame.size + " 处可见档全部由 815 体脂「" + fat.label + "」推出",
            )
        }
    }

    /** ② 的后半：可触及档同样从 815 真枚举推，与可见分开出账。 */
    fun palpableDerived(p: FramePerson): FrameFinding {
        val fat = p.profile.bodyFatTier
        val wrong = p.frame.all().filter {
            it.palpable != FrameCatalog.palpableOf(it.point, fat)
        }
        return if (wrong.isEmpty()) {
            FrameFinding(
                FrameVerdict.PASS, "PALPABLE-DERIVED",
                p.roleId + "：" + p.frame.size + " 处可触及档全部落闭集，与体脂「" + fat.label + "」对得上",
            )
        } else {
            FrameFinding(
                FrameVerdict.DENY, "PALPABLE-NOT-DERIVED",
                p.roleId + "：" + names(wrong) + " 的可触及档不是从 815 体脂真枚举推出来的",
            )
        }
    }

    /** ③ 每点都有唯一名与八向，都能被下游当参照锚；示例数 ≥3。 */
    fun anchorExamples(p: FramePerson): FrameFinding {
        val examples = FrameCatalog.ANCHOR_EXAMPLES
        val wellFormed = examples.size >= 3 && examples.all { example ->
            example.line().contains(example.reference.label) &&
                example.line().contains(example.target.label) &&
                example.line().contains(example.step.label)
        }
        return if (wellFormed) {
            FrameFinding(
                FrameVerdict.PASS, "ANCHOR-READY",
                p.roleId + "：骨点 " + FrameCatalog.POINTS.size + " 处均可作参照锚；" +
                    examples.size + " 条示例照「距某点 N 档」写，如「" + examples.first().line() + "」",
            )
        } else {
            FrameFinding(
                FrameVerdict.DENY, "ANCHOR-MISSING",
                p.roleId + "：锚示例不足三条或没点名已冻结点，下游无法引用",
            )
        }
    }

    /** ④ 出口全闸：命中评价/数值/诊断词即抛，不返回普通结论。 */
    fun noNumbers(p: FramePerson): FrameFinding {
        FrameGuard.rejectAll(p.line())
        return FrameFinding(
            FrameVerdict.PASS, "NO-NUMBERS",
            p.roleId + "：只出档名与八向，不写尺寸、围度或比例口径",
        )
    }

    /**
     * ⑤ 与 660 截面正交：宽骨架不等于骨点更明显。
     *
     * [section] 是从 660 档化后传入的只读截面档；本函数只看它给没给，
     * 换遍宽档也不许改可见度指纹。不提供截面就跳过，不假判一致。
     */
    fun sectionOrthogonal(p: FramePerson, section: SectionSpec?): FrameFinding {
        if (section == null) {
            return FrameFinding(
                FrameVerdict.PASS, "SECTION-NA",
                p.roleId + "：未给 660 截面档，跳过宽骨架交叉比对（不假判一致）",
            )
        }
        val base = visibleFingerprint(p.profile, section)
        val skeletonLeak = SkeletonTier.entries.any {
            visibleFingerprint(p.profile.copy(skeletonTier = it), section) != base
        }
        val widthLeak = SectionGrade.entries.any { grade ->
            visibleFingerprint(p.profile, section.copy(width = grade)) != base
        }
        return if (skeletonLeak || widthLeak) {
            FrameFinding(
                FrameVerdict.DENY, "WIDE-FRAME-BOOST",
                p.roleId + "：换骨架档或截面宽档后可见结论变了——宽骨架不等于骨点更明显",
            )
        } else {
            FrameFinding(
                FrameVerdict.PASS, "SECTION-ORTHOGONAL",
                p.roleId + "：骨架 " + SkeletonTier.COUNT + " 档与截面宽 " + SectionGrade.COUNT +
                    " 档换遍，可见度指纹逐字不变",
            )
        }
    }

    /** 补丁五/六：区名只许三档人读名，子区只许 775 冻结 code。 */
    fun zoneAligned(p: FramePerson): FrameFinding {
        val names = setOf(ZONE_TRUNK, ZONE_LEG_FOOT, ZONE_HEAD)
        val badName = FrameCatalog.POINTS.filterNot { it.zoneName in names }
        val badCode = FrameCatalog.POINTS.filter { point ->
            val code = point.subzoneId
            code != null && code !in FrameCatalog.subzoneCodes()
        }
        return when {
            badName.isNotEmpty() || badCode.isNotEmpty() -> FrameFinding(
                FrameVerdict.DENY, "ZONE-NOT-ALIGNED",
                p.roleId + "：区名或子区 code 没对齐——区名只许 " + names.joinToString("／") +
                    "，子区只许 775 的 " + FrameCatalog.subzoneCodes().size + " 个 code",
            )
            else -> FrameFinding(
                FrameVerdict.PASS, "ZONE-ALIGNED",
                p.roleId + "：" + FrameCatalog.POINTS.size + " 点均标 zoneId/zoneName；" +
                    "子区字段与 zoneId 分列，当前 " + FrameCatalog.POINTS.count { it.subzoneId != null } +
                    " 处登记（非空时必为 775 真 code）",
            )
        }
    }

    /** 判据①–⑥ 全跑，顺序固定，逐字可回放；工具门是外部第七条。 */
    fun all(p: FramePerson, section: SectionSpec? = null): List<FrameFinding> = listOf(
        completeness(p),
        fatVisible(p),
        palpableDerived(p),
        anchorExamples(p),
        noNumbers(p),
        sectionOrthogonal(p, section),
        zoneAligned(p),
    )

    fun worst(findings: List<FrameFinding>): FrameVerdict =
        findings.fold(FrameVerdict.PASS) { acc, f -> FrameVerdict.worst(acc, f.verdict) }

    /** 一行汇总：通过／提示／红 各几条。 */
    fun summary(findings: List<FrameFinding>): String {
        val pass = findings.count { it.verdict == FrameVerdict.PASS }
        val hint = findings.count { it.verdict == FrameVerdict.HINT }
        val deny = findings.count { it.verdict == FrameVerdict.DENY }
        return "通过 " + pass + "／提示 " + hint + "／红 " + deny
    }

    /** 一张单的唯一出口：汇总一行＋逐条结论，出门前再过一次闸。 */
    fun sceneLine(p: FramePerson, section: SectionSpec? = null): String {
        val findings = all(p, section)
        val text = summary(findings) + "\n" + findings.joinToString("\n") { it.line() }
        FrameGuard.rejectAll(text)
        return text
    }

    private fun names(readings: List<PointReading>): String =
        readings.joinToString("、") { it.point.label + "（" + it.visible.label + "）" }

    /**
     * 可见度指纹。截面档刻意不参与推导：[sectionMark] 只记给没给，
     * 换宽档时它不变；骨架档同理不在指纹里。
     */
    private fun visibleFingerprint(
        profile: com.example.zhengdao.baopu.state.bodytype.BodyProfile,
        section: SectionSpec?,
    ): String {
        val sectionMark = if (section == null) "未给截面" else "截面档化"
        return sectionMark + "|" + FrameCatalog.POINTS.joinToString("；") { point ->
            point.name + "=" + FrameCatalog.visibleOf(point, profile.bodyFatTier).label
        }
    }
}
