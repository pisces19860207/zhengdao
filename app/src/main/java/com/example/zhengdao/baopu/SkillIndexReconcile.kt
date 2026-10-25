// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零四批 · 技能索引两向对账（只报不改）。来源：162 提案轮 cand-162-2——
// 索引悬空 punct3、有正文无索引 haiku-helper、同场景双行 note1/note2 三条真实存量。
//
// 一句话：**索引行 ⇄ `技能/<名>/SKILL.md` 两向对账**，不一致就报出来。
// 红线：**只报不改**——自动改索引属写路径（归 192/184 的确认通道），本类
// 没有任何写盘口：不收任何文件对象与人设层对象、不出 proposal，
// 目录清单由调用方从盘上列好了递进来（纯函数、无 IO、可 JVM 直测）。
//
// 三类不一致（派工单 §1.1，判据全部钉在这层）：
// 1. **索引悬空**：索引里有该行，但 `技能/<名>/SKILL.md` 不存在；
// 2. **正文失联**：`SKILL.md` 存在，但索引里没有对应行；
// 3. **同场景双行**：两条索引行的场景归一后相同（去标点/空白、取前 20 字）。
//
// 三条去噪（不该报的就不报，免得自检自己变成噪音源）：
// 1. `.bak` 不算正文——目录里只剩 `<名>.SKILL.md.bak-<ts>` ⇒ 正文已失，该悬空就悬空
//    （真实例 punct3：把备份当正文就会漏报）；
// 2. `.deleted-*` 软删不算悬空——目录里有 `SKILL.md.deleted-<ts>`（口径照
//    `SkillStore.archive`：删除是确认制、有意为之）⇒ 不报，报出来就是噪音；
// 3. 名字按**精确相等**对账——近似名不救两边也不误报（note1 不吃 note10 的正文，
//    同名两条不同场景也不算双行：双行只看场景归一，不看名字）。
package com.example.zhengdao.baopu

/**
 * 技能索引两向对账的**纯判据**（房型照 `SkillRules`：算得清的都在这，壳只做读写）。
 */
internal object SkillIndexReconcile {

    /** 双行判据：场景去标点/空白后取前 20 字（派工单 §1.1 原口径）。 */
    const val SCENARIO_NORM_CHARS = 20

    /** 便签里每类最多点名几个；超出的收成「等」，一行便签不许被名字撑爆。 */
    const val NOTE_NAME_LIMIT = 3

    /** 软删落名前缀（`SkillStore.archive` 把 `SKILL.md` 改名成 `SKILL.md.deleted-<ts>` 留在原地）。 */
    const val SOFT_DELETE_MARK = "SKILL.md.deleted-"

    /**
     * 一份技能目录的清单项（调用方从盘上列出：`技能/` 下每个子目录一行）。
     *
     * @param files 目录里的**文件名**（只看名字不读内容；空目录给空表——
     *   真实例 punct2：空目录既不是失联（没有正文）也不是悬空（没有索引行），不报）。
     */
    data class SkillDir(val name: String, val files: List<String>) {
        /** 正文在不在：**只认 `SKILL.md` 本名**——`.bak`／`.deleted-*`／手放的别的文件都不算。 */
        val hasBody: Boolean get() = files.contains(SkillRules.SKILL_FILE)

        /** 这条技能是不是被软删了（目录里留着 `SKILL.md.deleted-<ts>`）。 */
        val isSoftDeleted: Boolean get() = files.any { it.startsWith(SOFT_DELETE_MARK) }
    }

    /** 一组同场景双行：同一份索引里场景归一后相同的 ≥2 条行（真实例 note1/note2）。 */
    data class DoubleRow(
        /** 组内各行的技能名（按索引行序；同名两条就是两个一样的名字，便签渲染时再去重）。 */
        val names: List<String>,
        /** 该组的场景（取第一条的原文，报告里给人看）。 */
        val scenario: String,
    )

    /**
     * 一次对账的账本。三类各自成表，次序＝**发现序**（悬空按索引行序、失联按目录清单序）。
     */
    data class Report(
        val dangling: List<String> = emptyList(),
        val orphans: List<String> = emptyList(),
        val doubleRows: List<DoubleRow> = emptyList(),
    ) {
        /** 全一致 ⇒ true（收尾便签必须保持零噪音：全一致时便签不出现）。 */
        val isClean: Boolean
            get() = dangling.isEmpty() && orphans.isEmpty() && doubleRows.isEmpty()

        /**
         * 两层账本合成一份：收尾便签**一行**要同时带全局与项目两层，先把两份账并起来再出文案。
         * 同类各自拼接（全局在前）；双行组是「一层内」的判据，跨层不并组——与
         * 「一份索引文本 ⇄ 一份技能目录清单」的输入口径一致，合并只搬不动判。
         */
        operator fun plus(other: Report): Report = Report(
            dangling = dangling + other.dangling,
            orphans = orphans + other.orphans,
            doubleRows = doubleRows + other.doubleRows,
        )

        /**
         * 这批不一致的去重签名（便签「每会话最多报一次」的钥匙）：
         * 名字排序后拼死——同一批不一致换了行序仍是同一批，签名不变。
         */
        val signature: String
            get() {
                val d = dangling.sorted().joinToString("+")
                val o = orphans.sorted().joinToString("+")
                val g = doubleRows.map { it.names.sorted().joinToString("+") }.sorted().joinToString("|")
                return "dangling[$d]orphan[$o]double[$g]"
            }
    }

    /**
     * 两向对账（一层一算：一份索引文本 ⇄ 一份技能目录清单；两层由调用方各调一次）。
     *
     * @param indexText 该层人设文本原文（照 `SkillRules.parseIndexLines` 解析，
     *   与写侧同一套口径，不另起一套行解析）
     * @param dirs 技能目录清单（调用方列出；本函数不做任何 IO）
     * @param scope 该层索引的层（只为喂给解析器定层；项目层用 PROJECT、全局层用 GLOBAL）
     */
    fun reconcile(
        indexText: String?,
        dirs: List<SkillDir>,
        scope: MemoryScope = MemoryScope.PROJECT,
    ): Report {
        val lines = SkillRules.parseIndexLines(indexText, scope)
        val dirsByName = dirs.associateBy { it.name }
        val indexedNames = lines.map { it.name }.toHashSet()

        // ① 索引悬空：有行没正文。软删的行有意压下（去噪 2），其余一个名字只报一次。
        val dangling = LinkedHashSet<String>()
        for (line in lines) {
            val dir = dirsByName[line.name]
            val suppressed = dir != null && dir.isSoftDeleted
            if (dir?.hasBody != true && !suppressed) dangling.add(line.name)
        }

        // ② 正文失联：有正文没行（名字精确相等才算「有行」，去噪 3）。
        val orphans = dirs.filter { it.hasBody && it.name !in indexedNames }.map { it.name }

        // ③ 同场景双行：场景归一后相同（去噪 3：只看场景不看名字；归一为空的不判，
        //    全标点的残行说不出「同场景」，报出来是噪音）。
        val byNorm = lines.groupBy { normalizeScenario(it.scenario) }.filter { it.key.isNotEmpty() }
        val doubleRows = byNorm
            .filter { it.value.size >= 2 }
            .map { (norm, group) -> DoubleRow(names = group.map { it.name }, scenario = group.first().scenario) }

        return Report(dangling = dangling.toList(), orphans = orphans, doubleRows = doubleRows)
    }

    /** 场景归一（双行判据用）：标点／空白／符号全剥掉，取前 [SCENARIO_NORM_CHARS] 字。 */
    fun normalizeScenario(scenario: String): String =
        scenario.filter { it.isLetterOrDigit() }.take(SCENARIO_NORM_CHARS)

    // ── 收尾便签（§1.2：只报，不打断会话、不弹卡） ─────────────────────────

    /**
     * 收尾便签的**一行文案**；全一致 ⇒ null（零噪音）。
     *
     * 房型（162 存量整成一行）：
     * `技能账实不符：悬空 1（punct3）、失联 1（haiku-helper）、双行 1（note1/note2）——去技能页看`
     *
     * 只列不为零的三类；每类点名不超过 [NOTE_NAME_LIMIT] 个，超出收「等」。
     */
    fun noteLine(report: Report): String? {
        if (report.isClean) return null
        val parts = mutableListOf<String>()
        if (report.dangling.isNotEmpty()) {
            parts += "悬空 ${report.dangling.size}（${namesForNote(report.dangling)}）"
        }
        if (report.orphans.isNotEmpty()) {
            parts += "失联 ${report.orphans.size}（${namesForNote(report.orphans)}）"
        }
        if (report.doubleRows.isNotEmpty()) {
            val groups = report.doubleRows.joinToString("、") { group ->
                val names = group.names.distinct()
                names.take(NOTE_NAME_LIMIT).joinToString("/") + if (names.size > NOTE_NAME_LIMIT) "等" else ""
            }
            parts += "双行 ${report.doubleRows.size}（$groups）"
        }
        return "技能账实不符：${parts.joinToString("、")}——去技能页看"
    }

    private fun namesForNote(names: List<String>): String =
        names.take(NOTE_NAME_LIMIT).joinToString("、") + if (names.size > NOTE_NAME_LIMIT) "等" else ""

    /**
     * 便签去重门：**同一批不一致每会话最多报一次**（纯内存，不落任何盘上状态）。
     *
     * - 同会话、同签名 ⇒ 只在第一次给文案，之后静默（免得每轮收尾都念一遍）；
     * - 同会话、签名变了 ⇒ 算新的一批，再报一次（账目变了是新信息）；
     * - 换了会话 ⇒ 重新开账（新会话的人还没看过，本会话内依旧最多一次）；
     * - 中间一度全一致不清账：同一批在本会话里再次出现仍然静默（口径照 §1.2「最多报一次」）。
     *
     * 会话收尾处一行 `gate.noteFor(sid, reconcile(...))` 即用（接线见交付报告 §接线）。
     */
    class NoteGate {
        private var sessionId: Long = Long.MIN_VALUE
        private val reported = LinkedHashSet<String>()

        /** 全一致 ⇒ null；本会话没报过这一批 ⇒ 一行文案；报过 ⇒ null。 */
        fun noteFor(sessionId: Long, report: Report): String? {
            if (sessionId != this.sessionId) {
                this.sessionId = sessionId
                reported.clear()
            }
            if (report.isClean) return null
            if (!reported.add(report.signature)) return null
            return noteLine(report)
        }
    }
}
