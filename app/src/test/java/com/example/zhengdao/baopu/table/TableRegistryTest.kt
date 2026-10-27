// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十二批 · 表登记（发现 / 不扫 PDF / 增量 / 落盘）。
package com.example.zhengdao.baopu.table

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class TableRegistryTest {

    private lateinit var ws: File

    @Before
    fun setUp() {
        ws = tempWorkspace("registry")
    }

    @After
    fun tearDown() {
        wipe(ws)
    }

    private fun registry() = TableRegistry(TableRegistry.homeUnder(ws), listOf(TableRoot(ws)))

    @Test
    fun `发现 xlsx csv tsv 三种表`() {
        writeScoreXlsx(ws)
        writeCsv(ws, "名单.csv", "姓名,班级\n张三,三班\n李四,三班\n")
        writeCsv(ws, "名单.tsv", "姓名\t班级\n张三\t三班\n李四\t三班\n")
        val reg = registry()
        val report = reg.refresh()
        assertEquals(3, report.scanned)
        assertEquals(3, report.parsed)
        assertEquals(
            listOf("名单.csv", "名单.tsv", "成绩表.xlsx"),
            reg.entries().map { it.rel }.sorted(),
        )
    }

    @Test
    fun `PDF 不扫 但要如实报跳了几个`() {
        writeScoreXlsx(ws)
        writeCsv(ws, "扫描件.pdf", "%PDF-1.4 假装是一份 PDF")
        writeCsv(ws, "sub/另一个.pdf", "%PDF-1.4")
        val reg = registry()
        val report = reg.refresh()
        assertEquals(1, report.scanned)
        assertEquals(2, report.pdfSkipped)
        assertTrue(reg.entries().none { it.rel.endsWith(".pdf") })
        assertFalse(TableRegistry.isTableFile("a.pdf"))
        assertTrue(TableRegistry.isPdf("a.PDF"))
    }

    @Test
    fun `登记簿落在 抱朴记忆 表格 registry json`() {
        writeScoreXlsx(ws)
        registry().refresh()
        val f = File(File(File(ws, "抱朴记忆"), "表格"), "registry.json")
        assertTrue("登记簿没落在约定位置：${f.absolutePath}", f.isFile)
        val body = f.readText(Charsets.UTF_8)
        assertTrue(body.contains("成绩表.xlsx"))
        assertTrue(body.contains("\"headerRow\": 0"))
        assertTrue(body.contains("姓名"))
    }

    @Test
    fun `登记簿不存任何单元格取值（姓名与手机号不许落盘）`() {
        writeCsv(ws, "通讯录.csv", PHONE_ROWS.joinToString("\n") { r -> r.joinToString(",") { it ?: "" } })
        registry().refresh()
        val body = File(File(File(ws, "抱朴记忆"), "表格"), "registry.json").readText(Charsets.UTF_8)
        assertFalse("姓名进了登记簿", body.contains("张三"))
        assertFalse("手机号进了登记簿", body.contains("13800138000"))
        assertTrue("列名应当留着（那是元数据）", body.contains("手机号"))
    }

    @Test
    fun `增量 文件没变就一个都不重解析`() {
        writeScoreXlsx(ws)
        val reg = registry()
        val first = reg.refresh()
        assertEquals(1, first.parsed)
        val afterFirst = reg.parseCount
        val second = reg.refresh()
        assertEquals("没有文件变化时 parsed 必须是 0", 0, second.parsed)
        assertEquals(1, second.reused)
        assertEquals("parseCount 不许涨", afterFirst, reg.parseCount)
    }

    @Test
    fun `增量 只有变了的那个文件重解析`() {
        writeScoreXlsx(ws, "成绩表.xlsx")
        writeCsv(ws, "名单.csv", "姓名,班级\n张三,三班\n")
        val reg = registry()
        reg.refresh()
        val before = reg.parseCount
        // 改名单：内容变长 ⇒ size 变 ⇒ 只有它该重解析
        writeCsv(ws, "名单.csv", "姓名,班级\n张三,三班\n李四,三班\n王五,三班\n")
        val report = reg.refresh()
        assertEquals(1, report.parsed)
        assertEquals(1, report.reused)
        assertEquals(before + 1, reg.parseCount)
    }

    @Test
    fun `删掉的表从登记簿里退出去`() {
        val f = writeScoreXlsx(ws)
        val reg = registry()
        reg.refresh()
        assertEquals(1, reg.entries().size)
        assertTrue(f.delete())
        val report = reg.refresh()
        assertEquals(1, report.removed)
        assertEquals(0, reg.entries().size)
    }

    @Test
    fun `表头认不出的表 登记里带着那句问话`() {
        writeCsv(ws, "没表头.csv", "1,2,3\n4,5,6\n")
        val reg = registry()
        reg.refresh()
        val entry = reg.entries().single()
        assertFalse(entry.known)
        val ask = entry.sheets.single().ask
        assertNotNull(ask)
        assertTrue(ask!!.contains("第几行是表头"))
    }

    @Test
    fun `换一个新实例 从 registry json 读回来（不重解析）`() {
        writeScoreXlsx(ws)
        registry().refresh()
        val fresh = registry()
        val report = fresh.refresh()
        assertEquals("磁盘上的登记簿应当被认下来", 0, report.parsed)
        assertEquals(1, report.reused)
        assertNotNull(fresh.modelOf("成绩表.xlsx"))
    }

    @Test
    fun `问一张没登记过的表 返回 null 而不是空表`() {
        writeScoreXlsx(ws)
        val reg = registry()
        reg.refresh()
        assertNull(reg.modelOf("不存在的表.xlsx"))
    }

    @Test
    fun `备份与回收站不进登记`() {
        writeScoreXlsx(ws)
        writeScoreXlsx(File(ws, "备份"), "旧成绩表.xlsx")
        writeScoreXlsx(File(ws, ".回收站"), "删掉的.xlsx")
        val reg = registry()
        reg.refresh()
        assertEquals(listOf("成绩表.xlsx"), reg.entries().map { it.rel })
    }

    @Test
    fun `243 原件区的表扫不到_资料库的表能扫到`() {
        writeCsv(ws, "资料库/原始/原件.csv", "a,b\n1,2\n")
        writeCsv(ws, "资料库/普通表.csv", "a,b\n1,2\n")
        val reg = registry()
        val report = reg.refresh()
        assertEquals(1, report.scanned)
        assertEquals(listOf("资料库/普通表.csv"), reg.entries().map { it.rel })
    }

    @Test
    fun `243 用户指定表头行会记住_重开仍在`() {
        writeCsv(ws, "资料库/表.csv", "姓名,班级\n张三,三班\n李四,三班\n")
        val reg = registry()
        reg.refresh()
        val default = reg.modelOf("资料库/表.csv")
        assertNotNull(default)
        assertEquals("\u9ed8\u8ba4\u5c31\u662f\u7b2c 1 \u884c", 1, default!!.headerRowNumber)

        assertTrue(reg.rememberHeaderRow("资料库/表.csv", null, 2))
        assertEquals(2, reg.modelOf("资料库/表.csv")!!.headerRowNumber)

        val reloaded = registry()
        reloaded.refresh()
        assertEquals(2, reloaded.modelOf("资料库/表.csv")!!.headerRowNumber)
    }
}
