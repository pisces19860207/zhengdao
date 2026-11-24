// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百一十批 · 两态对照表：五行齐才成表，缺行点名、重复红、表外红，唯一出口过闸。
package com.example.zhengdao.baopu.state.erect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ErectSheetTest {

    private fun allRows() = listOf(
        ErectFixtures.size(), ErectFixtures.angle(), ErectFixtures.hardness(),
        ErectFixtures.prepuce(), ErectFixtures.corona(),
    )

    @Test
    fun `五行齐才成表_顺序固定`() {
        val sheet = ErectFixtures.sheet()
        assertEquals(5, sheet.size)
        assertEquals(SheetRow.ALL_KEYS, sheet.ordered().map { it.key })
        assertEquals(ErectSheet.EXPECTED_SIZE, SheetRow.ALL_KEYS.size)
    }

    @Test
    fun `缺任意一行即红_且点名缺哪行`() {
        val full = allRows()
        SheetRow.ALL_KEYS.indices.forEach { missingAt ->
            val short = full.filterIndexed { i, _ -> i != missingAt }
            val e = assertThrows(IllegalArgumentException::class.java) {
                ErectSheet.fromRows(short)
            }
            assertTrue("应点名缺行：${e.message}", e.message!!.contains(ErectSheet.MISSING_PREFIX))
            assertTrue("应点名具体行：${e.message}", e.message!!.contains(SheetRow.ALL_KEYS[missingAt]))
        }
    }

    @Test
    fun `同一行记两次即红`() {
        val dup = allRows() + ErectFixtures.hardness()
        val e = assertThrows(IllegalArgumentException::class.java) { ErectSheet.fromRows(dup) }
        assertTrue(e.message!!.contains(ErectSheet.DUPLICATE_REJECT))
    }

    @Test
    fun `空表即红_五行全点名`() {
        val e = assertThrows(IllegalArgumentException::class.java) { ErectSheet.fromRows(emptyList()) }
        SheetRow.ALL_KEYS.forEach { assertTrue(e.message!!.contains(it)) }
    }

    @Test
    fun `唯一出口五行全出_无数值`() {
        val line = ErectFixtures.sheet().line()
        SheetRow.ALL_KEYS.forEach { assertTrue("出口缺 $it：$line", line.contains(it)) }
        assertTrue(line.contains(PENDING_SUBZONE))
        assertTrue(line.contains(PENDING_SECTION))
        ErectGuard.rejectFactor(line)
        ErectGuard.rejectMerged(line)
    }

    @Test
    fun `硬度与大小是两行不是一格`() {
        val line = ErectFixtures.sheet().line()
        val sizeLine = line.lines().first { it.startsWith(SheetRow.SIZE) }
        val hardLine = line.lines().first { it.startsWith(SheetRow.HARDNESS) }
        assertTrue(sizeLine.contains("分型="))
        assertTrue(sizeLine.contains("增长="))
        assertTrue(hardLine.contains("硬度="))
        assertTrue(hardLine.contains(ErectFixtures.hardness().tier.label))
    }

    @Test
    fun `row按key取行`() {
        val sheet = ErectFixtures.sheet()
        assertEquals(SheetRow.SIZE, sheet.row(SheetRow.SIZE).key)
        assertEquals(SheetRow.CORONA, sheet.row(SheetRow.CORONA).key)
    }
}
