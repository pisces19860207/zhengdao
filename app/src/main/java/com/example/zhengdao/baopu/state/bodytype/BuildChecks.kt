// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百一十五批 · 判据：纯函数，只回答「这张体型单自洽吗」。
// 不下生理结论、不进闸、不落库、不装配（源码门禁钉）。
//
// 六条判据（派工单 §判据 + 用户 2026-11-24 当面口径）：
// ① 五档齐、闭集、禁数值——结构上造不出缺档的单，这里出账；
// ② 默认中中中；低脂高肌无依据 ⇒ 红；
// ③ 与 660 截面一致——骨架宽厚／肌量围度与截面档矛盾 ⇒ 红（档对档，不读 660 数值）；
// ④ 不做身材评价——出口过评价闸，出现价值词即拒；
// ⑤ 少见标注 ≥3 例且各给一句为什么少见，低脂高肌必标少见；
// ⑥ 职业只影响倾向不决定三轴——「工人一定低脂」⇒ 红。
//
// 三层口径写在这里，免得每单重吵：
// · 三轴是「现状」不是「练没练过」；肌量高档不等于训练史，只现状。
// · 修饰维（年龄／状态）只作坐标，不计常见程度——状态不打分。
// · 体脂档对 805 的 FatBand 只做映射；本包不新造体脂档、不改 660。
package com.example.zhengdao.baopu.state.bodytype

/** 三态判定。[severity] 越大越严——取最严时用它，不依赖枚举声明次序。 */
enum class BuildVerdict(val label: String, val severity: Int) {
    PASS("通过", 0),
    HINT("提示", 1),
    DENY("红", 2),
    ;

    companion object {
        fun worst(a: BuildVerdict, b: BuildVerdict): BuildVerdict =
            if (a.severity >= b.severity) a else b
    }
}

/** 一条结论。理由带角色与档名，出门前过三道闸。 */
data class BuildFinding(
    val verdict: BuildVerdict,
    val code: String,
    val why: String,
    val source: String = BUILD_SOURCE,
    val asOf: String = BUILD_AS_OF,
) {
    init {
        require(code.isNotBlank()) { "结论缺代码：无来源" }
        require(why.isNotBlank()) { "结论缺理由：无来源" }
        BuildGuard.rejectAll(why)
    }

    fun line(): String =
        "[" + verdict.label + "] " + code + "：" + why + "（" + source + " " + asOf + "）"
}

/**
 * 判据集合。全部纯函数：同一张单跑两遍，结论逐字一致。
 * [section] 为 null 时判据③ 只出「未给截面，跳过」的通过项——不假判「一致」。
 */
object BuildChecks {

    /** 判据① 五档齐：结构保证五个 tier 非空，这里出账。 */
    fun completeness(p: BodyProfile): BuildFinding = BuildFinding(
        BuildVerdict.PASS, "TIER-COMPLETE",
        p.roleId + "：五档齐——" + BodyProfile.TIER_FIELDS.joinToString("／") + " 全部落闭集档，禁数值",
    )

    /** 判据② 默认纠偏 + 依据闸：低脂高肌无依据即红；默认组合就是中中中。 */
    fun defaultBias(p: BodyProfile): BuildFinding {
        val entry = BuildCatalog.entryOf(p.skeletonTier, p.bodyFatTier, p.muscleTier)
        return when {
            p.lowFatHighMuscle && !Basis.isReal(p.basis) -> BuildFinding(
                BuildVerdict.DENY, "LOW-FAT-HIGH-MUSCLE-NO-BASIS",
                p.roleId + "：体脂低档却肌量高档，属少见组合，却没给职业／训练史／照片级描述中的任何依据——" +
                    "不能把少数当默认",
            )
            p.lowFatHighMuscle -> BuildFinding(
                BuildVerdict.HINT, "LOW-FAT-HIGH-MUSCLE-WITH-BASIS",
                p.roleId + "：低脂高肌少见，已记依据「" + p.basis.label + "」；已标少见，可留但别当默认",
            )
            p.combo == BuildCatalog.DEFAULT_COMBO && entry.commonness == Commonness.COMMON ->
                BuildFinding(
                    BuildVerdict.PASS, "DEFAULT-MID",
                    p.roleId + "：骨架中·体脂中·肌量中，正是纠偏默认组合，常见档",
                )
            else -> BuildFinding(
                BuildVerdict.PASS, "COMBO-NORMAL",
                p.roleId + "：三轴组合「" + p.combo.label + "」" + entry.commonness.label + "，与默认不同但自洽",
            )
        }
    }

    /**
     * 判据③ 与 660 截面一致：三轴映射档与截面档相差超过一档即红（宽厚／围度矛盾）。
     * 相差一档只提示。不提供截面则跳过，不假判一致。
     */
    fun sectionConsistency(p: BodyProfile, section: SectionSpec?): BuildFinding {
        if (section == null) {
            return BuildFinding(
                BuildVerdict.PASS, "SECTION-NA",
                p.roleId + "：未提供 660 截面档，跳过宽厚／围度交叉比对（不假判一致）",
            )
        }
        val gaps = listOf(
            Triple("骨架宽", BuildCatalog.widthOf(p.skeletonTier).level, section.width.level),
            Triple("体脂厚", BuildCatalog.thicknessOf(p.bodyFatTier).level, section.thickness.level),
            Triple("肌量围", BuildCatalog.girthOf(p.muscleTier).level, section.girth.level),
        )
        val bad = gaps.filter { Math.abs(it.second - it.third) > MAX_SECTION_GAP }
        val soft = gaps.filter { Math.abs(it.second - it.third) == MAX_SECTION_GAP }
        return when {
            bad.isNotEmpty() -> BuildFinding(
                BuildVerdict.DENY, "SECTION-CONFLICT",
                p.roleId + "：" + bad.joinToString("；") { it.first + "档与截面档差 " + Math.abs(it.second - it.third) + " 档" } +
                    "——与 660 躯干截面的宽厚／围度矛盾",
            )
            soft.isNotEmpty() -> BuildFinding(
                BuildVerdict.HINT, "SECTION-SOFT",
                p.roleId + "：" + soft.joinToString("；") { it.first + "档与截面档差一档" } +
                    "：不算矛盾，但要能说清哪一侧宽厚",
            )
            else -> BuildFinding(
                BuildVerdict.PASS, "SECTION-OK",
                p.roleId + "：骨架宽／体脂厚／肌量围三档与 660 截面对得上",
            )
        }
    }

    /** 判据④ 不做身材评价：出口三闸一次过；有命中即抛（这不是普通 finding，是拒收）。 */
    fun noEvaluation(p: BodyProfile): BuildFinding {
        val text = p.line()
        BuildGuard.rejectValueWords(text)
        return BuildFinding(
            BuildVerdict.PASS, "NO-EVALUATION",
            p.roleId + "：只做分布描述，无任何价值评价词",
        )
    }

    /** 判据⑤ 少见 ≥3 例且各给一句为什么；低脂高肌必标少见。查表，不另算。 */
    fun rarity(p: BodyProfile): BuildFinding {
        val rare = BuildCatalog.RARE_ENTRIES
        val lfh = BuildCatalog.ENTRIES.values.first { it.key.lowFatHighMuscle }
        return if (rare.size >= BuildCatalog.MIN_RARE &&
            rare.all { it.why.isNotBlank() } &&
            lfh.commonness == Commonness.RARE
        ) {
            BuildFinding(
                BuildVerdict.PASS, "RARE-TABLE-OK",
                "少见组合 " + rare.size + " 例（≥" + BuildCatalog.MIN_RARE +
                    "）各给一句为什么少见，低脂高肌已标少见，未把少数当默认",
            )
        } else {
            BuildFinding(
                BuildVerdict.DENY, "RARE-TABLE-BAD",
                "少见表不合格：例数不足或原因缺失或低脂高肌未标少见",
            )
        }
    }

    /** 判据⑥ 职业只影响倾向：「工人一定低脂」这类把职业当决定的写法即红。 */
    fun occupationTendency(p: BodyProfile): BuildFinding = when {
        p.basis == Basis.OCCUPATION && p.influence == InfluenceEffect.DECISIVE -> BuildFinding(
            BuildVerdict.DENY, "OCCUPATION-DECIDES",
            p.roleId + "：把职业写成三轴取值的决定因素——职业只影响倾向，不决定骨架／体脂／肌量",
        )
        p.basis == Basis.OCCUPATION -> BuildFinding(
            BuildVerdict.PASS, "OCCUPATION-TENDENCY",
            p.roleId + "：职业只作倾向坐标，未决定三轴取值",
        )
        else -> BuildFinding(
            BuildVerdict.PASS, "BASIS-NEUTRAL",
            p.roleId + "：依据「" + p.basis.label + "」以" + p.influence.label + "方式记录，未越界决定三轴",
        )
    }

    /** 判据 ①–⑥ 全跑，顺序固定，逐字可回放。 */
    fun all(p: BodyProfile, section: SectionSpec? = null): List<BuildFinding> = listOf(
        completeness(p),
        defaultBias(p),
        sectionConsistency(p, section),
        noEvaluation(p),
        rarity(p),
        occupationTendency(p),
    )

    /** 取最严的一条：红压提示、提示压通过。 */
    fun worst(findings: List<BuildFinding>): BuildVerdict =
        findings.fold(BuildVerdict.PASS) { acc, f -> BuildVerdict.worst(acc, f.verdict) }

    /** 一行汇总：通过／提示／红 各几条。 */
    fun summary(findings: List<BuildFinding>): String {
        val pass = findings.count { it.verdict == BuildVerdict.PASS }
        val hint = findings.count { it.verdict == BuildVerdict.HINT }
        val deny = findings.count { it.verdict == BuildVerdict.DENY }
        return "通过 " + pass + "／提示 " + hint + "／红 " + deny
    }

    /** 一张单的唯一出口：汇总一行＋逐条结论。出门前三闸再过一次。 */
    fun sceneLine(p: BodyProfile, section: SectionSpec? = null): String {
        val findings = all(p, section)
        val text = summary(findings) + "\n" + findings.joinToString("\n") { it.line() }
        BuildGuard.rejectAll(text)
        return text
    }

    /** 截面档与三轴映射的最大允许差（超过即红）。 */
    const val MAX_SECTION_GAP: Int = 1
}
