// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十二批 · 选表与联表（**这两件事都不许猜**）。
//
// 派工单 §2.3①：「问题里若提到表名/列名，直接定；否则按 registry 里『含相关列』的表排优先级，
// **多于 1 张时先问一句**（不猜）」。
// 派工单 §2.3④：「只有**明确 join key** 才联（如 单号/车牌+日期），否则**分开答**并在末尾标『未联表』」。
//
// 两处的「不猜」在这里是同一条纪律：**候选不是 1 就交出去问**。选表猜错的代价是
// 拿另一张表的数回答这张表的问题——那是「表里没有却给了数」的上游，比答不出来坏得多。
package com.example.zhengdao.baopu.table

/** 选表（表名优先，其次按列名打分）。 */
internal object TableSelector {

    fun pick(registry: TableRegistry, ask: TableAsk): Picked {
        ask.table?.takeIf { it.isNotBlank() }?.let { return byName(registry, ask, it) }
        return byColumns(registry, ask)
    }

    /**
     * 一句话问句的选表：**问话里提到了哪张表的列**就优先那张。
     *
     * 与 [pick] 的区别只在「拿什么当线索」：结构化查询拿 `column/groupBy/where`，
     * 一句话拿**问话原文**。两者落到同一套「唯一才认、多于一张就问」的纪律上。
     */
    fun pickForQuestion(
        registry: TableRegistry,
        question: String,
        table: String?,
        sheet: String?,
        headerRow: Int?,
    ): Picked {
        val probe = TableAsk(TableOp.COUNT, table = table, sheet = sheet, headerRow = headerRow)
        if (!table.isNullOrBlank()) return pick(registry, probe)
        val q = TableText.normalizeName(question)
        val known = registry.entries().filter { it.known }
        if (known.isEmpty()) {
            return Picked.Stop(
                TableAnswer.notInTable(
                    "工作区里没有能读的表（${registry.entries().size} 个文件都没认出表头）",
                    evidence = null,
                    hint = "把 xlsx / csv / tsv / .xls 放进工作区再问；PDF 不在本批范围。",
                ),
            )
        }
        val scored = known.map { e ->
            e to (e.sheets.maxOfOrNull { s -> s.columns.count { mentionedIn(it.name, q) } } ?: 0)
        }
        val best = scored.maxOfOrNull { it.second } ?: 0
        if (best == 0) {
            return if (known.size == 1) open(registry, probe, known.first()) else Picked.Many(known.map { it.rel })
        }
        val top = scored.filter { it.second == best }
        return if (top.size == 1) open(registry, probe, top.first().first) else Picked.Many(top.map { it.first.rel })
    }

    /** 列名（或它的同义写法）在问话里出现了没有。 */
    private fun mentionedIn(columnName: String, normalizedQuestion: String): Boolean {
        val n = TableText.normalizeName(columnName)
        if (n.length >= 2 && normalizedQuestion.contains(n)) return true
        val group = TableAlias.conceptOf(columnName) ?: return false
        return group.forms.any { f ->
            val fn = TableText.normalizeName(f)
            fn.isNotEmpty() && normalizedQuestion.contains(fn)
        }
    }

    /** 点名了表：精确路径 → 文件名 → 唯一包含。**包含到两张就退回去问**。 */
    private fun byName(registry: TableRegistry, ask: TableAsk, name: String): Picked {
        val bare = name.replace('\\', '/').trim()
        val all = registry.entries()
        val exact = all.firstOrNull { it.rel == bare }
            ?: all.firstOrNull { it.rel.substringAfterLast('/') == bare }
        if (exact != null) return open(registry, ask, exact)
        val fuzzy = all.filter { it.rel.contains(bare) }
        return when (fuzzy.size) {
            0 -> Picked.Stop(
                TableAnswer.notInTable(
                    "没找到这张表：$bare",
                    evidence = null,
                    hint = "工作区里的表：${all.joinToString("、") { it.rel }}",
                ),
            )
            1 -> open(registry, ask, fuzzy.first())
            else -> Picked.Many(fuzzy.map { it.rel })
        }
    }

    private fun open(registry: TableRegistry, ask: TableAsk, entry: TableEntry): Picked {
        val model = registry.modelOf(entry.rel, ask.sheet, ask.headerRow)
            ?: return Picked.Stop(
                TableAnswer.unsupported(
                    "「${entry.rel}」读不动（加密 / 损坏 / 不是表格）。",
                    hint = "在电脑上另存为普通 xlsx 或 csv 再放进来。",
                ),
            )
        return Picked.One(model)
    }

    /**
     * 没点名：按「问到的列」给每张表打分。
     *
     * 分数 = 问到的列名/概念在这张表里能对上几列。最高分并列 ⇒ [Picked.Many]（问一句）；
     * 一张都不沾 ⇒ 「没找到含这些列的表」（不是「表里没有这个数」，两种事分得很清）。
     */
    private fun byColumns(registry: TableRegistry, ask: TableAsk): Picked {
        val terms = buildList {
            ask.where.forEach { add(it.column) }
            listOfNotNull(ask.column, ask.groupBy, ask.orderBy).forEach { add(it) }
        }.filter { it.isNotBlank() }.distinct()
        val known = registry.entries().filter { it.known }
        if (known.isEmpty()) {
            return Picked.Stop(
                TableAnswer.notInTable(
                    "工作区里没有能读的表（${registry.entries().size} 个文件都没认出表头）",
                    evidence = null,
                    hint = "把 xlsx / csv / tsv / .xls 放进工作区再问；PDF 不在本批范围。",
                ),
            )
        }
        if (terms.isEmpty()) {
            return when (known.size) {
                1 -> open(registry, ask, known.first())
                else -> Picked.Many(known.map { it.rel })
            }
        }
        val scored = known.map { e -> e to (e.sheets.maxOfOrNull { s -> score(s, terms) } ?: 0) }
        val best = scored.maxOfOrNull { it.second } ?: 0
        val top = scored.filter { it.second == best && best > 0 }
        return when (top.size) {
            0 -> Picked.Stop(
                TableAnswer.notInTable(
                    "没找到含「${terms.joinToString("、")}」这些列的表",
                    evidence = null,
                    hint = "工作区里的表：${known.joinToString("、") { it.rel }}",
                ),
            )
            1 -> open(registry, ask, top.first().first)
            else -> Picked.Many(top.map { it.first.rel })
        }
    }

    /** 一张 sheet 对上了几个问法里的列（原文相等或同概念都算）。 */
    private fun score(sheet: SheetEntry, terms: List<String>): Int = terms.count { t ->
        sheet.columns.any { c ->
            TableText.normalizeName(c.name) == TableText.normalizeName(t) || TableAlias.sameConcept(c.name, t)
        }
    }
}

/**
 * 联表（**只有明确 join key 才联**）。
 *
 * 联出来的是一张**新表模型**：列＝左表全部 + 右表除 key 外的列，行＝左表里 key 在右表出现的那些。
 * 这样上层所有运算（过滤/计数/分组/排序）一个字都不用改，也就没有「联表专用的一套口径」。
 *
 * 出处口径：联表答案的 `path` 写成 `A ⋈ B（按 key）`，行号取**左表**的（右表那半的出处
 * 写在 path 里）；这是本批能给的诚实做法——两边行号混着报会让人对不上任何一张表。
 */
internal object TableJoin {

    sealed interface Result {
        data class Ok(val model: TableModel) : Result
        data class Stop(val answer: TableAnswer) : Result
    }

    fun inner(left: TableModel, right: TableModel, keyTerm: String): Result {
        val lk = (TableColumns.resolve(left, keyTerm) as? ColumnHit.One)?.column
            ?: return Result.Stop(
                TableAnswer.notInTable(
                    "左表「${left.path}」里没有联表要用的「$keyTerm」列",
                    Evidence(left.path, left.sheet, left.headerRowNumber, emptyList(), left.rows.size),
                ),
            )
        val rk = (TableColumns.resolve(right, keyTerm) as? ColumnHit.One)?.column
            ?: return Result.Stop(
                TableAnswer.notInTable(
                    "右表「${right.path}」里没有联表要用的「$keyTerm」列——两张表对不上，只能分开答",
                    Evidence(right.path, right.sheet, right.headerRowNumber, emptyList(), right.rows.size),
                ),
            )
        val rightKeys = right.rows.mapNotNull { right.valueOf(it, rk)?.trim()?.takeIf { v -> v.isNotEmpty() } }.toSet()
        if (rightKeys.isEmpty()) {
            return Result.Stop(
                TableAnswer.notInTable(
                    "右表「${right.path}」的「${rk.name}」一列全是空的，联不上",
                    Evidence(right.path, right.sheet, right.headerRowNumber, emptyList(), right.rows.size),
                ),
            )
        }
        val cols = ArrayList<TableColumn>(left.columns.size + right.columns.size - 1)
        left.columns.forEach { cols += it }
        right.columns.filter { it.index != rk.index }.forEach { c ->
            val name = if (left.columnNamed(c.name) == null) c.name else "${c.name}（${right.sheet}）"
            cols += c.copy(index = cols.size, name = name)
        }
        val rows = ArrayList<TableRow>()
        left.rows.forEach { r ->
            val k = left.valueOf(r, lk)?.trim()
            if (k.isNullOrEmpty() || k !in rightKeys) return@forEach
            val other = right.rows.firstOrNull { right.valueOf(it, rk)?.trim() == k } ?: return@forEach
            val cells = ArrayList<String?>(cols.size)
            left.columns.forEach { cells += left.valueOf(r, it) }
            right.columns.filter { it.index != rk.index }.forEach { cells += right.valueOf(other, it) }
            rows += TableRow(r.no, cells)
        }
        return Result.Ok(
            TableModel(
                path = "${left.path} ⋈ ${right.path}（按 ${lk.name}）",
                sheet = "${left.sheet}+${right.sheet}",
                headerRow = left.headerRow,
                columns = cols.mapIndexed { i, c -> c.copy(index = i) },
                keys = left.keys,
                rows = rows,
                warnings = (left.warnings + right.warnings).distinct(),
                truncated = left.truncated || right.truncated,
            ),
        )
    }
}
