// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十二批 · **验收正例**：拿一张**非物流**的真表（学生成绩表 姓名/科目/分数）
// 跑通派工单 §2.0 点名的四问：「一共几个人 / 几个人不及格 / 每科平均分 / 最高分是谁」。
//
// 每个断言都同时钉三件事：① 数对不对（能手算核）；② 有没有出处（文件·sheet·行号）；
// ③ 口径有没有写出来（不及格线 60、按姓名去重）。缺一条这条用例就红。
package com.example.zhengdao.baopu.table

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class TableAcceptanceTest {

    private lateinit var ws: File
    private lateinit var registry: TableRegistry

    @Before
    fun setUp() {
        ws = tempWorkspace("accept")
        writeScoreXlsx(ws)
        registry = TableRegistry(TableRegistry.homeUnder(ws), listOf(TableRoot(ws)))
    }

    @After
    fun tearDown() {
        wipe(ws)
    }

    private fun ask(question: String): TableAnswer = TableQuery.askQuestion(registry, question)

    // ── 四问（派工单 §2.0 点名的验收口径） ──────────────────────────────────

    @Test
    fun `一共几个人`() {
        val a = ask("一共几个人")
        assertEquals(AnswerKind.ANSWER, a.kind)
        assertTrue(a.text(), a.text().contains("一共 5 个人"))
        // 一人两行（语文/数学）⇒ 必须写明「按姓名去重」，否则 5 个人会被数成 10 行
        assertTrue(a.text(), a.text().contains("按「姓名」去重"))
        assertProvenance(a)
        assertEquals(listOf(5.0, 10.0), a.numbers)
    }

    @Test
    fun `几个人不及格`() {
        val a = ask("几个人不及格")
        assertEquals(AnswerKind.ANSWER, a.kind)
        assertTrue(a.text(), a.text().contains("2 个人不及格"))
        // 口径必须写出来：及格线 60 不是从表里读的
        assertTrue(a.text(), a.text().contains("不及格按 <60 分算"))
        assertProvenance(a)
        assertEquals(listOf(2.0, 3.0), a.numbers)
    }

    @Test
    fun `每科平均分`() {
        val a = ask("每科平均分")
        assertEquals(AnswerKind.ANSWER, a.kind)
        val t = a.text()
        assertTrue(t, t.contains("语文 78.4"))
        assertTrue(t, t.contains("数学 74.4"))
        assertTrue(t, t.contains("分组列：科目"))
        assertProvenance(a)
        assertTrue(a.numbers.contains(78.4))
        assertTrue(a.numbers.contains(74.4))
    }

    @Test
    fun `最高分是谁`() {
        val a = ask("最高分是谁")
        assertEquals(AnswerKind.ANSWER, a.kind)
        val t = a.text()
        assertTrue(t, t.contains("最高 100"))
        assertTrue(t, t.contains("孙七"))
        assertProvenance(a)
        assertEquals(listOf(1.0, 100.0), a.numbers)
    }

    // ── 同一张表上的其余能力（§2.0 的能力清单逐条过） ──────────────────────

    @Test
    fun `筛选加列举 数学不及格的都有谁`() {
        val a = ask("数学不及格的都有谁")
        assertEquals(AnswerKind.ANSWER, a.kind)
        val t = a.text()
        assertTrue(t, t.contains("李四"))
        assertTrue(t, t.contains("科目=数学"))
        assertTrue(t, t.contains("分数<60"))
        assertProvenance(a)
    }

    @Test
    fun `求和 总分多少`() {
        val a = ask("语文总分多少")
        // 「语文总分多少」里的「语文」是科目列的一个真实取值 ⇒ 等值筛选；求和的是分数列
        assertEquals(AnswerKind.ANSWER, a.kind)
        assertTrue(a.text(), a.text().contains("392"))
        assertProvenance(a)
    }

    @Test
    fun `排序前 N 前三名`() {
        val a = ask("前三名")
        assertEquals(AnswerKind.ANSWER, a.kind)
        val t = a.text()
        assertTrue(t, t.contains("前 3 名"))
        assertTrue(t, t.contains("孙七"))
        assertProvenance(a)
    }

    @Test
    fun `有几种 科目有几种`() {
        val a = ask("科目有几种")
        assertEquals(AnswerKind.ANSWER, a.kind)
        assertTrue(a.text(), a.text().contains("2 种"))
        assertProvenance(a)
    }

    @Test
    fun `结构化入口 与 一句话入口 同一张表同一条出话路径`() {
        val byQuestion = ask("一共几个人")
        val byOp = TableQuery.ask(registry, TableAsk(op = TableOp.COUNT, subject = "个人", distinctBy = "姓名"))
        assertEquals(byQuestion.text(), byOp.text())
    }

    @Test
    fun `csv 与 xlsx 走同一个模型`() {
        writeCsv(ws, "成绩表-csv.csv", scoreCsvText())
        val reg = TableRegistry(TableRegistry.homeUnder(ws), listOf(TableRoot(ws)))
        reg.refresh()
        val csv = reg.modelOf("成绩表-csv.csv")
        assertNotNull(csv)
        assertEquals(listOf("姓名", "科目", "分数"), csv!!.columns.map { it.name })
        assertEquals(ColumnType.NUMBER, csv.columns[2].type)
        assertEquals(10, csv.rowCount)
    }

    @Test
    fun `表头在第 3 行的表 也能直接问`() {
        writeScoreXlsx(ws, "带标题的成绩表.xlsx", titled = true)
        val reg = TableRegistry(TableRegistry.homeUnder(ws), listOf(TableRoot(ws)))
        val a = TableQuery.askQuestion(reg, "一共几个人", table = "带标题的成绩表.xlsx")
        assertEquals(AnswerKind.ANSWER, a.kind)
        assertTrue(a.text(), a.text().contains("一共 5 个人"))
        // 出处要指到真表头那一行（第 3 行）
        assertEquals(3, a.evidence!!.headerRow)
    }

    @Test
    fun `指定了 sheet 名 出处就念那个 sheet`() {
        val a = TableQuery.ask(registry, TableAsk(op = TableOp.COUNT, table = "成绩表.xlsx", sheet = "Sheet1"))
        assertEquals(AnswerKind.ANSWER, a.kind)
        assertTrue(a.text(), a.text().contains("sheet「Sheet1」"))
    }

    // ── 共用断言 ────────────────────────────────────────────────────────────

    /** 出处三件套：文件名 + sheet + 行号，缺一个就红（负例 2 的正面）。 */
    private fun assertProvenance(a: TableAnswer) {
        val t = a.text()
        assertTrue("答案没有「表：」出处：$t", t.contains("表："))
        assertTrue("答案没有 sheet：$t", t.contains("sheet「"))
        assertTrue("答案没有行号：$t", t.contains("行"))
        assertNotNull("ANSWER 必须带 Evidence", a.evidence)
        assertFalse("ANSWER 的出处不许是空行号", a.evidence!!.rows.isEmpty())
        assertTrue("出处的文件必须是这张表", a.evidence!!.path.endsWith(".xlsx"))
    }
}
