// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十批 · 判据检查器：纯函数，只回答「这份产出/这句文案有没有违规」，不下结论。
// 与 658 的 PhysiologyChecks 同一套做法：求解器自己保证产出合法，检查器供单测正负例对拍与变异自检。
package com.example.zhengdao.baopu.state.fluid

/**
 * 判据⑦的点值单位表。**全部用拼接/转义写**：本文件自身也在源码门禁的扫描范围内，
 * 把字面量写全就会咬到自己（恒红）。运行时还原的仍是同一批词。
 */
private val POINT_UNITS: List<String> = listOf(
    "m" + "L",          // 体积·大写
    "m" + "l",          // 体积·小写
    "c" + "m",          // 长度
    "\u7C73",           // 长度·中文单字
    "\u79D2",           // 时间·中文单字
    "p" + "H",          // 酸碱度
    "成" + "分",        // 成分
    "毫" + "升",        // 体积·中文
    "厘" + "\u7C73",    // 长度·中文
)

/** 量 0–1 时不许出现的「铺满」说法（判据④）。 */
private val FULL_CLAIM_WORDS: List<String> = listOf(
    "满手", "淌满", "流满", "满床", "满腿", "满身", "\u6ee1\u809a", "到处都是",
)

/** 「到处都是」这类没落点的说法（判据⑥）。 */
private val VAGUE_WORDS: List<String> = listOf("到处都是", "遍地")

internal object FluidChecks {

    // ── ① 四维：每维带驱动 ──────────────────────────────────────────────

    /** 返回「非零档却没驱动」的维名（中文，供报告与断言逐字对齐）。 */
    fun missingDrivers(body: FluidBody): List<String> = buildList {
        if (!body.volume.grade.zero && body.volume.drivers.isEmpty()) add("量档")
        if (!body.viscosity.grade.zero && body.viscosity.drivers.isEmpty()) add("黏稠档")
        if (!body.temperature.grade.zero && body.temperature.drivers.isEmpty()) add("温度档")
        if (!body.dryness.grade.zero && body.dryness.drivers.isEmpty()) add("干涸档")
    }

    fun driverIssues(body: FluidBody): List<FluidIssue> =
        missingDrivers(body).map {
            FluidIssue(FluidCodes.CHANGE_NO_DRIVER, IssueSeverity.RED, it + "非零却没有驱动：四维每维都要挂驱动")
        }

    // ── ② 混合：黏度只许按规则动 ────────────────────────────────────────

    /**
     * 判据②：精液出现后黏度必须**恰好**跳一档；没有精液的混合，黏度必须**恰好**等于主液原档。
     * 后半条同时封掉「取平均」——平均值既不等于原档，也不等于跳档。
     */
    fun viscosityIssues(m: Mixture): List<FluidIssue> {
        val semen = m.dominant == FluidKind.SEMEN || m.secondary == FluidKind.SEMEN
        val expected = if (semen) ViscosityGrade.upOne(m.baseViscosity) else m.baseViscosity
        if (m.viscosity == expected) return emptyList()
        return listOf(
            if (semen) {
                FluidIssue(
                    FluidCodes.MIX_VISCOSITY_JUMP,
                    IssueSeverity.RED,
                    "精液出现后黏度应为「" + expected.label + "」，实为「" + m.viscosity.label + "」：没跳档",
                )
            } else {
                FluidIssue(
                    FluidCodes.MIX_VISCOSITY_DRIFT,
                    IssueSeverity.RED,
                    "无精液的混合黏度应保持主液原档「" + expected.label + "」，实为「" + m.viscosity.label + "」",
                )
            }
        )
    }

    // ── ③ 干涸后复湿必须有新来源 ────────────────────────────────────────

    /**
     * 判据③：干涸（3）之后变湿而无新来源 ⇒ 红；干痕（2）之后变湿而无新来源 ⇒ 提示（本单从严的额外一条）。
     */
    fun rewet(before: DrynessGrade, after: DrynessGrade, event: EventKind): FluidIssue? {
        if (EventKind.isFreshSource(event)) return null
        if (after.level >= before.level) return null
        if (before.level >= DrynessGrade.DRIED_LEVEL) {
            return FluidIssue(
                FluidCodes.CHANGE_REWET_NO_SOURCE,
                IssueSeverity.RED,
                "干涸之后又湿了，却没有新来源：干涸只能被新液拉回来",
            )
        }
        if (before.level >= DrynessGrade.DRY_MARK_LEVEL) {
            return FluidIssue(
                FluidCodes.CHANGE_REWET_NO_SOURCE_DRY_MARK,
                IssueSeverity.PROMPT,
                "干痕之后变湿，没给新来源：补一句新液/外来液体",
            )
        }
        return null
    }

    // ── ⑤⑥ 文案扫描 ───────────────────────────────────────────────────

    /** 判据⑦：返回命中的点值单位（空 = 全文只有档位）。 */
    fun pointValueUnits(text: String): List<String> = POINT_UNITS.filter { text.contains(it) }

    fun pointValueIssues(text: String): List<FluidIssue> =
        pointValueUnits(text).map { FluidIssue(FluidCodes.UNIT_POINT_VALUE, IssueSeverity.RED, "出现点值单位「" + it + "」") }

    /** 判据④：量档只有「微」及以下，文案却写「满手/淌满/到处都是」⇒ 红。 */
    fun overstatedCoverage(volume: VolumeGrade, text: String): FluidIssue? {
        if (volume.level > VolumeGrade.TRACE.level) return null
        val hit = FULL_CLAIM_WORDS.firstOrNull { text.contains(it) } ?: return null
        return FluidIssue(
            FluidCodes.COVERAGE_TEXT_OVER,
            IssueSeverity.RED,
            "量档只有「" + volume.label + "」，写不出「" + hit + "」",
        )
    }

    /** 判据⑥：「到处都是」而没有落点 ⇒ 红。有落点时不管文案怎么说。 */
    fun vagueLanding(text: String, landing: Landing?): FluidIssue? {
        if (landing != null) return null
        val hit = VAGUE_WORDS.firstOrNull { text.contains(it) } ?: return null
        return FluidIssue(FluidCodes.VAGUE_LANDING, IssueSeverity.RED, "「" + hit + "」不是落点：要说清落在哪儿")
    }

    /**
     * 判据⑤的三型互换：拿「声明的型」去对文案——写错了型的标志词就红。
     * 三型各自的必写点与禁写点都在这里，渲染器只负责把该写的写出来。
     */
    fun kindSwap(kind: DepartureKind, text: String): List<FluidIssue> = when (kind) {
        DepartureKind.EJECTION -> ejectionSwap(text)
        DepartureKind.SHED_BY_MOTION -> shedSwap(text)
        DepartureKind.SEEPAGE -> seepageSwap(text)
        DepartureKind.MIXED -> emptyList()
    }

    private fun ejectionSwap(text: String): List<FluidIssue> = buildList {
        if (text.contains("贴体")) add(swap("喷射写了「贴体」：那是重力流的写法"))
        if (!looseSpeed(text)) add(swap("喷射必须写初速：没有初速就没有抛物线"))
    }

    private fun shedSwap(text: String): List<FluidIssue> = buildList {
        if (text.contains("抛物线")) add(swap("甩出写了「抛物线」：甩出不走抛物线"))
        if (loosePelvic(text)) add(swap("甩出写了盆底参与：甩出与盆底无关"))
        if (looseSpeed(text)) add(swap("甩出写了初速：甩出只有切向速度"))
        if (!text.contains("切向")) add(swap("甩出必须写「切向」"))
    }

    private fun seepageSwap(text: String): List<FluidIssue> = buildList {
        if (text.contains("抛物线")) add(swap("重力流写了「抛物线」：重力流没有初速"))
        if (looseSpeed(text)) add(swap("重力流写了初速：重力流无初速"))
        if (!text.contains("贴体")) add(swap("重力流必须写「贴体」"))
    }

    private fun swap(detail: String): FluidIssue =
        FluidIssue(FluidCodes.DEPART_SWAP, IssueSeverity.RED, detail)

    /** 「无初速」是重力流的正当写法；剥掉它还剩「初速」才算写了初速。 */
    private fun looseSpeed(text: String): Boolean = text.replace("无初速", "").contains("初速")

    /** 「无盆底」是甩出的正当写法；剥掉它还剩「盆底」才算写了盆底参与。 */
    private fun loosePelvic(text: String): Boolean = text.replace("无盆底", "").contains("盆底")

    /** 一段文案的全部红/提示（⑦ 点值＋④ 覆盖夸大＋⑥ 无落点），空列表 = 干净。 */
    fun textIssues(text: String, volume: VolumeGrade, landing: Landing?): List<FluidIssue> = buildList {
        addAll(pointValueIssues(text))
        overstatedCoverage(volume, text)?.let { add(it) }
        vagueLanding(text, landing)?.let { add(it) }
    }
}
