// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十二批 · 工具面（`table_query`）：schema 门禁、分组/档位、两条入口、PDF 出口、
// 以及 227 [com.example.zhengdao.baopu.agent.AnswerGate] 的复用（数字必须来自表）。
package com.example.zhengdao.baopu.table

import com.example.zhengdao.baopu.agent.BaopuToolCatalog
import com.example.zhengdao.baopu.agent.TableQueryTool
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolGroupTable
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolSchemaRules
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class TableQueryToolTest {

    private lateinit var ws: File

    @Before
    fun setUp() {
        ws = tempWorkspace("tool")
        writeScoreXlsx(ws)
        TableQueryTool.resetForTest()
    }

    @After
    fun tearDown() {
        wipe(ws)
        TableQueryTool.resetForTest()
    }

    private fun spec() = TableQueryTool.spec { ws }

    private fun run(vararg pairs: Pair<String, Any?>): JSONObject {
        val args = JSONObject()
        pairs.forEach { (k, v) -> args.put(k, v) }
        val out = TableQueryTool.run(args, ws)
        assertTrue("应当是 Ok，实际：$out", out is ToolOutcome.Ok)
        return JSONObject((out as ToolOutcome.Ok).text)
    }

    // ── schema / 分组 / 档位 ────────────────────────────────────────────────

    @Test
    fun `schema 体检零违规`() {
        val s = spec()
        assertEquals(TableQueryTool.NAME, s.name)
        val issues = ToolSchemaRules.audit(s.name, s.inputSchema, s.description)
        assertTrue("schema 不合规：$issues", issues.isEmpty())
        assertTrue("描述超长会吃掉上下文预算", s.description.length <= 500)
    }

    @Test
    fun `归 DATA 组 默认可见面不破 12 条`() {
        assertEquals(ToolGroup.DATA, ToolGroupTable.GROUP_OF[TableQueryTool.NAME])
        assertTrue(TableQueryTool.NAME in BaopuToolCatalog.DEFAULT_ENABLED)
        assertTrue(BaopuToolCatalog.ENTRIES.any { it.name == TableQueryTool.NAME })
        // 分组表双向相等（孤儿工具直接红）——类加载时已核，这里显式再核一次
        assertEquals(null, ToolGroupTable.checkCoverage())
    }

    @Test
    fun `只读且可并行`() {
        val s = spec()
        assertTrue(s.readOnly)
        assertTrue(s.parallelSafe)
        assertEquals("R0", s.riskTier)
    }

    // ── 两条入口 ────────────────────────────────────────────────────────────

    @Test
    fun `一句话入口 回灌里带答案 出处 与行摘要`() {
        val j = run("question" to "每科平均分")
        assertTrue(j.getBoolean("ok"))
        assertEquals("ANSWER", j.getString("kind"))
        val answer = j.getString("answer")
        assertTrue(answer, answer.contains("语文 78.4"))
        assertTrue(answer, answer.contains("表：成绩表.xlsx"))
        assertTrue(answer, answer.contains("sheet「Sheet1」"))
        val ev = j.getJSONObject("evidence")
        assertEquals("成绩表.xlsx", ev.getString("file"))
        assertTrue(ev.getJSONArray("rows").length() > 0)
        assertTrue(j.getJSONArray("rows").length() > 0)
    }

    @Test
    fun `两条入口指向同一张表 同一条出处路径`() {
        val byQuestion = run("question" to "一共几个人")
        val byOp = run("op" to "count")
        assertTrue(byQuestion.getString("answer"), byQuestion.getString("answer").contains("一共 5 个人"))
        // 结构化 count 不按姓名去重 ⇒ 数的是行（10 行）；两条入口的**出处必须同源**
        assertTrue(byOp.getString("answer"), byOp.getString("answer").contains("一共 10 行"))
        assertEquals(
            byQuestion.getJSONObject("evidence").getString("file"),
            byOp.getJSONObject("evidence").getString("file"),
        )
        assertEquals(
            byQuestion.getJSONObject("evidence").getString("sheet"),
            byOp.getJSONObject("evidence").getString("sheet"),
        )
    }

    @Test
    fun `什么都不给 回一份能问什么的名册 而不是报错`() {
        val j = run()
        assertEquals("HELP", j.getString("kind"))
        assertTrue(j.getJSONArray("tables").length() == 1)
        val t = j.getJSONArray("tables").getJSONObject(0)
        assertEquals("成绩表.xlsx", t.getString("file"))
        assertEquals(1, t.getInt("headerRow"))
        assertTrue(j.getString("answer").contains("count"))
    }

    @Test
    fun `不认的 op 明说 不猜成最像的那个`() {
        val out = TableQueryTool.run(JSONObject().put("op", "median"), ws)
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("median"))
    }

    // ── 227 AnswerGate 复用 ─────────────────────────────────────────────────

    @Test
    fun `数字必须来自表 自查零可疑`() {
        listOf("一共几个人", "几个人不及格", "每科平均分", "最高分是谁", "前三名").forEach { q ->
            val j = run("question" to q)
            assertFalse("「$q」被 AnswerGate 报了可疑：${j.optJSONArray("selfCheck")}", j.has("selfCheck"))
        }
    }

    // ── PDF ────────────────────────────────────────────────────────────────

    @Test
    fun `PDF 立刻拒 连表都不打开`() {
        writeCsv(ws, "扫描件.pdf", "%PDF-1.4")
        val j = run("question" to "一共几个人", "table" to "扫描件.pdf")
        assertEquals("PDF", j.getString("kind"))
        assertTrue(j.getString("answer").contains(TableQueryTool.PDF_REFUSAL))
        assertEquals(0, j.getJSONArray("numbers").length())
    }

    @Test
    fun `路径越界一律拒`() {
        val out = TableQueryTool.run(JSONObject().put("question", "一共几个人").put("table", "../别的表.xlsx"), ws)
        assertTrue(out is ToolOutcome.Rejected)
    }

    // ── 筛选参数 ────────────────────────────────────────────────────────────

    @Test
    fun `filter 解析 六种比较符`() {
        val conds = TableQueryTool.parseFilter("科目=数学,分数<60,分数>=90,备注含优,班级≠三班")
        assertEquals(5, conds.size)
        assertEquals(Cmp.EQ, conds[0].cmp)
        assertEquals("数学", conds[0].value)
        assertEquals(Cmp.LT, conds[1].cmp)
        assertEquals(Cmp.GE, conds[2].cmp)
        assertEquals(Cmp.CONTAINS, conds[3].cmp)
        assertEquals(Cmp.NE, conds[4].cmp)
        assertTrue(TableQueryTool.parseFilter("看不懂的一段").isEmpty())
        assertTrue(TableQueryTool.parseFilter("").isEmpty())
    }

    @Test
    fun `filter 走结构化入口 能筛出正确的数`() {
        val j = run("op" to "count", "filter" to "科目=数学,分数<60")
        assertEquals("ANSWER", j.getString("kind"))
        assertTrue(j.getString("answer"), j.getString("answer").contains("1 行"))
    }

    // ── 日志 ────────────────────────────────────────────────────────────────

    @Test
    fun `日志行只写种类 表名 运算 行数`() {
        val a = TableQuery.askQuestion(
            TableRegistry(TableRegistry.homeUnder(ws), listOf(TableRoot(ws))),
            "一共几个人",
        )
        val line = TableQueryTool.logLine(a, "question")
        assertNotNull(line)
        assertTrue(line, line.startsWith("表格问答：ANSWER op=question"))
        assertTrue(line, line.contains("表=成绩表.xlsx"))
        assertFalse("日志不许带查询原话", line.contains("一共几个人"))
        assertFalse("日志不许带姓名", line.contains("张三"))
    }
}
