// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十二批 · 通用表模型的判据（表头行 / 弱类型 / 主键 / warnings / 行号）。
package com.example.zhengdao.baopu.table

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TableModelTest {

    // ── 表头行识别 ──────────────────────────────────────────────────────────

    @Test
    fun `表头在第 1 行的普通表`() {
        val m = scoreModel()
        assertEquals(0, m.headerRow)
        assertEquals(1, m.headerRowNumber)
        assertEquals(listOf("姓名", "科目", "分数"), m.columns.map { it.name })
        assertEquals(10, m.rowCount)
        assertTrue(m.known)
        assertNull(m.headerAsk)
    }

    @Test
    fun `表头在第 3 行（前两行是标题）也要认出来`() {
        val m = modelOf(titledScoreRows())
        assertEquals(2, m.headerRow)
        assertEquals(3, m.headerRowNumber)
        assertEquals(listOf("姓名", "科目", "分数"), m.columns.map { it.name })
        assertEquals(10, m.rowCount)
    }

    @Test
    fun `合并单元格表头 中间有空洞 要标 header_unknown 并问第几行`() {
        val m = modelOf(
            listOf("姓名", null, "分数"),
            listOf("张三", "语文", "88"),
            listOf("李四", "语文", "55"),
        )
        assertFalse(m.known)
        assertEquals(-1, m.headerRow)
        assertNotNull(m.headerAsk)
        assertTrue(m.headerAsk!!.contains(TableHeaderAsk.ASK))
        assertTrue(m.headerAsk!!.contains("合并单元格"))
        // 认不出就**一行都不给**：不许拿半张表硬答
        assertEquals(0, m.rowCount)
        assertTrue(m.columns.isEmpty())
    }

    @Test
    fun `没有表头（前几行都是数据）要问第几行是表头 不猜`() {
        val m = modelOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
        )
        assertFalse(m.known)
        assertNotNull(m.headerAsk)
        assertTrue(m.headerAsk!!.contains("第几行是表头"))
    }

    @Test
    fun `两行都像表头时按最上面那行算 并把这件事写进 warnings`() {
        // 全文本表：标题行 3 格、表头行 3 格——同宽 ⇒ 前一行就是表头（标题行天生比表头窄）。
        // 这是**披露过的默认**，不是猜：warnings 里明写「第 2 行也像表头，按第 1 行算」。
        val m = modelOf(
            listOf("姓名", "科目", "等级"),
            listOf("姓名", "科目", "等级"),
            listOf("张三", "语文", "优"),
            listOf("李四", "数学", "良"),
        )
        assertTrue(m.known)
        assertEquals(0, m.headerRow)
        assertTrue(m.warnings.any { it.contains("也像表头") && it.contains("第 1 行") })
    }

    @Test
    fun `用户指定第 3 行是表头 就按第 3 行 不再判`() {
        val m = modelOf(titledScoreRows(), headerRow = 3)
        assertEquals(2, m.headerRow)
        assertEquals(listOf("姓名", "科目", "分数"), m.columns.map { it.name })
    }

    @Test
    fun `用户指定的行号越界 要说清 不硬用`() {
        val m = modelOf(titledScoreRows(), headerRow = 99)
        assertFalse(m.known)
        assertTrue(m.headerAsk!!.contains("99"))
        assertTrue(m.headerAsk!!.contains("第几行是表头"))
    }

    // ── 弱类型 ──────────────────────────────────────────────────────────────

    @Test
    fun `整列都是数字才算数字列`() {
        val m = scoreModel()
        assertEquals(ColumnType.NUMBER, m.columns[2].type)
        assertEquals(ColumnType.TEXT, m.columns[0].type)
        assertEquals(ColumnType.TEXT, m.columns[1].type)
    }

    @Test
    fun `混进一格文本 这列就是文本列（不把缺考当 0）`() {
        val m = modelOf(
            listOf("姓名", "分数"),
            listOf("张三", "88"),
            listOf("李四", "缺考"),
            listOf("王五", "90"),
        )
        assertEquals(ColumnType.TEXT, m.columns[1].type)
    }

    @Test
    fun `手机号与车牌各认各的类型 不混进数字列`() {
        val m = modelOf(
            listOf("姓名", "手机号", "车牌"),
            listOf("张三", "13800138000", "冀A12345"),
            listOf("李四", "13900139000", "京B54321"),
        )
        assertEquals(ColumnType.PHONE, m.columns[1].type)
        assertEquals(ColumnType.PLATE, m.columns[2].type)
    }

    @Test
    fun `千分位与百分号也算数字`() {
        assertEquals(1234.5, TableValues.numberOrNull("1,234.5")!!, 0.0001)
        assertEquals(90.0, TableValues.numberOrNull("90分")!!, 0.0001)
        assertEquals(35.0, TableValues.numberOrNull("35%")!!, 0.0001)
        assertNull(TableValues.numberOrNull("缺考"))
        assertNull(TableValues.numberOrNull(""))
    }

    // ── 主键 / warnings / 行号 ───────────────────────────────────────────────

    @Test
    fun `整列有值且每行都不同才算主键候选`() {
        val m = modelOf(
            listOf("学号", "姓名"),
            listOf("S01", "张三"),
            listOf("S02", "李四"),
        )
        // 两列都唯一 ⇒ 都是候选；名字像编号的排前面（只影响先后，不影响是否候选）
        assertTrue(m.keys.contains("学号"))
        assertEquals("学号", m.keys.first())
    }

    @Test
    fun `空列要进 warnings 且是 BLANK`() {
        val m = modelOf(
            listOf("姓名", "备注"),
            listOf("张三", ""),
            listOf("李四", ""),
        )
        assertEquals(ColumnType.BLANK, m.columns[1].type)
        assertTrue(m.warnings.any { it.contains("空列") })
    }

    @Test
    fun `重名列要进 warnings`() {
        val m = modelOf(
            listOf("分数", "分数"),
            listOf("88", "90"),
        )
        assertTrue(m.warnings.any { it.contains("重名") })
    }

    @Test
    fun `整行全空的那一行跳过 并说明`() {
        val m = modelOf(
            listOf("姓名", "分数"),
            listOf("张三", "88"),
            listOf(null, null),
            listOf("李四", "90"),
        )
        assertEquals(2, m.rowCount)
        assertTrue(m.warnings.any { it.contains("整行全空") })
    }

    @Test
    fun `行号是文件里的真行号 不是下标现推`() {
        // xlsx 的 <row r> 会跳号（中间删过行）：数据行是第 2 行和第 5 行
        val m = TableModelBuilder.build(
            "t.xlsx",
            "S",
            gridOf(
                listOf(listOf<String?>("姓名", "分数"), listOf("张三", "88"), listOf("李四", "90")),
                rowNumbers = listOf(1, 2, 5),
            ),
        )
        assertEquals(listOf(2, 5), m.rows.map { it.no })
    }

    @Test
    fun `比表头多出来的格子按没读到处理 并点名行号`() {
        val m = modelOf(
            listOf("姓名", "分数"),
            listOf("张三", "88", "多余的"),
            listOf("李四", "90"),
        )
        assertEquals(2, m.columns.size)
        assertTrue(m.warnings.any { it.contains("多格") && it.contains("第 2 行") })
    }

    @Test
    fun `空表不装作有表`() {
        val m = TableModelBuilder.build("t.xlsx", "S", gridOf(emptyList()))
        assertFalse(m.known)
        assertNotNull(m.headerAsk)
    }
}
