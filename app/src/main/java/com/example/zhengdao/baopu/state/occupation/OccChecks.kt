// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 判据检查器：把派工单十三道判据做成一条能跑、能注入、能复跑的红。
//
// 规则号（每条都在 OccChecksTest 里有正例＋负例，判据不在文档里说话，在这里说话）：
// · C1 六维齐（派工单判据①）——整格缺或某一格缺都红；
// · C2 具体工种必须挂到环境／着装／负荷（追加判据③）——三样全缺是「只列名字」＝提示，
//   挂上来却没填齐是本期必做项没做完＝红；
// · C3 驱动面（判据②③）——四轴各给方向，且给了 ↑／↓ 就必须举得出那一轴的物理机制，
//   个体差异档不许抽走（抽了就等于把倾向说成断定）；
// · C4 刻板禁词（判据②＋2026-11-24 补充更正）——**双向**：性格／品德／智力／阶层，
//   以及卫生类的「脏」这一侧和「爱干净、讲究、洁癖」那一侧，同一颗枪子；
// · C5 交接面（判据④）——Handoff 的三个禁区值一出现就红，驱动四格少交接一格也红，
//   非军警消却带 dutyCycle 交接同样红（其余行业不填）；
// · C6 军警消（ERRATA）——细类、场景、使命期、五个特有字段按臂齐，串臂红、别的行业填了也红；
// · C7 宗教（追加二 §一）——教派七件齐，且必须带教制依据，空依据＝印象＝红；
// · C8 行业覆盖（判据①）——16 个行业每个至少一个岗位示例；
// · C9 年代（追加二 §二）——五档可用集齐、城乡两套齐、只走这两条交接；
//   网购不许提前到 2000 前、早期形制不许留到当代之后，方向写反就是「用年代推行为」的前兆；
// · C10 痕迹（⑥）——病名与诊断词表命中即红：痕迹停在痕迹；
// · C11 第二期（判据①后半）——四槽一个不许缺、状态位一个不许漏、填了状态没填内容算假填；
// · C12 涉密（ERRATA 判据）——类别之外的词、序数黏部队单位字，命中即红；
// · C13 少数派（⑧）——处境类别·占比·目光·称谓四件齐才算「只作事实」，贬损与玩笑词另由 C4 侧的词表拦。
package com.example.zhengdao.baopu.state.occupation

/** 红＝判据破；提示＝该补的没补（派工单里写明是「提示」的那几条）。 */
enum class OccSeverity(val label: String) {
    ERROR("红"),
    WARN("提示"),
}

/** 一条发现：rule＝规则号，at＝哪一行（表级用 table），message＝缺什么。 */
data class OccFinding(
    val rule: String,
    val severity: OccSeverity,
    val at: String,
    val message: String,
) {
    fun text(): String = rule + "/" + at + "：" + message
}

/** 十三道判据的唯一入口；生产表默认参数，负例走显式 rows／eras 入参。 */
object OccupationGate {

    /** 早期形制：只许存在于 1949 前与 1950–70 两档的可用集里。 */
    private val EARLY_FORMS: Set<EraForm> = setOf(EraForm.NONE_WORN, EraForm.LOOSE_PANTS)

    /** 网购只许出现在 2000 后与当代两档的获取方式里。 */
    private val ONLINE_FROM: Set<Era> = setOf(Era.EARLY_2000, Era.NOW)

    fun check(
        rows: List<OccupationRow> = OccupationTable.ROWS,
        eras: List<EraAvailability> = EraTable.ROWS,
    ): List<OccFinding> = rows.flatMap { one(it) } + coverage(rows) + eraRules(eras)

    fun redCount(findings: List<OccFinding>): Int = findings.count { it.severity == OccSeverity.ERROR }

    fun warnCount(findings: List<OccFinding>): Int = findings.count { it.severity == OccSeverity.WARN }

    private fun one(row: OccupationRow): List<OccFinding> =
        sixRule(row) + hangRule(row) + driveRule(row) + wordRule(row) + handRule(row) +
            serviceRule(row) + faithRule(row) + phaseRule(row) + phaseHands(row) + secretRule(row)

    // ── C1 六维 ────────────────────────────────────────────────

    private fun sixRule(row: OccupationRow): List<OccFinding> {
        val out = mutableListOf<OccFinding>()
        if (row.industry == null) out.add(red("C1", row.key, "没挂行业，六维缺第一维"))
        val six = row.six
        if (six == null) {
            out.add(red("C1", row.key, "六维整格缺失"))
            return out
        }
        six.missingNames().forEach { out.add(red("C1", row.key, "六维缺格：" + it)) }
        return out
    }

    // ── C2 三挂 ────────────────────────────────────────────────

    private fun hangRule(row: OccupationRow): List<OccFinding> {
        val gaps = row.missingHangs()
        if (gaps.size == 3) return listOf(warn("C2", row.key, "只列名字，环境／着装／负荷一样都没挂"))
        val out = mutableListOf<OccFinding>()
        gaps.forEach { out.add(red("C2", row.key, "三挂缺样：" + it)) }
        row.env?.missingNames()?.forEach { out.add(red("C2", row.key, "环境细节缺格：" + it)) }
        val attire = row.attire
        if (attire != null) {
            if (attire.items == null) out.add(red("C2", row.key, "防护件清单缺失（无防护也要显式给空表）"))
            if (attire.closure == null) out.add(red("C2", row.key, "开合形态缺格（881 交接没源头）"))
        }
        row.burden?.missingNames()?.forEach { out.add(red("C2", row.key, "劳动负荷缺格：" + it)) }
        return out
    }

    // ── C3 驱动面 ──────────────────────────────────────────────

    private fun driveRule(row: OccupationRow): List<OccFinding> {
        val drives = row.drives ?: return listOf(red("C3", row.key, "驱动面整格缺失"))
        val out = mutableListOf<OccFinding>()
        drives.missingAxes().forEach { out.add(red("C3", row.key, "这一轴没给方向：" + it.label)) }
        val mechs = drives.mechanisms
        if (mechs == null || mechs.isEmpty()) out.add(red("C3", row.key, "驱动举不出物理机制"))
        if (drives.spread == null) out.add(red("C3", row.key, "驱动没带个体差异档"))
        DriveAxis.entries.forEach { axis -> driveNeed(row, drives, mechs, axis)?.let { out.add(it) } }
        return out
    }

    /** 给了 ↑／↓ 却举不出该轴的机制＝把印象当因果。中性不需要因果故事。 */
    private fun driveNeed(
        row: OccupationRow, drives: DriveSet, mechs: Set<Mech>?, axis: DriveAxis,
    ): OccFinding? {
        val dir = drives.dirOf(axis)
        if (dir == null || dir == Dir.FLAT || mechs == null) return null
        if (mechs.intersect(DriveAxis.mechanismsOf(axis)).isNotEmpty()) return null
        return red("C3", row.key, "方向没有机制支撑：" + axis.label)
    }

    // ── C4 C10 C13 词表 ────────────────────────────────────────

    private fun wordRule(row: OccupationRow): List<OccFinding> {
        val texts = row.texts()
        val out = mutableListOf<OccFinding>()
        scan(out, "C4", row.key, "性格与评价词", texts, OccWords.STEREOTYPE)
        scan(out, "C4", row.key, "HYGIENE 词表", texts, OccWords.HYGIENE)
        scan(out, "C4", row.key, "宗教印象词", texts, OccWords.FAITH_WORDS)
        scan(out, "C10", row.key, "痕迹越界词表", texts, OccWords.DISEASE)
        scan(out, "C13", row.key, "贬损与玩笑词", texts, OccWords.MOCK)
        return out
    }

    private fun scan(
        into: MutableList<OccFinding>, rule: String, at: String, bucket: String,
        texts: List<String>, words: List<String>,
    ) {
        texts.forEach { text ->
            words.forEach { word ->
                if (text.contains(word)) into.add(red(rule, at, bucket + " 命中：" + word))
            }
        }
    }

    // ── C5 交接面 ──────────────────────────────────────────────

    private fun handRule(row: OccupationRow): List<OccFinding> {
        val hands = row.hands ?: return listOf(red("C5", row.key, "交接面缺失"))
        val out = mutableListOf<OccFinding>()
        hands.filter { !it.allowed }.forEach { out.add(red("C5", row.key, "禁区交接：" + it.target)) }
        DRIVE_HANDS.forEach { if (!hands.contains(it)) out.add(red("C5", row.key, "驱动未交接：" + it.target)) }
        if (row.service != null && !hands.contains(Handoff.DUTY_WINDOW_883)) {
            out.add(red("C5", row.key, "驻勤时段没交给条件档"))
        }
        if (row.service == null && hands.contains(Handoff.DUTY_WINDOW_883)) {
            out.add(red("C5", row.key, "别的行业带了 dutyCycle 交接（其余行业不填）"))
        }
        val needsFly = row.attire?.needsFlyHandoff() == true
        if (needsFly && !hands.contains(Handoff.STRUCTURE_FLY_881)) {
            out.add(warn("C5", row.key, "连体／背带没写 881 联动"))
        }
        if (!needsFly && hands.contains(Handoff.STRUCTURE_FLY_881)) {
            out.add(red("C5", row.key, "881 交接与开合形态不符"))
        }
        return out
    }

    // ── C6 军警消 ──────────────────────────────────────────────

    private fun serviceRule(row: OccupationRow): List<OccFinding> {
        val out = mutableListOf<OccFinding>()
        val slot = row.service
        if (row.industry == Industry.UNIFORM && slot == null) out.add(red("C6", row.key, "军警消行没有特有槽"))
        if (row.industry != Industry.UNIFORM && slot != null) out.add(red("C6", row.key, "别的行业填了特有槽"))
        if (slot != null) {
            slot.missingNames().forEach { out.add(red("C6", row.key, "特有字段缺：" + it)) }
            slot.wrongArmPosts().forEach { out.add(red("C6", row.key, it)) }
        }
        return out
    }

    // ── C7 宗教 ────────────────────────────────────────────────

    private fun faithRule(row: OccupationRow): List<OccFinding> {
        val out = mutableListOf<OccFinding>()
        val slot = row.faith
        if (row.industry == Industry.FAITH && slot == null) out.add(red("C7", row.key, "宗教职没有教派槽"))
        if (row.industry != Industry.FAITH && slot != null) out.add(red("C7", row.key, "非宗教职填了教派槽"))
        if (slot != null) {
            // 派工单把「只写道士／和尚而不给教派」定成提示补，不是红；其余字段缺都是红。
            if (slot.branch == null) out.add(warn("C7", row.key, "只写了笼统名称，请补教派"))
            slot.missingNames().filter { it != "教派" }.forEach { out.add(red("C7", row.key, "教派字段缺：" + it)) }
        }
        return out
    }

    // ── C11 第二期＋C13 少数派四件 ─────────────────────────────

    private fun phaseRule(row: OccupationRow): List<OccFinding> {
        val slots = row.phase2 ?: return listOf(red("C11", row.key, "第二期四槽整格缺失"))
        val out = mutableListOf<OccFinding>()
        slots.missingNames().forEach { out.add(red("C11", row.key, "占位缺槽：" + it)) }
        slots.statusMissingNames().forEach { out.add(red("C11", row.key, "占位缺状态位：" + it)) }
        slots.falselySet().forEach { out.add(red("C11", row.key, "填了状态没填内容：" + it)) }
        minorityRule(row, slots)?.let { out.addAll(it) }
        return out
    }

    /** ⑧一旦转成已填，比例·目光·称谓四件必须齐——只写一半就变成评论。 */
    private fun minorityRule(row: OccupationRow, slots: Phase2Slots): List<OccFinding>? {
        val m = slots.minority ?: return null
        if (m.status != SlotStatus.SET) return null
        val gaps = mutableListOf<String>()
        if (m.kinds == null || m.kinds.isEmpty()) gaps.add("处境类别")
        if (m.share == null) gaps.add("占比")
        if (m.gaze == null) gaps.add("目光")
        if (m.address == null) gaps.add("称谓")
        return gaps.map { red("C13", row.key, "⑧只作事实的四件缺：" + it) }
    }

    /**
     * 第二期某一槽转成已填，就必须带上那条交接；反过来，占位却提前交接也算红。
     * 这一条是给第二期准备的轨道：补内容的人不必再想「还要接哪儿」，忘了就直接红。
     */
    private fun phaseHands(row: OccupationRow): List<OccFinding> {
        val p2 = row.phase2 ?: return emptyList()
        val hands = row.hands ?: return emptyList()
        val pairs = listOf(
            p2.habitat?.status to Handoff.HABITAT_883,
            p2.traces?.status to Handoff.TRACE_BODY_805,
            p2.odor?.status to Handoff.ODOR_SOURCE_827,
        )
        val out = mutableListOf<OccFinding>()
        pairs.forEach { (status, hand) ->
            val filled = status == SlotStatus.SET
            if (filled && !hands.contains(hand)) out.add(red("C11", row.key, "第二期填了内容却没交接：" + hand.target))
            if (!filled && hands.contains(hand)) out.add(red("C11", row.key, "占位没填却提前交接：" + hand.target))
        }
        return out
    }

    // ── C12 涉密（只核军警消行）─────────────────────────────────

    private fun secretRule(row: OccupationRow): List<OccFinding> {
        if (row.service == null) return emptyList()
        val texts = row.texts()
        val out = mutableListOf<OccFinding>()
        scan(out, "C12", row.key, "类别之外的词", texts, OccWords.SECRET)
        texts.forEach { text ->
            ordinalUnit(text)?.let { out.add(red("C12", row.key, "序数黏部队单位字：" + it)) }
        }
        return out
    }

    /** 「第×旅」「7 团」这类形态；单字的「营区」「连体」是正常词，不算。 */
    private fun ordinalUnit(text: String): String? {
        var i = 0
        while (i < text.length) {
            if (text[i] == '第') {
                val tail = unitAfter(text, i + 1)
                if (tail != null) return tail
            }
            if (OccWords.DIGITS.contains(text[i])) {
                val tail = unitAfter(text, i)
                if (tail != null) return tail
            }
            i++
        }
        return null
    }

    /** 从 from 起吃掉一串数字，紧跟着是部队单位字就命中。 */
    private fun unitAfter(text: String, from: Int): String? {
        var j = from
        while (j < text.length && OccWords.DIGITS.contains(text[j])) j++
        if (j == from || j >= text.length) return null
        if (!OccWords.UNIT_WORDS.contains(text[j].toString())) return null
        return text.substring(from, j + 1)
    }

    // ── C8 行业覆盖 ────────────────────────────────────────────

    private fun coverage(rows: List<OccupationRow>): List<OccFinding> =
        Industry.entries.filter { ind -> rows.none { it.industry == ind } }
            .map { red("C8", TABLE, "行业没有岗位示例：" + it.label) }

    // ── C9 年代可用集 ──────────────────────────────────────────

    private fun eraRules(eras: List<EraAvailability>): List<OccFinding> {
        val out = mutableListOf<OccFinding>()
        Era.entries.forEach { if (eras.none { e -> e.era == it }) out.add(red("C9", TABLE, "年代缺档：" + it.label)) }
        eras.forEach { e ->
            val menus: List<Pair<String, List<*>?>> = listOf(
                "形制可用集" to e.forms, "材质可用集" to e.fabrics, "获取方式可用集" to e.supply,
                "城镇场所可用集" to e.venueCity, "乡村场所可用集" to e.venueRural,
            )
            menus.forEach { (name, menu) ->
                if (menu.isNullOrEmpty()) out.add(red("C9", e.era.key, "可用集缺项：" + name))
            }
            if (e.hands != ERA_HANDS) out.add(red("C9", e.era.key, "年代只许走衣物与场所两条交接"))
            scan(out, "C9", e.era.key, "年代越界词", eraTexts(e), OccWords.HYGIENE)
            scan(out, "C9", e.era.key, "年代评价词", eraTexts(e), OccWords.STEREOTYPE)
            eraDirection(e)?.let { out.addAll(it) }
        }
        return out
    }

    /** 方向写反（网购提前／早期形制滞留）＝拿年代推行为的第一步，先在这儿拦住。 */
    private fun eraDirection(e: EraAvailability): List<OccFinding>? {
        val out = mutableListOf<OccFinding>()
        val supply = e.supply
        if (supply != null && supply.contains(GarmentSupply.ONLINE) && !ONLINE_FROM.contains(e.era)) {
            out.add(red("C9", e.era.key, "网购提前到这个年代"))
        }
        val forms = e.forms
        if (forms != null && forms.any { EARLY_FORMS.contains(it) } && !EARLY_ERAS.contains(e.era)) {
            out.add(red("C9", e.era.key, "早期形制留到了后期"))
        }
        return out
    }

    private fun eraTexts(e: EraAvailability): List<String> {
        val parts = mutableListOf(e.era.label, e.era.span)
        e.forms?.forEach { parts.add(it.label) }
        e.fabrics?.forEach { parts.add(it.label) }
        e.supply?.forEach { parts.add(it.label) }
        e.venueCity?.forEach { parts.add(it.label) }
        e.venueRural?.forEach { parts.add(it.label) }
        e.hands?.forEach { parts.add(it.target) }
        return parts
    }

    private val EARLY_ERAS: Set<Era> = setOf(Era.PRE_1949, Era.MID_50_70)

    private val ERA_HANDS: List<Handoff> = listOf(Handoff.ERA_GARMENT_881, Handoff.ERA_VENUE_883)

    private fun red(rule: String, at: String, message: String) =
        OccFinding(rule, OccSeverity.ERROR, at, message)

    private fun warn(rule: String, at: String, message: String) =
        OccFinding(rule, OccSeverity.WARN, at, message)

    private const val TABLE = "table"
}
