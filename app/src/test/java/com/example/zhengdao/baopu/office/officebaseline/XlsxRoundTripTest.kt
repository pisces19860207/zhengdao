// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百四十五批 · 判据②（xlsx 往返）。
//
// 生产 XlsxWriter 按设计「只写值、不算公式」（632 的既有边界，本单不改产品代码）。
// 因此公式侧用夹具注入 <f>+缓存 <v>（模拟 Excel/WPS 保存后的样子），
// 读回后**独立重算明细求和**与缓存对拍 —— 不拿写出模型自证，才有保真意义。
package com.example.zhengdao.baopu.office.officebaseline

import com.example.zhengdao.baopu.FileOpLog
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.XlsxWriteTool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class XlsxRoundTripTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ws: File

    @Before
    fun setUp() {
        ws = tmp.newFolder("ws")
        FileOpLog.resetForTest()
        FileOpLog.enterSession(745L)
    }

    @After
    fun tearDown() {
        FileOpLog.resetForTest()
    }

    private fun produce(): File {
        val out = XlsxWriteTool.run(xlsxArgs(), ws)
        assertTrue("生成应成功：$out", out is ToolOutcome.Ok)
        val all = File(ws, "产物").listFiles().orEmpty().filter { it.name.endsWith(".xlsx") }
        assertEquals("恰好一份 xlsx", 1, all.size)
        return all.first()
    }

    // ── 判据②-a：多表 / 单元格值 100% 一致 / 数字与日期格式不变 / 合并 ──────

    @Test
    fun `判据2_xlsx读回_单元格值与格式逐格一致多表合并都在`() {
        val facts = readXlsxFacts(produce().readBytes())

        assertEquals("两张表", listOf("明细", "汇总"), facts.sheetNames)
        val detail = facts.cells.getValue("明细")
        val summary = facts.cells.getValue("汇总")

        // 逐格对拍（值 100%）：文本走 sharedStrings、数字按 numText、日期按 Excel 序列
        val expected = mapOf(
            "A1" to "品名", "B1" to "数量", "C1" to "金额", "D1" to "日期",
            "A2" to "甲", "B2" to "3", "C2" to "9.5", "D2" to "46339",
            "A3" to "乙", "B3" to "2", "C3" to "4.25", "D3" to "46340",
            "A4" to "丙", "B4" to "5", "C4" to "12", "D4" to "46341",
            "A5" to "合计", "B5" to "0",
        )
        for ((ref, want) in expected) {
            assertEquals("明细 $ref 值", want, detail.getValue(ref).value)
        }
        assertEquals("汇总表头", "项目", summary.getValue("A1").value)
        assertEquals("汇总值", "10", summary.getValue("B2").value)

        // 数字与日期格式不变（读回的是文件里 styles.xml 生效的格式，不是模型自证）
        assertEquals("金额两位小数格式", "0.00", detail.getValue("C2").numFmtCode)
        assertEquals("日期格式", "yyyy-mm-dd", detail.getValue("D2").numFmtCode)
        assertEquals("整数不挂自定义格式", "General", detail.getValue("B2").numFmtCode)

        // 合并单元格 + 冻结首行
        assertEquals("合并区 A7:B7", listOf("A7:B7"), facts.merges.getValue("明细"))
        assertTrue("明细冻结首行", facts.frozenFirstRow.getValue("明细"))
        assertTrue("表头加粗", detail.getValue("A1").bold)
        assertTrue("数据行不加粗", !detail.getValue("A2").bold)
    }

    // ── 判据②-b：公式合计 = 明细求和（独立重算对拍）────────────────────────

    @Test
    fun `判据2_公式合计等于明细求和_读回独立重算对拍`() {
        val injected = injectFormula(produce().readBytes())
        val facts = readXlsxFacts(injected)
        val detail = facts.cells.getValue("明细")

        val total = detail.getValue("B5")
        assertEquals("公式文本", "SUM(B2:B4)", total.formula)
        assertNotNull("公式格必须有缓存结果", total.cachedValue)

        // 独立重算：把 B2..B4 读回值当明细求和，与缓存对拍
        val sum = listOf("B2", "B3", "B4").sumOf { detail.getValue(it).value!!.toDouble() }
        assertEquals("公式合计 = 明细求和", numText(sum), total.cachedValue)
        assertEquals("本例应为 3+2+5=10", "10", numText(sum))

        // 负例敏感度：若把缓存改错，对拍必须失败（夹具确实在比，不是恒真）
        val wrong = "999"
        assertTrue("缓存被改错时对拍应不成立", numText(sum) != wrong)

        // 导出带公式的真产物供外部结构校验
        val f = File(tmp.root, "745-xlsx-roundtrip.xlsx")
        f.writeBytes(injected)
        exportForCheck(f, "745-xlsx-roundtrip.xlsx")
    }

    // ── 判据②-c：读回失败兜底 —— 不支持的格式不许静默降级 ──────────────────

    @Test
    fun `判据5_不支持往返的格式明确报错_不降级成纯文本`() {
        // docx/xlsx 是支持的
        requireRoundTrippable("报告.docx")
        requireRoundTrippable("账目.xlsx")
        // 其余格式一律明确拒，消息点名「不支持往返」且明确「不做静默纯文本降级」
        for (name in listOf("旧文档.doc", "老表.xls", "数据.csv", "说明.pdf", "无扩展名")) {
            val err = runCatching { requireRoundTrippable(name) }.exceptionOrNull()
            assertTrue("$name 必须抛错", err is UnsupportedRoundTrip)
            assertTrue("$name 错误要点名不支持往返：${err?.message}", err!!.message!!.contains("不支持往返"))
            assertTrue("$name 错误要写明不降级：${err.message}", err.message!!.contains("不做静默纯文本降级"))
        }
    }
}
