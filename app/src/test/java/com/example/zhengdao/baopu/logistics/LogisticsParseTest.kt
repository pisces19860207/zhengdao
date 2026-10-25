// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 读取层测试（CSV / xlsx / 行解析）。
//
// 钉的是派工单 §1 的三句话：
//   「不引 POI，自己读 zip+XML，只取 sharedStrings 与第一张 sheet 的单元格文本」
//   「多 sheet 只读第一张并提示」（负例⑥）
//   「读不出的件数按未填，绝不补 0」（负例①的前半，后半在 LogisticsImportTest 里连着库钉）
// xlsx 用的是**真 zip 字节**（XlsxSheet 现拼），不是改名的文本文件——否则「读不出多 sheet」这条
// 测试只是在给测试代码打分。
package com.example.zhengdao.baopu.logistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogisticsParseTest {

    private fun grid(text: String): Grid = when (val r = LogisticsCsv.parse(text)) {
        is ReadOutcome.Ok -> r.grid
        is ReadOutcome.Failed -> error("这份 CSV 本该读通：${r.reason}")
    }

    private fun failReason(text: String): String = when (val r = LogisticsCsv.parse(text)) {
        is ReadOutcome.Ok -> error("本该拒读却读通了：${r.grid}")
        is ReadOutcome.Failed -> r.reason
    }

    // ── CSV ──────────────────────────────────────────────────────────────────

    @Test
    fun `csv 表头与行号按真实行号留证`() {
        val g = grid(sampleCsv())
        assertEquals(FULL_HEADER, g.header)
        assertEquals(3, g.rows.size)
        // 行号是「证据」那一半：第 2/3/4 行要跟用户在编辑器里数出来的一致
        assertEquals(listOf(2, 3, 4), g.rowNumbers)
        assertEquals(listOf("10231", "PH101", "北京朝阳", "三间房", "钢材", "12", "3.5", "4.2", "王师傅", "13800000001", "冀A12345", "已装"), g.rows[0])
    }

    @Test
    fun `csv 空行与只填横杠的行不算数据行但行不错位`() {
        val g = grid(
            """
            单号,方向,件数

            10231,北京朝阳,12
            -,-,-
            10232,上海青浦,8
            """.trimIndent() + "\n",
        )
        assertEquals(2, g.rows.size)
        // 表头是第 1 行；空行第 2 行、横杠行第 4 行都不收，但 10232 的行号仍是 5
        assertEquals(listOf(3, 5), g.rowNumbers)
    }

    @Test
    fun `csv 引号里的逗号换行与双引号都按一格读`() {
        val g = grid("单号,方向,货名\r\n10231,北京朝阳,\"钢材, 长料\"\r\n10232,上海,\"他说的\"\"到货\"\"\"\r\n")
        assertEquals("钢材, 长料", g.rows[0][2])
        assertEquals("他说的\"到货\"", g.rows[1][2])
    }

    @Test
    fun `csv 引号里的换行仍然计入行号`() {
        val g = grid("单号,方向,货名\n10231,北京朝阳,\"钢材\n长料\"\n10232,上海青浦,家具\n")
        assertEquals(2, g.rows.size)
        // 第一条数据起于第 2 行；第二条因为上一格含一个换行，真实行号是 4 而不是 3
        assertEquals(listOf(2, 4), g.rowNumbers)
        assertEquals("钢材\n长料", g.rows[0][2])
    }

    @Test
    fun `csv 非 utf8 按 gb18030 读并注记`() {
        val bytes = gbkBytes(sampleCsv())
        // 硬证据是「严格 UTF-8 解码失败」：这两串字节按 UTF-8 解必定解不出中文
        val r = LogisticsCsv.parseBytes(bytes)
        assertTrue("GB18030 的中文表头该读通：$r", r is ReadOutcome.Ok)
        val g = (r as ReadOutcome.Ok).grid
        assertEquals(FULL_HEADER, g.header)
        assertTrue("注记里要说明按哪把编码读的：${g.notes}", g.notes.any { it.contains("GB18030") })
    }

    @Test
    fun `csv 带 bom 时表头第一项不带杂字符`() {
        val withBom = ByteArray(3) { 0xEF.toByte() }.also { it[1] = 0xBB.toByte(); it[2] = 0xBF.toByte() } +
            sampleCsv().toByteArray(Charsets.UTF_8)
        val g = (LogisticsCsv.parseBytes(withBom) as ReadOutcome.Ok).grid
        assertEquals("单号", g.header[0])
    }

    @Test
    fun `csv 没闭合的引号拒读而不是读出一串糊`() {
        val reason = failReason("单号,方向\n10231,\"北京朝阳\n")
        assertTrue(reason, reason.contains("没闭合"))
    }

    @Test
    fun `csv 空文件与只有表头都拒读`() {
        assertTrue((LogisticsCsv.parseBytes(ByteArray(0)) as ReadOutcome.Failed).reason.contains("0 字节"))
        assertTrue(failReason("单号,方向,件数\n").contains("只有表头"))
        assertTrue(failReason("\n\n\n").contains("没有可读的行"))
    }

    @Test
    fun `csv 行比表头长时多出的格丢掉并点名行号`() {
        val g = grid("单号,方向\n10231,北京朝阳,多出来的一格\n")
        assertEquals(listOf("10231", "北京朝阳"), g.rows[0])
        assertTrue("多出的格不许塞进任何字段：${g.notes}", g.notes.any { it.contains("比表头多格") })
    }

    // ── xlsx（真 zip 字节）────────────────────────────────────────────────────

    private fun xlsxGrid(bytes: ByteArray): Grid = when (val r = LogisticsXlsx.parseBytes(bytes)) {
        is ReadOutcome.Ok -> r.grid
        is ReadOutcome.Failed -> error("这份 xlsx 本该读通：${r.reason}")
    }

    @Test
    fun `xlsx 与 csv 同一份数据读到同一张方格`() {
        val fromCsv = grid(sampleCsv())
        val fromXlsx = xlsxGrid(sampleXlsxBytes())
        assertEquals(fromCsv.header, fromXlsx.header)
        assertEquals(fromCsv.rows, fromXlsx.rows)
        assertEquals(fromCsv.rowNumbers, fromXlsx.rowNumbers)
    }

    @Test
    fun `xlsx 数字格不带尾巴的小数点也不带引号`() {
        val sheet = XlsxSheet()
        sheet.putRow(1, listOf("单号", "方向", "件数"))
        sheet.putRow(2, listOf("10231", "北京朝阳", "12"))
        val g = xlsxGrid(xlsxBytes(sheet))
        assertEquals("12", g.rows[0][2])
        // 件数解析层收的也是 12，不是 "12.0"
        val built = LogisticsRows.build(g, LogisticsContract.resolve(g.header) as HeaderOutcome.Ok)
        assertEquals(12, (built as RowBuild.Ok).rows[0].pieces)
    }

    @Test
    fun `xlsx 多张表只读第一张并明确提示另外几张叫什么`() {
        // 负例⑥：多 sheet 不提示 ⇒ 这里必须能在注记里读到「另外几张」的名字
        val sheet = XlsxSheet()
        sheet.putRow(1, listOf("单号", "方向", "件数"))
        sheet.putRow(2, listOf("10231", "北京朝阳", "12"))
        val g = xlsxGrid(xlsxBytes(sheet, extraSheetNames = listOf("回单", "对账")))
        assertEquals(listOf("10231", "北京朝阳", "12"), g.rows[0])
        val note = g.notes.firstOrNull { it.contains("只读了第一张") }
        assertNotNull("多 sheet 必须提示：${g.notes}", note)
        assertTrue(note!!.contains("回单"))
        assertTrue(note.contains("对账"))
        assertTrue(note.contains("配货单"))
    }

    @Test
    fun `xlsx 不是合法包时拒读并指一条出路`() {
        val r = LogisticsXlsx.parseBytes("这不是 zip".toByteArray(Charsets.UTF_8))
        assertTrue("$r", r is ReadOutcome.Failed)
        assertTrue((r as ReadOutcome.Failed).reason.contains("另存为 CSV"))
    }

    @Test
    fun `xlsx 缺 workbook 时不猜哪张是第一张`() {
        // workbook.xml 是唯一说明「页签顺序」的地方；没有它就只能按文件名猜，
        // 猜错等于把第二张表当今天的单子读——所以整份不读
        val bytes = zipOf(
            "[Content_Types].xml" to "<?xml version=\"1.0\"?><Types/>",
            "xl/worksheets/sheet1.xml" to "<worksheet><sheetData><row r=\"1\"><c r=\"A1\"><v>1</v></c></row></sheetData></worksheet>",
        )
        val r = LogisticsXlsx.parseBytes(bytes)
        assertTrue("$r", r is ReadOutcome.Failed)
        assertTrue((r as ReadOutcome.Failed).reason.contains("workbook"))
    }

    @Test
    fun `xlsx 关系表指不到第一张表时不兜底`() {
        // 包里两张 worksheet、rels 又对不上 ⇒ 没有「唯一一张」可兜，必须拒读（兜了就是猜）
        val bytes = zipOf(
            "xl/workbook.xml" to "<workbook><sheets>" +
                "<sheet name=\"配货单\" sheetId=\"1\" r:id=\"rId9\"/>" +
                "<sheet name=\"回单\" sheetId=\"2\" r:id=\"rId8\"/>" +
                "</sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships/>",
            "xl/worksheets/sheet1.xml" to "<worksheet><sheetData/></worksheet>",
            "xl/worksheets/sheet2.xml" to "<worksheet><sheetData/></worksheet>",
        )
        val r = LogisticsXlsx.parseBytes(bytes)
        assertTrue("$r", r is ReadOutcome.Failed)
        assertTrue((r as ReadOutcome.Failed).reason.contains("认不出第一张表"))
    }

    // ── 行解析（负例①：读不出绝不变成 0）──────────────────────────────────────

    private fun rowsOf(header: List<String>, vararg lines: List<String>): RowBuild {
        // 一格一格用逗号接，含逗号/引号的格子按 CSV 规矩括起来（「1,280」这种千分位
        // 必须**真的待在一格里**，否则这条负例打的是三个格子，测不到判据）
        fun cellOf(c: String): String =
            if (c.contains(',') || c.contains('"') || c.contains('\n')) "\"${c.replace("\"", "\"\"")}\"" else c
        val text = (listOf(header) + lines).joinToString("\n") { r -> r.joinToString(",") { cellOf(it) } } + "\n"
        val g = LogisticsCsv.parse(text)
        val grid = (g as ReadOutcome.Ok).grid
        return LogisticsRows.build(grid, LogisticsContract.resolve(grid.header) as HeaderOutcome.Ok)
    }

    @Test
    fun `件数写的不是一串数字时读不出而不是补零`() {
        val ok = rowsOf(listOf("单号", "方向", "件数"), listOf("10231", "北京朝阳", "一批")) as RowBuild.Ok
        val row = ok.rows[0]
        assertNull("「一批」不是数字，件数必须是 null 而不是 0", row.pieces)
        assertEquals("原文要留着，答案里要念出来", "一批", row.piecesRaw)
        assertTrue("注记要点名哪一格读不出：${ok.notes}", ok.notes.any { it.contains("不是数字") })
    }

    @Test
    fun `件数小数只在浮点尾巴上收成整数`() {
        // xlsx 的数字格常写 12，WPS 有的版本写 12.0 —— 那是同一个数的两种写法
        val zero = rowsOf(listOf("单号", "方向", "件数"), listOf("10231", "北京朝阳", "12.0")) as RowBuild.Ok
        assertEquals(12, zero.rows[0].pieces)
        // 12.5 不是「12 件多一点」，是这一格没法进合计：取整就是猜
        val half = rowsOf(listOf("单号", "方向", "件数"), listOf("10231", "北京朝阳", "12.5")) as RowBuild.Ok
        assertNull(half.rows[0].pieces)
        assertEquals("12.5", half.rows[0].piecesRaw)
    }

    @Test
    fun `件数带量词收千分位不收`() {
        val unit = rowsOf(listOf("单号", "方向", "件数"), listOf("10231", "北京朝阳", "12 件")) as RowBuild.Ok
        assertEquals(12, unit.rows[0].pieces)
        // 「1,280」看着像 1280，但逗号在导出里也可能是两个数挤在一格——不认，按读不出报出去
        val comma = rowsOf(listOf("单号", "方向", "件数"), listOf("10231", "北京朝阳", "1,280")) as RowBuild.Ok
        assertNull(comma.rows[0].pieces)
        assertTrue("注记要把它报出去：${comma.notes}", comma.notes.any { it.contains("不是数字") })
    }

    @Test
    fun `状态这句话不认识时按未填而不是猜一档`() {
        val ok = rowsOf(listOf("单号", "方向", "状态"), listOf("10231", "北京朝阳", "在路上")) as RowBuild.Ok
        assertEquals(LogisticsStatus.UNKNOWN, ok.rows[0].status)
        assertEquals("在路上", ok.rows[0].statusRaw)
        assertTrue("注记要报这句话不认识：${ok.notes}", ok.notes.any { it.contains("这句话不认识") })
    }

    @Test
    fun `状态关键词逐条只归一档`() {
        // 表驱动：这几对如果同时进两档，「已提车」到底算已装还是已发就取决于遍历顺序
        listOf(
            "已装" to LogisticsStatus.LOADED,
            "装车完成" to LogisticsStatus.LOADED,
            "待发" to LogisticsStatus.PENDING,
            "未发货" to LogisticsStatus.PENDING,
            "已发" to LogisticsStatus.SENT,
            "在途" to LogisticsStatus.SENT,
            "缺件" to LogisticsStatus.SHORT,
            "" to LogisticsStatus.UNKNOWN,
            "-" to LogisticsStatus.UNKNOWN,
            "随便一句话" to LogisticsStatus.UNKNOWN,
        ).forEach { (raw, expect) ->
            assertEquals("「$raw」", expect, LogisticsStatus.of(raw))
        }
    }

    @Test
    fun `一格并列两台车拆开存`() {
        val ok = rowsOf(listOf("单号", "方向", "车牌"), listOf("10231", "北京朝阳", "冀A12345/冀B67890")) as RowBuild.Ok
        assertEquals(listOf("冀A12345", "冀B67890"), ok.rows[0].plates)
    }

    @Test
    fun `单号去空白转大写并剥掉表格的文本标记`() {
        assertEquals("10231A", LogisticsRows.waybillOf(" '10231a "))
        assertEquals("", LogisticsRows.waybillOf("   "))
    }

    @Test
    fun `单号那格只填横杠算没单号整行不收`() {
        // 横杠在读取层就统一成空（LogisticsGrid.cell），所以它走的是「这行没单号」那条路，
        // 而不是留下一个叫「-」的单号去当去重键
        val failed = rowsOf(listOf("单号", "方向"), listOf("-", "北京朝阳"))
        assertTrue("$failed", failed is RowBuild.Failed)
    }

    @Test
    fun `整行没有单号时不收这一行并报出行号`() {
        val ok = rowsOf(
            listOf("单号", "方向"),
            listOf("10231", "北京朝阳"),
            listOf("", "上海青浦"),
        ) as RowBuild.Ok
        assertEquals(1, ok.rows.size)
        assertTrue("要报出没收的行号：${ok.notes}", ok.notes.any { it.contains("没有单号") && it.contains("3") })
    }

    @Test
    fun `一行都认不出单号时整张表拒收`() {
        val failed = rowsOf(listOf("单号", "方向"), listOf("", "北京朝阳"))
        assertTrue("$failed", failed is RowBuild.Failed)
        assertTrue((failed as RowBuild.Failed).reason.contains("一行都没记"))
    }

    @Test
    fun `缺列时对应字段全为空而不是拿别列填上`() {
        // 没有车牌列：plates 必须是空，不许「看哪列像车牌就当成车牌」（负例：错位填充）
        val ok = rowsOf(listOf("单号", "方向", "备注"), listOf("10231", "北京朝阳", "冀A12345")) as RowBuild.Ok
        assertEquals(emptyList<String>(), ok.rows[0].plates)
        assertEquals("", ok.rows[0].goods)
    }
}
