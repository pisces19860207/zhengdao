// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十二批 · **明确不做**的两件（派工单判据⑤：调用即拒；负例「接受 ⇒ 红」）。
//
// 为什么这两条要单独立一个测试，而不是塞进功能测试里：
// 「不做」也是一种承诺。承诺没人验，下一批就会有人顺手加一条 `formula` 参数、
// 然后用户拿到一份"公式变成一串文本"的文档——所以这几条必须**红着脸**守住。
package com.example.zhengdao.baopu.office

import com.example.zhengdao.baopu.agent.DocxWriteTool
import com.example.zhengdao.baopu.agent.ToolOutcome
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DocxRefusalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ws: File

    @Before
    fun setUp() {
        ws = tmp.newFolder("ws")
    }

    // ── 公式：顶层参数与块级两种都给拒 ──────────────────────────────────────

    @Test
    fun `顶层给formula_当场拒并说清为什么`() {
        val args = base().put("formula", "E=mc^2")
        val out = DocxWriteTool.run(args, ws)
        assertTrue("必须拒：$out", out is ToolOutcome.Failed)
        val err = (out as ToolOutcome.Failed).error
        assertTrue("要说清不做公式：$err", "不做公式" in err)
        assertTrue("要给出路（先算好/写成图）：$err", "图片" in err || "文字" in err)
        assertEquals("拒了就不许落盘", 0, products().size)
    }

    @Test
    fun `块级塞formula_同样拒`() {
        val blocks = JSONArray().put(JSONObject().put("type", "formula").put("text", "\\frac{1}{2}"))
        val out = DocxWriteTool.run(base().put("blocks", blocks), ws)
        assertTrue("必须拒：$out", out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error, "公式" in (out as ToolOutcome.Failed).error)
        assertEquals(0, products().size)
    }

    @Test
    fun `latex与equation这些叫法也认得住`() {
        for (key in listOf("latex", "equation", "math", "omml")) {
            val out = DocxWriteTool.run(base().put(key, "x^2"), ws)
            assertTrue("$key 也要拒：$out", out is ToolOutcome.Failed)
        }
        assertEquals("一个都不许落盘", 0, products().size)
    }

    // ── 宏：不给入口 ──────────────────────────────────────────────────────

    @Test
    fun `顶层给macro_当场拒`() {
        val out = DocxWriteTool.run(base().put("macro", "Sub AutoOpen()"), ws)
        assertTrue("必须拒：$out", out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error, "不做宏" in (out as ToolOutcome.Failed).error)
        assertEquals(0, products().size)
    }

    @Test
    fun `块级塞macro或vba_同样拒`() {
        for (type in listOf("macro", "vba")) {
            val blocks = JSONArray().put(JSONObject().put("type", type).put("text", "x"))
            val out = DocxWriteTool.run(base().put("blocks", blocks), ws)
            assertTrue("$type 也要拒：$out", out is ToolOutcome.Failed)
            assertTrue((out as ToolOutcome.Failed).error, "宏" in (out as ToolOutcome.Failed).error)
        }
        assertEquals(0, products().size)
    }

    // ── 对照：正常请求不受影响（别把闸做成"全都拒"）────────────────────────

    @Test
    fun `正常一段文字_照常能写`() {
        val out = DocxWriteTool.run(base(), ws)
        assertTrue("正常请求不许被误伤：$out", out is ToolOutcome.Ok)
        assertEquals("应当落一份", 1, products().size)
    }

    private fun base(): JSONObject = JSONObject()
        .put("name", "拒稿示例")
        .put(
            "blocks",
            JSONArray().put(JSONObject().put("type", "paragraph").put("text", "一句话")),
        )

    private fun products(): List<File> = File(ws, "产物").listFiles().orEmpty().filter { it.isFile }
}
