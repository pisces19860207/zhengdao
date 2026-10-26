// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十二批 · 列归一（**别名表只做同义词统一，不是使用前提**）。
//
// 用户口径（派工单 §2.0「冷启动要求」原话）：「没有别名表也要能用——按列名原文匹配
// （"分数"就是"分数"）；别名表只用来把同义列（单号/运单号）统一，**不是使用前提**」。
//
// 于是本文件的分层是硬的：
// - 第一步**永远**是原文精确匹配（[TableColumns.resolve] 的 arm 1）——零配置可用；
// - 第二步才看别名表，且它只能把「同义词」认成同一个概念（成绩＝分数），
//   不做模糊匹配、不做相似度、不做繁简转换。别名表被清空 ⇒ 能力退化成原文匹配，**不报错**；
// - 第三步的「包含」是给 `数学分数` 这种复合列名留的活路，且**只在唯一命中时**才认；
//   命中两列以上一律 [ColumnHit.Many]（问一句），绝不替用户挑一列。
package com.example.zhengdao.baopu.table

/**
 * 一组同义写法。
 *
 * @param key 概念键（代码里引用它，不用中文标签）
 * @param label 规范标签（问「表里没有 X」时念它）
 * @param forms 收录的写法（**整词**收录：`科` 收、`科目代码` 不收）
 */
data class AliasGroup(val key: String, val label: String, val forms: List<String>)

/**
 * 别名表（**唯一一张**，写死在代码里；表驱动测试逐条打）。
 *
 * 收录口径：只收「中文表头里真会出现的整词同义写法」。一个写法只归一个概念；
 * 跨概念歧义时**不收**（宁可让用户说全名，也不猜）。
 *
 * 与 195 物流契约的关系：那张表是**字段契约**（单号是硬列，缺了拒读），这张是
 * **问话→列名**的翻译表。两者互不引用——物流冻结口径一个字没动，这里也不认「卸点」
 * 那种领域字段（通用层不知道也不该知道卸点是什么）。
 */
object TableAlias {

    val GROUPS: List<AliasGroup> = listOf(
        AliasGroup("person_name", "姓名", listOf("姓名", "名字", "学生姓名", "员工姓名", "人名", "人员姓名")),
        AliasGroup("student_id", "学号", listOf("学号", "学籍号", "学生编号", "考号")),
        AliasGroup("subject", "科目", listOf("科目", "课程", "学科", "科")),
        // 「平均分/总分/最高分/最低分」是**问法**，不是列名：它们是「分数这一列 + 一个运算」。
        // 收进来是为了让「每科平均分」「最高分是谁」能找到「分数」列；
        // 表里真有一列就叫「最高分」时，[TableColumns.resolve] 的第 1 步（原文精确）先命中它，
        // 所以这条别名不会把「最高分」列抢走。
        AliasGroup(
            "score",
            "分数",
            listOf("分数", "成绩", "得分", "分值", "考试成绩", "平均分", "总分", "最高分", "最低分"),
        ),
        AliasGroup("klass", "班级", listOf("班级", "班", "班别", "所在班级")),
        AliasGroup("amount", "金额", listOf("金额", "费用", "价款", "合计金额", "应收")),
        AliasGroup("qty", "数量", listOf("数量", "件数", "数目", "个数", "件")),
        AliasGroup("day", "日期", listOf("日期", "时间", "制单日期", "发生日期", "填报日期")),
        AliasGroup("phone", "手机号", listOf("手机号", "手机号码", "手机", "电话", "联系电话", "联系方式")),
        AliasGroup("plate", "车牌", listOf("车牌", "车牌号", "车号", "车牌号码")),
        AliasGroup("order_no", "单号", listOf("单号", "运单号", "单据号", "订单号", "编号")),
        AliasGroup("dept", "部门", listOf("部门", "科室", "单位")),
        AliasGroup("remark", "备注", listOf("备注", "说明", "附注")),
    )

    private val byForm: Map<String, AliasGroup> = buildMap {
        GROUPS.forEach { g ->
            g.forms.forEach { put(TableText.normalizeName(it), g) }
            putIfAbsent(TableText.normalizeName(g.label), g)
        }
    }

    /** 这个写法属于哪个概念（归一后整词相等才算；不是就算 null）。 */
    fun conceptOf(term: String): AliasGroup? = byForm[TableText.normalizeName(term)]

    /** 两个写法是不是同一个概念（原文相等，或同属一组同义词）。 */
    fun sameConcept(a: String, b: String): Boolean {
        val na = TableText.normalizeName(a)
        val nb = TableText.normalizeName(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        val ga = conceptOf(a) ?: return false
        return ga.key == conceptOf(b)?.key
    }
}

/** 一个问法落到列上的三种结局。 */
sealed interface ColumnHit {

    data class One(val column: TableColumn) : ColumnHit

    /**
     * 命中多列（重名列，或这个词同时像两列）。
     *
     * **不许取第一个**：取哪个都是猜。上层必须把 [columns] 念给用户再问一句。
     */
    data class Many(val term: String, val columns: List<TableColumn>) : ColumnHit

    data object None : ColumnHit

    /** 便捷取值（唯一命中才给，其余 null——调用方必须自己处理 Many/None）。 */
    fun columnOrNull(): TableColumn? = (this as? One)?.column
}

/** 问法 → 列的解析（判据次序写在 [resolve] 上）。 */
object TableColumns {

    /**
     * 解析一个问法到列。**次序即判据**：
     *
     * 1. **原文精确**（归一后整词相等）——冷启动唯一依赖的一条，没有别名表也能走；
     * 2. **别名概念**：问法属于某概念 ⇒ 找同概念的列；一列 ⇒ 命中，多列 ⇒ [ColumnHit.Many]；
     * 3. **包含**：问法长度 ≥ 2 且是某列名的子串（`分数` ⊂ `数学分数`）⇒ 唯一才命中；
     * 4. 都不中 ⇒ [ColumnHit.None]（上层回「表里没有这一列」，不猜）。
     */
    fun resolve(model: TableModel, term: String): ColumnHit {
        val t = TableText.normalizeName(term)
        if (t.isEmpty() || !model.known) return ColumnHit.None
        val exact = model.columns.filter { TableText.normalizeName(it.name) == t }
        if (exact.size == 1) return ColumnHit.One(exact.first())
        if (exact.size > 1) return ColumnHit.Many(term, exact)

        val concept = TableAlias.conceptOf(term)
        if (concept != null) {
            val same = model.columns.filter {
                TableText.normalizeName(it.name) == TableText.normalizeName(concept.label) ||
                    TableAlias.conceptOf(it.name)?.key == concept.key
            }
            if (same.size == 1) return ColumnHit.One(same.first())
            if (same.size > 1) return ColumnHit.Many(term, same)
        }

        if (t.length >= 2) {
            val contains = model.columns.filter { TableText.normalizeName(it.name).contains(t) }
            if (contains.size == 1) return ColumnHit.One(contains.first())
            if (contains.size > 1) return ColumnHit.Many(term, contains)
        }
        return ColumnHit.None
    }

    /**
     * 一列在问话里被提到的**原文**（用于「问话里点名的列」）。
     *
     * 判据：列名归一后整词出现在问话里，**或**该列所属概念的某个写法出现在问话里。
     * 两条都只看「整词是否被包含」——不做分词、不做近义扩展。
     *
     * @return 命中的列（可能多列；调用方按语义挑，挑不出就问）
     */
    fun mentioned(model: TableModel, question: String): List<TableColumn> {
        if (!model.known) return emptyList()
        val q = TableText.normalizeName(question)
        if (q.isEmpty()) return emptyList()
        return model.columns.filter { col ->
            val n = TableText.normalizeName(col.name)
            (n.length >= 2 && q.contains(n)) || formsMentioned(col.name, q)
        }
    }

    /** 该列所属概念的写法里，有没有一个出现在问话里（`每科` 里的 `科` 算）。 */
    private fun formsMentioned(columnName: String, normalizedQuestion: String): Boolean {
        val group = TableAlias.conceptOf(columnName) ?: return false
        return group.forms.any { f ->
            val n = TableText.normalizeName(f)
            n.length >= 1 && normalizedQuestion.contains(n)
        }
    }

    /** 「表里没有这一列」那句话（把表里真有的列念出来，她才改得动问法）。 */
    fun absentSentence(model: TableModel, term: String): String {
        val names = model.columns.joinToString("、") { it.name }
        return "表里没有「$term」这一列（这张表的列：$names）。"
    }
}
