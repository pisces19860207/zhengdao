// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 汇总表：五张分表合成唯一真相（第一期 70 行）。
//
// 行数拆法（判据①的行业覆盖与判据⑥「每个行业至少一个岗位示例」都按这张表核）：
// · 采矿建筑 5＋制造装配 4＝9；运输物流 5＋餐饮后厨 4＋医疗 4＝13；
//   农渔 2＋教育科研 3＋IT与办公 2＋商业服务 4＋美发美容 2＋艺术传媒 2＋体育教练 2＋自由与无业 2＋学生 2＝21；
// · 军警消防 18（军 8：三等级×六场景全覆盖；警 6；消 4）；宗教职 9（教派细类）。
//
// 这里**故意不用 init-require 把判据全钉死**：构造期抛错会变成 ExceptionInInitializerError，
// 一次红法掩掉整包所有断言，取证反而更难。结构性自证（键唯一、行业非空）留在这里，
// 其余一律走 OccupationGate 的发现列表——红得清楚，也红得可复跑。
package com.example.zhengdao.baopu.state.occupation

/** 职业池唯一真相。分表各自按行业聚，改哪一行只动那一张表。 */
object OccupationTable {

    val TRADE: List<OccupationRow> = TRADE_ROWS

    val SERVICE: List<OccupationRow> = SERVICE_ROWS

    val CIVILIAN: List<OccupationRow> = CIVILIAN_ROWS

    val UNIFORM: List<OccupationRow> = UNIFORM_ROWS

    val FAITH: List<OccupationRow> = FAITH_ROWS

    val ROWS: List<OccupationRow> = TRADE + SERVICE + CIVILIAN + UNIFORM + FAITH

    /** 第一期行数（派工单点名的工种一个不落，加上「每行业至少一例」补齐的行）。 */
    val COUNT: Int = ROWS.size

    fun of(industry: Industry): List<OccupationRow> = ROWS.filter { it.industry == industry }

    fun byKey(key: String): OccupationRow? = ROWS.firstOrNull { it.key == key }

    /** 军警消行（特有槽非空）。 */
    fun withService(): List<OccupationRow> = ROWS.filter { it.service != null }

    /** 宗教职行（教派槽非空）。 */
    fun withFaith(): List<OccupationRow> = ROWS.filter { it.faith != null }

    /** 非军警消、非宗教职的行——它们不许出现特有槽与 dutyCycle 交接（C5／C6 的反向一半）。 */
    fun plain(): List<OccupationRow> = ROWS.filter { it.service == null && it.faith == null }

    /**
     * 档位使用清点：枚举简名 → 表里真用过的条目名。
     * 测试拿它核「派工单点名的档名不能只列不用」，也核反向（没造永远取不到的死档）。
     *
     * 按面拆成九件是**闸的账不是审美的账**：合在一格里 CC=24，全仓 189 的 detekt 预算被顶成 190。
     * 拆完每格取值范围逐字不变，`OccTableTest` 的「档名全用」五条断言仍照原样跑。
     */
    fun usedGrades(): Map<String, Set<String>> {
        val out = mutableMapOf<String, MutableSet<String>>()
        ROWS.forEach { row -> rowGrades(row).forEach { put(out, it) } }
        // Reach 由 Closure 带着走，单独清一次，免得「便利／更麻烦」哪档没人取到却没人发现。
        Closure.entries.forEach { put(out, it.reach) }
        return out
    }

    private fun rowGrades(row: OccupationRow): List<Enum<*>?> = listOf(row.industry) +
        sixGrades(row.six) + envGrades(row.env) + attireGrades(row.attire) + burdenGrades(row.burden) +
        driveGrades(row.drives) + row.hands.orEmpty() + phase2Grades(row.phase2) +
        serviceGrades(row.service) + faithGrades(row.faith)

    private fun sixGrades(s: SixDims?): List<Enum<*>?> =
        if (s == null) emptyList() else listOf(s.posture, s.dirt, s.dress, s.sched, s.risk)

    private fun envGrades(e: EnvProfile?): List<Enum<*>?> =
        if (e == null) emptyList() else listOf(e.place, e.noise, e.dust, e.temp, e.moist, e.chem)

    private fun attireGrades(a: AttireProfile?): List<Enum<*>?> =
        if (a == null) emptyList() else a.items.orEmpty() + listOf<Enum<*>?>(a.closure)

    private fun burdenGrades(b: BurdenProfile?): List<Enum<*>?> =
        if (b == null) emptyList() else
            listOf<Enum<*>?>(b.carry, b.standing, b.shift) + b.repeats.orEmpty()

    private fun driveGrades(d: DriveSet?): List<Enum<*>?> = if (d == null) emptyList() else
        DriveAxis.entries.map { d.dirOf(it) } + d.mechanisms.orEmpty() + listOf<Enum<*>?>(d.spread)

    /** 第一期只清状态位——四槽的内容本来就是占位，第二期填了内容自然会被各自的档名面收到。 */
    private fun phase2Grades(p: Phase2Slots?): List<Enum<*>?> = if (p == null) emptyList() else
        listOf(p.habitat?.status, p.traces?.status, p.odor?.status, p.minority?.status)

    private fun serviceGrades(s: ServiceSlot?): List<Enum<*>?> = if (s == null) emptyList() else
        listOf<Enum<*>?>(
            s.arm, s.armyRank, s.policePost, s.firePost, s.scene, s.phase, s.dutyCycle, s.returnGap,
        ) + s.gear.orEmpty() + s.envExtra.orEmpty() + s.bodyLoad.orEmpty()

    private fun faithGrades(f: FaithSlot?): List<Enum<*>?> = if (f == null) emptyList() else
        listOf<Enum<*>?>(f.branch, f.celibate, f.diet, f.hair, f.dress, f.residence) + f.basis.orEmpty()

    /** 年代可用集的使用清点（形制／材质／获取／场所四类）。 */
    fun eraUsedGrades(): Map<String, Set<String>> {
        val out = mutableMapOf<String, MutableSet<String>>()
        EraTable.ROWS.forEach { row ->
            put(out, row.era)
            row.forms?.forEach { put(out, it) }
            row.fabrics?.forEach { put(out, it) }
            row.supply?.forEach { put(out, it) }
            row.venueCity?.forEach { put(out, it) }
            row.venueRural?.forEach { put(out, it) }
            row.hands?.forEach { put(out, it) }
        }
        return out
    }

    private fun put(into: MutableMap<String, MutableSet<String>>, value: Enum<*>?) {
        if (value == null) return
        into.getOrPut(value.javaClass.simpleName) { mutableSetOf() }.add(value.name)
    }

    init {
        val dupes = ROWS.groupBy { it.key }.filterValues { it.size > 1 }.keys
        require(dupes.isEmpty()) { "工种键重复，表就成了两份真相：" + dupes.joinToString("、") }
        require(ROWS.none { it.industry == null }) { "有行没挂行业，行业覆盖没法核" }
    }
}
