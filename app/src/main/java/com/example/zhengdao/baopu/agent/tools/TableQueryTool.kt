// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百三十二批 · `table_query`：**任何表格都能问**（xlsx / csv / tsv；**PDF 除外**）。
//
// 用户口径（2026-10-26）：「把查询助手改成全能而不只是物流，只要有表格就能识别，
// 一问就能按表格上的东西回答真实答案，除了 PDF」。
//
// ## 与 `logistics_query` 的关系（**不是替换，是上游**）
// `logistics_query` 是**物流特化档**：十二个固定字段、冻结的车数口径、别名表，一个字都没动。
// 本工具是**通用档**：它不知道「卸点」「配货单」是什么，只认「表头、列、行、数」。
// 物流表进本工具也能问（它就是一张表），但要车数那类**领域口径**仍该走 `logistics_query`。
//
// ## 两条入口
// - `question`：一句话（「一共几个人」「每科平均分」）——由 [TableNl] 翻成结构化查询；
// - `op` 等结构化参数：模型自己给出运算与列（与 195 同一条口径：结构化参数比原话稳）。
// 两个都不给 ⇒ 回一份「能问什么」的名册（**不是报错**：模型不该为「探路」付一次失败重试）。
//
// ## 只读与唯一的落盘
// 本工具**只读用户的表**。唯一会写的是 App 自己的登记簿 `抱朴记忆/表格/registry.json`
// （可整包删掉重建，等同缓存；不含任何单元格取值）。这一点在交付报告里单列，供蓝总管裁定档位。
//
// ## 隐私（负例 8）
// 姓名与手机号**只显示、不落盘**：日志只写条数/运算/列名，`question` 原话不进日志，
// registry 只存列名与类型、不存取值。落盘那一行由 [logLine] **纯函数**产出——
// 它只吃「种类/表名/运算/命中行数」，结构上拿不到任何单元格，所以泄漏不是靠自觉。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.table.Cmp
import com.example.zhengdao.baopu.table.Cond
import com.example.zhengdao.baopu.table.TableAnswer
import com.example.zhengdao.baopu.table.TableAsk
import com.example.zhengdao.baopu.table.TableOp
import com.example.zhengdao.baopu.table.TableQuery
import com.example.zhengdao.baopu.table.TableRegistry
import com.example.zhengdao.baopu.table.TableRoot
import com.example.zhengdao.baopu.table.TableText
import com.example.zhengdao.core.RunLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 通用表格问答工具（只读、零网络、零模型调用）。 */
internal object TableQueryTool {

    const val NAME = "table_query"

    const val ARG_QUESTION = "question"
    const val ARG_TABLE = "table"
    const val ARG_SHEET = "sheet"
    const val ARG_HEADER_ROW = "headerRow"
    const val ARG_OP = "op"
    const val ARG_COLUMN = "column"
    const val ARG_GROUP_BY = "groupBy"
    const val ARG_ORDER_BY = "orderBy"
    const val ARG_DESC = "desc"
    const val ARG_LIMIT = "limit"
    const val ARG_FILTER = "filter"
    const val ARG_JOIN_WITH = "joinWith"
    const val ARG_JOIN_KEY = "joinKey"

    /** `op` 的取值（一个不多：加一种运算要同时想清楚「算不了」怎么说）。 */
    val OPS: List<String> = listOf(
        "count", "countDistinct", "sum", "avg", "min", "max", "list", "groupCount", "groupSum", "groupAvg", "top",
    )

    /** 解锁话术（与 195 同一句：看不到就先解锁 DATA 组）。 */
    const val UNLOCK_SENTENCE = "若你看不到本工具，先调用 capabilities(enable=\"data\")。"

    /** PDF 的唯一说法（与 [TableAnswer.pdf] 同一句，测试按它断言）。 */
    const val PDF_REFUSAL = "PDF 暂不支持"

    /** 进程内登记簿缓存（按工作区绝对路径）：**不缓存就每次问答都重解析一遍**，那是负例 7。 */
    private var cached: Pair<String, TableRegistry>? = null

    /**
     * 取工作区的登记簿。
     *
     * 扫描根只有两个：工作区根与默认工作区（`Download/证道`）。157/179 收口后旧 `资料库/`
     * 的内容就在工作区里，递归扫一遍即覆盖——这里**不引用** legacy 常量（内核把它列为禁字）。
     */
    fun registryFor(root: File, extraRoots: List<TableRoot> = emptyList()): TableRegistry {
        val key = root.absolutePath
        synchronized(this) {
            cached?.let { if (it.first == key) return it.second }
            val roots = buildList {
                add(TableRoot(root))
                extraRoots.forEach { if (it.dir.absolutePath != key) add(it) }
            }
            val made = TableRegistry(TableRegistry.homeUnder(root), roots)
            cached = key to made
            return made
        }
    }

    /** 测试用：把缓存清掉（每个用例一份干净登记簿）。 */
    fun resetForTest() {
        synchronized(this) { cached = null }
    }

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME,
        description = "按表格回答真实答案（只读、不联网、不调模型）：xlsx / csv / tsv 都认，" +
            "**PDF 不支持**（会直接说暂不支持）。问法两种：①question 一句话（「一共几个人」" +
            "「每科平均分」「最高分是谁」「几个人不及格」）；②op + column 等结构化参数。" +
            "答案必带出处（文件·sheet·行号）；表里没有就说「表里没有」，不推算、不补 0；" +
            "多张候选表会先问一句，不猜。 " + UNLOCK_SENTENCE,
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_QUESTION to jsonObjectOf(
                    "type" to "string",
                    "description" to "一句话问法（如「一共几个人」「每科平均分」「最高分是谁」）；与 op 二选一",
                ),
                ARG_TABLE to jsonObjectOf(
                    "type" to "string",
                    "description" to "表名或相对工作区的路径（如「成绩表.xlsx」）；不填＝按问到的列选，多张就问",
                ),
                ARG_SHEET to jsonObjectOf("type" to "string", "description" to "sheet 名；不填＝第一张能认出表头的"),
                ARG_HEADER_ROW to jsonObjectOf(
                    "type" to "integer",
                    "description" to "表头在第几行（1 起，用户数的行号）；只在认不出表头、用户答了之后才填",
                ),
                ARG_OP to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf(*OPS.toTypedArray()),
                    "description" to "结构化运算：count=几行 / sum=合计 / avg=平均 / max / min / list=列出来 / " +
                        "groupCount / groupSum / groupAvg=按列分组 / top=排序取前 N",
                ),
                ARG_COLUMN to jsonObjectOf("type" to "string", "description" to "要算的那一列（表里的列名原文）"),
                ARG_GROUP_BY to jsonObjectOf("type" to "string", "description" to "分组列（如「科目」）"),
                ARG_ORDER_BY to jsonObjectOf("type" to "string", "description" to "排序用的列；不填＝column"),
                ARG_DESC to jsonObjectOf("type" to "boolean", "description" to "排序是否从大到小（默认 true）"),
                ARG_LIMIT to jsonObjectOf("type" to "integer", "description" to "取前几行（top 默认 1，list 默认 10）"),
                ARG_FILTER to jsonObjectOf(
                    "type" to "string",
                    "description" to "筛选条件，逗号分隔，如「科目=数学,分数<60」；比较符：= ≠ > >= < <= 含",
                ),
                ARG_JOIN_WITH to jsonObjectOf("type" to "string", "description" to "要联的另一张表（必须同时给 joinKey）"),
                ARG_JOIN_KEY to jsonObjectOf("type" to "string", "description" to "联表按哪一列对（如「学号」）；没给就不联"),
            ),
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        riskTier = "R0", // 只读用户的表；唯一落盘是 App 自己的登记簿（见文件头）
        readOnly = true,
        parallelSafe = true,
        // 首次解析一个 20MB 的 xlsx 可能超过 5 秒（FILE_MS），判超时会让模型以为工具坏了去重试
        timeoutMs = ToolTimeout.DRAW_MS,
    ) { args -> run(args, workspaceRoot()) }

    /** 生产入口。 */
    fun run(args: JSONObject, root: File): ToolOutcome {
        val question = args.optString(ARG_QUESTION).trim()
        val opRaw = args.optString(ARG_OP).trim()
        val table = args.optString(ARG_TABLE).trim().ifEmpty { null }
        val sheet = args.optString(ARG_SHEET).trim().ifEmpty { null }
        val headerRow = if (args.has(ARG_HEADER_ROW)) args.optInt(ARG_HEADER_ROW) else null

        // PDF：**在碰任何文件之前**拒（负例 6：不许硬解 PDF）
        listOfNotNull(table, args.optString(ARG_JOIN_WITH).trim().ifEmpty { null }).forEach { name ->
            if (TableRegistry.isPdf(name)) {
                return ToolOutcome.Ok(jsonOf(TableAnswer.pdf(name), null).toString())
            }
        }
        if (table != null && (table.contains("..") || table.startsWith("/") || table.startsWith("\\"))) {
            return ToolOutcome.Rejected("表名只接受工作区里的相对路径：$table")
        }
        val registry = registryFor(root)
        val answer = when {
            question.isNotEmpty() -> TableQuery.askQuestion(registry, question, table, sheet, headerRow)
            opRaw.isNotEmpty() -> {
                val ask = buildAsk(opRaw, args, table, sheet, headerRow)
                    ?: return ToolOutcome.Failed(
                        "「$opRaw」不是本工具认的 op（只认：${OPS.joinToString("／")}）",
                    )
                TableQuery.ask(registry, ask)
            }
            else -> return ToolOutcome.Ok(helpOf(registry).toString())
        }
        // 日志：只写这一行（**纯函数**产出，拿不到任何单元格取值；查询原话也不在里面）
        runCatching { RunLog.log(logLine(answer, opRaw.ifEmpty { "question" })) }
        return ToolOutcome.Ok(jsonOf(answer, registry).toString())
    }

    /**
     * 落盘的那一行（**纯函数**，单测直接钉它）。
     *
     * 参数里只有「种类/表名/运算/命中行数」——姓名、手机号、查询原话**根本进不来**，
     * 所以「姓名手机号进日志」这条负例不是靠 review，是靠签名。
     */
    internal fun logLine(answer: TableAnswer, op: String): String {
        val rows = answer.evidence?.rows?.size ?: 0
        return "表格问答：${answer.kind.name} op=$op 表=${answer.evidence?.path ?: "-"} 命中行=$rows"
    }

    /** 结构化参数 → [TableAsk]。 */
    internal fun buildAsk(
        opRaw: String,
        args: JSONObject,
        table: String?,
        sheet: String?,
        headerRow: Int?,
    ): TableAsk? {
        val op = when (opRaw.lowercase()) {
            "count" -> TableOp.COUNT
            "countdistinct" -> TableOp.COUNT_DISTINCT
            "sum" -> TableOp.SUM
            "avg" -> TableOp.AVG
            "min" -> TableOp.MIN
            "max" -> TableOp.MAX
            "list" -> TableOp.LIST
            "groupcount" -> TableOp.GROUP_COUNT
            "groupsum" -> TableOp.GROUP_SUM
            "groupavg" -> TableOp.GROUP_AVG
            "top" -> TableOp.TOP
            else -> return null
        }
        return TableAsk(
            op = op,
            table = table,
            sheet = sheet,
            headerRow = headerRow,
            column = args.optString(ARG_COLUMN).trim().ifEmpty { null },
            groupBy = args.optString(ARG_GROUP_BY).trim().ifEmpty { null },
            where = parseFilter(args.optString(ARG_FILTER)),
            orderBy = args.optString(ARG_ORDER_BY).trim().ifEmpty { null },
            desc = args.optBoolean(ARG_DESC, true),
            limit = if (args.has(ARG_LIMIT)) args.optInt(ARG_LIMIT) else null,
            joinWith = args.optString(ARG_JOIN_WITH).trim().ifEmpty { null },
            joinKey = args.optString(ARG_JOIN_KEY).trim().ifEmpty { null },
        )
    }

    /** `科目=数学,分数<60` → 条件表（认不出的那一段直接丢掉，**不猜它的意思**）。 */
    internal fun parseFilter(raw: String): List<Cond> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',', '，').mapNotNull { piece ->
            val m = FILTER.find(piece.trim()) ?: return@mapNotNull null
            val (col, sym, value) = m.destructured
            val cmp = when (sym) {
                "=" -> Cmp.EQ
                "≠", "!=" -> Cmp.NE
                ">" -> Cmp.GT
                ">=" -> Cmp.GE
                "<" -> Cmp.LT
                "<=" -> Cmp.LE
                "含" -> Cmp.CONTAINS
                else -> return@mapNotNull null
            }
            Cond(col.trim(), cmp, value.trim())
        }
    }

    private val FILTER = Regex("""^(.+?)(<=|>=|!=|≠|<|>|=|含)(.+)$""")

    /**
     * 答案 → 工具回灌。
     *
     * `answer` 是给模型照抄的三行话；`selfCheck` 是 227 [AnswerGate] 对这句话的核对
     * （数字必须在 ground 里出现过）。ground 由**这次真读到的行**与**算出来的数**拼成，
     * 所以「模型把 3 说成 5」会在下一层的 gate 上显形。
     */
    internal fun jsonOf(answer: TableAnswer, registry: TableRegistry?): JSONObject {
        val out = JSONObject()
            .put("ok", answer.ok)
            .put("kind", answer.kind.name)
            .put("answer", answer.text())
        answer.evidence?.let { e ->
            out.put(
                "evidence",
                JSONObject()
                    .put("file", e.path)
                    .put("sheet", e.sheet)
                    .put("headerRow", e.headerRow)
                    .put("rows", JSONArray(e.rows))
                    .put("totalRows", e.totalRows),
            )
        }
        out.put("numbers", JSONArray(answer.numbers))
        if (answer.criteria.isNotEmpty()) out.put("criteria", JSONArray(answer.criteria))
        if (answer.hint.isNotEmpty()) out.put("hint", answer.hint)
        if (answer.digest.isNotEmpty()) out.put("rows", JSONArray(answer.digest))
        registry?.let { r ->
            out.put("tables", JSONArray(r.entries().map { it.rel }))
            out.put("pdfSkipped", r.pdfSkipped)
        }
        val findings = AnswerGate.check(answer.text(), groundOf(answer), workMode = true)
        if (findings.isNotEmpty()) {
            out.put("selfCheck", JSONArray(findings.map { it.text }))
        }
        return out
    }

    /**
     * AnswerGate 的 ground：口径 + 命中行摘要 + 算出来的数 + 出处行号 + 表的总行数。
     *
     * 为什么连「共 N 行」也要进 ground：答案是「第 4/8 行（这张表共 10 行）」，
     * 那个 10 是从表里读出来的真数，不写进 ground 就会被 227 的 gate 误报成「数字不是来自表」。
     */
    private fun groundOf(answer: TableAnswer): String {
        val sb = StringBuilder()
        answer.criteria.forEach { AnswerGate.appendGround(sb, it) }
        answer.digest.forEach { AnswerGate.appendGround(sb, it) }
        answer.numbers.forEach { AnswerGate.appendGround(sb, TableText.number(it)) }
        answer.evidence?.let { e ->
            e.rows.forEach { AnswerGate.appendGround(sb, "第 $it 行") }
            AnswerGate.appendGround(sb, "这张表共 ${e.totalRows} 行；表头在第 ${e.headerRow} 行")
        }
        return sb.toString()
    }

    /** 什么都没给时回的名册（**不是报错**：模型探路不该付一次失败重试）。 */
    private fun helpOf(registry: TableRegistry): JSONObject {
        registry.refresh()
        val tables = JSONArray()
        registry.entries().forEach { e ->
            tables.put(
                JSONObject()
                    .put("file", e.rel)
                    .put("sheets", JSONArray(e.sheets.map { it.name }))
                    .put("rows", e.sheets.maxOfOrNull { it.rows } ?: 0)
                    .put("columns", JSONArray(e.sheets.firstOrNull()?.columns?.map { it.name } ?: emptyList<String>()))
                    .put("keys", JSONArray(e.defaultSheet?.keys ?: emptyList<String>()))
                    .put("headerRow", (e.defaultSheet?.headerRow ?: -1) + 1)
                    .put("needHeaderRow", e.defaultSheet?.ask ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("kind", "HELP")
            .put("tables", tables)
            .put("pdfSkipped", registry.pdfSkipped)
            .put(
                "answer",
                "能问：" + OPS.joinToString("／") +
                    "；也可以直接给一句话（question）。表：${registry.entries().joinToString("、") { it.rel }}。" +
                    "PDF 不在本批范围。",
            )
    }
}
