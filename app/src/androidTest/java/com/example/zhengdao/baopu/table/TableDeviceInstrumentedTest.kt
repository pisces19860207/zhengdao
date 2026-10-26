// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十二批 · **真机证据**：一张真表（xlsx）在手机上走「识别 → 问一题 → 带出处回答」。
//
// 为什么 JVM 全绿了还要上机（沿用 223 那条理由）：真机的 `org.json` 实现、ext4/f2fs 的
// mtime 精度、`ZipInputStream` 对真 zip 的读法都与 JVM 不是一回事——「mtime+size 变了才重解析」
// 这条判据在真机上才第一次真的跑过（JVM 里文件时间戳精度不同，可能掩盖问题）。
//
// 每条结论同时打到 logcat（tag `ZD232`）**并写进 `cache/zd232-evidence.txt`**。
// 为什么还要写文件：本机这台 PGT-AN10（鸿蒙）的 logcat 是被加密包起来的
// （`--------- beginning of (HKS)…(HKE)`），`adb logcat -s ZD232` 一行都取不出来。
// 落一个证据文件，用 `adb exec-out run-as com.example.zhengdao cat cache/zd232-evidence.txt`
// 就能原样拿到——报告里贴的就是它。
//
//   adb logcat -s ZD232        （能读 logcat 的机型）
//   adb exec-out run-as com.example.zhengdao cat cache/zd232-evidence.txt
//
// 判据：
// ① 表在手机存储上被认出来（表头行＝1，三列：姓名/科目/分数）；
// ② 四问答案与手算一致，且每一条都带「表：文件 · sheet「X」 · 第 N 行」；
// ③ 第二次问同一件事，一个文件都不重解析（登记簿增量）；
// ④ PDF 只回「暂不支持」，不打开文件。
package com.example.zhengdao.baopu.table

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zhengdao.baopu.agent.TableQueryTool
import com.example.zhengdao.baopu.agent.ToolOutcome
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class TableDeviceInstrumentedTest {

    private lateinit var ws: File
    private lateinit var evidence: File

    /** 证据文件（放在 ws 外面，tearDown 删 ws 时不会连它一起删掉）。 */
    private fun say(line: String) {
        Log.i(TAG, line)
        // 三个出口都写：logcat（能读的机型）、证据文件（run-as 取）、**stdout**
        // （测试报告 XML 的 system-out 里一定有——这是本机唯一稳定取得到的那个）
        println("ZD232| $line")
        runCatching { evidence.appendText(line + "\n", Charsets.UTF_8) }
    }

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        evidence = File(ctx.cacheDir, "zd232-evidence.txt")
        evidence.writeText("", Charsets.UTF_8)
        ws = File(ctx.cacheDir, "zd232-ws")
        ws.deleteRecursively()
        ws.mkdirs()
        TableQueryTool.resetForTest()
        writeScoreXlsx(File(ws, "成绩表.xlsx"))
        say("工作区=${ws.absolutePath} 表=${File(ws, "成绩表.xlsx").length()} 字节")
    }

    @After
    fun tearDown() {
        TableQueryTool.resetForTest()
        ws.deleteRecursively()
    }

    @Test
    fun 真机_四问都答得出来且带出处() {
        val expect = listOf(
            "一共几个人" to "一共 5 个人",
            "几个人不及格" to "2 个人不及格",
            "每科平均分" to "语文 78.4",
            "最高分是谁" to "最高 100",
        )
        expect.forEach { (q, want) ->
            val j = ask(q)
            val answer = j.getString("answer")
            say("问「$q」→ ${answer.replace('\n', ' ')}")
            assertTrue("「$q」答案里没有「$want」：$answer", answer.contains(want))
            assertTrue("「$q」没有文件出处", answer.contains("表：成绩表.xlsx"))
            assertTrue("「$q」没有 sheet", answer.contains("sheet「Sheet1」"))
            assertTrue("「$q」没有行号", answer.contains("行"))
        }
    }

    @Test
    fun 真机_表头行与列类型认对了() {
        val reg = TableQueryTool.registryFor(ws)
        reg.refresh()
        val entry = reg.entries().single()
        val sheet = entry.defaultSheet!!
        say("表头行=${sheet.headerRow + 1} 行数=${sheet.rows} 列=${sheet.columns.map { "${it.name}:${it.type}" }}")
        assertEquals(0, sheet.headerRow)
        assertEquals(10, sheet.rows)
        assertEquals(listOf("姓名", "科目", "分数"), sheet.columns.map { it.name })
        assertEquals("NUMBER", sheet.columns[2].type)
    }

    @Test
    fun 真机_第二次问不重解析() {
        ask("一共几个人")
        val reg = TableQueryTool.registryFor(ws)
        val after = reg.parseCount
        say("首问解析次数=$after")
        repeat(3) { ask("几个人不及格") }
        assertEquals("真机上重复问也不许重解析", after, reg.parseCount)
        assertEquals(0, reg.refresh().parsed)
        say("三问之后解析次数=${reg.parseCount}（不变）")
    }

    @Test
    fun 真机_登记簿落在工作区里() {
        ask("一共几个人")
        val f = File(File(File(ws, "抱朴记忆"), "表格"), "registry.json")
        assertTrue("登记簿没生成：${f.absolutePath}", f.isFile)
        val body = f.readText()
        assertFalse("登记簿不许存单元格取值", body.contains("张三"))
        say("registry.json=${f.length()} 字节（不含姓名/手机号取值）")
    }

    @Test
    fun 真机_PDF_只说暂不支持() {
        File(ws, "扫描件.pdf").writeText("%PDF-1.4")
        val j = ask("一共几个人", table = "扫描件.pdf")
        say("PDF → ${j.getString("answer").replace('\n', ' ')}")
        assertEquals("PDF", j.getString("kind"))
        assertTrue(j.getString("answer").contains(TableQueryTool.PDF_REFUSAL))
        assertEquals(0, j.getJSONArray("numbers").length())
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private fun ask(question: String, table: String? = null): JSONObject {
        val args = JSONObject().put("question", question)
        if (table != null) args.put("table", table)
        val out = TableQueryTool.run(args, ws)
        assertTrue("工具应当回 Ok，实际：$out", out is ToolOutcome.Ok)
        return JSONObject((out as ToolOutcome.Ok).text)
    }

    /** 真 zip、真 xlsx（不借任何第三方库，与 App 的读法同源）。 */
    private fun writeScoreXlsx(f: File) {
        val rows = listOf(
            listOf("姓名", "科目", "分数"),
            listOf("张三", "语文", "88"),
            listOf("张三", "数学", "92"),
            listOf("李四", "语文", "55"),
            listOf("李四", "数学", "48"),
            listOf("王五", "语文", "90"),
            listOf("王五", "数学", "76"),
            listOf("赵六", "语文", "59"),
            listOf("赵六", "数学", "61"),
            listOf("孙七", "语文", "100"),
            listOf("孙七", "数学", "95"),
        )
        val shared = ArrayList<String>()
        fun idx(t: String): Int {
            val at = shared.indexOf(t)
            return if (at >= 0) at else shared.size.also { shared += t }
        }
        val numeric = Regex("""^-?\d+(\.\d+)?$""")
        val sheet = StringBuilder("<?xml version=\"1.0\"?><worksheet><sheetData>")
        rows.forEachIndexed { r, cells ->
            val no = r + 1
            sheet.append("<row r=\"$no\">")
            cells.forEachIndexed { c, v ->
                val ref = "${('A' + c)}$no"
                if (numeric.matches(v)) {
                    sheet.append("<c r=\"$ref\"><v>$v</v></c>")
                } else {
                    sheet.append("<c r=\"$ref\" t=\"s\"><v>${idx(v)}</v></c>")
                }
            }
            sheet.append("</row>")
        }
        sheet.append("</sheetData></worksheet>")
        val sst = StringBuilder("<?xml version=\"1.0\"?><sst>")
        shared.forEach { sst.append("<si><t xml:space=\"preserve\">").append(it).append("</t></si>") }
        sst.append("</sst>")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun add(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            add(
                "[Content_Types].xml",
                "<?xml version=\"1.0\"?><Types>" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/xl/worksheets/sheet1.xml\" " +
                    "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                    "</Types>",
            )
            add("_rels/.rels", "<?xml version=\"1.0\"?><Relationships/>")
            add(
                "xl/workbook.xml",
                "<?xml version=\"1.0\"?><workbook " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                    "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            )
            add(
                "xl/_rels/workbook.xml.rels",
                "<?xml version=\"1.0\"?><Relationships>" +
                    "<Relationship Id=\"rId1\" Type=\"sheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
            )
            add("xl/sharedStrings.xml", sst.toString())
            add("xl/worksheets/sheet1.xml", sheet.toString())
        }
        f.writeBytes(out.toByteArray())
    }

    private companion object {
        const val TAG = "ZD232"
    }
}
