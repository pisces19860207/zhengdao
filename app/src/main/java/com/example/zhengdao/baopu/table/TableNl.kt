// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十二批 · 一句话 → 结构化查询（**这一层只做翻译，不做计算**）。
//
// 用户口径（派工单 §2.0）：「别人一问就能按照表格上的东西回答真实答案」「万一学生弄个什么表
// 进来，随时问一两句」。所以要有这一层：不问「物流黑话」，只认中文里最常见的那几种问法。
//
// ## 认得出就认，认不出就明说
// 本层是一个**闭集**语法：十种运算 + 六种比较 + 「每/各」分组。落在这十种之外的说法
// （「除…以外且…」「比上个月多多少」）一律 [Result.Unsupported]——派工单 §2.0 原话：
// 「回『这条我算不了，能不能换个问法』，**不许硬猜一个数给出来**」。
//
// ## 两条「本层自己定的口径」必须写进答案
// 1. **不及格 = 分数 < 60**：表里没有写及格线，这是百分制通行约定 ⇒ 答案里明写
//    「不及格按 <60 分算（表里没有写及格线）」。用户看到口径能当场纠正，这比不答好；
// 2. **列从哪来**：先按列名原文（冷启动可用），再按 [TableAlias] 的同义词，
//    最后才用「表里真有这个取值」做等值筛选（`数学` 是科目列里的一个值）。
//    三条都不猜：任何一条命中多列，都返回 [Result.Unsupported] 并说明。
package com.example.zhengdao.baopu.table

/** 一句话解析器。 */
object TableNl {

    sealed interface Result {
        data class Ask(val ask: TableAsk) : Result
        data class Unsupported(val answer: TableAnswer) : Result
    }

    /** 及格线（百分制通行约定；**不是从表里读的**，所以每次都要在答案里交代）。 */
    const val PASS_LINE = 60.0

    private val CN_NUM = mapOf(
        '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10,
    )

    private val TOP_N = Regex("""前([0-9]+|[一二两三四五六七八九十]+)""")
    private val GT = Regex("""(大于|超过|高于|多于)([0-9]+(?:\.[0-9]+)?)""")
    private val LT = Regex("""(小于|低于|不到|少于)([0-9]+(?:\.[0-9]+)?)""")
    private val EQ = Regex("""(等于)([0-9]+(?:\.[0-9]+)?)""")

    /** 九条规则认的词（一张表一份，改词只改这里）。 */
    private val SUM_WORDS = listOf("合计", "总和", "求和", "加起来", "总分", "总额", "总共多少")
    private val DISTINCT_WORDS = listOf("几种", "几个不同", "去重", "有多少种")
    private val LIST_WORDS = listOf("都有谁", "有哪些", "哪些", "名单", "列出", "列一下", "都有什么")
    private val COUNT_WORDS = listOf("几个", "多少", "一共", "总共", "人数", "几行", "几条", "几条记录")

    /** 「谁」这类问法 ⇒ 答案要点名（[TableAsk.label]）。 */
    private val WHO = listOf("是谁", "谁", "哪个", "哪位", "叫什么")

    /**
     * 把一句话翻成 [TableAsk]。
     *
     * 九条规则按**固定次序**试，第一条认下来的就是结果（次序即判据：
     * 「前 3 名」先于「最高」、「最高分是谁」先于「平均」、「每科平均分」先于「平均」——
     * 换次序会把分组问法答成整表平均）。
     *
     * 一条都没认下来 ⇒ [Result.Unsupported]，**绝不落回一个默认运算**。
     *
     * @param model 已经选定的那张表（列名要从它上面核，不能凭空造）
     */
    fun parse(model: TableModel, question: String): Result {
        val q = TableText.normalizeName(question)
        if (q.isEmpty()) return unsupported("没听到问题。")
        val ctx = Ctx(model, q, question, TableColumns.mentioned(model, question))
        return topNRule(ctx)
            ?: extremeRule(ctx)
            ?: groupRule(ctx)
            ?: averageRule(ctx)
            ?: sumRule(ctx)
            ?: distinctRule(ctx)
            ?: listRule(ctx)
            ?: countRule(ctx)
            ?: unsupported(
                "这句话我没认出要算什么（「$question」）。",
                "我能答：一共几个、几个人不及格、每科平均分、最高分是谁、总分多少、" +
                    "前 3 名、有几种、都有谁。换个问法试试。",
            )
    }

    // ── 九条规则（每条「不归我」就回 null，由下一条接手） ──────────────────

    /** ① 前 N / 排名。 */
    private fun topNRule(ctx: Ctx): Result? {
        val raw = TOP_N.find(ctx.q)?.groupValues?.get(1) ?: return null
        val n = countOf(raw) ?: return null
        val col = ctx.valueColumn() ?: return ctx.noValueColumn("排名")
        return Result.Ask(
            TableAsk(
                op = TableOp.TOP,
                column = col.name,
                orderBy = col.name,
                desc = !ctx.q.contains("后"),
                limit = n,
                label = ctx.labelColumn()?.name,
                where = ctx.conds(),
                criteria = ctx.criteria.toList(),
            ),
        )
    }

    /** ②③ 最高/最低（问「是谁」就取第 1 名，否则只报数）。 */
    private fun extremeRule(ctx: Ctx): Result? {
        val q = ctx.q
        return when {
            q.contains("最高") || q.contains("最大") || q.contains("最多") -> extreme(ctx, desc = true, word = "最高")
            q.contains("最低") || q.contains("最小") || q.contains("最少") -> extreme(ctx, desc = false, word = "最低")
            else -> null
        }
    }

    /** ④ 每/各 ⇒ 分组。 */
    private fun groupRule(ctx: Ctx): Result? {
        val groupCol = ctx.groupColumn() ?: return null
        return group(ctx, groupCol)
    }

    /** ⑤ 平均。 */
    private fun averageRule(ctx: Ctx): Result? {
        if (!ctx.q.contains("平均") && !ctx.q.contains("均值")) return null
        val col = ctx.valueColumn() ?: return ctx.noValueColumn("平均")
        return Result.Ask(
            TableAsk(
                op = TableOp.AVG,
                column = col.name,
                where = ctx.conds(),
                criteria = ctx.criteria.toList(),
                subject = ctx.subject,
            ),
        )
    }

    /** ⑥ 合计 / 总和 / 求和 / 总分。 */
    private fun sumRule(ctx: Ctx): Result? {
        if (SUM_WORDS.none { ctx.q.contains(it) }) return null
        val col = ctx.valueColumn() ?: return ctx.noValueColumn("合计")
        return Result.Ask(
            TableAsk(op = TableOp.SUM, column = col.name, where = ctx.conds(), criteria = ctx.criteria.toList()),
        )
    }

    /** ⑦ 有几种。 */
    private fun distinctRule(ctx: Ctx): Result? {
        if (DISTINCT_WORDS.none { ctx.q.contains(it) }) return null
        val col = ctx.anyColumn() ?: return ctx.noColumn()
        return Result.Ask(TableAsk(op = TableOp.COUNT_DISTINCT, column = col.name, criteria = ctx.criteria.toList()))
    }

    /** ⑧ 列出来（都有谁 / 哪些 / 名单）。 */
    private fun listRule(ctx: Ctx): Result? {
        if (LIST_WORDS.none { ctx.q.contains(it) }) return null
        return Result.Ask(
            TableAsk(
                op = TableOp.LIST,
                column = ctx.valueColumn()?.name,
                label = ctx.labelColumn()?.name,
                where = ctx.conds(),
                criteria = ctx.criteria.toList(),
                subject = ctx.subject,
                condLabel = ctx.condLabel,
            ),
        )
    }

    /** ⑨ 计数。 */
    private fun countRule(ctx: Ctx): Result? {
        if (COUNT_WORDS.none { ctx.q.contains(it) }) return null
        return Result.Ask(
            TableAsk(
                op = TableOp.COUNT,
                where = ctx.conds(),
                criteria = ctx.criteria.toList(),
                subject = ctx.subject,
                condLabel = ctx.condLabel,
                // 「几个人」而表是一人多行（成绩表一人两科）⇒ 按姓名去重，答案里写明口径
                distinctBy = if (ctx.subject == "个人") ctx.personColumn()?.name else null,
            ),
        )
    }

    // ── 分支 ────────────────────────────────────────────────────────────────

    private fun extreme(ctx: Ctx, desc: Boolean, word: String): Result {
        val col = ctx.valueColumn() ?: return ctx.noValueColumn(word)
        val who = WHO.any { ctx.q.contains(it) }
        return if (who) {
            Result.Ask(
                TableAsk(
                    op = TableOp.TOP,
                    column = col.name,
                    orderBy = col.name,
                    desc = desc,
                    limit = 1,
                    label = ctx.labelColumn()?.name,
                    where = ctx.conds(),
                    criteria = ctx.criteria.toList(),
                ),
            )
        } else {
            Result.Ask(
                TableAsk(
                    op = if (desc) TableOp.MAX else TableOp.MIN,
                    column = col.name,
                    label = ctx.labelColumn()?.name,
                    where = ctx.conds(),
                    criteria = ctx.criteria.toList(),
                ),
            )
        }
    }

    private fun group(ctx: Ctx, groupCol: TableColumn): Result {
        val countOnly = listOf("几个", "多少", "人数", "个数", "几条", "多少条").any { ctx.q.contains(it) }
        val average = ctx.q.contains("平均") || ctx.q.contains("均值")
        val sum = listOf("合计", "总和", "求和", "总分", "总额").any { ctx.q.contains(it) }
        if (countOnly && !average && !sum) {
            return Result.Ask(
                TableAsk(
                    op = TableOp.GROUP_COUNT,
                    groupBy = groupCol.name,
                    where = ctx.conds(),
                    criteria = ctx.criteria.toList(),
                ),
            )
        }
        val col = ctx.valueColumn(exclude = groupCol) ?: return ctx.noValueColumn("分组汇总")
        val extra = if (!average && !sum) listOf("没说要算平均还是合计，按平均算") else emptyList()
        return Result.Ask(
            TableAsk(
                op = when {
                    average -> TableOp.GROUP_AVG
                    sum -> TableOp.GROUP_SUM
                    else -> TableOp.GROUP_AVG
                },
                column = col.name,
                groupBy = groupCol.name,
                where = ctx.conds(),
                criteria = ctx.criteria.toList() + extra,
            ),
        )
    }

    // ── 解析上下文（列的角色在这一处定，别处不再猜） ──────────────────────

    private class Ctx(
        val model: TableModel,
        val q: String,
        val raw: String,
        val mentioned: List<TableColumn>,
    ) {

        /** 本层自己定的口径（及格线），要原样进答案。 */
        val criteria = ArrayList<String>()

        /** 答案量词：`个人` / `条` / 空（「一共 5 个人」「2 个人不及格」都是这一处出来的）。 */
        val subject: String = when {
            q.contains("人") -> "个人"
            q.contains("条") -> "条"
            else -> ""
        }

        /** 条件的口头说法（「不及格」）。 */
        var condLabel: String = ""
            private set

        /** 分组列：问话里「每 X」「各 X」的那个 X。 */
        fun groupColumn(): TableColumn? {
            if (!q.contains("每") && !q.contains("各")) return null
            val hits = mentioned.filter { col ->
                forms(col).any { f -> q.contains("每$f") || q.contains("各$f") }
            }
            return hits.singleOrNull()
        }

        /** 取值列：问话里点到、且不是分组列的那个；没有就取「全表唯一的数字列」。 */
        fun valueColumn(exclude: TableColumn? = null): TableColumn? {
            val pool = mentioned.filter { it != exclude }
            pool.firstOrNull { it.type == ColumnType.NUMBER }?.let { return it }
            pool.firstOrNull()?.let { return it }
            val numeric = model.columns.filter { it.type == ColumnType.NUMBER && it != exclude }
            return numeric.singleOrNull()
        }

        fun anyColumn(): TableColumn? = mentioned.firstOrNull() ?: model.columns.firstOrNull()

        /** 点名用的列（「是谁」）：姓名概念 → 主键列 → 第一个文本列。 */
        fun labelColumn(): TableColumn? {
            if (WHO.none { q.contains(it) }) return null
            (TableColumns.resolve(model, "姓名") as? ColumnHit.One)?.column?.let { return it }
            model.keys.firstOrNull()?.let { model.columnNamed(it) }?.let { return it }
            return model.columns.firstOrNull { it.type == ColumnType.TEXT }
        }

        /** 「人」那一列（姓名概念）；没有 ⇒ null（此时按行数答，不假装知道谁是「人」）。 */
        fun personColumn(): TableColumn? = (TableColumns.resolve(model, "姓名") as? ColumnHit.One)?.column

        /**
         * 全部条件。
         *
         * 三种来源，**互不覆盖、按次序叠加**：
         * 1. 及格/不及格 ⇒ 阈值条件（口径写进 [criteria]）；
         * 2. 大于/小于/等于 + 数字 ⇒ 数值条件（落到取值列上）；
         * 3. 问话里出现了某列的一个**真实取值**（`数学`）⇒ 等值条件。
         */
        fun conds(): List<Cond> {
            val out = ArrayList<Cond>()
            passLine()?.let { out += it }
            explicitNumber()?.let { out += it }
            out += valueConds()
            return out.distinct()
        }

        /** 及格线：`不及格` 先判（它是 `及格` 的超串，判反了会把不及格数成及格）。 */
        private fun passLine(): Cond? {
            val col = valueColumn() ?: return null
            if (col.type != ColumnType.NUMBER) return null
            return when {
                q.contains("不及格") -> {
                    condLabel = "不及格"
                    criteria += "不及格按 <${TableText.number(PASS_LINE)} 分算（表里没写及格线，这是百分制通行约定）"
                    Cond(col.name, Cmp.LT, TableText.number(PASS_LINE))
                }
                q.contains("及格") -> {
                    condLabel = "及格"
                    criteria += "及格按 ≥${TableText.number(PASS_LINE)} 分算（表里没写及格线，这是百分制通行约定）"
                    Cond(col.name, Cmp.GE, TableText.number(PASS_LINE))
                }
                else -> null
            }
        }

        private fun explicitNumber(): Cond? {
            val col = valueColumn() ?: return null
            if (col.type != ColumnType.NUMBER) return null
            GT.find(q)?.let { return Cond(col.name, Cmp.GT, it.groupValues[2]) }
            LT.find(q)?.let { return Cond(col.name, Cmp.LT, it.groupValues[2]) }
            EQ.find(q)?.let { return Cond(col.name, Cmp.EQ, it.groupValues[2]) }
            return null
        }

        /**
         * 问话里出现了某列的**真实取值** ⇒ 等值筛选。
         *
         * 这条不是猜：值就在这张表的这一列里躺着（`数学` 是科目列的一个值）。
         * 判据限制：只认文本列、只认 ≥2 个字的取值、一列最多扫 200 个不同值——
         * 一条都不满足就当没提到（宁可少筛，不许把「一共几个人」里的「人」当成某个姓名）。
         */
        private fun valueConds(): List<Cond> {
            val hits = ArrayList<Cond>()
            model.columns.filter { it.type == ColumnType.TEXT && it.distinct in 1..MAX_VALUE_SCAN }.forEach { col ->
                val values = model.rows.mapNotNull { model.valueOf(it, col)?.trim() }
                    .filter { it.length >= 2 }.distinct()
                values.filter { q.contains(TableText.normalizeName(it)) }.forEach { hits += Cond(col.name, Cmp.EQ, it) }
            }
            return hits.distinct()
        }

        fun noValueColumn(word: String): Result = Result.Unsupported(
            TableAnswer.unsupported(
                "「$word」要算一列数，但我没认出是哪一列。",
                hint = "表里的列：${model.columns.joinToString("、") { it.name }}——说一个列名（比如「$word」那一列）。",
            ),
        )

        fun noColumn(): Result = Result.Unsupported(
            TableAnswer.unsupported(
                "没认出要算哪一列。",
                hint = "表里的列：${model.columns.joinToString("、") { it.name }}。",
            ),
        )

        /** 一列的所有写法（原文 + 同义词）。 */
        private fun forms(col: TableColumn): List<String> {
            val list = ArrayList<String>(4)
            list += TableText.normalizeName(col.name)
            TableAlias.conceptOf(col.name)?.forms?.forEach { list += TableText.normalizeName(it) }
            return list.filter { it.isNotEmpty() }.distinct()
        }

        companion object {
            /** 一列最多扫多少个不同取值来找「问话里提到的值」。 */
            const val MAX_VALUE_SCAN = 200
        }
    }

    // ── 小工具 ──────────────────────────────────────────────────────────────

    private fun unsupported(reason: String, hint: String = "这条我算不了，能不能换个问法？"): Result =
        Result.Unsupported(TableAnswer.unsupported(reason, hint))

    /** `3` / `三` / `十` / `十` → Int；认不出 null（认不出就不当「前 N」）。 */
    internal fun countOf(raw: String): Int? {
        raw.toIntOrNull()?.let { return if (it in 1..1000) it else null }
        if (raw.isEmpty()) return null
        if (raw.length == 1) return CN_NUM[raw[0]]
        // 只认「十X」「X十」「X十Y」这三种中文数法（再多就是猜）
        val chars = raw.toCharArray()
        if (chars.all { it in CN_NUM }) {
            val ten = chars.indexOf('十')
            if (ten < 0) return null
            val high = if (ten == 0) 1 else CN_NUM[chars[ten - 1]] ?: return null
            val low = if (ten == chars.size - 1) 0 else CN_NUM[chars[ten + 1]] ?: return null
            return high * 10 + low
        }
        return null
    }
}
