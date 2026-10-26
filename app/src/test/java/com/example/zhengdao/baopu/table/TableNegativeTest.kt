// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十二批 · **八条负例**（派工单 §3 逐条对号）。
//
// 每一条都在这里红：不是「写了注释提醒别这么做」，而是**真跑一遍那条错路，断言它走不通**。
// 顺序与派工单 §3 完全一致，便于蓝总管一条一条核。
package com.example.zhengdao.baopu.table

import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskMatrix
import com.example.zhengdao.baopu.agent.TableQueryTool
import com.example.zhengdao.baopu.logistics.HeaderOutcome
import com.example.zhengdao.baopu.logistics.LogisticsBasis
import com.example.zhengdao.baopu.logistics.LogisticsContract
import com.example.zhengdao.baopu.logistics.LogisticsField
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class TableNegativeTest {

    private lateinit var ws: File
    private lateinit var registry: TableRegistry

    @Before
    fun setUp() {
        ws = tempWorkspace("neg")
        writeScoreXlsx(ws)
        registry = TableRegistry(TableRegistry.homeUnder(ws), listOf(TableRoot(ws)))
    }

    @After
    fun tearDown() {
        wipe(ws)
    }

    // ── 负例 1：表里没有这个数却给了数（推算 / 补 0） ────────────────────────

    @Test
    fun `负例1 表里没有就说表里没有 不给 0 不推算`() {
        // ① 条件筛不出任何行
        val none = TableQuery.ask(
            registry,
            TableAsk(op = TableOp.COUNT, table = "成绩表.xlsx", where = listOf(Cond("科目", Cmp.EQ, "英语"))),
        )
        assertEquals(AnswerKind.NOT_IN_TABLE, none.kind)
        assertTrue(none.text(), none.text().contains("表里没有"))
        assertTrue("「没有」不许带任何数（补 0 就是这一条的反面）", none.numbers.isEmpty())
        assertFalse("答案里不许出现 0 这个读数", none.text().contains("：0"))

        // ② 问一个表里根本没有的列
        val noCol = TableQuery.ask(registry, TableAsk(op = TableOp.SUM, table = "成绩表.xlsx", column = "重量"))
        assertEquals(AnswerKind.NOT_IN_TABLE, noCol.kind)
        assertTrue(noCol.numbers.isEmpty())

        // ③ 一句话问到表里没有的词，也不许硬凑一个数
        val ask = TableQuery.askQuestion(registry, "货主是谁")
        assertNotEquals(AnswerKind.ANSWER, ask.kind)
    }

    // ── 负例 2：答案没有出处 ────────────────────────────────────────────────

    @Test
    fun `负例2 每一条 ANSWER 都必须带出处 文件 sheet 行号`() {
        val questions = listOf(
            "一共几个人", "几个人不及格", "每科平均分", "最高分是谁",
            "数学不及格的都有谁", "前三名", "科目有几种", "语文总分多少",
        )
        questions.forEach { q ->
            val a = TableQuery.askQuestion(registry, q)
            assertEquals("「$q」应当是正常答案", AnswerKind.ANSWER, a.kind)
            val t = a.text()
            assertTrue("「$q」没有文件出处：$t", t.contains("表：成绩表.xlsx"))
            assertTrue("「$q」没有 sheet：$t", t.contains("sheet「Sheet1」"))
            assertTrue("「$q」没有行号：$t", t.contains("行"))
            assertNotNull("「$q」的 Evidence 是空的", a.evidence)
            assertTrue("「$q」的出处行号是空的", a.evidence!!.rows.isNotEmpty())
        }
    }

    // ── 负例 3：多张候选表不问就猜 ──────────────────────────────────────────

    @Test
    fun `负例3 两张表都像 先问一句 不猜一张`() {
        writeScoreXlsx(ws, "成绩表B.xlsx")
        val reg = TableRegistry(TableRegistry.homeUnder(ws), listOf(TableRoot(ws)))
        val a = TableQuery.askQuestion(reg, "几个人不及格")
        assertEquals(AnswerKind.NEED_TABLE, a.kind)
        assertTrue("要问一句", a.hint.contains("候选表"))
        assertTrue("不许给出任何数", a.numbers.isEmpty())
        assertTrue("答案里不许有读数", !a.text().contains("不及格："))
    }

    // ── 负例 4：没有 join key 也硬联表 ─────────────────────────────────────

    @Test
    fun `负例4 没有 join key 就不联表 只回一句要哪一列`() {
        writeCsv(ws, "名单.csv", "姓名,班级\n张三,三班\n李四,三班\n")
        val reg = TableRegistry(TableRegistry.homeUnder(ws), listOf(TableRoot(ws)))
        val a = TableQuery.ask(
            reg,
            TableAsk(op = TableOp.COUNT, table = "成绩表.xlsx", joinWith = "名单.csv"),
        )
        assertEquals(AnswerKind.UNSUPPORTED, a.kind)
        assertTrue(a.headline, a.headline.contains("按哪一列对"))
        assertTrue("没联成表就不许有读数", a.numbers.isEmpty())
    }

    @Test
    fun `负例4 补 有明确 key 才联 且出处写明是联表`() {
        writeCsv(ws, "班级表.csv", "姓名,班级\n张三,一班\n李四,二班\n王五,一班\n赵六,二班\n孙七,一班\n")
        val reg = TableRegistry(TableRegistry.homeUnder(ws), listOf(TableRoot(ws)))
        val a = TableQuery.ask(
            reg,
            TableAsk(
                op = TableOp.COUNT,
                table = "成绩表.xlsx",
                joinWith = "班级表.csv",
                joinKey = "姓名",
                where = listOf(Cond("班级", Cmp.EQ, "一班")),
            ),
        )
        assertEquals(AnswerKind.ANSWER, a.kind)
        assertTrue("出处要说明这是联表", a.evidence!!.path.contains("⋈"))
        assertTrue(a.text(), a.text().contains("按 姓名"))
    }

    // ── 负例 5：改坏 195 物流冻结口径 ──────────────────────────────────────

    @Test
    fun `负例5 195 物流冻结口径一个字没动`() {
        // 硬列：只有「单号」
        assertEquals(setOf(LogisticsField.WAYBILL), LogisticsContract.HARD)
        // 范围对：方向与卸点至少一列
        assertEquals(setOf(LogisticsField.DIRECTION, LogisticsField.UNLOAD), LogisticsContract.SCOPE_PAIR)
        // 车数默认口径：一张配货单＝一辆车
        assertEquals("配货单", LogisticsBasis.ORDER.label)
        assertTrue(LogisticsBasis.ORDER.footnote.contains("按配货单去重"))
        // 配货单号不是单号的别名（194 实测 40 行运单不能被压成 9 行）
        val orderAliases = LogisticsContract.aliasesOf(LogisticsField.ORDER_NO)
        assertTrue(orderAliases.contains("配货单号"))
        assertFalse("「单号」不许被收进配货单号的别名", orderAliases.contains("单号"))
        // 合计列不认领（件数合计不能当件数加起来）
        assertFalse(LogisticsContract.aliasesOf(LogisticsField.PIECES).contains("件数合计"))
        // 缺硬列仍然拒读
        val rejected = LogisticsContract.resolve(listOf("方向", "卸点", "件数"))
        assertTrue("缺单号必须拒读，实际：$rejected", rejected is HeaderOutcome.Rejected)
    }

    // ── 负例 6：硬解 PDF ──────────────────────────────────────────────────

    @Test
    fun `负例6 PDF 直接说暂不支持 不打开 不解析`() {
        writeCsv(ws, "扫描件.pdf", "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n")
        val reg = TableRegistry(TableRegistry.homeUnder(ws), listOf(TableRoot(ws)))
        reg.refresh()
        // ① 登记层根本不认它
        assertTrue(reg.entries().none { it.rel.endsWith(".pdf") })
        // ② 指名道姓问它 ⇒ 只回一句「暂不支持」
        val a = TableQuery.askQuestion(reg, "一共几个人", table = "扫描件.pdf")
        assertEquals(AnswerKind.PDF, a.kind)
        assertTrue(a.headline, a.headline.contains(TableQueryTool.PDF_REFUSAL))
        assertTrue("PDF 不许有任何读数", a.numbers.isEmpty())
        // ③ 工具面同样一条出口
        TableQueryTool.resetForTest()
        val out = TableQueryTool.run(JSONObject().put("question", "一共几个人").put("table", "扫描件.pdf"), ws)
        assertTrue(out is com.example.zhengdao.baopu.agent.ToolOutcome.Ok)
        val body = (out as com.example.zhengdao.baopu.agent.ToolOutcome.Ok).text
        assertTrue(body, body.contains(TableQueryTool.PDF_REFUSAL))
    }

    // ── 负例 7：每次问答都全量重扫 ─────────────────────────────────────────

    @Test
    fun `负例7 第二次问同一个问题 一个文件都不重解析`() {
        val reg = TableRegistry(TableRegistry.homeUnder(ws), listOf(TableRoot(ws)))
        TableQuery.askQuestion(reg, "一共几个人")
        val afterFirst = reg.parseCount
        assertEquals(1, afterFirst)
        repeat(3) { TableQuery.askQuestion(reg, "几个人不及格") }
        assertEquals("重复问不许重解析（应走登记簿＋内存缓存）", afterFirst, reg.parseCount)
        val report = reg.refresh()
        assertEquals(0, report.parsed)
    }

    // ── 负例 8：手机号 / 姓名进了日志 ───────────────────────────────────────

    @Test
    fun `负例8 姓名与手机号只显示 不落盘 不进日志`() {
        writeCsv(ws, "通讯录.csv", PHONE_ROWS.joinToString("\n") { r -> r.joinToString(",") { it ?: "" } })
        val reg = TableRegistry(TableRegistry.homeUnder(ws), listOf(TableRoot(ws)))
        // 显示可以（答案里念得出名字）——「都有谁」这条问法会把姓名念出来
        val shown = TableQuery.askQuestion(reg, "都有谁", table = "通讯录.csv")
        assertEquals(AnswerKind.ANSWER, shown.kind)
        assertTrue(shown.text(), shown.text().contains("张三"))
        val a = TableQuery.askQuestion(reg, "一共几个人", table = "通讯录.csv")
        assertEquals(AnswerKind.ANSWER, a.kind)
        // 落盘不行：登记簿里一个取值都没有
        val body = File(File(File(ws, "抱朴记忆"), "表格"), "registry.json").readText(Charsets.UTF_8)
        assertFalse(body.contains("13800138000"))
        assertFalse(body.contains("张三"))
        // 日志不行：logLine 是纯函数，只吃「种类/表名/运算/行数」，拿不到单元格
        val line = TableQueryTool.logLine(a, "question")
        assertFalse("手机号进了日志：$line", line.contains("13800138000"))
        assertFalse("姓名进了日志：$line", line.contains("张三"))
        assertTrue(line, line.contains("表格问答：ANSWER"))
    }

    // ── 顺带：档位与分组不许漏 ──────────────────────────────────────────────

    @Test
    fun `table_query 在风险矩阵里有档 且是只读档`() {
        assertEquals(RiskClass.R0, RiskMatrix.baseOf("table_query"))
    }
}
