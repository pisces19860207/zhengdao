// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十二批 · 问答执行（问题 → 查询 → 真实答案）。
//
// 派工单 §2.3 的五条，在本文件里各落一处：
// 1. **选表**（[TableSelector]）：问题里提到表名/列名就直接定；否则按「含相关列」排优先级，
//    **多于 1 张时先问一句**（不猜）；
// 2. **查询**（[TableQuery.run]）：过滤、计数、求和、平均、最大最小、分组、排序取前 N；
// 3. **回答**：先答案、再口径、最后一行出处（[TableAnswer] 的三行纪律）；
// 4. **多表**：只有**明确 join key** 才联（[TableJoin]），否则分开答并标「未联表」；
// 5. **PDF**：`.pdf` ⇒ 立刻回「暂不支持」，**连表都不打开**。
//
// ## 为什么这一层不解析自然语言
// 「问题 → 结构化查询」有两段：本文件只做**第二段**（结构化 → 真实答案），第一段在
// [TableNl]。分开的理由：第二段是纯函数、可穷举单测；第一段天生模糊，必须能单独说
// 「这条我算不了」而不污染答案层。工具的参数也是结构化的（与 195 同一条口径：
// 不让用户原话变成参数，否则原话会进调用记录）。
package com.example.zhengdao.baopu.table

/** 本层认的运算（闭集：日志与「不支持」文案都按它归类）。 */
enum class TableOp {
    /** 行数（＝满足条件的行数）。 */
    COUNT,

    /** 某列有几种取值。 */
    COUNT_DISTINCT,
    SUM,
    AVG,
    MIN,
    MAX,

    /** 列出来（谁/哪些/名单）。 */
    LIST,

    /** 按某列分组数行数。 */
    GROUP_COUNT,
    GROUP_SUM,
    GROUP_AVG,

    /** 排序取前 N。 */
    TOP,
}

/** 比较方式（只有这六种：再多就要解释「除…以外且…」这类自然语言，那是算不了的）。 */
enum class Cmp { EQ, NE, LT, LE, GT, GE, CONTAINS }

/** 一个条件。 */
data class Cond(val column: String, val cmp: Cmp, val value: String) {
    fun text(): String {
        val op = when (cmp) {
            Cmp.EQ -> "="
            Cmp.NE -> "≠"
            Cmp.LT -> "<"
            Cmp.LE -> "≤"
            Cmp.GT -> ">"
            Cmp.GE -> "≥"
            Cmp.CONTAINS -> "含"
        }
        return "$column$op$value"
    }
}

/**
 * 一次查询的结构化描述（工具参数、面板、[TableNl] 都产出它）。
 *
 * @param table 指定表（相对工作区的路径或文件名）；null ＝ 由 [TableSelector] 选，多于一张就问
 * @param headerRow 用户指定的表头行（**1 起**，她数的行号）
 * @param subject 答案里的量词（「人」「条」「行」），只影响出话，不影响计算
 * @param condLabel 条件的口头说法（「不及格」）——有它答案才像人话
 * @param distinctBy 计数按哪一列去重（问「几个人」而表是一人多行时用；null ＝ 数行）
 * @param criteria 本层自己定的口径（「不及格按 <60 分算」），**必须原样进答案**
 */
data class TableAsk(
    val op: TableOp,
    val table: String? = null,
    val sheet: String? = null,
    val headerRow: Int? = null,
    val column: String? = null,
    val groupBy: String? = null,
    val where: List<Cond> = emptyList(),
    val orderBy: String? = null,
    val desc: Boolean = true,
    val limit: Int? = null,
    val label: String? = null,
    val joinKey: String? = null,
    val joinWith: String? = null,
    val subject: String = "",
    val condLabel: String = "",
    val distinctBy: String? = null,
    val criteria: List<String> = emptyList(),
)

/** 选表的结果。 */
internal sealed interface Picked {
    data class One(val model: TableModel) : Picked
    data class Many(val options: List<String>) : Picked
    data class Stop(val answer: TableAnswer) : Picked
}

/** 问答执行器。 */
object TableQuery {

    /** 默认列几行（「都有谁」不给上限会把 2000 行全念出来）。 */
    const val DEFAULT_LIST = 10

    /** 分组结果最多念几组（再多就只报前几组＋总数）。 */
    const val MAX_GROUPS = 12

    /**
     * 一句话问答（工具与面板的唯一入口）。
     *
     * @param refresh 每次先做**增量**刷新（mtime/size 没变就一个文件都不重解析）。
     *   这一步是「不扫就不知道有没有新表」与「不许全量重扫」两条的交点：目录列一遍是便宜的，
     *   重解析才是贵的，所以只重解析变了的。
     */
    fun ask(registry: TableRegistry, ask: TableAsk, refresh: Boolean = true): TableAnswer {
        // ⑤ PDF 先拒：**在打开任何文件之前**，所以「硬解 PDF」这条负例没有可走的路
        listOfNotNull(ask.table, ask.joinWith).forEach {
            if (TableRegistry.isPdf(it)) return TableAnswer.pdf(it)
        }
        if (refresh) registry.refresh()
        if (registry.isEmpty()) {
            return TableAnswer.notInTable(
                "没找到任何表（工作区里没有 xlsx / csv / tsv / .xls）",
                evidence = null,
                hint = "把表放进工作区（默认 Download/证道）再问。PDF 不在本批范围。",
            )
        }
        return when (val picked = TableSelector.pick(registry, ask)) {
            is Picked.Stop -> picked.answer
            is Picked.Many -> TableAnswer.needTable(
                "有 ${picked.options.size} 张表都可能，我不替你猜一张。",
                picked.options,
            )
            is Picked.One -> {
                val model = picked.model
                when {
                    model.headerAsk != null -> TableAnswer.needHeader(model.headerAsk, model.path, model.sheet)
                    ask.joinWith != null -> joinAndRun(registry, ask, model)
                    else -> run(model, ask)
                }.withXls(registry, model.path)
            }
        }
    }

    /**
     * 252：这张表是 `.xls` 转来的 ⇒ 答案里**交代一句**（派工单 §1④：「已把 X.xls 自动转成 CSV（N 行 × M 列）」）。
     *
     * 放在这一层而不是工具层：面板与工具共用 [ask] / [askQuestion]，
     * 只有挂在这里，两处才会**用同一句**说话（各挂一次迟早会说出两个版本）。
     */
    private fun TableAnswer.withXls(registry: TableRegistry, path: String): TableAnswer {
        if (converted.isNotEmpty()) return this
        val note = registry.entryOf(path)?.xls?.sentence() ?: return this
        return copy(converted = note)
    }

    /**
     * 一句话问答（**"一问就按表格上的真实答案回答"** 的入口）。
     *
     * 与 [ask] 的分工：这里多一段「中文 → [TableAsk]」的翻译（[TableNl]）。翻译不出来
     * 就回「这条我算不了」——**绝不落回 [ask] 去猜一个默认运算**。
     */
    fun askQuestion(
        registry: TableRegistry,
        question: String,
        table: String? = null,
        sheet: String? = null,
        headerRow: Int? = null,
        refresh: Boolean = true,
    ): TableAnswer {
        if (table != null && TableRegistry.isPdf(table)) return TableAnswer.pdf(table)
        if (refresh) registry.refresh()
        if (registry.isEmpty()) {
            return TableAnswer.notInTable(
                "没找到任何表（工作区里没有 xlsx / csv / tsv / .xls）",
                evidence = null,
                hint = "把表放进工作区（默认 Download/证道）再问。PDF 不在本批范围。",
            )
        }
        return when (val picked = TableSelector.pickForQuestion(registry, question, table, sheet, headerRow)) {
            is Picked.Stop -> picked.answer
            is Picked.Many -> TableAnswer.needTable(
                "有 ${picked.options.size} 张表都可能，我不替你猜一张。",
                picked.options,
            )
            is Picked.One -> {
                val model = picked.model
                val out = if (model.headerAsk != null) {
                    TableAnswer.needHeader(model.headerAsk, model.path, model.sheet)
                } else {
                    when (val parsed = TableNl.parse(model, question)) {
                        is TableNl.Result.Unsupported -> parsed.answer
                        is TableNl.Result.Ask -> run(model, parsed.ask)
                    }
                }
                out.withXls(registry, model.path)
            }
        }
    }

    /**
     * 联表后跑同一条执行路径。
     *
     * 「只有明确 join key 才联」在这里是硬条件：`joinWith` 有而 `joinKey` 没有 ⇒ 直接回
     * 「得说清按哪一列对」，**不拿『名字差不多』去猜一个 key**（那是负例 4 的靶子）。
     */
    private fun joinAndRun(registry: TableRegistry, ask: TableAsk, left: TableModel): TableAnswer {
        val key = ask.joinKey
        if (key.isNullOrBlank()) {
            return TableAnswer.unsupported(
                "两张表要联，得说清按哪一列对（比如「按学号」）。",
                hint = "没有共同的列就只能分开答——你问哪一张，我就按那一张答。",
            )
        }
        val other = ask.joinWith.orEmpty()
        val entry = registry.entries().firstOrNull {
            it.rel == other || it.rel.substringAfterLast('/') == other
        } ?: return TableAnswer.notInTable(
            "没找到要联的那张表：$other",
            evidence = null,
            hint = "工作区里的表：${registry.entries().joinToString("、") { it.rel }}",
        )
        val right = registry.modelOf(entry.rel)
            ?: return TableAnswer.unsupported("「${entry.rel}」读不动，联不了。")
        return when (val joined = TableJoin.inner(left, right, key)) {
            is TableJoin.Result.Stop -> joined.answer
            is TableJoin.Result.Ok -> run(joined.model, ask)
        }
    }

    // ── 执行 ────────────────────────────────────────────────────────────────

    /** 结构化查询 → 答案。 */
    internal fun run(model: TableModel, ask: TableAsk): TableAnswer {
        val ctx = Context(model, ask)
        ctx.stop?.let { return it }
        return when (ask.op) {
            TableOp.COUNT -> ctx.count()
            TableOp.COUNT_DISTINCT -> ctx.countDistinct()
            TableOp.SUM -> ctx.numeric("合计") { it.sum() }
            TableOp.AVG -> ctx.numeric("平均") { it.average() }
            TableOp.MIN -> ctx.numeric("最低") { it.min() }
            TableOp.MAX -> ctx.numeric("最高") { it.max() }
            TableOp.LIST -> ctx.list()
            TableOp.GROUP_COUNT -> ctx.group(count = true)
            TableOp.GROUP_SUM -> ctx.group(count = false, average = false)
            TableOp.GROUP_AVG -> ctx.group(count = false, average = true)
            TableOp.TOP -> ctx.top()
        }
    }

    /**
     * 一次执行的上下文。
     *
     * 列在**构造时**一次解析完（[stop] 非空 ＝ 有个问法里的列对不上或对上好几列，
     * 这时一个数都不算，直接把「问哪一列」交出去）——「先解析、后计算」是为了让
     * 「重名列不问就猜」这条负例在结构上不成立。
     */
    internal class Context(val model: TableModel, val ask: TableAsk) {

        private val resolved = HashMap<String, TableColumn>()

        /** 列解析的早退答案（null ＝ 全部对上了）。 */
        val stop: TableAnswer? = resolveTerms()

        private fun resolveTerms(): TableAnswer? {
            val strict = buildList {
                ask.where.forEach { add(it.column) }
                listOfNotNull(ask.column, ask.groupBy, ask.orderBy).forEach { add(it) }
            }
            for (term in strict) {
                if (term.isBlank() || resolved.containsKey(term)) continue
                when (val hit = TableColumns.resolve(model, term)) {
                    is ColumnHit.One -> resolved[term] = hit.column
                    is ColumnHit.Many -> return TableAnswer.needColumn(term, hit.columns.map { it.name })
                    ColumnHit.None -> return TableAnswer.notInTable(
                        "「$term」这一列（这张表的列：${model.columns.joinToString("、") { it.name }}）",
                        Evidence(model.path, model.sheet, model.headerRowNumber, emptyList(), model.rows.size),
                    )
                }
            }
            // label 与 distinctBy 是**可选**的（「最高分是谁」没姓名列就只答分数；
            // 「几个人」没姓名列就数行）——解析不上不算错，只影响出话像不像人话
            listOfNotNull(ask.label, ask.distinctBy).forEach { t ->
                (TableColumns.resolve(model, t) as? ColumnHit.One)?.let { resolved[t] = it.column }
            }
            return null
        }

        /** 过滤后的行。 */
        val hits: List<TableRow> by lazy { model.rows.filter { matches(it) } }

        private fun matches(row: TableRow): Boolean = ask.where.all { cond ->
            val col = column(cond.column) ?: return@all false
            val v = model.valueOf(row, col)?.trim().orEmpty()
            cmp(v, cond)
        }

        /**
         * 一格 vs 一个条件。
         *
         * 两边都能解析成数字就**按数比**（`60` 与 `60.0` 是一回事），否则按文本比；
         * 文本上的 `> <` **不算命中**——「按字典序比大小」是猜，猜错的代价是筛出一批假行。
         */
        private fun cmp(cell: String, cond: Cond): Boolean {
            if (TableValues.isBlank(cell)) return false
            val a = TableValues.numberOrNull(cell)
            val b = TableValues.numberOrNull(cond.value)
            return if (a != null && b != null) cmpNumber(a, b, cond.cmp) else cmpText(cell, cond)
        }

        private fun cmpNumber(a: Double, b: Double, op: Cmp): Boolean = when (op) {
            Cmp.EQ -> a == b
            Cmp.NE -> a != b
            Cmp.LT -> a < b
            Cmp.LE -> a <= b
            Cmp.GT -> a > b
            Cmp.GE -> a >= b
            Cmp.CONTAINS -> false
        }

        private fun cmpText(cell: String, cond: Cond): Boolean = when (cond.cmp) {
            Cmp.EQ -> cell == cond.value
            Cmp.NE -> cell != cond.value
            Cmp.CONTAINS -> cell.contains(cond.value)
            // 文本列上的 > < 没有定义，**不算**（不猜「按字典序」）
            else -> false
        }

        private fun condText(): String = ask.where.joinToString(" 且 ") { it.text() }

        private fun evidence(rows: List<TableRow>): Evidence = Evidence(
            path = model.path,
            sheet = model.sheet,
            headerRow = model.headerRowNumber,
            rows = rows.map { it.no },
            totalRows = model.rows.size,
        )

        private fun digest(rows: List<TableRow>, cap: Int = 50): List<String> = rows.take(cap).map { r ->
            val cells = model.columns.joinToString(", ") { c -> "${c.name}=${TableText.display(model.valueOf(r, c))}" }
            "第 ${r.no} 行：$cells"
        }

        private fun subjectText(): String = if (ask.subject.isEmpty()) "行" else ask.subject

        private fun criteriaPlus(): List<String> =
            if (ask.where.isEmpty()) ask.criteria else ask.criteria + "筛选条件：${condText()}"

        // ① 计数
        fun count(): TableAnswer {
            val dedupe = ask.distinctBy?.let { column(it) }
            if (dedupe != null) return countPeople(dedupe)
            val n = hits.size
            if (n == 0) return TableAnswer.notInTable(emptyWhat(), evidence(emptyList()))
            val head = if (ask.where.isEmpty() && ask.condLabel.isEmpty()) {
                "一共 $n ${subjectText()}"
            } else {
                "$n ${subjectText()}${ask.condLabel}"
            }
            return TableAnswer.answer(
                headline = head,
                evidence = evidence(hits),
                numbers = listOf(n.toDouble()),
                criteria = criteriaPlus(),
                digest = digest(hits),
            )
        }

        /**
         * 按一列去重计数（问「几个人」而表是**一人多行**时走这条）。
         *
         * 为什么要这一步：成绩表一人两行（语文/数学），数行会把 5 个人数成 10 个。
         * 去重不是猜——它把「按哪一列去重」写进答案（`按「姓名」去重；表里 10 行`），
         * 用户当场能看出我数的是人还是行。没这一列时 [count] 的数行口径不变。
         */
        private fun countPeople(col: TableColumn): TableAnswer {
            val seen = LinkedHashMap<String, TableRow>()
            hits.forEach { r ->
                val v = model.valueOf(r, col)?.trim().orEmpty()
                if (v.isNotEmpty()) seen.putIfAbsent(v, r)
            }
            if (seen.isEmpty()) {
                return TableAnswer.notInTable(
                    "「${col.name}」这一列在命中的行里都是空的，数不出人",
                    evidence(emptyList()),
                )
            }
            val n = seen.size
            val note = if (n != hits.size) "（按「${col.name}」去重；表里 ${hits.size} 行）" else ""
            val head = if (ask.where.isEmpty() && ask.condLabel.isEmpty()) {
                "一共 $n ${subjectText()}$note"
            } else {
                "$n ${subjectText()}${ask.condLabel}$note"
            }
            return TableAnswer.answer(
                headline = head,
                evidence = evidence(seen.values.toList()),
                numbers = listOf(n.toDouble(), hits.size.toDouble()),
                criteria = criteriaPlus() + "按「${col.name}」去重计数",
                digest = digest(hits),
            )
        }

        // ② 有几种
        fun countDistinct(): TableAnswer {
            val col = column(ask.column) ?: return missingColumn(ask.column)
            val values = hits.mapNotNull { model.valueOf(it, col)?.trim() }.distinct().sorted()
            if (values.isEmpty()) return TableAnswer.notInTable("「${col.name}」这一列一个值都没有", evidence(hits))
            val tail = if (values.size > MAX_GROUPS) " 等 ${values.size} 种" else ""
            return TableAnswer.answer(
                headline = "「${col.name}」有 ${values.size} 种：${values.take(MAX_GROUPS).joinToString("、")}$tail",
                evidence = evidence(hits),
                numbers = listOf(values.size.toDouble()),
                criteria = criteriaPlus(),
                digest = digest(hits),
            )
        }

        // ③ 求和/平均/最大/最小
        fun numeric(word: String, reduce: (List<Double>) -> Double): TableAnswer {
            val col = column(ask.column) ?: return missingColumn(ask.column)
            if (col.type == ColumnType.BLANK) {
                return TableAnswer.notInTable("「${col.name}」整列都是空的，没有数可以$word", evidence(hits))
            }
            if (col.type != ColumnType.NUMBER) {
                return TableAnswer.unsupported(
                    "「${col.name}」不是数字列（有 ${col.filled} 格是文本），$word 算不了——我不猜「缺考」算几分。",
                    hint = "要么把这列改成纯数字，要么换个问法（比如「有几个」「都有谁」）。",
                )
            }
            val pairs = hits.mapNotNull { r -> TableValues.numberOrNull(model.valueOf(r, col))?.let { it to r } }
            if (pairs.isEmpty()) {
                return TableAnswer.notInTable("这张表里没有「${col.name}」的数（命中的行这一列都是空的）", evidence(hits))
            }
            val value = reduce(pairs.map { it.first })
            val head = if (ask.op == TableOp.MIN || ask.op == TableOp.MAX) {
                val who = labelOf(pairs.firstOrNull { it.first == value }?.second)
                "「${col.name}」$word ${TableText.number(value)}" + (who?.let { "（$it）" } ?: "")
            } else {
                "「${col.name}」$word ${TableText.number(value)}"
            }
            return TableAnswer.answer(
                headline = head,
                evidence = evidence(pairs.map { it.second }),
                numbers = listOf(value) + pairs.map { it.first },
                criteria = criteriaPlus(),
                digest = digest(pairs.map { it.second }),
            )
        }

        // ④ 列出来
        fun list(): TableAnswer {
            if (hits.isEmpty()) return TableAnswer.notInTable(emptyWhat(), evidence(emptyList()))
            val cap = ask.limit ?: DEFAULT_LIST
            val shown = hits.take(cap)
            val names = shown.map { rowText(it) }
            val tail = if (hits.size > shown.size) " 等 ${hits.size} 个（这里只念前 ${shown.size} 个）" else ""
            return TableAnswer.answer(
                headline = "${hits.size} ${subjectText()}${ask.condLabel}：${names.joinToString("、")}$tail",
                evidence = evidence(hits),
                numbers = listOf(hits.size.toDouble()),
                criteria = criteriaPlus(),
                digest = digest(hits),
            )
        }

        // ⑤ 分组
        fun group(count: Boolean, average: Boolean = false): TableAnswer {
            val key = column(ask.groupBy) ?: return missingColumn(ask.groupBy)
            val valueCol = if (count) null else column(ask.column) ?: return missingColumn(ask.column)
            groupStop(key, valueCol)?.let { return it }
            val entries = orderedGroups(key)
            val parts = ArrayList<String>(entries.size)
            val nums = ArrayList<Double>(entries.size)
            entries.take(MAX_GROUPS).forEach { (k, rows) ->
                val piece = pieceOf(k, rows, count, average, valueCol)
                parts += piece.first
                piece.second?.let { nums += it }
            }
            return groupAnswer(key, valueCol, count, average, entries.size, parts, nums)
        }

        /** 分组前的两道早退：取值列不是数字 / 一行都没命中。 */
        private fun groupStop(key: TableColumn, valueCol: TableColumn?): TableAnswer? {
            if (valueCol != null && valueCol.type != ColumnType.NUMBER) {
                return TableAnswer.unsupported(
                    "「${valueCol.name}」不是数字列，按「${key.name}」分组算不了平均/合计。",
                    hint = "换个问法：按「${key.name}」数行数（几个人/几条）。",
                )
            }
            return if (hits.isEmpty()) TableAnswer.notInTable(emptyWhat(), evidence(emptyList())) else null
        }

        /** 分好组并按组名排序（默认从大到小，与 `desc` 同一口径）。 */
        private fun orderedGroups(key: TableColumn): List<Map.Entry<String, MutableList<TableRow>>> {
            val groups = groupRows(key)
            return if (ask.desc) groups.entries.sortedByDescending { it.key } else groups.entries.toList()
        }

        private fun groupAnswer(
            key: TableColumn,
            valueCol: TableColumn?,
            count: Boolean,
            average: Boolean,
            total: Int,
            parts: List<String>,
            nums: List<Double>,
        ): TableAnswer {
            val verb = when {
                count -> "数行数"
                average -> "算「${valueCol!!.name}」平均"
                else -> "合计「${valueCol!!.name}」"
            }
            val tail = if (total > MAX_GROUPS) " 等 $total 组" else ""
            // 口径写成**一条**而不是两条：两条会被 "；" 拼成「；；」（真机日志里抓到过）
            val groupNote = "分组列：${key.name}" + (valueCol?.let { "；取值列：${it.name}" } ?: "")
            return TableAnswer.answer(
                headline = "按「${key.name}」$verb：${parts.joinToString(" / ")}$tail",
                evidence = evidence(hits),
                numbers = nums,
                criteria = criteriaPlus() + groupNote,
                digest = digest(hits),
            )
        }

        /** 按分组列把命中的行归堆（空值归「未填」，与「空格不给 0」同一条纪律）。 */
        private fun groupRows(key: TableColumn): LinkedHashMap<String, MutableList<TableRow>> {
            val groups = LinkedHashMap<String, MutableList<TableRow>>()
            hits.forEach { r ->
                val k = model.valueOf(r, key)?.trim().orEmpty().ifEmpty { "未填" }
                groups.getOrPut(k) { mutableListOf() } += r
            }
            return groups
        }

        /** 一组 → `语文 78.4` ＋ 那个数（算不出来时数为 null，**不补 0**）。 */
        private fun pieceOf(
            name: String,
            rows: List<TableRow>,
            count: Boolean,
            average: Boolean,
            valueCol: TableColumn?,
        ): Pair<String, Double?> {
            if (count) return "$name ${rows.size}" to rows.size.toDouble()
            val vals = rows.mapNotNull { TableValues.numberOrNull(model.valueOf(it, valueCol!!)) }
            if (vals.isEmpty()) return "$name 没有数" to null
            val v = if (average) vals.average() else vals.sum()
            return "$name ${TableText.number(v)}" to v
        }

        // ⑥ 排序取前 N
        fun top(): TableAnswer {
            val order = column(ask.orderBy ?: ask.column) ?: return missingColumn(ask.orderBy ?: ask.column)
            if (order.type != ColumnType.NUMBER) {
                return TableAnswer.unsupported(
                    "「${order.name}」不是数字列，排不了名次（我不按字典序排）。",
                    hint = "换个问法：按某一列数个数，或者直接问「有几个」。",
                )
            }
            if (hits.isEmpty()) return TableAnswer.notInTable(emptyWhat(), evidence(emptyList()))
            val sorted = ranked(order)
            if (sorted.isEmpty()) {
                return TableAnswer.notInTable("「${order.name}」这一列在命中的行里都是空的", evidence(hits))
            }
            val n = (ask.limit ?: 1).coerceAtLeast(1)
            val shown = sorted.take(n)
            val head = if (n == 1) oneHead(order, shown.first()) else manyHead(order, n, shown)
            return TableAnswer.answer(
                headline = head,
                evidence = evidence(shown.map { it.second }),
                numbers = listOf(n.toDouble()) + shown.map { it.first },
                criteria = criteriaPlus(),
                digest = digest(shown.map { it.second }),
            )
        }

        /** 按某一列排序（只收这一列有数的行；空格不参与排名，也不当成 0）。 */
        private fun ranked(order: TableColumn): List<Pair<Double, TableRow>> = hits
            .mapNotNull { r -> TableValues.numberOrNull(model.valueOf(r, order))?.let { it to r } }
            .sortedByDescending { if (ask.desc) it.first else -it.first }

        /** 取第 1 名的那一句：`「分数」最高 100（孙七）`。 */
        private fun oneHead(order: TableColumn, top: Pair<Double, TableRow>): String {
            val who = labelOf(top.second)
            return "「${order.name}」${if (ask.desc) "最高" else "最低"} " +
                TableText.number(top.first) + (who?.let { "（$it）" } ?: "")
        }

        /** 取前 N 名的那一句：`「分数」前 3 名：孙七 100、王五 90、张三 88`。 */
        private fun manyHead(order: TableColumn, n: Int, shown: List<Pair<Double, TableRow>>): String =
            "「${order.name}」${if (ask.desc) "前" else "后"} $n 名：" + shown.joinToString("、") { (v, r) ->
                (labelOf(r)?.let { "$it " } ?: "") + TableText.number(v)
            }

        // ── 小工具 ──────────────────────────────────────────────────────────

        /**
         * 一行在答案里的名字：优先用户点的那列（姓名），否则**第一个不是被算的那列的文本列**。
         *
         * 为什么要把「被算的那列」排掉：`前三名` 问的是分数，若拿分数列当名字，
         * 答案会变成「100 100、95 95」——同一列念两遍，等于没点名。
         */
        private fun labelOf(row: TableRow?): String? {
            if (row == null) return null
            val col = ask.label?.let { column(it) } ?: defaultLabel() ?: return null
            return model.valueOf(row, col)?.trim()?.takeIf { it.isNotEmpty() }
        }

        private fun defaultLabel(): TableColumn? {
            val skip = setOfNotNull(ask.column, ask.orderBy)
            model.columns.firstOrNull { it.type == ColumnType.TEXT && it.name !in skip }?.let { return it }
            return model.keys.firstOrNull { it !in skip }?.let { model.columnNamed(it) }
        }

        /** LIST 的一行：`张三（分数 55）`。 */
        private fun rowText(row: TableRow): String {
            val name = labelOf(row)
            val valueCol = ask.column?.let { column(it) }
                ?: model.columns.firstOrNull { it.type == ColumnType.NUMBER }
            val v = valueCol?.let { model.valueOf(row, it)?.trim() }?.takeIf { !it.isNullOrEmpty() }
            return when {
                name != null && v != null && valueCol != null -> "$name（${valueCol.name} $v）"
                name != null -> name
                v != null && valueCol != null -> "${valueCol.name} $v"
                else -> "第 ${row.no} 行"
            }
        }

        private fun column(term: String?): TableColumn? = term?.let { resolved[it] }

        private fun missingColumn(term: String?): TableAnswer {
            if (term.isNullOrBlank()) {
                return TableAnswer.unsupported(
                    "这条问法没说要算哪一列。",
                    hint = "说一下列名（表里的列：${model.columns.joinToString("、") { it.name }}）。",
                )
            }
            return TableAnswer.notInTable(
                "「$term」这一列（这张表的列：${model.columns.joinToString("、") { it.name }}）",
                evidence(emptyList()),
            )
        }

        private fun emptyWhat(): String =
            if (ask.where.isEmpty()) "满足条件的行（这张表 ${model.rows.size} 行都不符合）" else "「${condText()}」的行"
    }
}
