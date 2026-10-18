// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// `ask_user`（2026-10-19 用户口径：「加，位置在输入栏的上方弹出」）的判据层：
// 选项归一（2–4 个、推荐排第一、label 必填）与三条接线门禁。全部打在纯函数与源码上。
package com.example.zhengdao.baopu.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AskUserToolTest {

    private fun opts(vararg items: JSONObject): JSONArray = JSONArray().also { arr ->
        items.forEach { arr.put(it) }
    }

    private fun opt(label: String, detail: String = "", recommended: Boolean = false, id: String = "") =
        JSONObject().put("label", label).put("detail", detail).put("recommended", recommended).put("id", id)

    // ── 归一 ────────────────────────────────────────────────────────────────

    @Test
    fun `A1_推荐项排第一_其余保持原序`() {
        val list = AskUserTool.normalizeOptions(
            opts(opt("保留"), opt("删掉大的", recommended = true), opt("全删")),
        )!!
        assertEquals(3, list.size)
        assertEquals("删掉大的", list[0].label)
        assertTrue(list[0].recommended)
        assertEquals("保留", list[1].label)
        assertEquals("全删", list[2].label)
    }

    @Test
    fun `A2_少于两个或没有label都判非法`() {
        assertNull("一个选项等于命令，不该走卡", AskUserTool.normalizeOptions(opts(opt("只有一个"))))
        assertNull("全没 label", AskUserTool.normalizeOptions(opts(JSONObject(), JSONObject())))
        assertNull(AskUserTool.normalizeOptions(null))
    }

    @Test
    fun `A3_超过四个截断_没id的补序号`() {
        val list = AskUserTool.normalizeOptions(
            opts(opt("a"), opt("b"), opt("c"), opt("d"), opt("e")),
        )!!
        assertEquals(AskUserTool.MAX_OPTIONS, list.size)
        assertEquals("没 id 要补一个能用点到的", "o1", list[0].id)
        assertEquals("o2", list[1].id)
    }

    @Test
    fun `A4_label超20字截断_detail原样带`() {
        val long = "很长的选项名字".repeat(6)
        val list = AskUserTool.normalizeOptions(opts(opt(long, detail = "因为占 6.9 MB"), opt("b")))!!
        assertEquals(20, list[0].label.length)
        assertEquals("因为占 6.9 MB", list[0].detail)
    }

    // ── 执行体 ──────────────────────────────────────────────────────────────

    @Test
    fun `A5_没有界面时如实说_不干等`() {
        AskUserTool.sink = null
        val out = AskUserTool.spec().executor(
            JSONObject()
                .put("question", "回收站怎么清？")
                .put("options", opts(opt("全删", recommended = true), opt("留着"))),
        )
        assertTrue("没有 sink 必须 Failed：$out", out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("没有界面"))
    }

    @Test
    fun `A6_用户点了就把选择回灌成工具结果`() {
        var seen: AskUserRequest? = null
        AskUserTool.sink = { req ->
            seen = req
            req.options.first()
        }
        try {
            val out = AskUserTool.spec().executor(
                JSONObject()
                    .put("question", "回收站怎么清？")
                    .put(
                        "options",
                        opts(opt("只删大图", detail = "6.9 MB 那两张", recommended = true), opt("全删")),
                    ),
            )
            assertTrue("应当成功：$out", out is ToolOutcome.Ok)
            val text = (out as ToolOutcome.Ok).text
            assertTrue("要把选择说清楚：$text", text.contains("只删大图"))
            assertTrue("理由也要带上：$text", text.contains("6.9 MB"))
            assertEquals("sink 收到的是归一后的顺序", "只删大图", seen!!.options[0].label)
        } finally {
            AskUserTool.sink = null
        }
    }

    @Test
    fun `A7_超时没人点就如实说`() {
        AskUserTool.sink = { null }
        try {
            val out = AskUserTool.spec().executor(
                JSONObject()
                    .put("question", "选哪个？")
                    .put("options", opts(opt("甲"), opt("乙"))),
            )
            assertTrue(out is ToolOutcome.Failed)
            assertTrue((out as ToolOutcome.Failed).error.contains("没有选"))
        } finally {
            AskUserTool.sink = null
        }
    }

    // ── 源码级接线 ──────────────────────────────────────────────────────────

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$it") }.readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    @Test
    fun `G1_注册表里有ask_user且默认启用`() {
        val reg = code("src/main/java/com/example/zhengdao/baopu/agent/ToolRegistry.kt")
        assertTrue("ENTRIES 里没注册 ask_user", reg.contains("Entry(\"ask_user\")"))
        assertTrue("DEFAULT_ENABLED 里没有 ask_user", reg.contains("\"ask_user\","))
    }

    @Test
    fun `G2_引擎接上了sink且卡在输入栏上方`() {
        val rt = code("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")
        assertTrue("BaopuRuntime 没把 sink 接到引擎", rt.contains("AskUserTool.sink ="))
        val screen = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")
        assertTrue("屏幕里没渲染选项卡", screen.contains("BaopuChoiceCard("))
        val composerIdx = screen.indexOf("BaopuComposer(")
        val cardIdx = screen.indexOf("BaopuChoiceCard(")
        assertTrue("卡片必须在输入栏之前（上方）", cardIdx >= 0 && cardIdx < composerIdx)
    }

    @Test
    fun `G3_两种语言都有标签与副标题`() {
        val zh = code("src/main/res/values/strings.xml")
        val en = code("src/main/res/values-en/strings.xml")
        listOf("baopu_tool_ask_user", "baopu_tool_ask_user_desc").forEach { key ->
            assertTrue("中文缺 $key", zh.contains("name=\"$key\""))
            assertTrue("英文缺 $key", en.contains("name=\"$key\""))
        }
    }
}
